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
 * WineRunner — launches Wine directly using glibc-native execution.
 *
 * <p><b>Architecture:</b> No proot. Wine runs directly on the device's
 * CPU via box64 (x86_64 emulation). The rootfs provides glibc libraries
 * that box64 loads for the emulated Wine process. This is the same
 * architecture used by winlator (StevenMXZ/Winlator-Ludashi) and is
 * 10-30% faster than proot (no syscall translation overhead).
 *
 * <p><b>How it works:</b>
 * <ol>
 *   <li>The glibc dynamic linker ({@code rootfs/lib/ld-linux-aarch64.so.1})
 *       is invoked explicitly. This is necessary because our box64 is a
 *       glibc binary (from ptitSeb's Rootfs release), not a bionic binary.
 *       Android's linker can't load it directly — we need glibc's linker.</li>
 *   <li>The glibc linker loads glibc from {@code rootfs/usr/lib}, then
 *       loads box64 from {@code rootfs/usr/local/bin/box64}.</li>
 *   <li>box64 emulates x86_64 and loads Wine from the Proton installation
 *       at {@code contents/proton/active/files/bin/wine}.</li>
 *   <li>Wine runs the .exe, using glibc + Vulkan libraries from the rootfs.</li>
 * </ol>
 *
 * <p><b>Path model:</b> Unlike proot, there is ONE path space — host paths.
 * The rootfs at {@code getFilesDir()/imagefs/} is accessed via direct
 * absolute paths (e.g. {@code /data/.../imagefs/usr/lib}). No bind mounts,
 * no guest/host translation, no {@code --} option issues.
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

        // 1. Find the glibc dynamic linker in the rootfs.
        // debootstrap trixie installs it at /lib/ld-linux-aarch64.so.1
        File linker = new File(rootDir, "lib/ld-linux-aarch64.so.1");
        if (!linker.isFile()) {
            // Fallback: some rootfs layouts use /lib64/
            linker = new File(rootDir, "lib64/ld-linux-aarch64.so.1");
        }
        if (!linker.isFile()) {
            throw new IOException("glibc dynamic linker not found in rootfs. "
                    + "Looked for: " + rootDir + "/lib/ld-linux-aarch64.so.1 "
                    + "and " + rootDir + "/lib64/ld-linux-aarch64.so.1. "
                    + "The rootfs may be corrupt or incomplete. Try clearing "
                    + "app data to re-extract.");
        }

        // 2. Find Proton's Wine binary (HOST path — direct, no translation)
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

        // 3. Find box64 (x86_64 emulator). Our rootfs installs it at
        // /usr/local/bin/box64 (glibc binary from ptitSeb's Rootfs release).
        File box64 = new File(rootDir, "usr/local/bin/box64");
        if (!box64.exists()) {
            box64 = new File(rootDir, "usr/bin/box64");
        }
        // Make box64 executable
        if (box64.exists()) {
            box64.setExecutable(true, false);
        }

        // 4. Build the command:
        //    [linker] --library-path [libs] [box64] [wine] [exePath] [args]
        //
        // The glibc linker loads glibc from rootfs/usr/lib, then loads box64.
        // box64 then loads Wine (x86_64 binary) and emulates it.
        //
        // If box64 doesn't exist (e.g., Wine is arm64ec native), we try
        // launching wine directly through the linker. This may or may not
        // work depending on the Wine binary's architecture.
        List<String> cmd = new ArrayList<>();
        cmd.add(linker.getAbsolutePath());
        cmd.add("--library-path");
        cmd.add(new File(rootDir, "usr/lib").getAbsolutePath() + ":"
                + new File(rootDir, "usr/local/lib").getAbsolutePath());
        if (box64.exists()) {
            cmd.add(box64.getAbsolutePath());
            Log.i(TAG, "Using box64: " + box64);
        } else {
            Log.w(TAG, "box64 not found — launching wine directly (may fail for x86_64 binaries)");
        }
        cmd.add(wineBin.getAbsolutePath());
        cmd.add(exePath);
        if (extraArgs != null) {
            cmd.addAll(Arrays.asList(extraArgs));
        }

        Log.i(TAG, "Command: " + String.join(" ", cmd));
        Log.i(TAG, "Wine binary: " + wineBin);
        Log.i(TAG, "Working dir: " + rootDir);

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

        // FEX environment (if installed)
        File fexDir = new File(context.getFilesDir(), "contents/fex/active");
        if (fexDir.isDirectory()) {
            env.put("FEX_ROOT", fexDir.getAbsolutePath());
            Log.i(TAG, "FEX enabled: " + fexDir);
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
