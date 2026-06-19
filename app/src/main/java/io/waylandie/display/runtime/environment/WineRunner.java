package io.waylandie.display.runtime.environment;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * WineRunner — launches Wine directly, no proot, no glibc linker wrapper.
 *
 * <p><b>Architecture:</b> Detects the Wine binary's ELF architecture and
 * chooses the right launch path:
 * <ul>
 *   <li><b>arm64ec Wine</b> (ELF e_machine == EM_AARCH64 == 183): launches
 *       the Wine binary directly — it is an Android-native ARM64 binary.
 *       FEXCore (libarm64ecfex.dll) provides WoW64 hooks so x86_64 game
 *       code is translated on the fly. No box64, no glibc linker needed.</li>
 *   <li><b>x86_64 Wine</b> (ELF e_machine == EM_X86_64 == 62): wraps the
 *       Wine binary with the BIONIC box64 installed at
 *       {@code rootfs/usr/bin/box64}. The bionic box64 runs directly on
 *       Android (interpreter {@code /system/bin/linker64}) and loads glibc
 *       from the rootfs for the emulated Wine process. No glibc linker
 *       wrapper needed.</li>
 * </ul>
 *
 * <p><b>Path model:</b> One path space — host paths. The rootfs at
 * {@code getFilesDir()/imagefs/} is accessed via direct absolute paths.
 * No bind mounts, no guest/host translation, no {@code --} option issues.
 *
 * <p><b>Driver integration:</b>
 * <ul>
 *   <li>Proton: installed at {@code contents/proton/active/} — Wine binary
 *       found at {@code files/bin/wine}, {@code dist/bin/wine}, or
 *       {@code bin/wine}.</li>
 *   <li>DXVK: dlls already copied to the Wine prefix's system32/ during
 *       install (by SettingsActivity.installDxvkToWinePrefix).</li>
 *   <li>Turnip: ICD JSON at {@code rootfs/usr/local/etc/vulkan/icd.d/}
 *       pointing to the .so at {@code rootfs/usr/local/lib/}.</li>
 *   <li>Adrenotools: .so synced to rootfs by syncAdrenotoolsDriverToRootfs().</li>
 * </ul>
 */
public final class WineRunner {

    private static final String TAG = "WayLandIE/WineRunner";

    private final Context context;
    private final ImageFsManager imageFs;

    public WineRunner(Context context) {
        this.context = context;
        this.imageFs = new ImageFsManager(context);
    }

    /**
     * Returns true if the rootfs is valid and Wine can potentially run.
     */
    public boolean isReady() {
        return imageFs.isValid();
    }

    /**
     * Launches Wine with the given .exe path using glibc-native execution.
     *
     * @param exePath  absolute path to the .exe file (host path, accessible
     *                 directly — no proot translation needed)
     * @param extraArgs extra command-line args to pass to Wine
     * @param useProton ignored — Proton is always used (Wine is not bundled)
     * @return the started Process
     * @throws IOException if Wine binary not found or launch fails
     */
    public Process execWine(String exePath, String[] extraArgs, boolean useProton) throws IOException {
        if (!isReady()) {
            throw new IOException("WineRunner not ready: imagefs invalid. "
                    + imageFs.describeValidity());
        }

        File rootDir = imageFs.getRootDir();

        // 1. Find Proton's Wine binary (HOST path — direct, no translation)
        File protonDir = new File(context.getFilesDir(), "contents/proton/active");
        if (!protonDir.exists()) {
            throw new IOException("Proton is not installed. Please go to the "
                    + "Settings tab and install Proton first. "
                    + "(Wine is not bundled in the rootfs — Proton provides Wine.)");
        }

        File wineFromProton = new File(protonDir, "files/bin/wine");
        File wineFromProtonAlt = new File(protonDir, "dist/bin/wine");
        File wineFromProtonFlat = new File(protonDir, "bin/wine");

        File wineBin = null;
        if (wineFromProton.exists()) {
            wineBin = wineFromProton;
        } else if (wineFromProtonAlt.exists()) {
            wineBin = wineFromProtonAlt;
        } else if (wineFromProtonFlat.exists()) {
            wineBin = wineFromProtonFlat;
        }

        if (wineBin == null) {
            throw new IOException("Proton installation found at '" + protonDir
                    + "' but no wine binary was found inside it. Checked:\n"
                    + "  " + wineFromProton + "\n"
                    + "  " + wineFromProtonAlt + "\n"
                    + "  " + wineFromProtonFlat + "\n"
                    + "Please try reinstalling Proton via the Settings tab.");
        }

        // Make wine executable
        wineBin.setExecutable(true, false);

        // 2. Detect Wine architecture — arm64ec vs x86_64
        boolean isArm64ec = isArm64ecWine(wineBin);
        Log.i(TAG, "Wine architecture: " + (isArm64ec ? "arm64ec (native)" : "x86_64 (needs box64)"));

        // 3. Locate the bionic box64 (only needed for x86_64 Wine).
        //    Installed at rootfs/usr/bin/box64 by tools/build-imagefs.sh
        //    (BIONIC build from StevenMXZ/Winlator-Ludashi — Android native).
        File box64 = new File(rootDir, "usr/bin/box64");
        if (!box64.exists()) {
            // Legacy fallback location (older rootfs layouts)
            box64 = new File(rootDir, "usr/local/bin/box64");
        }
        if (box64.exists()) {
            box64.setExecutable(true, false);
        }

        // 4. Build the command:
        //    arm64ec Wine : [wine] [exePath] [args]            (direct)
        //    x86_64  Wine : [box64] [wine] [exePath] [args]    (bionic box64)
        List<String> cmd = new ArrayList<>();
        if (isArm64ec) {
            // arm64ec Wine is an Android-native ARM64 binary — launch directly.
            // FEXCore (libarm64ecfex.dll) inside the Proton prefix handles
            // x86_64 game code translation at the WoW64 boundary.
            cmd.add(wineBin.getAbsolutePath());
            Log.i(TAG, "Launching arm64ec Wine directly (no box64, no glibc linker)");
        } else {
            // x86_64 Wine — needs x86_64 emulation. Use the bionic box64
            // from rootfs/usr/bin/box64. Bionic box64 runs directly on
            // Android (interpreter /system/bin/linker64) and loads glibc
            // libraries from this rootfs for the emulated Wine process.
            if (!box64.exists()) {
                throw new IOException("x86_64 Wine requires box64, but box64 "
                        + "was not found in rootfs. Looked at: " + box64 + ". "
                        + "Install an arm64ec Proton (no box64 needed) or "
                        + "rebuild the imagefs to download bionic box64.");
            }
            cmd.add(box64.getAbsolutePath());
            cmd.add(wineBin.getAbsolutePath());
            Log.i(TAG, "Using bionic box64: " + box64);
        }
        cmd.add(exePath);
        if (extraArgs != null) {
            cmd.addAll(Arrays.asList(extraArgs));
        }

        Log.i(TAG, "Command: " + String.join(" ", cmd));
        Log.i(TAG, "Wine binary: " + wineBin);
        Log.i(TAG, "Working dir: " + rootDir);

        // 4.5. Start the Wayland bridge translator BEFORE launching Wine.
        // The bridge creates a real Wayland display socket that Wine connects
        // to. It translates Wayland dmabuf buffers → Android bridge protocol
        // → zero-copy SurfaceControl presentation. Without it, Wine has no
        // Wayland display to render to.
        Process bridgeProcess = startBridgeTranslator(rootDir);
        if (bridgeProcess != null) {
            Log.i(TAG, "Bridge translator started (pid=" + getPid(bridgeProcess) + ")");
            // Give the bridge 2 seconds to create the Wayland socket
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        } else {
            Log.w(TAG, "Bridge translator not started — Wine may not be able to render");
        }

        // 5. Build the process with environment
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(rootDir);
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        env.clear();

        // Core environment — all HOST paths (no guest/host translation)
        File homeDir = new File(rootDir, "home/xuser");
        if (!homeDir.exists()) homeDir.mkdirs();
        File tmpDir = new File(rootDir, "usr/tmp");
        if (!tmpDir.exists()) tmpDir.mkdirs();

        env.put("HOME", homeDir.getAbsolutePath());
        env.put("USER", "xuser");
        env.put("PATH", wineBin.getParent() + ":"
                + new File(rootDir, "usr/bin").getAbsolutePath() + ":"
                + new File(rootDir, "usr/local/bin").getAbsolutePath());
        env.put("LD_LIBRARY_PATH",
                new File(rootDir, "usr/lib").getAbsolutePath() + ":"
                + new File(rootDir, "usr/local/lib").getAbsolutePath() + ":"
                + new File(rootDir, "opt/proton/files/lib").getAbsolutePath());
        env.put("LANG", "en_US.UTF-8");
        env.put("TERM", "xterm-256color");
        env.put("TMPDIR", tmpDir.getAbsolutePath());
        env.put("XDG_RUNTIME_DIR", tmpDir.getAbsolutePath());

        // Wine environment
        File winePrefix = new File(homeDir, ".wine");
        if (!winePrefix.exists()) winePrefix.mkdirs();
        env.put("WINEPREFIX", winePrefix.getAbsolutePath());
        env.put("WINEDLLOVERRIDES", "d3d9,d3d10core,d3d11,dxgi=native");
        env.put("DXVK_STATE_CACHE_PATH", new File(homeDir, ".dxvk-cache").getAbsolutePath());
        env.put("MESA_VK_WSI_PRESENT_MODE", "immediate");

        // Proton environment
        env.put("PROTONPATH", protonDir.getAbsolutePath());
        env.put("STEAM_COMPAT_CLIENT_INSTALL_PATH",
                new File(context.getFilesDir(), "contents/steam").getAbsolutePath());
        env.put("STEAM_COMPAT_DATA_PATH",
                new File(context.getFilesDir(), "contents/steam/compatdata").getAbsolutePath());
        env.put("STEAM_RUNTIME", "0");  // no pressure-vessel

        // FEX environment (architecture-dependent)
        //   arm64ec + FEXCore: set HODLL=libarm64ecfex.dll — FEXCore hooks
        //     the WoW64 layer inside the Proton prefix and translates x86_64
        //     game code on the fly. No FEX_ROOT needed.
        //   x86_64 + standalone FEX: set FEX_ROOT — box64 + FEX work together
        //     for whole-process x86_64 emulation.
        File fexDir = new File(context.getFilesDir(), "contents/fex/active");
        if (isArm64ec) {
            // FEXCore is shipped inside the Proton prefix (libarm64ecfex.dll).
            // Tell Wine to load it as the HODLL (host optimizer DLL).
            env.put("HODLL", "libarm64ecfex.dll");
            Log.i(TAG, "FEXCore enabled (arm64ec HODLL=libarm64ecfex.dll)");
        } else if (fexDir.isDirectory()) {
            env.put("FEX_ROOT", fexDir.getAbsolutePath());
            Log.i(TAG, "FEX enabled (x86_64 path): " + fexDir);
        }

        // Vulkan driver — ICD JSON (direct HOST path, no bind mount needed)
        File icdFile = new File(rootDir, "usr/local/etc/vulkan/icd.d/freedreno_icd.json");
        if (icdFile.isFile()) {
            env.put("VK_ICD_FILENAMES", icdFile.getAbsolutePath());
            env.put("VK_DRIVER_FILES", icdFile.getAbsolutePath());
            Log.i(TAG, "Vulkan ICD: " + icdFile);
        } else {
            Log.w(TAG, "Vulkan ICD JSON not found at " + icdFile
                    + " — Vulkan may not work. Install Turnip via Settings tab.");
        }

        // Adrenotools driver sync (if active, copies .so to rootfs + updates ICD)
        io.waylandie.display.runtime.content.AdrenotoolsManager atm =
                new io.waylandie.display.runtime.content.AdrenotoolsManager(context);
        String activeDriverSo = atm.getActiveDriverSoPath();
        if (activeDriverSo != null) {
            syncAdrenotoolsDriverToRootfs(activeDriverSo);
            Log.i(TAG, "Using Adrenotools driver (synced to rootfs): " + activeDriverSo);
        }

        // WaylandIE bridge environment
        env.put("WAYLAND_DISPLAY", "waylandie");
        env.put("WAYLANDIE_BRIDGE_SOCKET", "waylandie.display.bridge.v1");
        env.put("WAYLANDIE_BRIDGE_PORT", "57391");
        env.put("WAYLANDIE_BRIDGE_PREFER", "abstract");
        env.put("WAYLANDIE_FINAL_COPY", "forbidden");

        // GPU device access — pass through /dev/kgsl-3d0 and /dev/dri
        // (no bind mount needed — glibc-native has direct device access)
        env.put("VK_DRMHDOJINJECT", "0");  // no DRM HD injection

        return pb.start();
    }

    /**
     * Copies the active adrenotools driver .so into the rootfs and updates
     * the ICD JSON. Same as ProotRunner's version — necessary because
     * adrenotools hooking can't work without proot's isolation layer.
     * The ICD JSON approach is used instead.
     */
    private void syncAdrenotoolsDriverToRootfs(String driverSoHostPath) {
        try {
            File srcSo = new File(driverSoHostPath);
            if (!srcSo.isFile()) {
                Log.w(TAG, "Adrenotools driver .so not found: " + driverSoHostPath);
                return;
            }
            File libDir = new File(imageFs.getRootDir(), "usr/local/lib");
            libDir.mkdirs();
            File destSo = new File(libDir, "libvulkan_freedreno.so");
            copyFile(srcSo, destSo);

            File icdDir = new File(imageFs.getRootDir(), "usr/local/etc/vulkan/icd.d");
            icdDir.mkdirs();
            File icdFile = new File(icdDir, "freedreno_icd.json");
            try (java.io.PrintWriter pw = new java.io.PrintWriter(icdFile)) {
                pw.println("{");
                pw.println("    \"file_format_version\": \"1.0.0\",");
                pw.println("    \"ICD\": {");
                // Library path is a HOST path (glibc-native — no guest paths)
                pw.println("        \"library_path\": \"" + destSo.getAbsolutePath() + "\",");
                pw.println("        \"api_version\": \"1.3.0\"");
                pw.println("    }");
                pw.println("}");
            }
            Log.i(TAG, "Synced adrenotools driver to rootfs: " + destSo);
        } catch (Exception e) {
            Log.e(TAG, "Failed to sync adrenotools driver to rootfs", e);
        }
    }

    /**
     * Starts the Wayland bridge translator. The bridge is a C program
     * (waylandie-wayland-bridge) that creates a real Wayland display socket,
     * accepts Wine client connections, and translates Wayland dmabuf buffers
     * to the Android bridge protocol for zero-copy SurfaceControl presentation.
     *
     * @return the bridge Process, or null if the binary is not found
     */
    private Process startBridgeTranslator(File rootDir) {
        File bridgeBin = new File(rootDir, "usr/local/bin/waylandie-wayland-bridge");
        if (!bridgeBin.exists()) {
            Log.w(TAG, "Bridge translator not found at " + bridgeBin
                    + " — Wine will have no Wayland display. Rebuild rootfs.");
            return null;
        }
        bridgeBin.setExecutable(true, false);

        try {
            ProcessBuilder pb = new ProcessBuilder(bridgeBin.getAbsolutePath());
            pb.directory(rootDir);
            pb.redirectErrorStream(true);

            Map<String, String> env = pb.environment();
            env.clear();
            File tmpDir = new File(rootDir, "usr/tmp");
            if (!tmpDir.exists()) tmpDir.mkdirs();
            File runtimeDir = new File(tmpDir, "runtime");
            if (!runtimeDir.exists()) runtimeDir.mkdirs();

            env.put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());
            env.put("WAYLAND_DISPLAY", "waylandie");
            env.put("WAYLANDIE_BRIDGE_SOCKET", "waylandie.display.bridge.v1");
            env.put("WAYLANDIE_BRIDGE_PORT", "57391");
            env.put("WAYLANDIE_BRIDGE_PREFER", "abstract");
            env.put("WAYLANDIE_FINAL_COPY", "forbidden");
            env.put("LD_LIBRARY_PATH",
                    new File(rootDir, "usr/lib").getAbsolutePath() + ":"
                    + new File(rootDir, "usr/local/lib").getAbsolutePath());
            env.put("PATH", new File(rootDir, "usr/bin").getAbsolutePath() + ":"
                    + new File(rootDir, "usr/local/bin").getAbsolutePath());

            Log.i(TAG, "Starting bridge translator: " + bridgeBin);
            return pb.start();
        } catch (IOException e) {
            Log.e(TAG, "Failed to start bridge translator", e);
            return null;
        }
    }

    private static long getPid(Process p) {
        try {
            java.lang.reflect.Field pidField = p.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            return pidField.getLong(p);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Detects whether a Wine binary is an arm64ec build by reading the ELF
     * e_machine field. arm64ec Wine is an ARM64 ELF (e_machine == EM_AARCH64
     * == 183) — Android runs it natively. x86_64 Wine has e_machine ==
     * EM_X86_64 == 62 and requires box64 emulation.
     *
     * <p>ELF header layout (first 64 bytes for ELF64):
     * <pre>
     *   offset 0x00 : 0x7F 'E' 'L' 'F'  (magic)
     *   offset 0x04 : EI_CLASS  (1 = 32-bit, 2 = 64-bit)
     *   offset 0x12 : e_machine (little-endian uint16)  ← what we read
     * </pre>
     *
     * @param wineBin the Wine executable to inspect
     * @return true if wineBin is an ELF with e_machine == EM_AARCH64 (arm64ec)
     */
    private static boolean isArm64ecWine(File wineBin) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(wineBin, "r")) {
            if (raf.length() < 20) return false;
            // Verify ELF magic: 0x7F 'E' 'L' 'F'
            byte[] magic = new byte[4];
            raf.readFully(magic);
            if (magic[0] != 0x7F || magic[1] != 'E' || magic[2] != 'L' || magic[3] != 'F') {
                Log.w(TAG, "Wine binary is not an ELF: " + wineBin);
                return false;
            }
            // Read e_machine at offset 0x12 (little-endian uint16)
            raf.seek(0x12);
            int lo = raf.readUnsignedByte();
            int hi = raf.readUnsignedByte();
            int eMachine = (hi << 8) | lo;
            // EM_AARCH64 = 183 (arm64ec is an AArch64 ELF)
            // EM_X86_64  =  62 (needs box64)
            Log.i(TAG, "Wine ELF e_machine=" + eMachine
                    + (eMachine == 183 ? " (EM_AARCH64 / arm64ec)"
                       : eMachine == 62 ? " (EM_X86_64)" : " (unknown)"));
            return eMachine == 183;  // EM_AARCH64
        } catch (Exception e) {
            Log.w(TAG, "Failed to read Wine ELF header: " + wineBin, e);
            return false;  // assume x86_64 (will try box64)
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }
}
