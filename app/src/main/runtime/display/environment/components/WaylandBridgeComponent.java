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
            Log.i(TAG, "Bridge translator started");

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
    }
}
