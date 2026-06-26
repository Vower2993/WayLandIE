package com.winlator.cmod.runtime.display.environment.components;

import com.winlator.cmod.runtime.display.environment.EnvironmentComponent;
import com.winlator.cmod.runtime.display.environment.ImageFs;
import com.winlator.cmod.runtime.display.environment.XEnvironment;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.IOException;
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

        List<String> cmd = new ArrayList<>();
        cmd.add(bridgeBin.getAbsolutePath());
        cmd.add("waylandie.display.bridge.v1");
        cmd.add("1");
        cmd.add(socketNameFile.getAbsolutePath());
        cmd.add("15000");
        cmd.add("0");
        cmd.add("0");
        cmd.add("2992");
        cmd.add("1440");

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
            Log.i(TAG, "Bridge process started, pid=" + bridgeProcess.pid());

            new Thread(() -> {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(bridgeProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.i("WayLandIE/Bridge", line);
                    }
                } catch (IOException e) {
                    Log.w(TAG, "Bridge output stream closed: " + e.getMessage());
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
        } catch (Exception e) {
            Log.e(TAG, "Failed to start bridge", e);
            writeDiagnostic(context, "BRIDGE_EXCEPTION: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private void writeDiagnostic(Context ctx, String message) {
        try {
            File logsDir = com.winlator.cmod.runtime.system.LogManager.getLogsDir(ctx);
            File diagFile = new File(logsDir, "wayland-bridge-startup.log");
            java.io.FileWriter fw = new java.io.FileWriter(diagFile, true);
            fw.write("[" + new java.util.Date() + "] " + message + "\n");
            fw.close();
        } catch (Exception e) {
            Log.w(TAG, "Failed to write diagnostic: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        if (bridgeProcess != null) {
            bridgeProcess.destroy();
            bridgeProcess = null;
        }
        if (socketNameFile != null) {
            socketNameFile.delete();
        }
    }
}
