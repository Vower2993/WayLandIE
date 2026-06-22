package io.waylandie.display.runtime.environment;

import android.util.Log;

/**
 * Controls the lifecycle of the in-process minimal X server
 * (libwaylandie_xserver.so) and routes Android input events to it.
 *
 * <p>The X server implements just enough of the X11 core wire protocol
 * to satisfy Wine's {@code winex11.drv}. The root window's framebuffer
 * is an {@link android.hardware.HardwareBuffer AHardwareBuffer}, which
 * is exported as a dmabuf fd to the existing Wayland bridge after every
 * damage event — so the desktop is displayed via the SAME dmabuf path
 * as {@code winewayland.drv} used, but with a much more mature X11
 * driver feeding it.
 *
 * <p>Usage:
 * <pre>
 * XServerController.start(width, height, ":0", "waylandie.display.bridge.v1");
 * // ... WineRunner.execWine(...) launches Wine with DISPLAY=:0 ...
 * XServerController.sendMouse(x, y, 1, true);   // left button down
 * XServerController.sendKey(keycode, true);     // key press
 * XServerController.stop();
 * </pre>
 *
 * <p>Thread-safety: native methods are safe to call from any thread.
 * Input injection is non-blocking — events are queued on each client's
 * output buffer and flushed immediately.
 */
public final class XServerController {

    private static final String TAG = "WayLandIE/XServerController";

    private static volatile boolean running = false;

    private XServerController() {}

    /** Load the native library. */
    public static void ensureLoaded() {
        try {
            System.loadLibrary("waylandie_xserver");
            Log.i(TAG, "libwaylandie_xserver.so loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load libwaylandie_xserver.so: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Start the X server.
     *
     * @param width       framebuffer width (px)
     * @param height      framebuffer height (px)
     * @param display     X11 display string, e.g. ":0"
     * @param bridgeSocket abstract socket name the Wayland bridge is
     *                     listening on (e.g. "waylandie.display.bridge.v1").
     *                     May be null if the bridge isn't up yet — the
     *                     server will run anyway but won't display.
     * @return 0 on success, -1 on failure
     */
    public static synchronized int start(int width, int height,
                                          String display, String bridgeSocket) {
        if (running) {
            Log.w(TAG, "X server already running");
            return 0;
        }
        ensureLoaded();
        Log.i(TAG, "Starting X server: " + width + "x" + height
                + " display=" + display + " bridge=" + bridgeSocket);
        int rc = nativeStart(width, height, display, bridgeSocket);
        if (rc == 0) {
            running = true;
            Log.i(TAG, "X server started successfully");
        } else {
            Log.e(TAG, "X server failed to start (rc=" + rc + ")");
        }
        return rc;
    }

    /** Stop the X server. */
    public static synchronized void stop() {
        if (!running) return;
        Log.i(TAG, "Stopping X server");
        nativeStop();
        running = false;
    }

    /** @return true if the X server is currently running. */
    public static boolean isRunning() {
        return running;
    }

    /**
     * Inject a mouse event.
     *
     * @param x       X coordinate in framebuffer space
     * @param y       Y coordinate in framebuffer space
     * @param button  1=left, 2=middle, 3=right, 0=motion-only
     * @param isDown  true for press, false for release (ignored if button=0)
     */
    public static void sendMouse(int x, int y, int button, boolean isDown) {
        if (!running) return;
        nativeSendMouse(x, y, button, isDown);
    }

    /**
     * Inject a keyboard event.
     *
     * @param keycode X11 keycode (8-255). Android keycodes should be
     *                converted via {@link android.view.KeyEvent} → X11
     *                keycode mapping (typically Android keycode + 8).
     * @param isDown  true for press, false for release
     */
    public static void sendKey(int keycode, boolean isDown) {
        if (!running) return;
        nativeSendKey(keycode, isDown);
    }

    // ----- JNI -----
    private static native int nativeStart(int width, int height,
                                           String display, String bridgeSocket);
    private static native void nativeStop();
    private static native void nativeSendMouse(int x, int y, int button, boolean isDown);
    private static native void nativeSendKey(int keycode, boolean isDown);
}
