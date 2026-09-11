package com.winlator.cmod.runtime.display.environment.components;

import com.winlator.cmod.runtime.display.environment.EnvironmentComponent;
import com.winlator.cmod.runtime.display.environment.ImageFs;
import com.winlator.cmod.runtime.display.environment.XEnvironment;
import com.winlator.cmod.runtime.display.ui.XServerSurfaceView;

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
 * Starts the WaylandIE bionic bridge process — a lightweight Wayland compositor
 * that receives dmabuf buffers from Wine's winewayland.drv and forwards them
 * to Android's SurfaceFlinger via AHB (zero-copy).
 *
 * Replaces XServerComponent for the Wayland display path.
 */
public class WaylandBridgeComponent extends EnvironmentComponent {
    private static final String TAG = "WaylandBridgeComponent";

    private Process bridgeProcess;
    private File socketNameFile;
    private File outputLogFile;
    private volatile boolean stopped = false;

    @Override
    public void start() {
        // Re-enabled. This was disabled when the in-process compositor (libwaylandie_comp.so)
        // landed, to stop the two racing for wayland-0 - a real hazard. The fix is not to
        // disable one of them, but to run exactly one: XServerSurfaceView.USE_IN_PROCESS_COMPOSITOR
        // is now false, so the in-process compositor does not start and this bridge owns the
        // socket alone.
        //
        // This path is the one that matters: it is the only compositor here with the SHM->AHB
        // conversion (WAYLANDIE_HAS_AHARDWAREBUFFER) whose AHardwareBuffer is exported as a
        // dmabuf fd and presented through WaylandBridgeServer + SurfaceControl. The in-process
        // compositor has no SHM->AHB path at all - it blits a linear VkImage - and the Wine
        // desktop has never been visible through it.
        if (XServerSurfaceView.USE_IN_PROCESS_COMPOSITOR) {
            Log.w(TAG, "WaylandBridgeComponent.start() skipped: the in-process compositor "
                    + "(libwaylandie_comp.so) is selected and would race this bridge for "
                    + "wayland-0.");
            return;
        }
        XEnvironment env = environment;
        if (env == null) {
            Log.e(TAG, "Environment not set");
            return;
        }
        Context context = env.getContext();
        ImageFs imageFs = env.getImageFs();
        File rootDir = imageFs.getRootDir();
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;

        File bridgeBin = new File(nativeLibDir, "libwaylandie_bridge_exe.so");
        Log.i(TAG, "Bridge binary path: " + bridgeBin.getAbsolutePath());
        Log.i(TAG, "Bridge binary exists: " + bridgeBin.exists());
        Log.i(TAG, "Bridge binary size: " + (bridgeBin.exists() ? bridgeBin.length() : 0) + " bytes");

        if (!bridgeBin.exists()) {
            Log.e(TAG, "Bridge binary not found at " + bridgeBin);
            writeDiagnostic(context, "BRIDGE_BINARY_NOT_FOUND: " + bridgeBin);
            return;
        }
        boolean execSet = bridgeBin.setExecutable(true, false);
        Log.i(TAG, "Bridge binary setExecutable: " + execSet);

        File runtimeDir = new File(new File(rootDir, "usr/tmp"), "runtime");
        if (!runtimeDir.exists()) runtimeDir.mkdirs();
        socketNameFile = new File(runtimeDir, "socket-name.txt");

        // Clean up stale sockets
        new File(runtimeDir, "wayland-0").delete();
        new File(runtimeDir, "wayland-0.lock").delete();
        socketNameFile.delete();

        Log.i(TAG, "XDG_RUNTIME_DIR: " + runtimeDir.getAbsolutePath());
        Log.i(TAG, "runtimeDir exists: " + runtimeDir.exists());
        Log.i(TAG, "runtimeDir writable: " + runtimeDir.canWrite());

        // Create a dedicated output log file for the bridge process's stdout/stderr.
        // This captures ALL bridge output (wayland-shm-ahb diagnostics, fprintf(stderr) lines,
        // crash messages, etc.) so we can diagnose bridge deaths without needing logcat.
        File logsDir = com.winlator.cmod.runtime.system.LogManager.getLogsDir(context);
        outputLogFile = new File(logsDir, "wayland-bridge-output.log");

        // Output size for the bridge's Wayland output. This MUST be the guest's desktop size
        // (the container's screenSize, which is also what the guest is launched with as
        // `wine explorer /desktop=shell,WxH`), NOT the Android panel size. The bridge uses it
        // for xdg_toplevel configure and for mapping pointer coordinates, and Wine sizes its
        // windows from the advertised output - so handing it 3120x1440 while the guest desktop
        // is 1280x720 makes the guest lay out against a screen that does not exist.
        int bridgeWidth = environment != null ? environment.getScreenWidth() : 0;
        int bridgeHeight = environment != null ? environment.getScreenHeight() : 0;
        if (bridgeWidth <= 0 || bridgeHeight <= 0) {
            // No size published: fall back to the panel, which at least produces a usable
            // output rather than a zero-sized one.
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            ((android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay().getRealMetrics(metrics);
            bridgeWidth = metrics.widthPixels > 0 ? metrics.widthPixels : 1280;
            bridgeHeight = metrics.heightPixels > 0 ? metrics.heightPixels : 720;
            Log.w(TAG, "guest screen size unavailable; falling back to panel "
                    + bridgeWidth + "x" + bridgeHeight);
        }
        Log.i(TAG, "bridge Wayland output size = " + bridgeWidth + "x" + bridgeHeight);

        List<String> cmd = new ArrayList<>();
        cmd.add(bridgeBin.getAbsolutePath());
        cmd.add("waylandie.display.bridge.v1");
        cmd.add("1");
        cmd.add(socketNameFile.getAbsolutePath());
        cmd.add("15000");
        cmd.add("0");
        cmd.add("0");
        cmd.add(String.valueOf(bridgeWidth));
        cmd.add(String.valueOf(bridgeHeight));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(rootDir);
        pb.redirectErrorStream(true);

        Map<String, String> envVars = pb.environment();
        envVars.clear();
        envVars.put("WAYLAND_DISPLAY", "wayland-0");
        envVars.put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());
        envVars.put("HOME", new File(rootDir, "home/xuser").getAbsolutePath());
        envVars.put("TMPDIR", new File(rootDir, "usr/tmp").getAbsolutePath());
        envVars.put("PATH", "/system/bin:/system/lib64");

        try {
            bridgeProcess = pb.start();
            Log.i(TAG, "Bridge process started");

            // Thread 1: Capture bridge stdout/stderr to BOTH logcat AND a file.
            // This ensures we have the bridge's output even if logcat isn't captured.
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(bridgeProcess.getInputStream()));
                     OutputStreamWriter osw = new OutputStreamWriter(
                             new FileOutputStream(outputLogFile, true))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.i("WayLandIE/Bridge", line);
                        osw.write("[" + new java.util.Date() + "] " + line + "\n");
                        osw.flush();
                    }
                } catch (IOException e) {
                    if (!stopped) {
                        Log.w(TAG, "Bridge output stream closed: " + e.getMessage());
                    }
                }
            }, "wl-bridge-output").start();

            // Wait for bridge to create the socket
            Thread.sleep(3000);

            boolean socketExists = new File(runtimeDir, "wayland-0").exists();
            boolean socketNameExists = socketNameFile.exists();
            Log.i(TAG, "After 3s wait: wayland-0 socket exists=" + socketExists
                    + " socket-name.txt exists=" + socketNameExists);

            // Check if process is still alive
            boolean alive = bridgeProcess.isAlive();
            Log.i(TAG, "Bridge process alive: " + alive);

            if (socketNameExists) {
                String socketName = new String(java.nio.file.Files.readAllBytes(socketNameFile.toPath())).trim();
                Log.i(TAG, "Wayland socket name: " + socketName);
            }

            if (!alive) {
                Log.e(TAG, "Bridge process died immediately! Exit code: " + bridgeProcess.exitValue());
                writeDiagnostic(context, "BRIDGE_DIED: exitCode=" + bridgeProcess.exitValue()
                        + " socketExists=" + socketExists
                        + " socketNameExists=" + socketNameExists);
            } else if (!socketExists) {
                Log.e(TAG, "Bridge process alive but wayland-0 socket NOT created!");
                writeDiagnostic(context, "BRIDGE_ALIVE_NO_SOCKET: socketExists=" + socketExists
                        + " socketNameExists=" + socketNameExists);
            } else {
                writeDiagnostic(context, "BRIDGE_OK: socketExists=true processAlive=" + alive);
            }

            // Thread 2: Death watcher — blocks until the bridge process exits,
            // then writes a diagnostic with the exit code and lifetime.
            // This is CRITICAL: if the bridge dies before wine, wine will crash
            // when it tries to use the dead Wayland compositor socket. Without
            // this watcher, we'd have no way to know the bridge died first.
            final long startTime = System.currentTimeMillis();
            new Thread(() -> {
                try {
                    int exitCode = bridgeProcess.waitFor();
                    long lifetimeMs = System.currentTimeMillis() - startTime;
                    Log.e(TAG, "BRIDGE PROCESS DIED: exitCode=" + exitCode
                            + " lifetimeMs=" + lifetimeMs
                            + " stopped=" + stopped);
                    writeDiagnostic(env.getContext(),
                            "BRIDGE_PROCESS_EXITED: exitCode=" + exitCode
                            + " lifetimeMs=" + lifetimeMs
                            + " stoppedByUs=" + stopped
                            + " timestamp=" + new java.util.Date());
                } catch (InterruptedException e) {
                    // Thread was interrupted during stop() — normal shutdown
                }
            }, "wl-bridge-death-watcher").start();

        } catch (Exception e) {
            Log.e(TAG, "Failed to start bridge", e);
            writeDiagnostic(context, "BRIDGE_EXCEPTION: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private void writeDiagnostic(Context ctx, String message) {
        try {
            File logsDir = com.winlator.cmod.runtime.system.LogManager.getLogsDir(ctx);
            File diagFile = new File(logsDir, "wayland-bridge-startup.log");
            FileWriter fw = new FileWriter(diagFile, true);
            fw.write("[" + new java.util.Date() + "] " + message + "\n");
            fw.close();
        } catch (Exception e) {
            Log.w(TAG, "Failed to write diagnostic: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        stopped = true;
        if (bridgeProcess != null) {
            writeDiagnostic(environment.getContext(),
                    "BRIDGE_STOP_CALLED: processAlive=" + bridgeProcess.isAlive()
                    + " timestamp=" + new java.util.Date());
            bridgeProcess.destroy();
            bridgeProcess = null;
        }
        if (socketNameFile != null) {
            socketNameFile.delete();
        }
    }
}
