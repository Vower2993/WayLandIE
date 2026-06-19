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

        // Bind-mount user-installed Proton into /opt/proton (if installed)
        File protonDir = new File(context.getFilesDir(), "contents/proton/active");
        if (protonDir.isDirectory()) {
            argv.add("-b");
            argv.add(protonDir.getAbsolutePath() + ":/opt/proton");
        }

        // Bind-mount user-installed DXVK into /opt/dxvk (if installed)
        File dxvkDir = new File(context.getFilesDir(), "contents/dxvk/active");
        if (dxvkDir.isDirectory()) {
            argv.add("-b");
            argv.add(dxvkDir.getAbsolutePath() + ":/opt/dxvk");
        }

        // Bind-mount user-installed Turnip into /opt/turnip (if installed)
        File turnipDir = new File(context.getFilesDir(), "contents/turnip/active");
        if (turnipDir.isDirectory()) {
            argv.add("-b");
            argv.add(turnipDir.getAbsolutePath() + ":/opt/turnip");
        }

        // Bind-mount user-installed FEX into /opt/fex (if installed)
        File fexDir = new File(context.getFilesDir(), "contents/fex/active");
        if (fexDir.isDirectory()) {
            argv.add("-b");
            argv.add(fexDir.getAbsolutePath() + ":/opt/fex");
        }

        // Link back to the app's bridge socket path so Wine processes can
        // connect to the abstract socket waylandie.display.bridge.v1
        // (proot allows abstract socket access by default — no bind needed).

        // Pass through the command
        argv.add("--");
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
        pb.environment().put("PATH", "/usr/bin:/bin:/usr/local/bin:"
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

        // Determine whether to use Proton or bare Wine.
        // Proton is installed via Settings tab → extracted to app-private storage.
        // Wine is NOT in the rootfs — if no Proton, we can't run.
        File protonDir = new File(context.getFilesDir(), "contents/proton/active");
        File wineFromProton = new File(protonDir, "files/bin/wine");
        File wineFromProtonAlt = new File(protonDir, "dist/bin/wine");

        if (useProton || (protonDir.exists() && (wineFromProton.exists() || wineFromProtonAlt.exists()))) {
            // Use Proton's bundled wine
            File wineBin = wineFromProton.exists() ? wineFromProton : wineFromProtonAlt;
            if (!wineBin.exists()) {
                throw new IOException("Proton installed but wine binary not found at "
                        + wineFromProton + " or " + wineFromProtonAlt);
            }
            cmd.add(wineBin.getAbsolutePath());
            Log.i(TAG, "Using Proton wine: " + wineBin);
        } else {
            throw new IOException("No Proton installed. Install Proton via Settings tab first. "
                    + "(Wine is not bundled in the rootfs — Proton provides Wine.)");
        }

        cmd.add(exePath);
        if (extraArgs != null) {
            cmd.addAll(java.util.Arrays.asList(extraArgs));
        }

        java.util.List<String> env = new java.util.ArrayList<>();
        env.add("WINEPREFIX"); env.add(imageFs.getWinePrefix().getAbsolutePath());
        env.add("WINEDLLOVERRIDES"); env.add("d3d9,d3d10core,d3d11,dxgi=native");
        env.add("DXVK_STATE_CACHE_PATH"); env.add("/home/xuser/.dxvk-cache");
        env.add("MESA_VK_WSI_PRESENT_MODE"); env.add("immediate");

        // Proton env vars
        if (protonDir.exists()) {
            env.add("PROTONPATH"); env.add(protonDir.getAbsolutePath());
            env.add("STEAM_COMPAT_CLIENT_INSTALL_PATH");
            env.add(new File(context.getFilesDir(), "contents/steam").getAbsolutePath());
            env.add("STEAM_COMPAT_DATA_PATH");
            env.add(new File(context.getFilesDir(), "contents/steam/compatdata").getAbsolutePath());
            env.add("STEAM_RUNTIME"); env.add("0");  // no pressure-vessel in proot
        }

        // Use active Adrenotools driver if one is set, otherwise rootfs Turnip
        io.waylandie.display.runtime.content.AdrenotoolsManager atm =
                new io.waylandie.display.runtime.content.AdrenotoolsManager(context);
        String activeDriverSo = atm.getActiveDriverSoPath();
        if (activeDriverSo != null) {
            env.add("VULKAN_ADRENOTOOLS_DRIVER_SO"); env.add(activeDriverSo);
            env.add("VK_ICD_FILENAMES"); env.add("/usr/local/etc/vulkan/icd.d/freedreno_icd.json");
            Log.i(TAG, "Using Adrenotools driver: " + activeDriverSo);
        } else {
            env.add("VK_ICD_FILENAMES"); env.add("/usr/local/etc/vulkan/icd.d/freedreno_icd.json");
            Log.i(TAG, "Using rootfs default Vulkan driver");
        }

        return exec(cmd.toArray(new String[0]), env.toArray(new String[0]));
    }

    /** @deprecated Use {@link #execWine(String, String[], boolean)} */
    @Deprecated
    public Process execWine(String exePath, String[] extraArgs) throws IOException {
        return execWine(exePath, extraArgs, false);
    }
}
