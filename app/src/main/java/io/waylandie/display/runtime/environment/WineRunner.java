package io.waylandie.display.runtime.environment;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WineRunner — launches Wine + bridge translator via native glibc linker.
 *
 * <p><b>Architecture (nativeLibraryDir trick):</b>
 * Android SELinux blocks execve() from app data directories but ALLOWS it
 * from nativeLibraryDir. We bundle the glibc dynamic linker as
 * {@code libld_glibc.so} in jniLibs — Android extracts it to
 * nativeLibraryDir at install time. We then launch it directly:
 * <pre>
 *   nativeLibraryDir/libld_glibc.so --library-path rootfs/usr/lib:proton/lib wine exePath
 * </pre>
 * This gives us <b>native-speed glibc execution</b> without proot or root.
 * No syscall translation overhead.
 *
 * <p><b>Fallback:</b> If libld_glibc.so is not present (e.g., older APK
 * build without the jniLibs step), falls back to ProotRunner.
 *
 * <p><b>Flow:</b>
 * <ol>
 *   <li>Start bridge translator via glibc linker (background)</li>
 *   <li>Wait 2s for Wayland socket creation</li>
 *   <li>Start Wine via glibc linker (foreground)</li>
 * </ol>
 */
public final class WineRunner {

    private static final String TAG = "WayLandIE/WineRunner";

    private final Context context;
    private final ImageFsManager imageFs;

    public WineRunner(Context context) {
        this.context = context;
        this.imageFs = new ImageFsManager(context);
    }

    public boolean isReady() {
        return imageFs.isValid();
    }

    /**
     * Checks if the native glibc linker is available in nativeLibraryDir.
     */
    private boolean hasNativeLinker() {
        File linker = new File(context.getApplicationInfo().nativeLibraryDir, "libld_glibc.so");
        boolean exists = linker.exists();
        Log.i(TAG, "Native glibc linker: " + linker + " (exists=" + exists + ")");
        return exists;
    }

    /**
     * Launches Wine with the given .exe path.
     *
     * Uses the native glibc linker (nativeLibraryDir/libld_glibc.so) for
     * zero-overhead glibc execution. Falls back to ProotRunner if the
     * linker is not available.
     */
    public Process execWine(String exePath, String[] extraArgs, boolean useProton) throws IOException {
        if (!isReady()) {
            throw new IOException("WineRunner not ready: imagefs invalid. "
                    + imageFs.describeValidity());
        }

        File rootDir = imageFs.getRootDir();
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;

        // 1. Find Proton's Wine binary (validate on HOST)
        File protonDir = new File(context.getFilesDir(), "contents/proton/active");
        if (!protonDir.exists()) {
            throw new IOException("Proton is not installed. Please go to the "
                    + "Settings tab and install Proton first.");
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
            throw new IOException("Proton found at '" + protonDir
                    + "' but no wine binary. Checked:\n"
                    + "  " + wineFromProton + "\n"
                    + "  " + wineFromProtonAlt + "\n"
                    + "  " + wineFromProtonFlat);
        }
        wineBin.setExecutable(true, false);

        boolean isArm64ec = isArm64ecWine(wineBin);
        File fexDir = new File(context.getFilesDir(), "contents/fex/active");
        boolean fexCoreInstalled = fexDir.isDirectory();

        Log.i(TAG, "Wine: " + wineBin + " (arm64ec=" + isArm64ec + ")");
        Log.i(TAG, "FEXCore: " + (fexCoreInstalled ? "installed" : "not installed"));

        // 2. Sync adrenotools driver if active
        io.waylandie.display.runtime.content.AdrenotoolsManager atm =
                new io.waylandie.display.runtime.content.AdrenotoolsManager(context);
        String activeDriverSo = atm.getActiveDriverSoPath();
        if (activeDriverSo != null) {
            syncAdrenotoolsDriverToRootfs(activeDriverSo);
        }

        // 3. Choose launch method: native linker (fast) or proot (fallback)
        if (hasNativeLinker()) {
            return launchNative(rootDir, nativeLibDir, wineBin, exePath,
                    extraArgs, isArm64ec, fexCoreInstalled, protonDir);
        } else {
            Log.w(TAG, "Native glibc linker not found — falling back to proot");
            return launchViaProot(rootDir, wineBin, exePath, extraArgs,
                    isArm64ec, fexCoreInstalled);
        }
    }

    /**
     * Native launch via glibc linker from nativeLibraryDir.
     * Zero syscall overhead — runs at full native speed.
     */
    private Process launchNative(File rootDir, String nativeLibDir,
            File wineBin, String exePath, String[] extraArgs,
            boolean isArm64ec, boolean fexCoreInstalled, File protonDir)
            throws IOException {

        File linker = new File(nativeLibDir, "libld_glibc.so");

        // Build library path: rootfs libs + proton libs
        // CRITICAL: Debian multiarch puts libs in /usr/lib/aarch64-linux-gnu/
        // not /usr/lib/. Without this path, wine + bridge can't find libX11,
        // libwayland-server, libfreetype, etc.
        // Also include protonDir/lib (flat layout) AND protonDir/files/lib
        // (standard layout) — covers both Proton package formats.
        String libPath = new File(rootDir, "usr/lib").getAbsolutePath() + ":"
                + new File(rootDir, "usr/lib/aarch64-linux-gnu").getAbsolutePath() + ":"
                + new File(rootDir, "usr/local/lib").getAbsolutePath() + ":"
                + new File(protonDir, "lib").getAbsolutePath() + ":"
                + new File(protonDir, "files/lib").getAbsolutePath();

        // Start bridge translator FIRST (in background, via glibc linker)
        File bridgeBin = new File(rootDir, "usr/local/bin/waylandie-wayland-bridge");
        if (bridgeBin.exists()) {
            bridgeBin.setExecutable(true, false);
            Log.i(TAG, "Starting bridge translator via native linker (background)…");
            try {
                List<String> bridgeCmd = new ArrayList<>();
                bridgeCmd.add(linker.getAbsolutePath());
                bridgeCmd.add("--library-path");
                bridgeCmd.add(libPath);
                bridgeCmd.add(bridgeBin.getAbsolutePath());

                ProcessBuilder pbBridge = new ProcessBuilder(bridgeCmd);
                pbBridge.directory(rootDir);
                pbBridge.redirectErrorStream(true);
                Map<String, String> bridgeEnv = pbBridge.environment();
                bridgeEnv.clear();
                setupEnvironment(bridgeEnv, rootDir, protonDir, isArm64ec, fexCoreInstalled);
                Process bridgeProcess = pbBridge.start();
                Log.i(TAG, "Bridge translator started (pid=" + getPid(bridgeProcess) + ")");
                // Give the bridge 2s to create the Wayland socket
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            } catch (IOException e) {
                Log.w(TAG, "Bridge failed to start: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "Bridge translator not found at " + bridgeBin);
        }

        // Build Wine command: linker --library-path libs wine exePath [args]
        List<String> cmd = new ArrayList<>();
        cmd.add(linker.getAbsolutePath());
        cmd.add("--library-path");
        cmd.add(libPath);
        cmd.add(wineBin.getAbsolutePath());
        cmd.add(exePath);
        if (extraArgs != null) {
            for (String arg : extraArgs) cmd.add(arg);
        }

        Log.i(TAG, "Native launch command: " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(rootDir);
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        env.clear();
        setupEnvironment(env, rootDir, protonDir, isArm64ec, fexCoreInstalled);

        Process p = pb.start();

        // Capture output in a background thread so we can see Wine errors
        // in logcat. Without this, if Wine crashes we have no idea why.
        new Thread(() -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.i("WayLandIE/Wine", line);
                    io.waylandie.display.shared.util.LogRingBuffer.append("[wine] " + line);
                }
            } catch (java.io.IOException e) {
                Log.w(TAG, "Wine output stream closed: " + e.getMessage());
            }
        }, "wl-wine-output").start();

        return p;
    }

    /**
     * Fallback: launch via ProotRunner (syscall translation overhead).
     */
    private Process launchViaProot(File rootDir, File wineBin, String exePath,
            String[] extraArgs, boolean isArm64ec, boolean fexCoreInstalled)
            throws IOException {

        ProotRunner proot = new ProotRunner(context);
        if (!proot.isReady()) {
            throw new IOException("ProotRunner not ready (fallback).");
        }

        // Start bridge via proot (background)
        File bridgeBin = new File(rootDir, "usr/local/bin/waylandie-wayland-bridge");
        if (bridgeBin.exists()) {
            Log.i(TAG, "Starting bridge translator via proot (background)…");
            try {
                proot.exec(
                        "XDG_RUNTIME_DIR=/tmp WAYLAND_DISPLAY=waylandie "
                        + "WAYLANDIE_BRIDGE_SOCKET=waylandie.display.bridge.v1 "
                        + "WAYLANDIE_BRIDGE_PORT=57391 "
                        + "/usr/local/bin/waylandie-wayland-bridge &");
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            } catch (IOException e) {
                Log.w(TAG, "Bridge proot launch failed: " + e.getMessage());
            }
        }

        // Determine guest wine path
        String wineGuestPath = "/opt/proton/files/bin/wine";
        if (wineBin.getAbsolutePath().contains("dist/bin")) {
            wineGuestPath = "/opt/proton/dist/bin/wine";
        } else if (wineBin.getAbsolutePath().contains("/bin/wine")
                && !wineBin.getAbsolutePath().contains("files/") && !wineBin.getAbsolutePath().contains("dist/")) {
            wineGuestPath = "/opt/proton/bin/wine";
        }

        StringBuilder wineCmd = new StringBuilder();
        if (fexCoreInstalled && isArm64ec) {
            wineCmd.append("HODLL=libarm64ecfex.dll ");
        }
        wineCmd.append(wineGuestPath).append(" ").append(exePath);
        if (extraArgs != null) {
            for (String arg : extraArgs) wineCmd.append(" ").append(arg);
        }

        Log.i(TAG, "Proot launch command: " + wineCmd);
        Process p = proot.exec(wineCmd.toString());

        // Capture Wine output — CRITICAL for debugging. Without this we have
        // ZERO visibility into why Wine crashes. The proot process stdout/stderr
        // is merged (redirectErrorStream=true in ProotRunner) so we read it all.
        new Thread(() -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.i("WayLandIE/Wine", line);
                    io.waylandie.display.shared.util.LogRingBuffer.append("[wine] " + line);
                }
            } catch (java.io.IOException e) {
                Log.w(TAG, "Wine output stream closed: " + e.getMessage());
            }
        }, "wl-wine-output-proot").start();

        return p;
    }

    /**
     * Sets up the process environment for native glibc execution.
     * All paths are HOST paths (direct, no proot translation).
     */
    private void setupEnvironment(Map<String, String> env, File rootDir,
            File protonDir, boolean isArm64ec, boolean fexCoreInstalled) {

        File homeDir = new File(rootDir, "home/xuser");
        if (!homeDir.exists()) homeDir.mkdirs();
        File tmpDir = new File(rootDir, "usr/tmp");
        if (!tmpDir.exists()) tmpDir.mkdirs();
        File runtimeDir = new File(tmpDir, "runtime");
        if (!runtimeDir.exists()) runtimeDir.mkdirs();

        env.put("HOME", homeDir.getAbsolutePath());
        env.put("USER", "xuser");
        env.put("PATH", new File(protonDir, "bin").getAbsolutePath() + ":"
                + new File(protonDir, "files/bin").getAbsolutePath() + ":"
                + new File(rootDir, "usr/bin").getAbsolutePath() + ":"
                + new File(rootDir, "usr/local/bin").getAbsolutePath());
        // LD_LIBRARY_PATH — include both flat (lib/) and standard (files/lib) Proton layouts
        env.put("LD_LIBRARY_PATH",
                new File(rootDir, "usr/lib").getAbsolutePath() + ":"
                + new File(rootDir, "usr/lib/aarch64-linux-gnu").getAbsolutePath() + ":"
                + new File(rootDir, "usr/local/lib").getAbsolutePath() + ":"
                + new File(protonDir, "lib").getAbsolutePath() + ":"
                + new File(protonDir, "files/lib").getAbsolutePath());
        env.put("LANG", "en_US.UTF-8");
        env.put("TERM", "xterm-256color");
        env.put("TMPDIR", tmpDir.getAbsolutePath());
        env.put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());

        // Wine prefix — unpack prefixPack.txz if present (Proton pre-built prefix)
        File winePrefix = new File(homeDir, ".wine");
        if (!winePrefix.exists() || winePrefix.list() == null || winePrefix.list().length == 0) {
            winePrefix.mkdirs();
            // Check for prefixPack.txz in Proton and unpack it
            File prefixPack = new File(protonDir, "prefixPack.txz");
            if (prefixPack.exists()) {
                Log.i(TAG, "Unpacking Proton prefix pack: " + prefixPack);
                try {
                    io.waylandie.display.shared.io.TarCompressorUtils.extractFileWithType(
                            prefixPack, winePrefix,
                            io.waylandie.display.shared.io.TarCompressorUtils.Type.XZ, null);
                    Log.i(TAG, "Prefix pack unpacked to " + winePrefix);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to unpack prefix pack: " + e.getMessage()
                            + " — Wine will create a new prefix on first run");
                }
            }
        }
        env.put("WINEPREFIX", winePrefix.getAbsolutePath());
        env.put("WINEDLLOVERRIDES", "d3d9,d3d10core,d3d11,dxgi=native");
        env.put("DXVK_STATE_CACHE_PATH", new File(homeDir, ".dxvk-cache").getAbsolutePath());
        env.put("MESA_VK_WSI_PRESENT_MODE", "immediate");

        // Proton env
        env.put("PROTONPATH", protonDir.getAbsolutePath());
        env.put("STEAM_COMPAT_CLIENT_INSTALL_PATH",
                new File(context.getFilesDir(), "contents/steam").getAbsolutePath());
        env.put("STEAM_COMPAT_DATA_PATH",
                new File(context.getFilesDir(), "contents/steam/compatdata").getAbsolutePath());
        env.put("STEAM_RUNTIME", "0");

        // FEXCore env
        if (fexCoreInstalled && isArm64ec) {
            env.put("HODLL", "libarm64ecfex.dll");
            Log.i(TAG, "FEXCore arm64ec: HODLL=libarm64ecfex.dll");
        }

        // Vulkan ICD JSON
        File icdFile = new File(rootDir, "usr/local/etc/vulkan/icd.d/freedreno_icd.json");
        if (icdFile.isFile()) {
            env.put("VK_ICD_FILENAMES", icdFile.getAbsolutePath());
            env.put("VK_DRIVER_FILES", icdFile.getAbsolutePath());
        }

        // Wayland bridge env
        env.put("WAYLAND_DISPLAY", "waylandie");
        env.put("WAYLANDIE_BRIDGE_SOCKET", "waylandie.display.bridge.v1");
        env.put("WAYLANDIE_BRIDGE_PORT", "57391");
        env.put("WAYLANDIE_BRIDGE_PREFER", "abstract");
        env.put("WAYLANDIE_FINAL_COPY", "forbidden");
    }

    private void syncAdrenotoolsDriverToRootfs(String driverSoHostPath) {
        try {
            File srcSo = new File(driverSoHostPath);
            if (!srcSo.isFile()) return;
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
                pw.println("        \"library_path\": \"/usr/local/lib/libvulkan_freedreno.so\",");
                pw.println("        \"api_version\": \"1.3.0\"");
                pw.println("    }");
                pw.println("}");
            }
            Log.i(TAG, "Synced adrenotools driver: " + destSo);
        } catch (Exception e) {
            Log.e(TAG, "Failed to sync adrenotools driver", e);
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
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

    private static boolean isArm64ecWine(File wineBin) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(wineBin, "r")) {
            if (raf.length() < 20) return false;
            byte[] magic = new byte[4];
            raf.readFully(magic);
            if (magic[0] != 0x7f || magic[1] != 'E' || magic[2] != 'L' || magic[3] != 'F')
                return false;
            // CRITICAL: ELF e_machine is little-endian on ARM64.
            // readUnsignedShort() reads big-endian (Java default) — WRONG.
            // Must read bytes manually in little-endian order.
            raf.seek(18);
            int lo = raf.readUnsignedByte();
            int hi = raf.readUnsignedByte();
            int eMachine = (hi << 8) | lo;  // little-endian
            Log.i(TAG, "Wine ELF e_machine=" + eMachine + " (183=ARM64, 62=x86_64)");
            return eMachine == 183; // EM_AARCH64
        } catch (Exception e) {
            return false;
        }
    }
}
