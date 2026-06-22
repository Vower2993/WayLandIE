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
    private static final long MONITOR_DURATION_MS = 30_000L;
    private static final long MONITOR_INTERVAL_MS = 1_000L;

    private final Context context;
    private final StringBuilder trace = new StringBuilder();
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
        env.put("WINEDLLOVERRIDES", "d3d9,d3d10core,d3d11,dxgi=native,winex11.drv=d,winewayland.drv=b,native");
        env.put("MESA_VK_WSI_PRESENT_MODE", "immediate");
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
        Process wineProcess;
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

            try {
                Thread.sleep(MONITOR_INTERVAL_MS);
            } catch (InterruptedException ignored) {
                break;
            }
        }
        log("");

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
        // 9. Write trace file
        // ---------------------------------------------------------------
        writeTraceFile();

        return wineProcess;
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
            File logDir = new File("/storage/emulated/0/Download/WayLandIE/logs");
            if (!logDir.exists() && !logDir.mkdirs()) {
                Log.e(TAG, "Failed to create log dir: " + logDir);
                return;
            }
            String fname = "launch-trace-"
                    + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date())
                    + ".txt";
            File outFile = new File(logDir, fname);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(outFile))) {
                w.write(trace.toString());
            }
            Log.i(TAG, "Trace file written: " + outFile.getAbsolutePath());
            io.waylandie.display.shared.util.LogRingBuffer.append(
                    "[trace] Trace file written: " + outFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to write trace file: " + e.getMessage());
        }
    }
}
