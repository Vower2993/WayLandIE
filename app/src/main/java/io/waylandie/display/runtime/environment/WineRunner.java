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

    // Static buffer for pre-launch diagnostics. GameLaunchTracer reads this
    // after WineRunner.execWine() returns and appends it to the trace file.
    public static final StringBuilder preLaunchDiagnostics = new StringBuilder();

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
     * Returns the device's screen size in landscape orientation as [width, height].
     *
     * <p>The bridge translator requires output-width and output-height as
     * argv[7] and argv[8]. Using the real display size ensures the Wayland
     * output advertises the correct resolution to Wine. Falls back to the
     * bridge's built-in defaults (2688x1216) if the display can't be queried.
     */
    private String[] getDisplaySize() {
        try {
            android.view.WindowManager wm = (android.view.WindowManager)
                    context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && wm.getDefaultDisplay() != null) {
                android.graphics.Point size = new android.graphics.Point();
                wm.getDefaultDisplay().getRealSize(size);
                // Landscape: width = larger dimension
                int w = Math.max(size.x, size.y);
                int h = Math.min(size.x, size.y);
                if (w > 0 && h > 0) {
                    return new String[]{String.valueOf(w), String.valueOf(h)};
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get display size: " + e.getMessage());
        }
        // Bridge defaults (match waylandie-wayland-bridge.c main() fallback)
        return new String[]{"2688", "1216"};
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
     *
     * <p>For GLIBC Wine: uses libld_glibc.so linker trick (the glibc linker
     * is in nativeLibraryDir where SELinux allows exec, then it loads Wine
     * from getFilesDir() — the linker's mmap doesn't trigger W^X).
     *
     * <p>For BIONIC Wine: cannot exec directly from getFilesDir() — Android's
     * W^X enforcement (targetSdk >= 29) blocks execve() of binaries in app
     * data directories. Instead, route through PRoot (libproot.so in
     * nativeLibraryDir) which uses ptrace to intercept the child's execve()
     * and bypass the W^X check. PRoot has syscall translation overhead but
     * is the only way to exec bionic binaries from getFilesDir() on
     * Android 10+ with targetSdk >= 29. This is exactly how Winlator and
     * Termux handle the same problem.
     */
    private Process launchNative(File rootDir, String nativeLibDir,
            File wineBin, String exePath, String[] extraArgs,
            boolean isArm64ec, boolean fexCoreInstalled, File protonDir)
            throws IOException {

        // BIONIC Wine detection — use native launcher stub to bypass W^X.
        // The launcher (libwine_launcher.so) is in nativeLibraryDir where
        // Android allows execve. It then execve()'s the wine binary from
        // getFilesDir() — works because the launcher process has the right
        // SELinux context. This eliminates PRoot entirely (no ptrace overhead).
        boolean bionicWine = isBionicWine(wineBin);
        Log.i(TAG, "Wine variant: " + (bionicWine ? "BIONIC" : "GLIBC"));
        if (bionicWine) {
            Log.i(TAG, "Bionic Wine detected — launching via native launcher stub "
                    + "(libwine_launcher.so in nativeLibraryDir bypasses W^X; "
                    + "no PRoot needed, no ptrace overhead)");
            return launchViaNativeLauncher(rootDir, nativeLibDir, wineBin, exePath,
                    extraArgs, isArm64ec, fexCoreInstalled, protonDir);
        }

        File linker = new File(nativeLibDir, "libld_glibc.so");

        // XDG_RUNTIME_DIR — must match the path used by setupEnvironment()
        // (rootDir/usr/tmp/runtime) so the bridge writes socket-name.txt where
        // Wine will look for the Wayland socket.
        File runtimeDir = new File(new File(rootDir, "usr/tmp"), "runtime");
        if (!runtimeDir.exists()) runtimeDir.mkdirs();
        // Socket name the bridge creates (filled in after bridge starts; applied
        // to the Wine env below once env is built).
        String waylandSocketName = null;

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

        // Start bridge translator FIRST (in background).
        //
        // Two bridge variants exist:
        //   1. Bionic bridge (PREFERRED): libwaylandie_bridge.so in nativeLibDir.
        //      Compiled with NDK against bionic libc — no glibc, no SIGSYS.
        //      Launched DIRECTLY via ProcessBuilder (no glibc linker needed).
        //      This is the long-term fix for the Test F finding (libwayland-server
        //      glibc constructor triggers SIGSYS).
        //   2. Glibc bridge (FALLBACK): rootfs/usr/local/bin/waylandie-wayland-bridge.
        //      Compiled inside the Focal rootfs against glibc libwayland-server.
        //      Launched via libld_glibc.so --library-path ... (the original path).
        //      Used when the bionic bridge isn't in the APK (e.g., older build
        //      or bionic-libs/ was missing during the CMake step).
        File bionicBridge = new File(nativeLibDir, "libwaylandie_bridge.so");
        File bridgeBin = new File(rootDir, "usr/local/bin/waylandie-wayland-bridge");
        boolean useBionicBridge = bionicBridge.exists();
        if (useBionicBridge) {
            bionicBridge.setExecutable(true, false);
            Log.i(TAG, "Starting BIONIC bridge translator (direct launch, no glibc)…");
        } else if (bridgeBin.exists()) {
            bridgeBin.setExecutable(true, false);
            Log.i(TAG, "Bionic bridge not found — starting GLIBC bridge translator via native linker…");
        } else {
            Log.w(TAG, "Bridge translator not found (neither bionic " + bionicBridge + " nor glibc " + bridgeBin + ")");
        }

        if (useBionicBridge || bridgeBin.exists()) {
            try {
                List<String> bridgeCmd = new ArrayList<>();
                if (useBionicBridge) {
                    // Bionic bridge: direct launch (no linker, no --library-path)
                    bridgeCmd.add(bionicBridge.getAbsolutePath());
                } else {
                    // Glibc bridge: launch via libld_glibc.so
                    bridgeCmd.add(linker.getAbsolutePath());
                    bridgeCmd.add("--library-path");
                    bridgeCmd.add(libPath);
                    bridgeCmd.add(bridgeBin.getAbsolutePath());
                }
                // Bridge requires 8 args (argc=9): bridge_socket, target_commits,
                // socket_file, timeout_ms, clear_ahb, accept_client, output_width, output_height.
                // Without all 8, the bridge prints a usage message to stderr and exits(2).
                String[] displaySize = getDisplaySize();
                bridgeCmd.add("waylandie.display.bridge.v1");  // argv[1]: Android bridge socket name
                bridgeCmd.add("1");                              // argv[2]: target_commits
                bridgeCmd.add(new File(runtimeDir, "socket-name.txt").getAbsolutePath()); // argv[3]: socket file
                bridgeCmd.add("15000");                          // argv[4]: timeout_ms
                bridgeCmd.add("0");                              // argv[5]: clear_ahb_outside
                bridgeCmd.add("0");                              // argv[6]: accept_client_complete
                bridgeCmd.add(displaySize[0]);                   // argv[7]: output_width
                bridgeCmd.add(displaySize[1]);                   // argv[8]: output_height

                ProcessBuilder pbBridge = new ProcessBuilder(bridgeCmd);
                pbBridge.directory(rootDir);
                pbBridge.redirectErrorStream(true);
                Map<String, String> bridgeEnv = pbBridge.environment();
                bridgeEnv.clear();
                // For bionic bridge, we still set up the environment variables
                // (WAYLAND_DISPLAY, XDG_RUNTIME_DIR, etc.) but the bionic bridge
                // doesn't need LD_LIBRARY_PATH or the LD_PRELOAD shim — those
                // are glibc-specific. setupEnvironment() handles both paths.
                setupEnvironment(bridgeEnv, rootDir, protonDir, isArm64ec, fexCoreInstalled, useBionicBridge);
                Process bridgeProcess = pbBridge.start();
                Log.i(TAG, "Bridge translator started (pid=" + getPid(bridgeProcess)
                        + ", variant=" + (useBionicBridge ? "bionic" : "glibc") + ")");

                // Capture bridge output — if the bridge crashes we need to see why
                new Thread(() -> {
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(bridgeProcess.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            Log.i("WayLandIE/Bridge", line);
                            io.waylandie.display.shared.util.LogRingBuffer.append("[bridge] " + line);
                        }
                    } catch (java.io.IOException e) {
                        Log.w(TAG, "Bridge output stream closed: " + e.getMessage());
                    }
                }, "wl-bridge-output").start();
                // Give the bridge 2s to create the Wayland socket
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                // Read the socket name the bridge created (e.g. "wayland-0")
                // and stash it; applied to WAYLAND_DISPLAY below once the Wine
                // env is built (env doesn't exist yet in this scope).
                File socketNameFile = new File(runtimeDir, "socket-name.txt");
                if (socketNameFile.exists()) {
                    try {
                        String socketName = new String(java.nio.file.Files.readAllBytes(socketNameFile.toPath())).trim();
                        if (!socketName.isEmpty()) {
                            waylandSocketName = socketName;
                            Log.i(TAG, "Wayland socket: " + socketName + " (from bridge)");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not read socket name: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "Bridge failed to start: " + e.getMessage());
            }
        }

        // Build Wine command — GLIBC path only (bionic was routed to PRoot above).
        //   libld_glibc.so --library-path <rootfs-libs> wine exePath
        //   - Wine's ELF interpreter is /lib/ld-linux-aarch64.so.1 (glibc)
        //   - We can't exec it directly because Android SELinux blocks execve
        //     of binaries with glibc interpreter from app data dirs
        //   - So we launch the glibc linker (packaged as libld_glibc.so in
        //     nativeLibraryDir, where SELinux allows execve) and pass it
        //     --library-path + the wine binary path
        List<String> cmd = new ArrayList<>();
        cmd.add(linker.getAbsolutePath());
        cmd.add("--library-path");
        cmd.add(libPath);
        cmd.add(wineBin.getAbsolutePath());
        cmd.add(exePath);
        if (extraArgs != null) {
            for (String arg : extraArgs) cmd.add(arg);
        }

        Log.i(TAG, "Native launch command (glibc): " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(rootDir);
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        env.clear();
        setupEnvironment(env, rootDir, protonDir, isArm64ec, fexCoreInstalled);

        // Override WAYLAND_DISPLAY with the actual socket name the bridge
        // created (e.g. "wayland-0") — setupEnvironment() sets it to the
        // default "waylandie" guess, which won't match wl_display_add_socket_auto().
        if (waylandSocketName != null) {
            env.put("WAYLAND_DISPLAY", waylandSocketName);
        }

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
     * Launch bionic Wine via the native launcher stub (libwine_launcher.so).
     *
     * <p>The launcher is a tiny C program packaged in jniLibs. Android
     * extracts it to nativeLibraryDir at install time, where SELinux
     * allows execve(). The launcher then execve()'s the actual wine binary
     * from getFilesDir() — works because the launcher process inherits
     * the app's SELinux context, which CAN exec from nativeLibraryDir.
     *
     * <p>This eliminates PRoot entirely. PRoot was adding 2-5x overhead
     * to every syscall (ptrace traps), which is brutal for games.
     *
     * <p>The launcher does NOT do path translation or bind mounts. Instead:
     * <ul>
     *   <li>Wine runs with the REAL Android filesystem (no fake rootfs view)</li>
     *   <li>LD_LIBRARY_PATH points at the rootfs libs (so libwine.so etc. load from there)</li>
     *   <li>HOME, WINEPREFIX, PATH etc. point at the rootfs paths</li>
     *   <li>/dev, /proc, /sys are Android's real ones (accessible from app processes)</li>
     * </ul>
     *
     * <p>Launch sequence:
     * <pre>
     *   libwine_launcher.so &lt;wineBinary&gt; &lt;exePath&gt; [extraArgs...]
     * </pre>
     * Environment (set by Java before exec):
     * <pre>
     *   LD_LIBRARY_PATH = rootfs/usr/lib:rootfs/usr/lib/aarch64-linux-gnu:proton/lib:...
     *   HOME            = rootfs/home/xuser
     *   WINEPREFIX      = rootfs/home/xuser/.wine
     *   PATH            = proton/bin:rootfs/usr/bin:...
     *   (plus all other Wine env vars from setupEnvironment)
     * </pre>
     */
    private Process launchViaNativeLauncher(File rootDir, String nativeLibDir,
            File wineBin, String exePath, String[] extraArgs,
            boolean isArm64ec, boolean fexCoreInstalled, File protonDir)
            throws IOException {

        File launcher = new File(nativeLibDir, "libwine_launcher.so");
        if (!launcher.exists()) {
            throw new IOException("Native wine launcher not found at " + launcher
                    + " — was libwine_launcher.so built and packaged in jniLibs?");
        }
        launcher.setExecutable(true, false);

        // XDG_RUNTIME_DIR — must match what setupEnvironment() sets
        File runtimeDir = new File(new File(rootDir, "usr/tmp"), "runtime");
        if (!runtimeDir.exists()) runtimeDir.mkdirs();

        // Start the bionic bridge FIRST (same as launchNative).
        // The bridge is also bionic-compiled, runs in nativeLibraryDir, no PRoot needed.
        File bionicBridge = new File(nativeLibDir, "libwaylandie_bridge.so");
        if (bionicBridge.exists()) {
            bionicBridge.setExecutable(true, false);
            Log.i(TAG, "Starting BIONIC bridge translator (direct launch)…");
            try {
                List<String> bridgeCmd = new ArrayList<>();
                bridgeCmd.add(bionicBridge.getAbsolutePath());
                String[] displaySize = getDisplaySize();
                bridgeCmd.add("waylandie.display.bridge.v1");
                bridgeCmd.add("1");
                bridgeCmd.add(new File(runtimeDir, "socket-name.txt").getAbsolutePath());
                bridgeCmd.add("15000");
                bridgeCmd.add("0");
                bridgeCmd.add("0");
                bridgeCmd.add(displaySize[0]);
                bridgeCmd.add(displaySize[1]);

                ProcessBuilder pbBridge = new ProcessBuilder(bridgeCmd);
                pbBridge.directory(rootDir);
                pbBridge.redirectErrorStream(true);
                Map<String, String> bridgeEnv = pbBridge.environment();
                bridgeEnv.clear();
                setupEnvironment(bridgeEnv, rootDir, protonDir, isArm64ec, fexCoreInstalled, true);
                Process bridgeProcess = pbBridge.start();
                Log.i(TAG, "Bridge translator started (pid=" + getPid(bridgeProcess)
                        + ", variant=bionic)");

                new Thread(() -> {
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(bridgeProcess.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            Log.i("WayLandIE/Bridge", line);
                            io.waylandie.display.shared.util.LogRingBuffer.append("[bridge] " + line);
                        }
                    } catch (java.io.IOException e) {
                        Log.w(TAG, "Bridge output stream closed: " + e.getMessage());
                    }
                }, "wl-bridge-output").start();
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            } catch (IOException e) {
                Log.w(TAG, "Bridge failed to start: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "Bionic bridge not found at " + bionicBridge);
        }

        // Read the socket name the bridge created
        String waylandSocketName = null;
        File socketNameFile = new File(runtimeDir, "socket-name.txt");
        if (socketNameFile.exists()) {
            try {
                String socketName = new String(java.nio.file.Files.readAllBytes(socketNameFile.toPath())).trim();
                if (!socketName.isEmpty()) {
                    waylandSocketName = socketName;
                    Log.i(TAG, "Wayland socket: " + socketName + " (from bridge)");
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not read socket name: " + e.getMessage());
            }
        }

        // Build the launcher command:
        //   libwine_launcher.so <wineBinary> <exePath> [extraArgs...]
        List<String> cmd = new ArrayList<>();
        cmd.add(launcher.getAbsolutePath());
        cmd.add(wineBin.getAbsolutePath());
        cmd.add(exePath);
        if (extraArgs != null) {
            for (String arg : extraArgs) cmd.add(arg);
        }

        Log.i(TAG, "Native launcher command: " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(rootDir);
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        env.clear();
        // setupEnvironment populates all the Wine env vars (HOME, WINEPREFIX,
        // PATH, LD_LIBRARY_PATH, WINEDLLOVERRIDES, etc.) pointing at rootfs paths.
        // The launcher passes these through unchanged to Wine via execve().
        // useBionicBridge=true skips glibc-specific env vars (GLIBC_TUNABLES,
        // LD_PRELOAD shim) that would crash a bionic Wine.
        setupEnvironment(env, rootDir, protonDir, isArm64ec, fexCoreInstalled, true);

        // Override WAYLAND_DISPLAY with the actual socket name the bridge created
        if (waylandSocketName != null) {
            env.put("WAYLAND_DISPLAY", waylandSocketName);
        }

        // Set FEX env var if FEX + arm64ec (HODLL tells Wine which FEX DLL to load)
        if (fexCoreInstalled && isArm64ec) {
            env.put("HODLL", "libarm64ecfex.dll");
            Log.i(TAG, "FEXCore arm64ec: HODLL=libarm64ecfex.dll");
        }

        // =================================================================
        // COMPREHENSIVE BIONIC LIBRARY FIX + RUNTIME DIAGNOSTICS
        // =================================================================
        // Bionic Wine (linker64) cannot load glibc-compiled .so files.
        // Create bionic-compatible symlinks in usr/local/lib (FIRST in
        // LD_LIBRARY_PATH for bionic) pointing to Android system libs.
        // Then run a full diagnostic dump of every component Wine needs.
        // =================================================================
        try {
            File localLib = new File(rootDir, "usr/local/lib");
            if (!localLib.exists()) localLib.mkdirs();

            // --- Create bionic-compatible symlinks ---
            String[][] symlinks = {
                // {symlink name, Android system lib path, description}
                {"libvulkan.so.1", "/system/lib64/libvulkan.so", "Vulkan loader"},
                {"libvulkan.so",   "/system/lib64/libvulkan.so", "Vulkan (unversioned)"},
                {"libfreetype.so.6", "/system/lib64/libfreetype.so", "FreeType fonts"},
                {"libfreetype.so",   "/system/lib64/libfreetype.so", "FreeType (unversioned)"},
                {"libX11.so.6",    "/system/lib64/libX11.so", "X11 (if Android has it)"},
                {"libX11.so",      "/system/lib64/libX11.so", "X11 (unversioned)"},
            };
            for (String[] sl : symlinks) {
                File symlink = new File(localLib, sl[0]);
                File target = new File(sl[1]);
                if (target.exists()) {
                    if (symlink.exists()) {
                        symlink.delete();
                    }
                    try {
                        java.nio.file.Files.createSymbolicLink(
                                symlink.toPath(), target.toPath());
                        Log.i(TAG, "  symlink ✓ " + sl[0] + " → " + sl[1]
                                + " (" + sl[2] + ")");
                        io.waylandie.display.shared.util.LogRingBuffer.append(
                                "[diag] symlink ✓ " + sl[0] + " → " + sl[1]);
                    } catch (Exception e) {
                        Log.w(TAG, "  symlink ✗ " + sl[0] + ": " + e.getMessage());
                    }
                } else {
                    Log.w(TAG, "  symlink ✗ " + sl[0] + " — target not found: " + sl[1]
                            + " (" + sl[2] + " — Wine will fail to load this)");
                    io.waylandie.display.shared.util.LogRingBuffer.append(
                            "[diag] symlink ✗ " + sl[0] + " — target missing: " + sl[1]);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Bionic symlink setup failed: " + e.getMessage());
        }

        // =================================================================
        // RUNTIME DIAGNOSTICS — dump every component Wine needs
        // =================================================================
        Log.i(TAG, "========== WINE RUNTIME DIAGNOSTICS ==========");
        io.waylandie.display.shared.util.LogRingBuffer.append("[diag] ===== WINE RUNTIME DIAGNOSTICS =====");
        preLaunchDiagnostics.append("[diag] ===== WINE RUNTIME DIAGNOSTICS =====\n");
        preLaunchDiagnostics.setLength(0);
        preLaunchDiagnostics.append("===== WINE RUNTIME DIAGNOSTICS =====\n");

        // 1. Wine binary info
        Log.i(TAG, "[diag] Wine binary: " + wineBin.getAbsolutePath()
                + " (" + wineBin.length() + " bytes)");
        io.waylandie.display.shared.util.LogRingBuffer.append(
                "[diag] Wine binary: " + wineBin.length() + " bytes");

        // 2. Wine ELF interpreter (bionic vs glibc)
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(wineBin, "r")) {
            raf.seek(0x20);
            long ePhoff = 0;
            for (int i = 7; i >= 0; i--) ePhoff = (ePhoff << 8) | raf.readUnsignedByte();
            raf.seek(0x36);
            int ePhentsize = raf.readUnsignedByte() | (raf.readUnsignedByte() << 8);
            raf.seek(0x38);
            int ePhnum = raf.readUnsignedByte() | (raf.readUnsignedByte() << 8);
            for (int i = 0; i < ePhnum; i++) {
                long phdrOff = ePhoff + ((long) i * ePhentsize);
                raf.seek(phdrOff);
                int pType = raf.readUnsignedByte() | (raf.readUnsignedByte() << 8)
                        | (raf.readUnsignedByte() << 16) | (raf.readUnsignedByte() << 24);
                if (pType == 3) {
                    raf.seek(phdrOff + 8);
                    long pOffset = 0;
                    for (int b = 7; b >= 0; b--) pOffset = (pOffset << 8) | raf.readUnsignedByte();
                    raf.seek(phdrOff + 32);
                    long pFilesz = 0;
                    for (int b = 7; b >= 0; b--) pFilesz = (pFilesz << 8) | raf.readUnsignedByte();
                    raf.seek(pOffset);
                    byte[] interp = new byte[(int) pFilesz];
                    raf.readFully(interp);
                    String interpStr = new String(interp, "UTF-8").trim();
                    Log.i(TAG, "[diag] Wine ELF interpreter: " + interpStr);
                    io.waylandie.display.shared.util.LogRingBuffer.append(
                            "[diag] Wine interpreter: " + interpStr);
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "[diag] Failed to read Wine ELF: " + e.getMessage());
        }

        // 3. Key environment variables
        String[] envKeys = {"LD_LIBRARY_PATH", "PATH", "HOME", "WINEPREFIX",
                "WAYLAND_DISPLAY", "XDG_RUNTIME_DIR", "VK_ICD_FILENAMES",
                "VK_LAYER_PATH", "WINEDLLOVERRIDES", "HODLL", "PROTONPATH"};
        for (String key : envKeys) {
            String val = env.get(key);
            String logLine = "[diag] ENV " + key + "=" + (val != null ? val : "(unset)");
            Log.i(TAG, logLine);
            io.waylandie.display.shared.util.LogRingBuffer.append(logLine);
        }

        // 4. Critical library check — verify each lib Wine needs is findable
        String ldPath = env.get("LD_LIBRARY_PATH");
        if (ldPath != null) {
            String[] criticalLibs = {
                "libvulkan.so.1", "libvulkan.so",
                "libfreetype.so.6", "libfreetype.so",
                "libwine.so", "libwine.so.1",
                "libX11.so.6", "libwayland-client.so.0"
            };
            for (String lib : criticalLibs) {
                boolean found = false;
                String foundAt = null;
                for (String dir : ldPath.split(":")) {
                    File f = new File(dir, lib);
                    if (f.exists()) {
                        found = true;
                        foundAt = dir;
                        break;
                    }
                }
                String status = found ? "✓ found at " + foundAt : "✗ NOT FOUND in LD_LIBRARY_PATH";
                Log.i(TAG, "[diag] LIB " + lib + ": " + status);
                io.waylandie.display.shared.util.LogRingBuffer.append(
                        "[diag] LIB " + lib + ": " + status);
            }
        }

        // 5. Display drivers — search EVERYWHERE Wine might have them
        try {
            // Search in the Wine prefix system32/
            File system32 = new File(
                    new File(rootDir, "home/xuser/.wine"),
                    "drive_c/windows/system32");
            // Also search in Proton's lib/wine/ directories (where built-in
            // Wine DLLs actually live — .drv files may not be copied to the
            // prefix until first use)
            File protonLibWine = new File(protonDir, "lib/wine");
            File protonFilesLibWine = new File(protonDir, "files/lib/wine");
            File protonLibWine64 = new File(protonDir, "lib/wine/aarch64-unix");
            File protonLibWineAlternate = new File(protonDir, "lib/wine/arm64-unix");

            // Search for ALL .drv files in all possible locations
            File[] searchDirs = {system32, protonLibWine, protonFilesLibWine,
                                 protonLibWine64, protonLibWineAlternate,
                                 new File(protonDir, "lib"),
                                 new File(protonDir, "files/lib")};
            for (File searchDir : searchDirs) {
                if (searchDir == null || !searchDir.isDirectory()) continue;
                searchDrvFiles(searchDir, "  ", 3);
            }

            // Specifically check for known display drivers
            String[] knownDrvs = {"winewayland.drv", "winex11.drv", "wineandroid.drv",
                                  "winemac.drv", "winetest.drv"};
            for (String drv : knownDrvs) {
                boolean found = false;
                String foundAt = null;
                for (File dir : searchDirs) {
                    if (dir == null || !dir.isDirectory()) continue;
                    File drvFile = new File(dir, drv);
                    if (drvFile.exists()) {
                        found = true;
                        foundAt = dir.getAbsolutePath();
                        break;
                    }
                    // Also search subdirectories (lib/wine/aarch64-unix/ etc.)
                    searchSubdir:
                    for (File sub : dir.listFiles()) {
                        if (sub.isDirectory()) {
                            File subDrv = new File(sub, drv);
                            if (subDrv.exists()) {
                                found = true;
                                foundAt = subDrv.getAbsolutePath();
                                break searchSubdir;
                            }
                        }
                    }
                    if (found) break;
                }
                String status = found ? "FOUND at " + foundAt : "NOT FOUND";
                String logLine = "[diag] DRV " + drv + ": " + status;
                Log.i(TAG, logLine);
                io.waylandie.display.shared.util.LogRingBuffer.append(logLine);
                preLaunchDiagnostics.append(logLine + "\n");
            }

            if (system32.isDirectory()) {
                File[] drvFiles = system32.listFiles(
                        (dir, name) -> name.endsWith(".drv"));
                if (drvFiles != null && drvFiles.length > 0) {
                    for (File drv : drvFiles) {
                        String logLine = "[diag] DRV " + drv.getName() + " (" + drv.length() + " bytes)";
                        Log.i(TAG, logLine);
                        io.waylandie.display.shared.util.LogRingBuffer.append(logLine);
                    }
                } else {
                    String logLine = "[diag] DRV ✗ NO .drv files in Wine prefix — Wine CANNOT create windows";
                    Log.w(TAG, logLine);
                    io.waylandie.display.shared.util.LogRingBuffer.append(logLine);
                }
                // Also check for DXVK DLLs
                String[] dxvkDlls = {"d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll"};
                for (String dll : dxvkDlls) {
                    File dllFile = new File(system32, dll);
                    String logLine = "[diag] DXVK " + dll + ": "
                            + (dllFile.exists()
                                ? "✓ (" + dllFile.length() + " bytes)"
                                : "✗ MISSING — DXVK won't work for this API");
                    Log.i(TAG, logLine);
                    io.waylandie.display.shared.util.LogRingBuffer.append(logLine);
                }
                // Check FEX DLL
                File fexDll = new File(system32, "libarm64ecfex.dll");
                String fexLine = "[diag] FEX " + fexDll.getName() + ": "
                        + (fexDll.exists()
                            ? "✓ (" + fexDll.length() + " bytes)"
                            : "✗ MISSING — x86_64 emulation won't work");
                Log.i(TAG, fexLine);
                io.waylandie.display.shared.util.LogRingBuffer.append(fexLine);
            } else {
                Log.w(TAG, "[diag] Wine prefix system32/ not found at " + system32);
            }
        } catch (Exception e) {
            Log.w(TAG, "[diag] Failed to check .drv/.dll: " + e.getMessage());
        }

        // 6. Vulkan ICD JSON check
        String icdPath = env.get("VK_ICD_FILENAMES");
        if (icdPath != null) {
            File icdFile = new File(icdPath);
            String logLine = "[diag] VK_ICD " + icdFile.getName() + ": "
                    + (icdFile.exists()
                        ? "✓ exists (" + icdFile.length() + " bytes)"
                        : "✗ MISSING — Vulkan driver won't be found");
            Log.i(TAG, logLine);
            io.waylandie.display.shared.util.LogRingBuffer.append(logLine);
            // Read ICD JSON to see what driver it points to
            if (icdFile.exists()) {
                try {
                    String json = new String(java.nio.file.Files.readAllBytes(icdFile.toPath()));
                    Log.i(TAG, "[diag] VK_ICD content: " + json.trim());
                    io.waylandie.display.shared.util.LogRingBuffer.append("[diag] VK_ICD JSON: " + json.trim());
                } catch (Exception e) {
                    Log.w(TAG, "[diag] Failed to read ICD JSON: " + e.getMessage());
                }
            }
        } else {
            Log.w(TAG, "[diag] VK_ICD_FILENAMES not set — Vulkan driver auto-discovery only");
        }

        // 7. Turnip driver check
        File turnipSo = new File(rootDir, "usr/local/lib/libvulkan_freedreno.so");
        String turnipLine = "[diag] Turnip libvulkan_freedreno.so: "
                + (turnipSo.exists()
                    ? "✓ (" + turnipSo.length() + " bytes)"
                    : "✗ MISSING — no GPU driver will load");
        Log.i(TAG, turnipLine);
        io.waylandie.display.shared.util.LogRingBuffer.append(turnipLine);

        // 8. Wayland socket check
        File socketFile = new File(runtimeDir, "socket-name.txt");
        String socketLine = "[diag] Wayland socket: "
                + (socketFile.exists()
                    ? "✓ name=" + new String(java.nio.file.Files.readAllBytes(socketFile.toPath())).trim()
                    : "✗ socket-name.txt not written — bridge may have failed");
        Log.i(TAG, socketLine);
        io.waylandie.display.shared.util.LogRingBuffer.append(socketLine);

        // 9. Android bridge socket check
        try {
            android.net.LocalSocket probe = new android.net.LocalSocket();
            probe.connect(new android.net.LocalSocketAddress(
                    "waylandie.display.bridge.v1",
                    android.net.LocalSocketAddress.Namespace.ABSTRACT));
            probe.close();
            Log.i(TAG, "[diag] Android bridge socket: ✓ listening");
            io.waylandie.display.shared.util.LogRingBuffer.append("[diag] Android bridge: ✓ listening");
            preLaunchDiagnostics.append("[diag] Android bridge: ✓ listening\n");
        } catch (Exception e) {
            Log.w(TAG, "[diag] Android bridge socket: ✗ " + e.getMessage());
            io.waylandie.display.shared.util.LogRingBuffer.append("[diag] Android bridge: ✗ " + e.getMessage());
        }

        // 10. Proton lib/ contents (Wine's own .so files)
        File protonLib = new File(protonDir, "lib");
        if (protonLib.isDirectory()) {
            File[] libs = protonLib.listFiles();
            if (libs != null) {
                Log.i(TAG, "[diag] Proton lib/ (" + libs.length + " items):");
                io.waylandie.display.shared.util.LogRingBuffer.append(
                        "[diag] Proton lib/: " + libs.length + " items");
                for (File lib : libs) {
                    String name = lib.getName();
                    // Only log .so files + dirs
                    if (name.endsWith(".so") || lib.isDirectory()) {
                        String logLine = "[diag]   " + name
                                + (lib.isDirectory() ? "/" : " (" + lib.length() + " bytes)");
                        Log.i(TAG, logLine);
                        io.waylandie.display.shared.util.LogRingBuffer.append(logLine);
                    }
                }
            }
        } else {
            Log.w(TAG, "[diag] Proton lib/ not found at " + protonLib);
        }

        Log.i(TAG, "========== END WINE RUNTIME DIAGNOSTICS ==========");
        io.waylandie.display.shared.util.LogRingBuffer.append("[diag] ===== END DIAGNOSTICS =====");
        preLaunchDiagnostics.append("===== END DIAGNOSTICS =====\n");
        preLaunchDiagnostics.append("[diag] ===== END DIAGNOSTICS =====\n");

        Process p = pb.start();
        // =================================================================
        // =================================================================
        // CRITICAL: Force Wine to use the Wayland display driver.
        // =================================================================
        // Fix 1: Set Graphics=wayland in BOTH system.reg AND user.reg.
        // Wine checks HKEY_CURRENT_USER first (user.reg), then
        // HKEY_LOCAL_MACHINE (system.reg). We set both to be safe.
        // The registry key is:
        //   [HKEY_CURRENT_USER\\Software\\Wine\\Drivers]
        //   "Graphics"="wayland"
        try {
            File winePrefixDir = new File(rootDir, "home/xuser/.wine");
            String driversKey = "[HKEY_CURRENT_USER\\\\Software\\\\Wine\\\\Drivers]";
            String graphicsValue = "\"Graphics\"=\"wayland\"";
            // Write to BOTH system.reg AND user.reg
            for (String regFileName : new String[]{"system.reg", "user.reg"}) {
                File regFile = new File(winePrefixDir, regFileName);
                if (!regFile.exists()) {
                    // Create the file if it does not exist (user.reg may not exist)
                    regFile.createNewFile();
                }
                String regContent = new String(java.nio.file.Files.readAllBytes(regFile.toPath()));
                if (!regContent.contains(graphicsValue)) {
                    if (regContent.contains(driversKey)) {
                        // Section exists — add Graphics value after the section header
                        regContent = regContent.replace(driversKey + "]",
                            driversKey + "]\n" + graphicsValue);
                    } else {
                        // Section does not exist — append it
                        regContent = regContent + "\n" + driversKey + "]\n" + graphicsValue + "\n";
                    }
                    java.nio.file.Files.write(regFile.toPath(), regContent.getBytes());
                    Log.i(TAG, "Set " + regFileName + ": Graphics=wayland");
                    preLaunchDiagnostics.append("[diag] Registry " + regFileName + ": Graphics=wayland SET\n");
                } else {
                    Log.i(TAG, regFileName + " already has Graphics=wayland");
                    preLaunchDiagnostics.append("[diag] Registry " + regFileName + ": already set\n");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to set Graphics=wayland: " + e.getMessage());
            preLaunchDiagnostics.append("[diag] Registry FAILED: " + e.getMessage() + "\n");
        }

        // =================================================================
        // Fix 2: Create bionic-compatible libwayland-client.so.
        // =================================================================
        // winewayland.so (Unix part) needs libwayland-client.so at runtime.
        // The rootfs has a glibc-compiled version that bionic linker64
        // cannot load. We need a bionic-compatible one.
        //
        // Strategy: try multiple sources in priority order:
        //   1. Android system /system/lib64/libwayland-client.so (if exists)
        //   2. Proton lib/libwayland-client.so (may be bionic-compiled)
        //   3. Rootfs glibc version (last resort — will fail on bionic but
        //      at least the file exists so dlopen does not get ENOENT)
        //   4. If none found, create a stub .so that logs + returns errors
        //      (so dlopen succeeds but calls fail gracefully)
        try {
            File localLib = new File(rootDir, "usr/local/lib");
            if (!localLib.exists()) localLib.mkdirs();

            // Priority 1: Android system lib
            File androidWl = new File("/system/lib64/libwayland-client.so");
            // Priority 2: Proton lib
            File protonWl = new File(protonDir, "lib/libwayland-client.so");
            File protonWl0 = new File(protonDir, "lib/libwayland-client.so.0");
            // Priority 3: Rootfs glibc lib
            File rootfsWl = new File(rootDir, "usr/lib/aarch64-linux-gnu/libwayland-client.so.0");
            File rootfsWlUnversioned = new File(rootDir, "usr/lib/aarch64-linux-gnu/libwayland-client.so");

            File wlTarget = null;
            String wlSource = "none";
            if (androidWl.exists()) {
                wlTarget = androidWl;
                wlSource = "Android system";
            } else if (protonWl.exists()) {
                wlTarget = protonWl;
                wlSource = "Proton lib";
            } else if (protonWl0.exists()) {
                wlTarget = protonWl0;
                wlSource = "Proton lib (.so.0)";
            } else if (rootfsWl.exists()) {
                wlTarget = rootfsWl;
                wlSource = "rootfs glibc (may not work with bionic)";
            } else if (rootfsWlUnversioned.exists()) {
                wlTarget = rootfsWlUnversioned;
                wlSource = "rootfs glibc unversioned (may not work with bionic)";
            }

            if (wlTarget != null) {
                // Create symlinks for both .so and .so.0
                for (String name : new String[]{"libwayland-client.so", "libwayland-client.so.0"}) {
                    File sym = new File(localLib, name);
                    if (sym.exists()) sym.delete();
                    try {
                        java.nio.file.Files.createSymbolicLink(sym.toPath(), wlTarget.toPath());
                        Log.i(TAG, "Symlink: " + name + " -> " + wlTarget + " (" + wlSource + ")");
                        preLaunchDiagnostics.append("[diag] Symlink: " + name + " -> " + wlTarget + " (" + wlSource + ")\n");
                    } catch (Exception e) {
                        Log.w(TAG, "Symlink failed for " + name + ": " + e.getMessage());
                        preLaunchDiagnostics.append("[diag] Symlink FAILED: " + name + " — " + e.getMessage() + "\n");
                    }
                }
            } else {
                Log.w(TAG, "libwayland-client.so not found anywhere — winewayland.drv will fail to load");
                preLaunchDiagnostics.append("[diag] WARNING: libwayland-client.so NOT FOUND anywhere\n");
            }

            // Also check if the Proton has its own libwayland-client in other locations
            searchForFile(new File(protonDir, "lib"), "libwayland-client.so", 5);
            searchForFile(new File(protonDir, "lib"), "libwayland-client.so.0", 5);
        } catch (Exception e) { Log.w(TAG, "libwayland-client setup failed: " + e.getMessage()); }

        // =================================================================
        // Fix 3: Search for winewayland.so (Unix part of the driver).
        // =================================================================
        // winewayland.drv (PE) calls winewayland.so (Unix) for Wayland ops.
        // If winewayland.so is missing, the PE driver loads but fails init.
        try {
            File protonLibForSearch = new File(protonDir, "lib");
            if (protonLibForSearch.isDirectory()) {
                searchForFile(protonLibForSearch, "winewayland.so", 5);
                // Also search for winewayland.drv in all subdirs
                searchForFile(protonLibForSearch, "winewayland.drv", 5);
            }
            // Also check if Wine prefix has it
            File prefixSystem32 = new File(
                    new File(rootDir, "home/xuser/.wine"),
                    "drive_c/windows/system32");
            if (prefixSystem32.isDirectory()) {
                searchForFile(prefixSystem32, "winewayland.drv", 2);
            }
        } catch (Exception e) { Log.w(TAG, "search failed: " + e.getMessage()); }


        // Capture Wine output (CRITICAL for debugging)
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

        // Socket name the bridge creates inside proot /tmp (filled in after
        // bridge starts; applied to WAYLAND_DISPLAY below).
        String prootWaylandSocket = null;

        // Start bridge via proot (background)
        File bridgeBin = new File(rootDir, "usr/local/bin/waylandie-wayland-bridge");
        if (bridgeBin.exists()) {
            Log.i(TAG, "Starting bridge translator via proot (background)…");
            try {
                String[] displaySize = getDisplaySize();
                proot.exec(
                        "XDG_RUNTIME_DIR=/tmp WAYLAND_DISPLAY=waylandie "
                        + "WAYLANDIE_BRIDGE_SOCKET=waylandie.display.bridge.v1 "
                        + "WAYLANDIE_BRIDGE_PORT=57391 "
                        + "/usr/local/bin/waylandie-wayland-bridge "
                        + "waylandie.display.bridge.v1 "  // bridge socket name
                        + "1 "                             // target commits
                        + "/tmp/socket-name.txt "         // socket file
                        + "15000 "                         // timeout ms
                        + "0 "                             // clear ahb outside
                        + "0 "                             // accept client complete
                        + displaySize[0] + " "             // output width
                        + displaySize[1] + " &");           // output height
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                // Read the socket name the bridge created (inside proot /tmp =
                // rootDir/tmp on host) and override WAYLAND_DISPLAY for Wine.
                File prootSocketNameFile = new File(new File(rootDir, "tmp"), "socket-name.txt");
                if (prootSocketNameFile.exists()) {
                    try {
                        String socketName = new String(
                                java.nio.file.Files.readAllBytes(prootSocketNameFile.toPath())).trim();
                        if (!socketName.isEmpty()) {
                            prootWaylandSocket = socketName;
                            Log.i(TAG, "Wayland socket (proot): " + socketName);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not read proot socket name: " + e.getMessage());
                    }
                }
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
        // Override WAYLAND_DISPLAY with the actual socket name the bridge created
        // (default "waylandie" guess won't match wl_display_add_socket_auto()).
        if (prootWaylandSocket != null) {
            wineCmd.append("WAYLAND_DISPLAY=").append(prootWaylandSocket).append(" ");
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
        setupEnvironment(env, rootDir, protonDir, isArm64ec, fexCoreInstalled, false);
    }

    /**
     * Sets up the process environment for native execution.
     *
     * @param useBionicBridge true if the bridge being launched is the bionic
     *     variant (compiled with NDK against bionic libc). When true, the
     *     glibc-specific environment variables (LD_LIBRARY_PATH pointing at
     *     glibc rootfs libs, /system/lib64) are SKIPPED for the bridge
     *     process — they don't apply to a bionic binary and would be
     *     actively harmful (linker64 might pick up glibc .so files and crash).
     *     Wine still gets the full glibc environment via the 5-arg overload
     *     above. (GLIBC_TUNABLES and the LD_PRELOAD shim were removed —
     *     GLIBC_TUNABLES is a no-op on glibc 2.31, and the shim didn't work
     *     because Android's seccomp uses SECCOMP_RET_KILL_PROCESS. The bionic
     *     bridge eliminates the SIGSYS issue entirely.)
     */
    private void setupEnvironment(Map<String, String> env, File rootDir,
            File protonDir, boolean isArm64ec, boolean fexCoreInstalled,
            boolean useBionicBridge) {

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
        // LD_LIBRARY_PATH — controls where dynamic linker searches for .so.
        // BIONIC Wine (linker64): CANNOT load glibc .so files. Needs only
        //   bionic-compatible paths: usr/local/lib (symlinks), proton/lib,
        //   /system/lib64. Do NOT include usr/lib/aarch64-linux-gnu (glibc).
        // GLIBC Wine (ld-linux): needs full rootfs lib paths.
        if (useBionicBridge) {
            env.put("LD_LIBRARY_PATH",
                    new File(rootDir, "usr/local/lib").getAbsolutePath() + ":"
                    + new File(protonDir, "lib").getAbsolutePath() + ":"
                    + new File(protonDir, "files/lib").getAbsolutePath() + ":"
                    + "/system/lib64");
        } else {
            env.put("LD_LIBRARY_PATH",
                    new File(rootDir, "usr/lib").getAbsolutePath() + ":"
                    + new File(rootDir, "usr/lib/aarch64-linux-gnu").getAbsolutePath() + ":"
                    + new File(rootDir, "usr/local/lib").getAbsolutePath() + ":"
                    + new File(protonDir, "lib").getAbsolutePath() + ":"
                    + new File(protonDir, "files/lib").getAbsolutePath());
        }
        env.put("LANG", "en_US.UTF-8");
        env.put("TERM", "xterm-256color");
        env.put("TMPDIR", tmpDir.getAbsolutePath());
        env.put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());

        // NOTE: GLIBC_TUNABLES (glibc.pthread.rseq=0) was removed — it's a
        // no-op on glibc 2.31 (our rootfs is Ubuntu 20.04 Focal). glibc 2.35+
        // calls rseq() during __libc_start_main(), but 2.31 doesn't. The
        // bionic bridge eliminates the SIGSYS issue entirely for the bridge;
        // Wine (which still uses glibc) works fine on 2.31 without the tunable.

        // NOTE: LD_PRELOAD syscall shim was removed — the shim didn't work
        // (Android's seccomp uses SECCOMP_RET_KILL_PROCESS for blocked syscalls,
        // which kills the process BEFORE the shim's signal handler can intercept).
        // The bionic bridge replaces the shim: it links against bionic libc
        // (no glibc constructors → no SIGSYS). Wine still uses glibc 2.31,
        // which is safe (no rseq/clone3 during startup).

        // Wine prefix — created during Proton install (prefixPack.txz unpacked there)
        File winePrefix = new File(homeDir, ".wine");
        if (!winePrefix.exists()) winePrefix.mkdirs();
        env.put("WINEPREFIX", winePrefix.getAbsolutePath());
        env.put("WINEDLLOVERRIDES", "d3d9,d3d10core,d3d11,dxgi=native");
        // CRITICAL: Enable Wine debug channels for display driver + module loading.
        // This tells us EXACTLY which .drv files Wine tries to load and why they
        // fail. The output goes to Wine's stderr (captured by GameLaunchTracer).
        env.put("WINEDEBUG", "+module,+display,+driver");
        env.put("DXVK_STATE_CACHE_PATH", new File(homeDir, ".dxvk-cache").getAbsolutePath());
        env.put("MESA_VK_WSI_PRESENT_MODE", "immediate");
        // Winlator-inspired env vars — Wine needs these for proper operation
        env.put("WINE_NO_DUPLICATE_EXPLORER", "1");
        env.put("FONTCONFIG_PATH", new File(rootDir, "usr/etc/fonts").getAbsolutePath());
        env.put("GST_PLUGIN_PATH", new File(rootDir, "usr/lib/gstreamer-1.0").getAbsolutePath());
        env.put("XDG_DATA_DIRS", new File(rootDir, "usr/share").getAbsolutePath());
        env.put("XDG_CONFIG_DIRS", new File(rootDir, "usr/etc/xdg").getAbsolutePath());
        // /system/lib64 already included in bionic path above.
        // For glibc path, append it here.
        if (!useBionicBridge) {
            env.put("LD_LIBRARY_PATH", env.get("LD_LIBRARY_PATH") + ":/system/lib64");
        }
        // Winlator-inspired env vars — Wine needs these for proper operation
        env.put("WINE_DISABLE_FULLSCREEN_HACK", "1");
        env.put("WINE_X11FORCEGLX", "1");
        env.put("WINE_GST_NO_GL", "1");
        env.put("VK_LAYER_PATH", new File(rootDir, "usr/share/vulkan/implicit_layer.d").getAbsolutePath()
                + ":" + new File(rootDir, "usr/share/vulkan/explicit_layer.d").getAbsolutePath());
        env.put("PREFIX", new File(rootDir, "usr").getAbsolutePath());
        env.put("GST_PLUGIN_FEATURE_RANK", "ximagesink:3000");
        env.put("ALSA_CONFIG_PATH", new File(rootDir, "usr/share/alsa/alsa.conf").getAbsolutePath());
        env.put("ALSA_PLUGIN_DIR", new File(rootDir, "usr/lib/alsa-lib").getAbsolutePath());
        env.put("OPENSSL_CONF", new File(rootDir, "usr/etc/tls/openssl.cnf").getAbsolutePath());
        env.put("SSL_CERT_FILE", new File(rootDir, "usr/etc/tls/cert.pem").getAbsolutePath());
        env.put("SSL_CERT_DIR", new File(rootDir, "usr/etc/tls/certs").getAbsolutePath());
        env.put("PROTON_AUDIO_CONVERT", "0");
        env.put("PROTON_VIDEO_CONVERT", "0");
        env.put("PROTON_DEMUX", "0");
        env.put("SteamGameId", "0");

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
        // Android's Process class doesn't expose pid() / toHandle() until
        // API 35, even though Java 9+ defines them. Try multiple approaches:
        //   1. Java 9+ Process.pid() via reflection (works on Android 15+)
        //   2. Java 9+ Process.toHandle().pid() via reflection
        //   3. Direct field reflection ("pid", "mPid")
        //   4. Give up, return -1 (the caller only uses this for logging)
        try {
            java.lang.reflect.Method pidMethod = p.getClass().getMethod("pid");
            pidMethod.setAccessible(true);
            return (long) pidMethod.invoke(p);
        } catch (Exception ignored) {
        }
        try {
            java.lang.reflect.Method toHandle = p.getClass().getMethod("toHandle");
            java.lang.reflect.Method pidOf = toHandle.invoke(p).getClass().getMethod("pid");
            return (long) pidOf.invoke(toHandle.invoke(p));
        } catch (Exception ignored) {
        }
        for (String fieldName : new String[]{"pid", "mPid", "id"}) {
            try {
                java.lang.reflect.Field pidField = p.getClass().getDeclaredField(fieldName);
                pidField.setAccessible(true);
                return pidField.getLong(p);
            } catch (Exception ignored) {
            }
        }
        return -1;
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
    /**
     * Detects whether a Wine binary is bionic-compiled (built with Android NDK,
     * ELF interpreter = /system/bin/linker64) vs glibc-compiled (interpreter
     * = /lib/ld-linux-aarch64.so.1).
     *
     * <p>Bionic Wine must be launched DIRECTLY via ProcessBuilder — no
     * libld_glibc.so linker trick. The glibc linker can't load bionic binaries
     * (different ABI, different linker protocol) and exits with code 127.
     *
     * <p>Detection: read PT_INTERP from the ELF program headers. On arm64,
     * the program headers start at offset e_phoff (0x20 + 0x10 = 0x30 for
     * 64-bit ELF header). Each program header is 56 bytes. PT_INTERP = 3.
     */
    private static boolean isBionicWine(File wineBin) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(wineBin, "r")) {
            if (raf.length() < 64) return false;
            // CRITICAL: arm64 ELF is LITTLE-ENDIAN, but Java's RandomAccessFile
            // reads BIG-ENDIAN by default. We must read bytes manually and
            // assemble them in little-endian order — same pattern as
            // isArm64ecWine() above.

            // ELF64 header layout (offsets):
            //   0x00: e_ident[16]   (magic + class + data + version + padding)
            //   0x10: e_type        (2 bytes)
            //   0x12: e_machine     (2 bytes)
            //   0x14: e_version     (4 bytes)
            //   0x18: e_entry       (8 bytes)
            //   0x20: e_phoff       (8 bytes) — program header table offset
            //   0x28: e_shoff       (8 bytes)
            //   0x30: e_flags       (4 bytes)
            //   0x34: e_ehsize      (2 bytes)
            //   0x36: e_phentsize   (2 bytes) — program header entry size
            //   0x38: e_phnum       (2 bytes) — number of program headers
            //   0x3A: e_shentsize   (2 bytes)
            //   0x3C: e_shnum       (2 bytes)
            //   0x3E: e_shstrndx    (2 bytes)

            // Read e_phoff (8 bytes, LE) at offset 0x20
            raf.seek(0x20);
            long ePhoff = readLongLE(raf);
            // Read e_phentsize (2 bytes, LE) at offset 0x36
            raf.seek(0x36);
            int ePhentsize = readUShortLE(raf);
            // Read e_phnum (2 bytes, LE) at offset 0x38
            raf.seek(0x38);
            int ePhnum = readUShortLE(raf);

            Log.i(TAG, "Wine ELF: e_phoff=" + ePhoff + " e_phentsize=" + ePhentsize + " e_phnum=" + ePhnum);

            // Sanity check: arm64 ELF should have e_phentsize=56 and e_phnum < 256
            if (ePhentsize != 56 || ePhnum <= 0 || ePhnum > 256) {
                Log.w(TAG, "Wine ELF: invalid program header table (e_phentsize="
                        + ePhentsize + ", e_phnum=" + ePhnum + ") — assuming glibc");
                return false;
            }

            // Program header entry layout (ELF64, 56 bytes):
            //   0x00: p_type   (4 bytes) — segment type
            //   0x04: p_flags  (4 bytes)
            //   0x08: p_offset (8 bytes) — segment offset in file
            //   0x10: p_vaddr  (8 bytes)
            //   0x18: p_paddr  (8 bytes)
            //   0x20: p_filesz (8 bytes) — segment size in file
            //   0x28: p_memsz  (8 bytes)
            //   0x30: p_align  (8 bytes)

            // Scan program headers for PT_INTERP (type = 3)
            for (int i = 0; i < ePhnum; i++) {
                long phdrOffset = ePhoff + ((long) i * ePhentsize);
                raf.seek(phdrOffset);
                int pType = readIntLE(raf);
                if (pType == 3) {  // PT_INTERP
                    // p_offset at phdrOffset + 8 (8 bytes, LE)
                    raf.seek(phdrOffset + 8);
                    long pOffset = readLongLE(raf);
                    // p_filesz at phdrOffset + 32 (8 bytes, LE)
                    raf.seek(phdrOffset + 32);
                    long pFilesz = readLongLE(raf);

                    Log.i(TAG, "Wine ELF: PT_INTERP found at phdr[" + i
                            + "] p_offset=" + pOffset + " p_filesz=" + pFilesz);

                    if (pFilesz <= 0 || pFilesz > 4096) {
                        Log.w(TAG, "Wine ELF: PT_INTERP has invalid p_filesz=" + pFilesz);
                        return false;
                    }
                    // Read the interpreter string
                    raf.seek(pOffset);
                    byte[] interp = new byte[(int) pFilesz];
                    raf.readFully(interp);
                    String interpStr = new String(interp, "UTF-8").trim();
                    Log.i(TAG, "Wine ELF interpreter: '" + interpStr + "'");
                    // Bionic linker is /system/bin/linker64
                    // Glibc linker is /lib/ld-linux-aarch64.so.1 (or similar)
                    boolean isBionic = interpStr.contains("linker64")
                            || interpStr.contains("/system/bin/");
                    Log.i(TAG, "Wine variant detected: " + (isBionic ? "BIONIC" : "GLIBC"));
                    return isBionic;
                }
            }
            Log.w(TAG, "Wine ELF: no PT_INTERP found (statically linked?) — assuming glibc");
            return false;  // statically linked is rare; safer to assume glibc
                            // (glibc linker trick will fail loudly if wrong)
        } catch (Exception e) {
            Log.w(TAG, "isBionicWine check failed: " + e.getMessage());
            return false;  // assume glibc on error (safer default for our setup)
        }
    }

    /** Reads a 2-byte little-endian unsigned short from RandomAccessFile. */
    private static int readUShortLE(java.io.RandomAccessFile raf) throws IOException {
        int lo = raf.readUnsignedByte();
        int hi = raf.readUnsignedByte();
        return (hi << 8) | lo;
    }

    /** Reads a 4-byte little-endian int from RandomAccessFile. */
    private static int readIntLE(java.io.RandomAccessFile raf) throws IOException {
        int b0 = raf.readUnsignedByte();
        int b1 = raf.readUnsignedByte();
        int b2 = raf.readUnsignedByte();
        int b3 = raf.readUnsignedByte();
        return (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
    }

    /** Reads an 8-byte little-endian long from RandomAccessFile. */
    /** Recursively searches for .drv files and logs them. */
    private void searchDrvFiles(File dir, String indent, int maxDepth) {
        if (dir == null || !dir.isDirectory() || maxDepth <= 0) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().endsWith(".drv")) {
                String logLine = "[diag] DRV FOUND: " + f.getAbsolutePath()
                        + " (" + f.length() + " bytes)";
                Log.i(TAG, logLine);
                io.waylandie.display.shared.util.LogRingBuffer.append(logLine);
                preLaunchDiagnostics.append(logLine + "\n");
            } else if (f.isDirectory()) {
                searchDrvFiles(f, indent + "  ", maxDepth - 1);
            }
        }
    }

    private void searchForFile(File dir, String name, int maxDepth) {
        if (dir == null || !dir.isDirectory() || maxDepth <= 0) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().equals(name)) {
                String line = "[diag] FOUND: " + f.getAbsolutePath() + " (" + f.length() + " bytes)";
                Log.i(TAG, line);
                io.waylandie.display.shared.util.LogRingBuffer.append(line);
                preLaunchDiagnostics.append(line + "\n");
            } else if (f.isDirectory()) {
                searchForFile(f, name, maxDepth - 1);
            }
        }
    }

    private static long readLongLE(java.io.RandomAccessFile raf) throws IOException {
        long b0 = raf.readUnsignedByte();
        long b1 = raf.readUnsignedByte();
        long b2 = raf.readUnsignedByte();
        long b3 = raf.readUnsignedByte();
        long b4 = raf.readUnsignedByte();
        long b5 = raf.readUnsignedByte();
        long b6 = raf.readUnsignedByte();
        long b7 = raf.readUnsignedByte();
        return (b7 << 56) | (b6 << 48) | (b5 << 40) | (b4 << 32)
                | (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
    }


}
