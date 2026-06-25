package com.winlator.cmod.runtime.display.environment.components;

import com.winlator.cmod.runtime.display.environment.EnvironmentComponent;
import com.winlator.cmod.runtime.display.environment.ImageFs;

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
 * The bridge is a bionic-compiled native binary (libwaylandie_bridge.so) that
 * runs as a separate process, creating the wayland-0 socket for Wine to connect.
 */
public class WaylandBridgeComponent extends EnvironmentComponent {
    private static final String TAG = "WaylandBridgeComponent";

    private Process bridgeProcess;
    private File socketNameFile;

    @Override
    public void start() {
        Context context = getEnvironment().getContext();
        ImageFs imageFs = getEnvironment().getImageFs();
        File rootDir = imageFs.getRootDir();
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;

        File bridgeBin = new File(nativeLibDir, "libwaylandie_bridge.so");
        if (!bridgeBin.exists()) {
            Log.e(TAG, "Bridge binary not found at " + bridgeBin);
            return;
        }
        bridgeBin.setExecutable(true, false);

        File runtimeDir = new File(new File(rootDir, "usr/tmp"), "runtime");
        if (!runtimeDir.exists()) runtimeDir.mkdirs();
        socketNameFile = new File(runtimeDir, "socket-name.txt");

        // Clean up stale sockets
        new File(runtimeDir, "wayland-0").delete();
        new File(runtimeDir, "wayland-0.lock").delete();
        socketNameFile.delete();

        List<String> cmd = new ArrayList<>();
        cmd.add(bridgeBin.getAbsolutePath());
        cmd.add("waylandie.display.bridge.v1");  // Android bridge socket
        cmd.add("1");                              // target_commits
        cmd.add(socketNameFile.getAbsolutePath()); // socket file
        cmd.add("15000");                          // timeout_ms
        cmd.add("0");                              // clear_ahb_outside
        cmd.add("0");                              // accept_client_complete
        cmd.add("2992");                           // output_width
        cmd.add("1440");                           // output_height

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(rootDir);
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        env.clear();
        env.put("WAYLAND_DISPLAY", "wayland-0");
        env.put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());
        env.put("HOME", new File(rootDir, "home/xuser").getAbsolutePath());
        env.put("TMPDIR", new File(rootDir, "usr/tmp").getAbsolutePath());
        env.put("PATH", "/system/bin:/system/lib64");

        try {
            bridgeProcess = pb.start();
            Log.i(TAG, "Bridge translator started (pid=" + bridgeProcess.pid() + ")");

            // Capture bridge output
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

            // Wait for socket
            Thread.sleep(2000);
            if (socketNameFile.exists()) {
                String socketName = new String(java.nio.file.Files.readAllBytes(socketNameFile.toPath())).trim();
                Log.i(TAG, "Wayland socket: " + socketName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start bridge", e);
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
        // Clean up sockets
        if (getEnvironment() != null) {
            File runtimeDir = new File(new File(getEnvironment().getImageFs().getRootDir(), "usr/tmp"), "runtime");
            new File(runtimeDir, "wayland-0").delete();
            new File(runtimeDir, "wayland-0.lock").delete();
        }
    }

    @Override
    public boolean isReady() {
        return bridgeProcess != null && bridgeProcess.isAlive();
    }
}
