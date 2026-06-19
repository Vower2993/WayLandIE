package io.waylandie.display.runtime.environment;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ProotRunner — execs a command inside the bundled rootfs using a static
 * proot binary. The proot binary is bundled as a native library
 * ({@code libproot.so} in the APK's jniLibs directory) and made
 * executable at runtime.
 *
 * <p>This is the WinNative/Winlator pattern: bundle proot + rootfs as APK
 * assets, exec via {@link Runtime#exec(String[], String[], File)}. No
 * bundled rootfs, no missing packages, no repo issues.
 *
 * <p>Usage:
 * <pre>
 * ProotRunner runner = new ProotRunner(context);
 * Process p = runner.exec(
 *     "wine", "/sdcard/Download/game.exe",
 *     env("WINEPREFIX", "/home/xuser/.wine"),
 *     env("WAYLAND_DISPLAY", "waylandie"));
 * </pre>
 */
public final class ProotRunner {

    private static final String TAG = "WayLandIE/Proot";

    private final Context context;
    private final ImageFsManager imageFs;
    private final File prootBin;

    public ProotRunner(Context context) {
        this.context = context;
        this.imageFs = new ImageFsManager(context);
        // proot ships as libproot.so in jniLibs (Android requires .so
        // extension for files in lib/arm64-v8a). We rename + chmod +x
        // at runtime.
        this.prootBin = new File(context.getApplicationInfo().nativeLibraryDir, "libproot.so");
    }

    /**
     * Returns true if both the rootfs and proot binary are present and
     * usable.
     */
    public boolean isReady() {
        return imageFs.isValid() && prootBin.exists();
    }

    /**
     * Builds the proot command-line for running {@code cmd} inside the
     * rootfs. The returned array can be passed to {@link ProcessBuilder}.
     *
     * <p>The rootfs is bind-mounted read-only. Writable bind-mounts are
     * set up for:
     * <ul>
     *   <li>{@code /home/xuser} (user home, persists Wine prefix)</li>
     *   <li>{@code /tmp} (temp files)</li>
     *   <li>{@code /sdcard} (so games can read .exe files)</li>
     * </ul>
     */
    public String[] buildProotCommand(String[] cmd, String[] env) {
        List<String> argv = new ArrayList<>();
        argv.add(prootBin.getAbsolutePath());

        // Rootfs = our extracted imagefs
        argv.add("-r");
        argv.add(imageFs.getRootDir().getAbsolutePath());

        // Don't actually need root — proot runs unprivileged
        argv.add("-w");
        argv.add("/home/xuser");

        // Bind mounts — read-only system, writable user dirs
        argv.add("-b");
        argv.add("/dev:/dev");
        argv.add("-b");
        argv.add("/dev/urandom:/dev/random");
        argv.add("-b");
        argv.add("/proc:/proc");
        argv.add("-b");
        argv.add("/sys:/sys");

        // /dev/kgsl-3d0 for Turnip KGSL driver (Adreno GPU)
        File kgsl = new File("/dev/kgsl-3d0");
        if (kgsl.exists()) {
            argv.add("-b");
            argv.add("/dev/kgsl-3d0:/dev/kgsl-3d0");
        }

        // /dev/dri for DRM render nodes
        File dri = new File("/dev/dri");
        if (dri.exists()) {
            argv.add("-b");
            argv.add("/dev/dri:/dev/dri");
        }

        // /sdcard read-only so games can read .exe files
        argv.add("-b");
        argv.add("/sdcard:/sdcard");
        argv.add("-b");
        argv.add("/storage:/storage:ro");

        // Writable home + tmp inside rootfs
        File homeDir = imageFs.getHomeDir();
        File tmpDir = imageFs.getTmpDir();
        if (!homeDir.exists()) homeDir.mkdirs();
        if (!tmpDir.exists()) tmpDir.mkdirs();
        argv.add("-b");
        argv.add(homeDir.getAbsolutePath() + ":/home/xuser");
        argv.add("-b");
        argv.add(tmpDir.getAbsolutePath() + ":/tmp");

        // Bind-mount the user-contents directory (where driver slots +
        // 'active' symlinks live) into the rootfs at /waylandie-contents.
        // This is writable so waylandie-install-driver can create slots
        // and 'active' symlinks here. Java checks
        // getFilesDir()/contents/<kind>/active which resolves to the
        // same physical path — bridging the install script (writes
        // inside proot at /waylandie-contents) and Java (reads from
        // app-private storage). WITHOUT this bind mount, the script
        // would write inside the rootfs and Java would never find the
        // installed driver.
        File contentsDir = new File(context.getFilesDir(), "contents");
        if (!contentsDir.exists()) contentsDir.mkdirs();
        argv.add("-b");
        argv.add(contentsDir.getAbsolutePath() + ":/waylandie-contents");

        // Bind-mount the waylandie-* helper scripts (waylandie-install-driver,
        // waylandie-doctor, etc.) from app-private storage into /waylandie-scripts
        // inside the rootfs. AssetInstaller extracts these from APK assets on
        // every app launch. Without this bind mount, bash inside proot can't
        // find waylandie-install-driver and ALL driver installs fail with
        // "command not found".
        //
        // CRITICAL: Do NOT bind to /usr/local/bin — the rootfs build script
        // (build-imagefs.sh step 3) installs box86 + box64 into /usr/local/bin.
        // Binding over it would make those emulators invisible inside proot,
        // breaking x86/x64 emulation that Proton depends on. Use a dedicated
        // /waylandie-scripts path instead, and add it to PATH in exec().
        //
        // Android 16 mount-point safety: create the guest directory inside
        // the rootfs BEFORE proot starts. On some Android 16 devices, proot
        // cannot dynamically synthesize new mount points inside the guest
        // unless the directory already exists.
        File scriptsHostDir = new File(context.getFilesDir(), "linux-runtime/bin");
        if (scriptsHostDir.isDirectory()) {
            File guestScriptsDir = new File(imageFs.getRootDir(), "waylandie-scripts");
            if (!guestScriptsDir.exists()) guestScriptsDir.mkdirs();
            File guestContentsDir = new File(imageFs.getRootDir(), "waylandie-contents");
            if (!guestContentsDir.exists()) guestContentsDir.mkdirs();
            argv.add("-b");
            argv.add(scriptsHostDir.getAbsolutePath() + ":/waylandie-scripts");
        } else {
            Log.w(TAG, "linux-runtime/bin not found at " + scriptsHostDir
                    + " — driver installs will fail. AssetInstaller may not have run.");
        }

        // Bind-mount user-installed Proton into /opt/proton (if installed).
        // Android 16 safety: create the guest mount-point directory in the
        // rootfs before proot starts.
        File protonDir = new File(context.getFilesDir(), "contents/proton/active");
        if (protonDir.isDirectory()) {
            File guestProton = new File(imageFs.getRootDir(), "opt/proton");
            if (!guestProton.exists()) guestProton.mkdirs();
            argv.add("-b");
            argv.add(protonDir.getAbsolutePath() + ":/opt/proton");
        }

        // Bind-mount user-installed DXVK into /opt/dxvk (if installed)
        File dxvkDir = new File(context.getFilesDir(), "contents/dxvk/active");
        if (dxvkDir.isDirectory()) {
            File guestDxvk = new File(imageFs.getRootDir(), "opt/dxvk");
            if (!guestDxvk.exists()) guestDxvk.mkdirs();
            argv.add("-b");
            argv.add(dxvkDir.getAbsolutePath() + ":/opt/dxvk");
        }

        // Bind-mount user-installed Turnip into /opt/turnip (if installed)
        File turnipDir = new File(context.getFilesDir(), "contents/turnip/active");
        if (turnipDir.isDirectory()) {
            File guestTurnip = new File(imageFs.getRootDir(), "opt/turnip");
            if (!guestTurnip.exists()) guestTurnip.mkdirs();
            argv.add("-b");
            argv.add(turnipDir.getAbsolutePath() + ":/opt/turnip");
        }

        // Bind-mount user-installed FEX into /opt/fex (if installed)
        File fexDir = new File(context.getFilesDir(), "contents/fex/active");
        if (fexDir.isDirectory()) {
            File guestFex = new File(imageFs.getRootDir(), "opt/fex");
            if (!guestFex.exists()) guestFex.mkdirs();
            argv.add("-b");
            argv.add(fexDir.getAbsolutePath() + ":/opt/fex");
        }

        // Link back to the app's bridge socket path so Wine processes can
        // connect to the abstract socket waylandie.display.bridge.v1
        // (proot allows abstract socket access by default — no bind needed).

        // Pass through the command. NOTE: do NOT add "--" before the command —
        // the bundled proot binary (libproot.so, a static proot build) does NOT
        // support the "--" option terminator and exits with
        // "proot error: unknown option '--'". Just append the command directly.
        argv.addAll(Arrays.asList(cmd));

        return argv.toArray(new String[0]);
    }

    /**
     * Convenience: execs a command inside the rootfs and returns the
     * {@link Process}. Caller is responsible for draining I/O.
     */
    public Process exec(String[] cmd, String[] env) throws IOException {
        if (!isReady()) {
            throw new IOException("ProotRunner not ready: imagefs valid="
                    + imageFs.isValid() + ", proot exists=" + prootBin.exists());
        }

        // Make sure proot is executable
        if (!prootBin.canExecute()) {
            try {
                new ProcessBuilder("chmod", "+x", prootBin.getAbsolutePath())
                        .redirectErrorStream(true).start().waitFor();
            } catch (Exception e) {
                Log.w(TAG, "chmod +x proot failed", e);
            }
        }

        String[] prootCmd = buildProotCommand(cmd, env);

        Log.i(TAG, "Exec: " + String.join(" ", prootCmd));

        ProcessBuilder pb = new ProcessBuilder(prootCmd);
        pb.redirectErrorStream(true);

        // Environment — pass through only what we explicitly set
        pb.environment().clear();
        if (env != null) {
            for (int i = 0; i < env.length; i += 2) {
                if (i + 1 < env.length) {
                    pb.environment().put(env[i], env[i + 1]);
                }
            }
        }

        // Always set these
        pb.environment().put("HOME", "/home/xuser");
        pb.environment().put("USER", "xuser");
        pb.environment().put("PATH", "/waylandie-scripts:/usr/local/bin:/usr/bin:/bin:"
                + imageFs.getWineDir().getAbsolutePath() + "/bin");
        pb.environment().put("LD_LIBRARY_PATH", "/usr/lib:/usr/local/lib:"
                + imageFs.getWineDir().getAbsolutePath() + "/lib");
        pb.environment().put("LANG", "en_US.UTF-8");
        pb.environment().put("TERM", "xterm-256color");
        pb.environment().put("TMPDIR", "/tmp");
        pb.environment().put("XDG_RUNTIME_DIR", "/tmp");

        // Contents dir — where driver slots + 'active' symlinks live.
        // waylandie-install-driver reads this to know where to install.
        // Must match the bind-mount in buildProotCommand().
        pb.environment().put("WAYLANDIE_CONTENTS_DIR", "/waylandie-contents");

        // WaylandIE bridge env
        pb.environment().put("WAYLAND_DISPLAY", "waylandie");
        pb.environment().put("WAYLANDIE_BRIDGE_SOCKET", "waylandie.display.bridge.v1");
        pb.environment().put("WAYLANDIE_BRIDGE_PORT", "57391");
        pb.environment().put("WAYLANDIE_BRIDGE_PREFER", "abstract");
        pb.environment().put("WAYLANDIE_FINAL_COPY", "forbidden");

        return pb.start();
    }

    /**
     * Convenience: execs a single command with the WaylandIE env pre-set.
     * Useful for one-off calls like {@code exec("waylandie-doctor")}.
     */
    public Process exec(String command) throws IOException {
        return exec(new String[]{"bash", "-lc", command}, null);
    }

    /**
     * Convenience: execs Wine with the given .exe path. Uses the active
     * Adrenotools driver if one is installed, otherwise falls back to the
     * rootfs's default Turnip. Proton is used if installed via Settings
     * tab and the user ticked "Use Proton".
     */
    public Process execWine(String exePath, String[] extraArgs, boolean useProton) throws IOException {
        java.util.List<String> cmd = new java.util.ArrayList<>();

        // Proton is installed via Settings tab → extracted to app-private storage.
        // Wine is NOT in the rootfs — if no Proton, we can't run.
        File protonDir = new File(context.getFilesDir(), "contents/proton/active");
        if (!protonDir.exists()) {
            throw new IOException("Proton is not installed. Please go to the Settings tab and install Proton first. "
                    + "(Wine is not bundled in the rootfs — Proton provides the Wine environment.)");
        }

        // Check for wine binary at known paths (HOST paths for validation)
        File wineFromProton = new File(protonDir, "files/bin/wine");
        File wineFromProtonAlt = new File(protonDir, "dist/bin/wine");
        File wineFromProtonBin = new File(protonDir, "bin/wine");

        File wineBinHost = null;
        String wineGuestPath = null;
        if (wineFromProton.exists()) {
            wineBinHost = wineFromProton;
            wineGuestPath = "/opt/proton/files/bin/wine";
        } else if (wineFromProtonAlt.exists()) {
            wineBinHost = wineFromProtonAlt;
            wineGuestPath = "/opt/proton/dist/bin/wine";
        } else if (wineFromProtonBin.exists()) {
            wineBinHost = wineFromProtonBin;
            wineGuestPath = "/opt/proton/bin/wine";
        }

        if (wineBinHost == null) {
            throw new IOException("Proton installation found at '" + protonDir.getAbsolutePath()
                    + "' but no wine binary was found inside it. Checked: "
                    + "files/bin/wine, dist/bin/wine, and bin/wine. "
                    + "Please try reinstalling Proton or choose a different Proton build.");
        }

        // CRITICAL: Use GUEST path (not HOST path) for the command.
        // The command runs inside proot where the root is imagefs.
        // The HOST path (/data/user/0/.../contents/proton/active/files/bin/wine)
        // doesn't exist inside proot — it resolves to rootfs/data/user/0/...
        // which doesn't exist. The GUEST path /opt/proton/files/bin/wine is
        // accessible via the bind mount in buildProotCommand().
        cmd.add(wineGuestPath);
        Log.i(TAG, "Using Proton wine (guest path): " + wineGuestPath
                + " (host: " + wineBinHost + ")");

        cmd.add(exePath);
        if (extraArgs != null) {
            cmd.addAll(java.util.Arrays.asList(extraArgs));
        }

        java.util.List<String> env = new java.util.ArrayList<>();
        // CRITICAL: WINEPREFIX must be the GUEST path, not the host path.
        // Inside proot, /home/xuser is bind-mounted from imagefs/home/xuser.
        env.add("WINEPREFIX"); env.add("/home/xuser/.wine");
        env.add("WINEDLLOVERRIDES"); env.add("d3d9,d3d10core,d3d11,dxgi=native");
        env.add("DXVK_STATE_CACHE_PATH"); env.add("/home/xuser/.dxvk-cache");
        env.add("MESA_VK_WSI_PRESENT_MODE"); env.add("immediate");

        // Proton env vars — all GUEST paths
        env.add("PROTONPATH"); env.add("/opt/proton");
        env.add("STEAM_COMPAT_CLIENT_INSTALL_PATH"); env.add("/home/xuser/.local/share/Steam");
        env.add("STEAM_COMPAT_DATA_PATH"); env.add("/home/xuser/.proton-prefix");
        env.add("STEAM_RUNTIME"); env.add("0");  // no pressure-vessel in proot

        // FEX env vars (if installed)
        File fexDir = new File(context.getFilesDir(), "contents/fex/active");
        if (fexDir.isDirectory()) {
            env.add("FEX_ROOT"); env.add("/opt/fex");
            Log.i(TAG, "FEX enabled: /opt/fex");
        }

        // Vulkan driver — ICD JSON approach.
        //
        // ARCHITECTURE NOTE: WayLandIE runs Wine inside proot. The Vulkan
        // loader runs INSIDE the proot guest, not on the Android host.
        // adrenotools hooking (adrenotools_open_libvulkan) hooks libvulkan.so
        // on the HOST and returns a handle for the host's Vulkan renderer.
        // Wine inside proot loads its OWN libvulkan.so from the rootfs —
        // it has no connection to host-side hooks. So adrenotools hooking
        // CANNOT work in this architecture.
        //
        // Instead, ALL Vulkan drivers (Turnip, adrenotools, Qualcomm) use
        // the standard ICD JSON mechanism:
        //   1. The .so is copied into the rootfs at /usr/local/lib/
        //   2. An ICD JSON at /usr/local/etc/vulkan/icd.d/ points to it
        //   3. VK_ICD_FILENAMES tells the Vulkan loader where to find the JSON
        //
        // The ICD JSON is created during driver install (SettingsActivity
        // installTurnipIcd). If an adrenotools driver is installed, its .so
        // is synced into the rootfs + ICD JSON updated (see
        // syncAdrenotoolsDriverToRootfs below).
        env.add("VK_ICD_FILENAMES"); env.add("/usr/local/etc/vulkan/icd.d/freedreno_icd.json");
        env.add("VK_DRIVER_FILES"); env.add("/usr/local/etc/vulkan/icd.d/freedreno_icd.json");

        // If an adrenotools driver is active, sync its .so into the rootfs
        // + update ICD JSON. This makes the adrenotools driver available to
        // Wine via the standard ICD mechanism (since adrenotools hooking
        // can't work inside proot).
        io.waylandie.display.runtime.content.AdrenotoolsManager atm =
                new io.waylandie.display.runtime.content.AdrenotoolsManager(context);
        String activeDriverSo = atm.getActiveDriverSoPath();
        if (activeDriverSo != null) {
            syncAdrenotoolsDriverToRootfs(activeDriverSo);
            Log.i(TAG, "Using Adrenotools driver (synced to rootfs): " + activeDriverSo);
        } else {
            Log.i(TAG, "Using rootfs Turnip driver (VK_ICD_FILENAMES=/usr/local/etc/vulkan/icd.d/freedreno_icd.json)");
        }

        return exec(cmd.toArray(new String[0]), env.toArray(new String[0]));
    }

    /**
     * Copies the active adrenotools driver .so into the rootfs and updates
     * the ICD JSON to point to it. Necessary because adrenotools hooking
     * (adrenotools_open_libvulkan) cannot work inside proot — Wine loads
     * its own libvulkan.so from the rootfs with no connection to host-side
     * hooks. Standard ICD JSON mechanism is used instead.
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
            // Update ICD JSON to point to rootfs-internal path
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

    /** @deprecated Use {@link #execWine(String, String[], boolean)} */
    @Deprecated
    public Process execWine(String exePath, String[] extraArgs) throws IOException {
        return execWine(exePath, extraArgs, false);
    }
}
