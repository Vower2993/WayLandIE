package com.winlator.cmod.runtime.display.environment.components;

import com.winlator.cmod.runtime.display.environment.EnvironmentComponent;
import com.winlator.cmod.runtime.display.environment.ImageFs;
import com.winlator.cmod.runtime.display.environment.XEnvironment;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Starts gamescope as a nested Wayland compositor between Wine and the
 * WaylandIE bionic bridge.
 *
 * Process tree:
 *   WaylandBridgeComponent (wayland-0)
 *     └── GamescopeComponent (gamescope-0 — its own internal Wayland display)
 *           └── GuestProgramLauncherComponent (Wine → connects to gamescope-0)
 *
 * Gamescope connects to wayland-0 as a Wayland CLIENT, attaches its composited
 * output via zwp_linux_dmabuf_v1, and the bridge forwards those dmabuf fds to
 * the JVM presenter (WaylandBridgeServer) which renders them to SurfaceFlinger
 * via adrenotools + Turnip.
 *
 * IMPORTANT: GamescopeComponent does NOT launch Wine itself. It only starts
 * the gamescope binary, which:
 *   1. Connects to wayland-0 (parent bridge compositor)
 *   2. Binds its own internal Wayland display as gamescope-0
 *   3. Waits indefinitely for client connections
 *
 * GuestProgramLauncherComponent is responsible for:
 *   - Setting WAYLAND_DISPLAY=gamescope-0 (instead of wayland-0) for Wine
 *   - Launching Wine directly (NOT wrapped in gamescope's `--`)
 *
 * Why not use gamescope's `-- <cmd>` to launch Wine?
 *   gamescope's CLI REQUIRES a command after `--`. If we pass Wine as that
 *   command, gamescope manages Wine's lifecycle — when Wine exits, gamescope
 *   exits. But Wine on Android often restarts itself (wineboot → services →
 *   explorer → game), and each restart would kill gamescope. Instead, we
 *   pass a `sleep infinity` placeholder as gamescope's child, keeping
 *   gamescope alive as a long-lived compositor. Wine is launched separately
 *   by GuestProgramLauncherComponent and connects to gamescope-0 as a
 *   Wayland client — Wine's lifecycle is decoupled from gamescope's.
 *
 * Order of operations during XEnvironment.startEnvironmentComponents():
 *   1. SysVSharedMemoryComponent.start()    (LD_PRELOAD shim server)
 *   2. XServerComponent.start()             (in-process X server, fallback)
 *   3. [Audio components].start()           (ALSA or PulseAudio)
 *   4. WaylandBridgeComponent.start()       (wayland-0 socket bound)
 *   5. GamescopeComponent.start()           (gamescope-0 socket bound)  ← NEW
 *   6. GuestProgramLauncherComponent.start() (Wine launches, connects to gamescope-0)
 *
 * The gamescope binary is packaged as `libgamescope.so` (despite being an ELF
 * executable) so AGP picks it up and ships it in lib/arm64-v8a/. This matches
 * the pattern used by libwaylandie_bridge_exe.so. See:
 *   tools/build-gamescope-stack.sh
 *
 * The LD_PRELOAD of android_sysvshm.so (set by SysVSharedMemoryComponent and
 * extended in android_sysvshm.c) covers both SysV shmget AND POSIX shm_open —
 * so gamescope's wl_shm_pool allocations work without modification.
 *
 * Gamescope flags used:
 *   -w <width> -h <height>     Internal (game) rendering resolution
 *   -W <width> -H <height>     Output (composited) resolution
 *   --hl 3.0 --sharpness 30    FSR EASU+RCAS upscaling
 *   --immediate-flips          Lower latency, no frame queueing
 *   --force-grab-cursor        Required for FPS games with relative pointer
 *   -b                         Borderless (avoid window decoration attempts)
 */
public class GamescopeComponent extends EnvironmentComponent {
    private static final String TAG = "GamescopeComponent";

    private Process gamescopeProcess;
    private File outputLogFile;
    private volatile boolean stopped = false;

    // Gamescope internal Wayland socket name. Wine connects to this (NOT wayland-0).
    public static final String GAMESCOPE_WAYLAND_DISPLAY = "gamescope-0";

    // Internal rendering resolution (what the game thinks it's rendering at)
    private int internalWidth = 1280;
    private int internalHeight = 720;
    // Output resolution (what gamescope composites to and presents to the bridge)
    private int outputWidth = 2400;
    private int outputHeight = 1080;
    // FSR sharpness (0-100)
    private int sharpness = 30;

    public void setResolutions(int internalW, int internalH, int outputW, int outputH) {
        this.internalWidth = internalW;
        this.internalHeight = internalH;
        this.outputWidth = outputW;
        this.outputHeight = outputH;
    }

    public void setSharpness(int sharpness) {
        this.sharpness = sharpness;
    }

    @Override
    public void start() {
        XEnvironment env = environment;
        if (env == null) {
            Log.e(TAG, "Environment not set");
            return;
        }
        Context context = env.getContext();
        ImageFs imageFs = env.getImageFs();
        File rootDir = imageFs.getRootDir();
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;

        // Locate the gamescope binary (packaged as libgamescope.so per AGP convention)
        File gamescopeBin = new File(nativeLibDir, "libgamescope.so");
        Log.i(TAG, "Gamescope binary path: " + gamescopeBin.getAbsolutePath());
        Log.i(TAG, "Gamescope binary exists: " + gamescopeBin.exists());
        if (gamescopeBin.exists()) {
            Log.i(TAG, "Gamescope binary size: " + gamescopeBin.length() + " bytes");
        }

        if (!gamescopeBin.exists()) {
            Log.e(TAG, "Gamescope binary not found at " + gamescopeBin);
            writeDiagnostic(context, "GAMESCOPE_BINARY_NOT_FOUND: " + gamescopeBin);
            return;
        }
        boolean execSet = gamescopeBin.setExecutable(true, false);
        Log.i(TAG, "Gamescope binary setExecutable: " + execSet);

        File runtimeDir = new File(new File(rootDir, "usr/tmp"), "runtime");
        if (!runtimeDir.exists()) runtimeDir.mkdirs();

        // Clean up stale gamescope sockets
        new File(runtimeDir, GAMESCOPE_WAYLAND_DISPLAY).delete();
        new File(runtimeDir, GAMESCOPE_WAYLAND_DISPLAY + ".lock").delete();

        Log.i(TAG, "XDG_RUNTIME_DIR: " + runtimeDir.getAbsolutePath());

        // Verify the bridge socket (wayland-0) is alive — gamescope needs it as a parent
        File bridgeSocket = new File(runtimeDir, "wayland-0");
        if (!bridgeSocket.exists()) {
            Log.e(TAG, "Bridge socket wayland-0 not found. WaylandBridgeComponent must start first!");
            writeDiagnostic(context, "BRIDGE_SOCKET_NOT_FOUND: " + bridgeSocket);
            return;
        }
        Log.i(TAG, "Bridge socket wayland-0 present: " + bridgeSocket.exists());

        // Create the placeholder child script. Gamescope REQUIRES a command after `--`;
        // if we pass nothing, gamescope exits immediately with "No command specified".
        // Use `sleep infinity` to keep gamescope alive as a long-lived compositor.
        // Wine is launched separately by GuestProgramLauncherComponent and connects
        // to gamescope-0 as a Wayland client.
        File placeholderScript = new File(runtimeDir, "gamescope-placeholder.sh");
        try {
            try (FileWriter fw = new FileWriter(placeholderScript)) {
                fw.write("#!/system/bin/sh\n");
                fw.write("# Placeholder child for gamescope. Keeps gamescope alive\n");
                fw.write("# until GamescopeComponent.stop() kills the process.\n");
                fw.write("# Wine is launched separately by GuestProgramLauncherComponent\n");
                fw.write("# and connects to gamescope-0 as a Wayland client.\n");
                // Use `sleep 999999999` instead of `sleep infinity` — busybox sleep
                // on Android may not support the 'infinity' keyword, but a very large
                // integer seconds value works everywhere. 999999999s ≈ 31 years.
                fw.write("exec sleep 999999999\n");
            }
            placeholderScript.setExecutable(true, false);
        } catch (IOException e) {
            Log.e(TAG, "Failed to write placeholder script", e);
            writeDiagnostic(context, "PLACEHOLDER_SCRIPT_FAILED: " + e.getMessage());
            return;
        }

        // Create the gamescope log file
        File logsDir = com.winlator.cmod.runtime.system.LogManager.getLogsDir(context);
        outputLogFile = new File(logsDir, "gamescope-output.log");

        // Build gamescope command line. The `--` separator tells gamescope to exec
        // the placeholder script as its child.
        List<String> cmd = new ArrayList<>();
        cmd.add(gamescopeBin.getAbsolutePath());
        cmd.add("-w"); cmd.add(String.valueOf(internalWidth));
        cmd.add("-h"); cmd.add(String.valueOf(internalHeight));
        cmd.add("-W"); cmd.add(String.valueOf(outputWidth));
        cmd.add("-H"); cmd.add(String.valueOf(outputHeight));
        cmd.add("-b");                          // borderless
        cmd.add("--hl"); cmd.add("3.0");        // FSR upscaling
        cmd.add("--sharpness"); cmd.add(String.valueOf(sharpness));
        cmd.add("--immediate-flips");           // lower latency
        cmd.add("--force-grab-cursor");         // FPS games: relative pointer
        cmd.add("--xwayland-verbose");          // surface Xwayland stderr to log
        cmd.add("--");
        // Android's shell is /system/bin/sh, NOT /bin/sh. The imagefs may also
        // have a busybox sh at usr/bin/sh — try /system/bin/sh first (always
        // present on Android), fall back to imagefs sh.
        File androidSh = new File("/system/bin/sh");
        File imagefsSh = new File(rootDir, "usr/bin/sh");
        if (androidSh.exists() && androidSh.canExecute()) {
            cmd.add(androidSh.getAbsolutePath());
        } else if (imagefsSh.exists() && imagefsSh.canExecute()) {
            cmd.add(imagefsSh.getAbsolutePath());
        } else {
            // Last resort: try /bin/sh (will fail on Android, but log it)
            Log.w(TAG, "Neither /system/bin/sh nor " + imagefsSh.getAbsolutePath()
                + " is executable — trying /bin/sh as last resort");
            cmd.add("/bin/sh");
        }
        cmd.add(placeholderScript.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(rootDir);
        pb.redirectErrorStream(true);

        Map<String, String> envVars = pb.environment();
        envVars.clear();
        // Gamescope is a CLIENT of the bridge — connect to wayland-0
        envVars.put("WAYLAND_DISPLAY", "wayland-0");
        envVars.put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());
        envVars.put("HOME", new File(rootDir, "home/xuser").getAbsolutePath());
        envVars.put("TMPDIR", new File(rootDir, "usr/tmp").getAbsolutePath());
        envVars.put("PATH", "/system/bin:/system/lib64:" + new File(rootDir, "usr/bin").getAbsolutePath());

        // Vulkan: force Turnip via adrenotools. The bridge JVM side already
        // sets up the adrenotools linker namespace; gamescope's wlroots Vulkan
        // renderer picks up the same Turnip ICD via VK_DRIVER_FILES.
        File turnipJson = new File(rootDir, "usr/share/vulkan/icd.d/freedreno_icd.aarch64.json");
        if (turnipJson.exists()) {
            envVars.put("VK_DRIVER_FILES", turnipJson.getAbsolutePath());
            envVars.put("VK_ICD_FILENAMES", turnipJson.getAbsolutePath());
        }
        envVars.put("MESA_VK_WSI_PRESENT_MODE", "immediate");
        envVars.put("TU_DEBUG", "noconform,sysmem,perf");
        envVars.put("MESA_NO_ERROR", "true");

        try {
            gamescopeProcess = pb.start();
            Log.i(TAG, "Gamescope process started: " + gamescopeProcess.toString());

            // Capture gamescope stdout/stderr to log + file
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(gamescopeProcess.getInputStream()));
                     OutputStreamWriter osw = new OutputStreamWriter(
                             new FileOutputStream(outputLogFile, true))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.i("WayLandIE/Gamescope", line);
                        osw.write("[" + new java.util.Date() + "] " + line + "\n");
                        osw.flush();
                    }
                } catch (IOException e) {
                    if (!stopped) {
                        Log.e(TAG, "Error reading gamescope output", e);
                    }
                }
            }, "Gamescope-OutputReader").start();

            // Wait briefly for the gamescope-0 socket to appear
            File gamescopeSocket = new File(runtimeDir, GAMESCOPE_WAYLAND_DISPLAY);
            int waitMs = 0;
            while (!gamescopeSocket.exists() && waitMs < 5000) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                waitMs += 50;
            }
            if (gamescopeSocket.exists()) {
                Log.i(TAG, "Gamescope internal Wayland socket ready: " + gamescopeSocket);
            } else {
                Log.w(TAG, "Gamescope socket did not appear within 5s — Wine may fail to connect");
            }

            // Watcher thread that detects gamescope crashes
            new Thread(() -> {
                try {
                    int exitCode = gamescopeProcess.waitFor();
                    if (!stopped) {
                        Log.e(TAG, "Gamescope exited unexpectedly with code " + exitCode);
                        writeDiagnostic(env.getContext(), "GAMESCOPE_EXITED: code=" + exitCode);
                    }
                } catch (InterruptedException e) {
                    if (!stopped) Log.w(TAG, "Gamescope watcher interrupted", e);
                }
            }, "Gamescope-Watcher").start();

        } catch (IOException e) {
            Log.e(TAG, "Failed to start gamescope process", e);
            writeDiagnostic(context, "GAMESCOPE_START_FAILED: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        stopped = true;
        if (gamescopeProcess != null) {
            Log.i(TAG, "Stopping gamescope process");
            gamescopeProcess.destroy();
            try {
                if (!gamescopeProcess.waitFor(2000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "Gamescope did not exit gracefully, force-killing");
                    gamescopeProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gamescopeProcess = null;
        }
        // Clean up the gamescope-0 socket and placeholder script
        XEnvironment env = environment;
        if (env != null) {
            File rootDir = env.getImageFs().getRootDir();
            File runtimeDir = new File(new File(rootDir, "usr/tmp"), "runtime");
            new File(runtimeDir, GAMESCOPE_WAYLAND_DISPLAY).delete();
            new File(runtimeDir, GAMESCOPE_WAYLAND_DISPLAY + ".lock").delete();
            new File(runtimeDir, "gamescope-placeholder.sh").delete();
        }
    }

    private void writeDiagnostic(Context context, String message) {
        try {
            File logsDir = com.winlator.cmod.runtime.system.LogManager.getLogsDir(context);
            File diag = new File(logsDir, "gamescope-diagnostics.log");
            try (FileWriter fw = new FileWriter(diag, true)) {
                fw.write("[" + new java.util.Date() + "] " + message + "\n");
            }
        } catch (IOException ignored) {}
    }

    /**
     * Returns the gamescope-internal Wayland display name (e.g. "gamescope-0").
     * Wine should be configured with WAYLAND_DISPLAY=<this value> instead of
     * "wayland-0" when gamescope is active.
     */
    public static String getGamescopeWaylandDisplay() {
        return GAMESCOPE_WAYLAND_DISPLAY;
    }
}
