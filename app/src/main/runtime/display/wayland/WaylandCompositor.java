package com.winlator.cmod.runtime.display.wayland;

import android.util.Log;
import android.view.Surface;
import java.io.File;

/**
 * In-process Wayland compositor — ported from Bannerlator's proven implementation.
 *
 * This replaces the old multi-process bridge (WaylandBridgeComponent +
 * WaylandBridgeServer). Instead of running the compositor as a separate
 * subprocess and passing dmabuf fds over Unix sockets, this runs the
 * compositor IN-PROCESS as a shared library (libwaylandie_comp.so).
 *
 * Architecture:
 *   Wine winewayland.drv → wl_surface.commit (attaches dmabuf)
 *     → compositor.c (in-process) receives the buffer
 *       → vk_present.c: import dmabuf → VkImage → blit → present to SurfaceView
 *
 * No IPC, no Unix socket for buffer transfer, no SCM_RIGHTS.
 * The dmabuf fd is passed as a function argument.
 *
 * JNI methods (implemented in waylandcomp/src/waylandcomp_jni.c):
 *   nativeStartWithSurface(surface, xdgRuntimeDir, driverPath, libraryName, nativeLibDir)
 *   nativeSendPointer(action, x, y)
 *   nativeSendKey(evdev, state)
 *   nativeSetSurface(surface)
 */
public class WaylandCompositor {
    private static final String TAG = "WaylandCompositor";
    private static boolean sLoaded = false;

    static {
        try {
            System.loadLibrary("waylandie_comp");
            sLoaded = true;
            Log.i(TAG, "libwaylandie_comp.so loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load libwaylandie_comp.so", e);
        }
    }

    /**
     * Start the compositor with a Surface to render to.
     *
     * @param surface The Android Surface to present to (from SurfaceView)
     * @param xdgRuntimeDir Directory for Wayland socket (e.g., files/.wayland-rt/)
     * @param driverPath Path to the Turnip driver directory
     * @param libraryName The Turnip .so filename (e.g., "libvulkan_freedreno.so")
     * @param nativeLibDir The app's nativeLibraryDir (for adrenotools)
     */
    public static void startWithSurface(Surface surface, String xdgRuntimeDir,
                                         String driverPath, String libraryName,
                                         String nativeLibDir) {
        if (!sLoaded) {
            Log.e(TAG, "Cannot start — native library not loaded");
            return;
        }

        // Ensure the runtime directory exists and is writable
        File rtDir = new File(xdgRuntimeDir);
        if (!rtDir.exists()) {
            rtDir.mkdirs();
        }
        // Set permissions (Wayland requires 0700)
        rtDir.setReadable(true, true);
        rtDir.setWritable(true, true);
        rtDir.setExecutable(true, true);

        // Clean up stale sockets
        new File(xdgRuntimeDir + "/wayland-0").delete();
        new File(xdgRuntimeDir + "/wayland-0.lock").delete();

        Log.i(TAG, "Starting compositor: xdgRuntimeDir=" + xdgRuntimeDir
            + " driverPath=" + driverPath + " libraryName=" + libraryName);

        nativeStartWithSurface(surface, xdgRuntimeDir, driverPath, libraryName, nativeLibDir);
    }

    /**
     * Stop the compositor (pass null surface to tear down).
     */
    public static void stop() {
        if (!sLoaded) return;
        nativeSetSurface(null);
        Log.i(TAG, "Compositor stopped");
    }

    /**
     * Send a pointer event (touch → mouse).
     * @param action 0=down, 1=move, 2=up
     * @param x X in output space (0..1919)
     * @param y Y in output space (0..1079)
     */
    public static void sendPointer(int action, int x, int y) {
        if (sLoaded) nativeSendPointer(action, x, y);
    }

    /**
     * Send a keyboard event.
     * @param evdev Linux evdev keycode (e.g., KEY_A=30)
     * @param state 1=pressed, 0=released
     */
    public static void sendKey(int evdev, int state) {
        if (sLoaded) nativeSendKey(evdev, state);
    }

    /**
     * Called from native code when the first client frame is presented.
     * Override this to dismiss loading overlays, etc.
     */
    public static void onFirstFramePresented() {
        Log.i(TAG, "First Wayland frame presented!");
    }

    // --- Native methods ---
    private static native void nativeStartWithSurface(
        Surface surface, String xdgRuntimeDir,
        String driverPath, String libraryName, String nativeLibDir);
    private static native void nativeSetSurface(Surface surface);
    private static native void nativeSendPointer(int action, int x, int y);
    private static native void nativeSendKey(int evdev, int state);
}
