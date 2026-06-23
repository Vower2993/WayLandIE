package io.waylandie.display.runtime.environment;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * GameLaunchTracer — comprehensive in-app diagnostic for game launch failures.
 *
 * <p>Wraps WineRunner.execWine() with full instrumentation:
 * <ul>
 *   <li>Pre-flight file existence checks (all files WineRunner will touch)</li>
 *   <li>Exact env var dump (what Wine will actually receive)</li>
 *   <li>Android-side bridge socket state (LocalServerSocket bind check)</li>
 *   <li>Wine process monitoring (alive? exit code? socket created?)</li>
 *   <li>Line-by-line stdout/stderr capture with timestamps</li>
 *   <li>Single trace file output to /storage/emulated/0/Download/WayLandIE/logs/</li>
 * </ul>
 *
 * <p><b>Usage:</b> Call {@link #launchAndTrace} instead of
 * {@code WineRunner.execWine(...)} from HomeActivity.
 *
 * <p><b>Why this exists:</b> Without ADB, diagnosing game launch failures
 * requires capturing WineRunner's internal state (env vars, process exit
 * codes) which isn't visible in the in-app log buffer. This class captures
 * everything in one shot so the user can share a single trace file and we
 * can diagnose without back-and-forth.
 */
public final class GameLaunchTracer {

    private static final String TAG = "WayLandIE/Tracer";
    // 300 seconds (5 minutes) — Wine is hanging for 180+ seconds before
    // rendering its first frame. We need a longer window to see if Wine
    // eventually recovers or if it's permanently stuck.
    private static final long MONITOR_DURATION_MS = 300_000L;
    private static final long MONITOR_INTERVAL_MS = 1_000L;

    private final Context context;
    private final StringBuilder trace = new StringBuilder();
    private Process wineProcess; // stored for forceWriteTrace()
    private final SimpleDateFormat tsFmt =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public GameLaunchTracer(Context context) {
        this.context = context;
    }

    /**
     * Launch a game with full diagnostic tracing.
     *
     * @return the Wine Process (same as WineRunner.execWine returns)
     */
    public Process launchAndTrace(String exePath, String[] extraArgs, boolean useProton)
            throws IOException {
        logSection("WayLandIE Game Launch Trace");
        log("Started: " + new Date());
        log("Exe path: " + exePath);
        log("Extra args: " + (extraArgs == null ? "null" : String.join(" ", extraArgs)));
        log("Use Proton: " + useProton);
        log("");

        // ---------------------------------------------------------------
        // 1. Pre-flight: check every file WineRunner will touch
        // ---------------------------------------------------------------
        logSection("PRE-FLIGHT FILE CHECKS");
        File rootDir = new File(context.getFilesDir(), "imagefs");
        File protonDir = new File(context.getFilesDir(), "contents/proton/active");
        File fexDir = new File(context.getFilesDir(), "contents/fex/active");
        File dxvkDir = new File(context.getFilesDir(), "contents/dxvk/active");
        File turnipDir = new File(context.getFilesDir(), "contents/turnip/active");
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;

        checkFile("nativeLibraryDir", new File(nativeLibDir));
        checkFile("libld_glibc.so", new File(nativeLibDir, "libld_glibc.so"));
        checkFile("libwaylandie_bridge.so", new File(nativeLibDir, "libwaylandie_bridge.so"));
        checkFile("libwaylandie_bionic_test.so", new File(nativeLibDir, "libwaylandie_bionic_test.so"));
        checkFile("libwaylandie_syscall_scan.so", new File(nativeLibDir, "libwaylandie_syscall_scan.so"));
        checkFile("libproot.so", new File(nativeLibDir, "libproot.so"));
        checkFile("imagefs root", rootDir);
        checkFile("imagefs/usr/bin", new File(rootDir, "usr/bin"));
        checkFile("imagefs/usr/lib", new File(rootDir, "usr/lib"));
        checkFile("imagefs/usr/lib/aarch64-linux-gnu", new File(rootDir, "usr/lib/aarch64-linux-gnu"));
        checkFile("imagefs/usr/local/lib", new File(rootDir, "usr/local/lib"));
        checkFile("imagefs/usr/local/bin/waylandie-wayland-bridge (glibc bridge)",
                new File(rootDir, "usr/local/bin/waylandie-wayland-bridge"));
        checkFile("imagefs/usr/local/lib/libwaylandie_shim.so (syscall shim)",
                new File(rootDir, "usr/local/lib/libwaylandie_shim.so"));
        checkFile("imagefs/usr/lib/ld-linux-aarch64.so.1",
                new File(rootDir, "usr/lib/ld-linux-aarch64.so.1"));
        checkFile("imagefs/lib/ld-linux-aarch64.so.1",
                new File(rootDir, "lib/ld-linux-aarch64.so.1"));
        checkFile("Proton active dir", protonDir);
        checkFile("Proton bin/wine", new File(protonDir, "bin/wine"));
        checkFile("Proton files/bin/wine", new File(protonDir, "files/bin/wine"));
        checkFile("Proton dist/bin/wine", new File(protonDir, "dist/bin/wine"));
        checkFile("Proton lib/", new File(protonDir, "lib"));
        checkFile("Proton files/lib/", new File(protonDir, "files/lib"));
        checkFile("Proton prefixPack.txz", new File(protonDir, "prefixPack.txz"));
        checkFile("FEX active dir", fexDir);
        checkFile("DXVK active dir", dxvkDir);
        checkFile("Turnip active dir", turnipDir);
        checkFile("Turnip libvulkan_freedreno.so",
                new File(turnipDir, "libvulkan_freedreno.so"));

        // Wine prefix
        File winePrefix = new File(context.getFilesDir(), "imagefs/home/xuser/.wine");
        checkFile("Wine prefix", winePrefix);
        checkFile("Wine prefix system.reg", new File(winePrefix, "system.reg"));
        checkFile("Wine prefix system32/d3d11.dll",
                new File(winePrefix, "drive_c/windows/system32/d3d11.dll"));
        checkFile("Wine prefix system32/libarm64ecfex.dll",
                new File(winePrefix, "drive_c/windows/system32/libarm64ecfex.dll"));

        // Runtime dirs
        File runtimeDir = new File(rootDir, "usr/tmp/runtime");
        checkFile("XDG_RUNTIME_DIR", runtimeDir);
        log("");

        // ---------------------------------------------------------------
        // 2. Check Android-side bridge socket state BEFORE launching
        // ---------------------------------------------------------------
        logSection("ANDROID BRIDGE SOCKET STATE (pre-launch)");
        checkAbstractSocket("waylandie.display.bridge.v1");
        checkTcpPort(57391, "Android display bridge");
        checkTcpPort(57392, "Audio bridge");
        log("");

        // ---------------------------------------------------------------
        // 3. Build + dump env vars (mirror WineRunner.setupEnvironment)
        // ---------------------------------------------------------------
        logSection("WINE ENVIRONMENT VARIABLES (computed)");
        Map<String, String> env = new TreeMap<>();
        File homeDir = new File(rootDir, "home/xuser");
        File tmpDir = new File(rootDir, "usr/tmp");
        env.put("HOME", homeDir.getAbsolutePath());
        env.put("USER", "xuser");
        env.put("PATH", new File(protonDir, "bin").getAbsolutePath() + ":"
                + new File(protonDir, "files/bin").getAbsolutePath() + ":"
                + new File(rootDir, "usr/bin").getAbsolutePath() + ":"
                + new File(rootDir, "usr/local/bin").getAbsolutePath());
        env.put("LD_LIBRARY_PATH",
                new File(rootDir, "usr/lib").getAbsolutePath() + ":"
                + new File(rootDir, "usr/lib/aarch64-linux-gnu").getAbsolutePath() + ":"
                + new File(rootDir, "usr/local/lib").getAbsolutePath() + ":"
                + new File(protonDir, "lib").getAbsolutePath() + ":"
                + new File(protonDir, "files/lib").getAbsolutePath()
                + ":/system/lib64");
        env.put("LANG", "en_US.UTF-8");
        env.put("TERM", "xterm-256color");
        env.put("TMPDIR", tmpDir.getAbsolutePath());
        env.put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());
        env.put("GLIBC_TUNABLES", "glibc.pthread.rseq=0");
        File shimFile = new File(rootDir, "usr/local/lib/libwaylandie_shim.so");
        if (shimFile.exists()) {
            env.put("LD_PRELOAD", shimFile.getAbsolutePath());
        }
        env.put("WINEPREFIX", winePrefix.getAbsolutePath());
        env.put("WINEDLLOVERRIDES", "d3d9,d3d10core,d3d11,dxgi=native;winex11.drv=d;winewayland.drv=b,native");
        env.put("MESA_VK_WSI_PRESENT_MODE", "immediate");
        // Mirror WineRunner diagnostics env (so trace shows what Wine will see)
        env.put("WINEDEBUG", "+loaddll,+module,+input,+event,+winewayland_drv");
        env.put("DXVK_LOG_LEVEL", "info");
        env.put("DXVK_LOG_PATH", "/storage/emulated/0/Download/WayLandIE/logs/dxvk");
        env.put("DXVK_HUD", "fps,frametimes,devinfo,gpuload");
        env.put("DXVK_FRAME_RATE", "0");
        env.put("FEX_LOG_LEVEL", "info");
        env.put("FEX_PRINT_OPTIONS", "1");
        env.put("FEX_DISABLE_JIT", "0");
        env.put("FEX_DEBUG_FILE", "/storage/emulated/0/Download/WayLandIE/logs/fex.log");
        env.put("FEX_THROW_ON_INVALID", "1");
        env.put("FEX_INTERPRETER_VISITOR", "0");
        env.put("VK_EXT_debug_utils", "1");
        env.put("VK_INSTANCE_LAYERS", "VK_LAYER_LUNARG_monitor");
        env.put("ADRENOTOOLS_LOG_LEVEL", "1");
        env.put("TU_DEBUG", "perf");
        env.put("WAYLANDIE_WAYLAND_INPUT_DEBUG", "1");
        env.put("WAYLANDIE_WAYLAND_INPUT_LOG", "/storage/emulated/0/Download/WayLandIE/logs/wayland-input.log");
        env.put("WINE_NO_DUPLICATE_EXPLORER", "1");
        env.put("FONTCONFIG_PATH", new File(rootDir, "usr/etc/fonts").getAbsolutePath());
        env.put("GST_PLUGIN_PATH", new File(rootDir, "usr/lib/gstreamer-1.0").getAbsolutePath());
        env.put("XDG_DATA_DIRS", new File(rootDir, "usr/share").getAbsolutePath());
        env.put("XDG_CONFIG_DIRS", new File(rootDir, "usr/etc/xdg").getAbsolutePath());
        env.put("WAYLANDIE_BRIDGE_SOCKET", "waylandie.display.bridge.v1");
        env.put("VK_LAYER_PATH", new File(rootDir, "usr/share/vulkan/implicit_layer.d").getAbsolutePath()
                + ":" + new File(rootDir, "usr/share/vulkan/explicit_layer.d").getAbsolutePath());
        env.put("PROTONPATH", protonDir.getAbsolutePath());
        env.put("STEAM_COMPAT_CLIENT_INSTALL_PATH",
                new File(context.getFilesDir(), "contents/steam").getAbsolutePath());
        env.put("STEAM_COMPAT_DATA_PATH",
                new File(context.getFilesDir(), "contents/steam/compatdata").getAbsolutePath());
        env.put("STEAM_RUNTIME", "0");

        for (Map.Entry<String, String> e : env.entrySet()) {
            log("  " + e.getKey() + "=" + e.getValue());
        }
        log("");

        // ---------------------------------------------------------------
        // 4. Launch Wine via WineRunner
        // ---------------------------------------------------------------
        logSection("LAUNCH");
        WineRunner wineRunner = new WineRunner(context);
        if (!wineRunner.isReady()) {
            log("✗ WineRunner not ready");
            writeTraceFile();
            throw new IOException("WineRunner not ready");
        }

        log("Calling WineRunner.execWine(exePath=" + exePath + ", useProton=" + useProton + ")…");
        try {
            wineProcess = wineRunner.execWine(exePath, extraArgs, useProton);
        } catch (IOException e) {
            log("✗ WineRunner.execWine threw: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            log("  Stack trace:");
            for (StackTraceElement ste : e.getStackTrace()) {
                log("    at " + ste);
            }
            writeTraceFile();
            throw e;
        }

        long winePid = -1;
        // Android's Process class doesn't expose pid() until API 35. Try
        // multiple reflection approaches. See WineRunner.getPid() for details.
        try {
            java.lang.reflect.Method pidMethod = wineProcess.getClass().getMethod("pid");
            pidMethod.setAccessible(true);
            winePid = (long) pidMethod.invoke(wineProcess);
        } catch (Exception ignored) {
        }
        if (winePid == -1) {
            try {
                java.lang.reflect.Method toHandle = wineProcess.getClass().getMethod("toHandle");
                Object handle = toHandle.invoke(wineProcess);
                java.lang.reflect.Method pidOf = handle.getClass().getMethod("pid");
                winePid = (long) pidOf.invoke(handle);
            } catch (Exception ignored) {
            }
        }
        if (winePid == -1) {
            for (String fieldName : new String[]{"pid", "mPid", "id"}) {
                try {
                    java.lang.reflect.Field pidField = wineProcess.getClass().getDeclaredField(fieldName);
                    pidField.setAccessible(true);
                    winePid = pidField.getLong(wineProcess);
                    break;
                } catch (Exception ignored) {
                }
            }
        }
        log("✓ Wine process started (pid=" + winePid + ")");
        log("");

        // ---------------------------------------------------------------
        // 4b. Dump WineRunner's pre-launch diagnostics into trace file
        // ---------------------------------------------------------------
        // WineRunner.execWine() ran diagnostics (library checks, .drv search,
        // env vars, symlinks, etc.) and stored them in a static buffer.
        // Write them to the trace file here so they're visible alongside
        // Wine's stdout.

        // First: dump installer diagnostics (separate buffer, not cleared)
        String installerDiag = io.waylandie.display.runtime.environment.WineRunner.installerDiagnostics.toString();
        if (installerDiag != null && !installerDiag.isEmpty()) {
            logSection("WAYLAND DRIVER INSTALLER");
            for (String line : installerDiag.split("\n")) {
                if (!line.isEmpty()) {
                    log("  " + line);
                }
            }
            log("");
        }

        logSection("WINE RUNNER DIAGNOSTICS (pre-launch)");
        String diag = io.waylandie.display.runtime.environment.WineRunner.preLaunchDiagnostics.toString();
        if (diag != null && !diag.isEmpty()) {
            for (String line : diag.split("\n")) {
                if (!line.isEmpty()) {
                    log("  " + line);
                }
            }
        } else {
            log("  (no diagnostics — WineRunner may not have reached the diagnostic section)");
        }
        log("");

        // ---------------------------------------------------------------
        // 4c. Dump any bridge output captured so far.
        // The bridgeOutput buffer is filled asynchronously by WineRunner's
        // wl-bridge-output thread. We dump what's accumulated by the time
        // we reach this point. More may accumulate during the process
        // monitor below — we'll dump the final buffer at the end.
        // ---------------------------------------------------------------
        String bridgeOutInitial = io.waylandie.display.runtime.environment.WineRunner.bridgeOutput.toString();
        if (bridgeOutInitial != null && !bridgeOutInitial.isEmpty()) {
            logSection("BRIDGE OUTPUT (initial — captured before Wine stdout)");
            for (String line : bridgeOutInitial.split("\n")) {
                if (!line.isEmpty()) {
                    log("  [bridge] " + line);
                }
            }
            log("");
        } else {
            logSection("BRIDGE OUTPUT (initial)");
            log("  (empty — bridge may not have produced output yet, or wl-bridge-output thread failed)");
            log("");
        }

        // ---------------------------------------------------------------
        // 5. Capture Wine stdout/stderr line-by-line with timestamps
        // ---------------------------------------------------------------
        logSection("WINE STDOUT/STDERR");
        List<String> wineOutput = new ArrayList<>();
        Thread wineOutputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(wineProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (wineOutput) {
                        wineOutput.add(line);
                        log("  [wine] " + line);
                    }
                }
                synchronized (wineOutput) {
                    log("  [wine] <stdout ended>");
                }
            } catch (IOException e) {
                synchronized (wineOutput) {
                    log("  [wine] <reader IOException: " + e.getMessage() + ">");
                }
            }
        }, "Tracer-Wine-Output");
        wineOutputThread.setDaemon(true);
        wineOutputThread.start();
        log("");

        // ---------------------------------------------------------------
        // 6. Monitor Wine process for 30 seconds
        // ---------------------------------------------------------------
        logSection("PROCESS MONITOR (" + (MONITOR_DURATION_MS / 1000) + "s)");
        long monitorStart = System.currentTimeMillis();
        File socketNameFile = new File(runtimeDir, "socket-name.txt");
        while (System.currentTimeMillis() - monitorStart < MONITOR_DURATION_MS) {
            long elapsed = System.currentTimeMillis() - monitorStart;
            boolean wineAlive = wineProcess.isAlive();

            // Check Wayland socket file
            String socketName = null;
            if (socketNameFile.exists()) {
                try {
                    socketName = new String(
                            java.nio.file.Files.readAllBytes(socketNameFile.toPath())).trim();
                } catch (IOException ignored) {
                    socketName = "<read-error>";
                }
            }

            // Check abstract socket existence (can we connect?)
            String bridgeSocketState;
            try {
                android.net.LocalSocket probe = new android.net.LocalSocket();
                probe.connect(new android.net.LocalSocketAddress(
                        "waylandie.display.bridge.v1",
                        android.net.LocalSocketAddress.Namespace.ABSTRACT));
                probe.close();
                bridgeSocketState = "listening";
            } catch (IOException notListening) {
                bridgeSocketState = "not-listening";
            }

            log(String.format(Locale.US, "  [%5.1fs] wineAlive=%s wineExit=%s wlSocket=%s androidBridgeSocket=%s",
                    elapsed / 1000.0,
                    wineAlive,
                    wineAlive ? "n/a" : String.valueOf(wineProcess.exitValue()),
                    socketName == null ? "<not-yet>" : socketName,
                    bridgeSocketState));

            if (!wineAlive) {
                log("  → Wine process exited. Stopping monitor.");
                break;
            }

            // Every 30 seconds, dump any new bridge output to the trace
            // so we can see bridge events as they happen (not just at the end).
            if (elapsed > 0 && elapsed % 30_000L < MONITOR_INTERVAL_MS) {
                String bridgeSnapshot = io.waylandie.display.runtime.environment.WineRunner.bridgeOutput.toString();
                if (bridgeSnapshot != null && !bridgeSnapshot.isEmpty()) {
                    int newLines = 0;
                    for (String line : bridgeSnapshot.split("\n")) {
                        if (!line.isEmpty() && !trace.toString().contains(line)) {
                            newLines++;
                        }
                    }
                    if (newLines > 0) {
                        log("  [bridge snapshot at " + (elapsed/1000) + "s: " + newLines + " new lines]");
                    }
                }
            }

            try {
                Thread.sleep(MONITOR_INTERVAL_MS);
            } catch (InterruptedException ignored) {
                break;
            }

            // Write trace file every 120 seconds so it's available even if
            // the app crashes. Not every 60s — Wine needs 5 minutes for
            // wineboot, and writing too frequently creates incomplete traces.
            if (elapsed > 0 && elapsed % 120_000L < MONITOR_INTERVAL_MS) {
                writeTraceFile();
            }
        }
        log("");

        // Grace period: wait 10 seconds after monitoring ends to let the
        // bridge's present_buffer_to_android() complete. The bridge sends
        // the dmabuf to the Java presenter and waits for a response — if
        // the trace is written immediately after the loop ends, the present
        // may still be in progress and the response won't be captured.
        log("Waiting 10s for pending bridge present to complete…");
        try { Thread.sleep(10_000L); } catch (InterruptedException ignored) {}

        // ---------------------------------------------------------------
        // 7. Final state
        // ---------------------------------------------------------------
        logSection("FINAL STATE");
        log("Wine alive: " + wineProcess.isAlive());
        if (!wineProcess.isAlive()) {
            int ec = wineProcess.exitValue();
            log("Wine exit code: " + ec);
            if (ec == 159) {
                log("  → exit 159 = SIGSYS (signal 31) — blocked syscall");
            } else if (ec == 139) {
                log("  → exit 139 = SIGSEGV — segmentation fault");
            } else if (ec == 134) {
                log("  → exit 134 = SIGABRT — Wine abort (often DLL load failure)");
            } else if (ec == 137) {
                log("  → exit 137 = SIGKILL — killed by system (OOM or seccomp)");
            }
        }
        log("");

        // ---------------------------------------------------------------
        // 8. Post-launch Android bridge socket state
        // ---------------------------------------------------------------
        logSection("ANDROID BRIDGE SOCKET STATE (post-launch)");
        checkAbstractSocket("waylandie.display.bridge.v1");
        checkTcpPort(57391, "Android display bridge");
        log("");

        // ---------------------------------------------------------------
        // 8b. Dump FULL bridge output (everything accumulated by now)
        // ---------------------------------------------------------------
        // The bridgeOutput buffer has been filling asynchronously throughout
        // the monitoring period. Dump the full content here so we see ALL
        // bridge events (wayland-shm-ahb present events, errors, etc.),
        // not just the initial burst.
        String bridgeOutFinal = io.waylandie.display.runtime.environment.WineRunner.bridgeOutput.toString();
        logSection("BRIDGE OUTPUT (full — captured during entire run)");
        if (bridgeOutFinal != null && !bridgeOutFinal.isEmpty()) {
            int lineCount = 0;
            for (String line : bridgeOutFinal.split("\n")) {
                if (!line.isEmpty()) {
                    log("  [bridge] " + line);
                    lineCount++;
                }
            }
            log("  (" + lineCount + " lines total)");
        } else {
            log("  (empty — bridge produced no stdout/stderr output)");
            log("  This is suspicious. The bridge should at least log its");
            log("  startup message. Possible causes:");
            log("    - Bridge crashed before producing any output");
            log("    - wl-bridge-output thread failed to start");
            log("    - Bridge process never started (check [bridge] lines in");
            log("      WAYLAND DRIVER INSTALLER section above)");
        }
        log("");

        // ---------------------------------------------------------------
        // 8c. Dump wine-stderr.log (Wine's WINEDEBUG output redirected to file)
        // ---------------------------------------------------------------
        logSection("WINE STDERR (from wine-stderr.log file)");
        File wineStderrFile = new File("/storage/emulated/0/Download/WayLandIE/logs/wine-stderr.log");
        dumpFileTail(wineStderrFile, 500);
        log("");

        // ---------------------------------------------------------------
        // 8d. Dump wayland-input.log (bridge's input_debug_log file output)
        // ---------------------------------------------------------------
        logSection("WAYLAND BRIDGE INPUT LOG (from wayland-input.log)");
        File waylandInputFile = new File("/storage/emulated/0/Download/WayLandIE/logs/wayland-input.log");
        dumpFileTail(waylandInputFile, 500);
        log("");

        // ---------------------------------------------------------------
        // 8e. Dump FEX log if it exists
        // ---------------------------------------------------------------
        logSection("FEX LOG (from fex.log)");
        File fexLogFile = new File("/storage/emulated/0/Download/WayLandIE/logs/fex.log");
        dumpFileTail(fexLogFile, 200);
        log("");

        // ---------------------------------------------------------------
        // 8f. Dump DXVK logs if they exist (dxvk/dxvk-<pid>.log)
        // ---------------------------------------------------------------
        logSection("DXVK LOG (from dxvk/dxvk-*.log)");
        File dxvkLogDir = new File("/storage/emulated/0/Download/WayLandIE/logs/dxvk");
        if (dxvkLogDir.isDirectory()) {
            File[] dxvkLogs = dxvkLogDir.listFiles();
            if (dxvkLogs != null && dxvkLogs.length > 0) {
                for (File dxvkLog : dxvkLogs) {
                    if (dxvkLog.isFile()) {
                        log("  --- " + dxvkLog.getName() + " ---");
                        dumpFileTail(dxvkLog, 200);
                    }
                }
            } else {
                log("  (dxvk dir empty — DXVK may not have loaded)");
            }
        } else {
            log("  (dxvk log dir not created — DXVK may not have loaded)");
        }
        log("");

        // ---------------------------------------------------------------
        // 9. Write trace file
        // ---------------------------------------------------------------
        writeTraceFile();

        return wineProcess;
    }

    /** Dump the last N lines of a file to the trace (prefixed with "    "). */
    private void dumpFileTail(File f, int maxLines) {
        if (f == null || !f.isFile()) {
            log("  (file not found: " + (f == null ? "null" : f.getAbsolutePath()) + ")");
            return;
        }
        try {
            java.util.Deque<String> tail = new java.util.ArrayDeque<>(maxLines + 1);
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f)))) {
                String line;
                while ((line = r.readLine()) != null) {
                    tail.addLast(line);
                    if (tail.size() > maxLines) tail.removeFirst();
                }
            }
            log("  (" + f.getName() + " — last " + tail.size() + " lines)");
            for (String line : tail) {
                log("    " + line);
            }
        } catch (IOException e) {
            log("  (read error: " + e.getMessage() + ")");
        }
    }

    private void checkFile(String label, File f) {
        String state;
        if (!f.exists()) {
            state = "✗ MISSING";
        } else if (f.isDirectory()) {
            String[] children = f.list();
            int count = children == null ? 0 : children.length;
            state = "✓ dir (" + count + " items)";
        } else {
            state = "✓ file (" + f.length() + " bytes)";
        }
        log("  " + label + ": " + state);
        log("    path: " + f.getAbsolutePath());
    }

    private void checkAbstractSocket(String name) {
        try {
            android.net.LocalSocket probe = new android.net.LocalSocket();
            probe.connect(new android.net.LocalSocketAddress(
                    name, android.net.LocalSocketAddress.Namespace.ABSTRACT));
            probe.close();
            log("  abstract socket " + name + ": ✓ listening");
        } catch (IOException e) {
            log("  abstract socket " + name + ": ✗ not listening (" + e.getMessage() + ")");
        }
    }

    private void checkTcpPort(int port, String label) {
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 200);
            s.close();
            log("  TCP " + port + " (" + label + "): ✓ listening");
        } catch (IOException e) {
            log("  TCP " + port + " (" + label + "): ✗ not listening (" + e.getMessage() + ")");
        }
    }

    private void logSection(String title) {
        log("");
        log("--- " + title + " ---");
    }

    private void log(String line) {
        String stamped = "[" + tsFmt.format(new Date()) + "] " + line;
        trace.append(stamped).append('\n');
        Log.i(TAG, line);
        io.waylandie.display.shared.util.LogRingBuffer.append("[trace] " + line);
    }

    private void writeTraceFile() {
        try {
            // Write trace to APP-PRIVATE storage first (safe from Wine crashes).
            File privateLogDir = new File(context.getFilesDir(), "logs");
            if (!privateLogDir.exists() && !privateLogDir.mkdirs()) {
                Log.e(TAG, "Failed to create private log dir: " + privateLogDir);
                return;
            }
            String fname = "launch-trace-"
                    + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date())
                    + ".txt";
            File privateFile = new File(privateLogDir, fname);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(privateFile))) {
                w.write(trace.toString());
            }
            Log.i(TAG, "Trace written to private storage: " + privateFile.getAbsolutePath());
            io.waylandie.display.shared.util.LogRingBuffer.append(
                    "[trace] Trace file written: " + privateFile.getAbsolutePath());

            // ALSO copy to Download folder so the user can access it from
            // their Android file explorer. This is safe because writeTraceFile()
            // is called AFTER Wine has exited (the trace monitoring loop has
            // finished), so Wine can't wipe it.
            try {
                File publicLogDir = new File(
                        android.os.Environment.getExternalStorageDirectory(),
                        "Download/WayLandIE/logs");
                if (!publicLogDir.exists() && !publicLogDir.mkdirs()) {
                    Log.w(TAG, "Could not create public log dir: " + publicLogDir);
                    return;
                }
                File publicFile = new File(publicLogDir, fname);
                java.nio.file.Files.copy(privateFile.toPath(), publicFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Log.i(TAG, "Trace copied to Download: " + publicFile.getAbsolutePath());
                io.waylandie.display.shared.util.LogRingBuffer.append(
                        "[trace] Trace file copied to: " + publicFile.getAbsolutePath());
            } catch (Exception e) {
                Log.w(TAG, "Could not copy trace to Download: " + e.getMessage());
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to write trace file: " + e.getMessage());
        }
    }

    /**
     * Force-writes the trace file immediately. Called from HomeActivity.onDestroy()
     * when the user swipes back before the monitoring window ends. This ensures
     * we capture whatever bridge/Wine output has been collected so far.
     */
    public void forceWriteTrace() {
        Log.i(TAG, "forceWriteTrace() called — writing trace with current data");
        // Append final state marker
        log("");
        logSection("FORCE-WRITTEN TRACE (activity destroyed)");
        log("Wine alive: " + (wineProcess != null && wineProcess.isAlive()));
        log("");
        // Dump bridge output (may be partial if monitoring was still running)
        String bridgeOut = io.waylandie.display.runtime.environment.WineRunner.bridgeOutput.toString();
        if (bridgeOut != null && !bridgeOut.isEmpty()) {
            logSection("BRIDGE OUTPUT (partial — force-written)");
            for (String line : bridgeOut.split("\n")) {
                if (!line.isEmpty()) {
                    log("  [bridge] " + line);
                }
            }
            log("");
        }
        writeTraceFile();
    }
}
