package com.winlator.cmod.runtime.display.wayland;

import android.util.Log;
import android.view.Surface;

/**
 * In-process Wayland compositor (ported from Bannerlator).
 * Loaded via System.loadLibrary("waylandie_comp") in WaylandBridgeServer.
 */
public class WaylandCompositor {
    private static final String TAG = "WaylandCompositor";

    public static native void nativeStartWithSurface(
        Surface surface, String xdgRuntimeDir,
        String driverPath, String libraryName, String nativeLibDir);
    public static native void nativeSetSurface(Surface surface);
    public static native void nativeSendPointer(int action, int x, int y);
    public static native void nativeSendKey(int evdev, int state);

    public static void onFirstFramePresented() {
        Log.i(TAG, "First Wayland frame presented!");
    }

    public static void startWithSurface(Surface surface, String xdgRuntimeDir,
                                         String driverPath, String libraryName,
                                         String nativeLibDir) {
        try {
            nativeStartWithSurface(surface, xdgRuntimeDir, driverPath, libraryName, nativeLibDir);
            Log.i(TAG, "Compositor started");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "native lib not loaded", e);
        }
    }

    public static void stop() {
        try {
            nativeSetSurface(null);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    public static void sendPointer(int action, int x, int y) {
        try { nativeSendPointer(action, x, y); } catch (UnsatisfiedLinkError ignored) {}
    }

    public static void sendKey(int evdev, int state) {
        try { nativeSendKey(evdev, state); } catch (UnsatisfiedLinkError ignored) {}
    }
}
