package com.winlator.cmod.runtime.display.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.winlator.cmod.runtime.display.renderer.RenderCallback;
import com.winlator.cmod.runtime.display.renderer.VulkanRenderer;
import com.winlator.cmod.runtime.display.xserver.XServer;
import java.util.ArrayDeque;
import java.util.Deque;

/** SurfaceView that drives a VulkanRenderer on a dedicated render thread. */
@SuppressLint("ViewConstructor")
public class XServerSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
    public static final int RENDERMODE_WHEN_DIRTY  = 0;
    public static final int RENDERMODE_CONTINUOUSLY = 1;
    private static final long TRANSIENT_FRAME_INTERVAL_NS = 1_000_000_000L / 120L;

    private final VulkanRenderer renderer;

    private final Object renderLock = new Object();
    private final Deque<Runnable> eventQueue = new ArrayDeque<>();
    private Thread renderThread;
    private volatile boolean running;
    private volatile boolean renderRequested;
    private volatile boolean transientRenderRequested;
    private volatile boolean paused;
    private volatile boolean surfaceReady;
    private volatile long transientRenderUntilNs;
    private long nextContinuousFrameNs;
    private int renderMode = RENDERMODE_WHEN_DIRTY;

    private volatile int width;
    private volatile int height;
    private volatile boolean waylandMode;
    // Wayland dmabuf frame (set by bridge, consumed by render thread)
    private volatile int wlDmabufFd = -1;
    private volatile int wlWidth = 0;
    private volatile int wlHeight = 0;
    private volatile int wlStride = 0;
    private volatile int wlDrmFormat = 0;
    private volatile boolean wlFrameAvailable = false;
    // ANativeWindow pointer (set by surfaceCreated, read by XServerDisplayActivity
    // before Wine launches). In Wayland mode, this is passed to Wine via envVars
    // so DXVK can create a Vulkan surface bound to this SurfaceView.
    private volatile String anativeWindowPointer = null;
    private final Object surfaceCreatedLock = new Object();

    public XServerSurfaceView(Context context, XServer xServer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        renderer = new VulkanRenderer(this, xServer);
        getHolder().addCallback(this);
    }

    public void setWaylandMode(boolean wayland) {
        this.waylandMode = wayland;
        // NOTE: do NOT call setZOrderOnTop(true) — the working 41d5ea6 build
        // does not use it. setZOrderOnTop puts the SurfaceView's surface above
        // all other windows in the container, which prevents the child
        // presentLayer (parented to this SurfaceView's SurfaceControl) from
        // compositing correctly above TouchpadView/InputControlsView overlays.
        // The default Z-ordering (below overlays) is what 41d5ea6 uses.
    }

    /** Dup the dmabuf fd via native dup() to avoid fdsan ownership conflicts. */
    public void setWaylandDmaBufFrame(int fd, int w, int h, int stride, int drmFormat) {
        if (wlDmabufFd >= 0) { closeFd(wlDmabufFd); wlDmabufFd = -1; }
        int dupFd = nativeDupFd(fd);
        if (dupFd < 0) {
            android.util.Log.e("XServerSurfaceView", "nativeDupFd failed for fd=" + fd);
        }
        wlDmabufFd = dupFd;
        wlWidth = w;
        wlHeight = h;
        wlStride = stride;
        wlDrmFormat = drmFormat;
        wlFrameAvailable = true;
        requestRender();
    }

    /** Native dup() syscall. Returns new fd or -1 on error. */
    private static native int nativeDupFd(int fd);

    private static void closeFd(int fd) {
        if (fd < 0) return;
        nativeCloseFd(fd);
    }

    /** Native close() syscall. */
    private static native void nativeCloseFd(int fd);

    public VulkanRenderer getRenderer() {
        return renderer;
    }

    public void queueEvent(Runnable r) {
        if (r == null) return;
        synchronized (renderLock) {
            eventQueue.add(r);
            renderRequested = true;
            renderLock.notifyAll();
        }
    }

    public void requestRender() {
        synchronized (renderLock) {
            renderRequested = true;
            renderLock.notifyAll();
        }
    }

    public void requestTransientRender(long durationMs) {
        long untilNs = System.nanoTime() + Math.max(1L, durationMs) * 1_000_000L;
        synchronized (renderLock) {
            if (untilNs > transientRenderUntilNs) transientRenderUntilNs = untilNs;
            transientRenderRequested = true;
            renderLock.notifyAll();
        }
    }

    public void setRenderMode(int mode) {
        if (mode != RENDERMODE_WHEN_DIRTY && mode != RENDERMODE_CONTINUOUSLY) return;
        synchronized (renderLock) {
            renderMode = mode;
            if (mode == RENDERMODE_CONTINUOUSLY) {
                renderRequested = true;
                renderLock.notifyAll();
            }
        }
    }

    public int getRenderMode() {
        return renderMode;
    }

    public void onResume() {
        synchronized (renderLock) {
            paused = false;
            renderRequested = true;
            renderLock.notifyAll();
        }
    }

    public void onPause() {
        synchronized (renderLock) {
            paused = true;
            renderLock.notifyAll();
        }
    }

    // --- SurfaceHolder.Callback ----------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        synchronized (renderLock) {
            surfaceReady = false;
            width = 0;
            height = 0;
        }
        // In Wayland mode, the game renders directly to this SurfaceView via
        // vkCreateXlibSurfaceKHR(ANativeWindow). The VkRenderer is NOT used —
        // the game's DXVK swapchain IS the buffer source for the parent SurfaceView.
        // This gives the parent a buffer so SurfaceFlinger composites child layers
        // (presentLayer for desktop overlay) on top.
        if (!waylandMode) {
            renderer.attachSurface(holder.getSurface());
        }
        // Get the ANativeWindow pointer and store it for XServerDisplayActivity
        // to pick up before launching Wine.
        try {
            String ptr = com.winlator.cmod.runtime.display.environment.components.WaylandBridgeServer
                    .nativeSetAnativeWindow(holder.getSurface());
            anativeWindowPointer = ptr;
            android.util.Log.i("XServerSurfaceView", "surfaceCreated: ANativeWindow=" + ptr);
            // Option B: Also write the pointer to a file so winewayland.drv can
            // read it at vkCreateInstance time (which happens ~20-30s after Wine
            // starts). This is a fallback for when the env var isn't set.
            if (ptr != null && !"0".equals(ptr)) {
                try {
                    java.io.File dataDir = getContext().getFilesDir();
                    java.io.File anwFile = new java.io.File(dataDir, "anative_window_ptr");
                    java.io.FileWriter fw = new java.io.FileWriter(anwFile, false);
                    fw.write(ptr);
                    fw.close();
                    android.util.Log.i("XServerSurfaceView",
                        "Wrote ANativeWindow pointer to " + anwFile.getAbsolutePath());
                } catch (Exception e) {
                    android.util.Log.w("XServerSurfaceView",
                        "Failed to write ANativeWindow file: " + e.getMessage());
                }
            }
        } catch (UnsatisfiedLinkError e) {
            android.util.Log.e("XServerSurfaceView", "nativeSetAnativeWindow failed", e);
            anativeWindowPointer = "0";
        } catch (Throwable t) {
            android.util.Log.e("XServerSurfaceView", "surfaceCreated exception", t);
            anativeWindowPointer = "0";
        }
        // Notify any thread waiting for the surface to be created (Option A: delay Wine launch).
        synchronized (surfaceCreatedLock) {
            surfaceCreatedLock.notifyAll();
        }

        startRenderThreadIfNeeded();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        if (w <= 0 || h <= 0) {
            synchronized (renderLock) {
                surfaceReady = false;
                width = 0;
                height = 0;
                renderLock.notifyAll();
            }
            return;
        }

        if (!waylandMode) renderer.notifySurfaceChanged(w, h);
        synchronized (renderLock) {
            width = w;
            height = h;
            eventQueue.add(() -> renderer.onSurfaceChanged(w, h));
            surfaceReady = true;
            renderRequested = true;
            renderLock.notifyAll();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        synchronized (renderLock) {
            surfaceReady = false;
            width = 0;
            height = 0;
            renderLock.notifyAll();
        }
        // Run the render thread one more iteration so it sees surfaceReady=false and exits.
        stopRenderThread();
        renderer.detachSurface();
    }

    // --- Render thread -------------------------------------------------------

    private void startRenderThreadIfNeeded() {
        if (renderThread != null && renderThread.isAlive()) return;
        if (waylandMode) {
            android.util.Log.i("XServerSurfaceView", "Wayland mode — skipping VulkanRenderer render thread");
            return;
        }
        running = true;
        renderThread = new Thread(this::renderLoop, "VkRenderer");
        renderThread.start();
    }

    private void stopRenderThread() {
        synchronized (renderLock) {
            running = false;
            renderLock.notifyAll();
            renderThread = null;
        }
    }

    private void renderLoop() {
        renderer.onSurfaceCreated();
        if (width > 0 && height > 0) renderer.onSurfaceChanged(width, height);

        while (true) {
            Runnable event = null;
            boolean draw = false;
            synchronized (renderLock) {
                while (true) {
                    if (!running) break;
                    if (paused || !surfaceReady) {
                        nextContinuousFrameNs = 0;
                        try { renderLock.wait(50); } catch (InterruptedException ignore) {}
                        continue;
                    }

                    long now = System.nanoTime();
                    boolean transientActive = transientRenderUntilNs > now;

                    if (!eventQueue.isEmpty()) {
                        event = eventQueue.poll();
                        break;
                    }

                    if (renderRequested) {
                        draw = true;
                        renderRequested = false;
                        transientRenderRequested = false;
                        if (!transientActive) nextContinuousFrameNs = 0;
                        break;
                    }

                    if (renderMode == RENDERMODE_CONTINUOUSLY) {
                        draw = true;
                        transientRenderRequested = false;
                        nextContinuousFrameNs = 0;
                        break;
                    }

                    if (transientRenderRequested) {
                        draw = true;
                        transientRenderRequested = false;
                        nextContinuousFrameNs = now + TRANSIENT_FRAME_INTERVAL_NS;
                        break;
                    }

                    if (transientActive) {
                        if (nextContinuousFrameNs == 0 || now >= nextContinuousFrameNs) {
                            draw = true;
                            nextContinuousFrameNs = now + TRANSIENT_FRAME_INTERVAL_NS;
                            break;
                        }
                        waitNanosLocked(nextContinuousFrameNs - now);
                        continue;
                    }

                    nextContinuousFrameNs = 0;
                    try { renderLock.wait(); } catch (InterruptedException ignore) {}
                }
            }
            if (!running) break;
            if (event != null) {
                try { event.run(); } catch (Throwable ignore) {}
            } else if (draw) {
                if (waylandMode && wlFrameAvailable) {
                    int fd = wlDmabufFd;
                    wlDmabufFd = -1;
                    wlFrameAvailable = false;
                    try {
                        boolean ok = VulkanRenderer.nativePresentDmaBufWayland(
                            renderer.getNativeHandle(),
                            fd, wlWidth, wlHeight, wlStride, wlDrmFormat);
                        if (!ok) {
                            android.util.Log.e("XServerSurfaceView",
                                "nativePresentDmaBufWayland FAILED fd=" + fd +
                                " " + wlWidth + "x" + wlHeight);
                        }
                    } catch (Throwable e) {
                        android.util.Log.e("XServerSurfaceView",
                            "nativePresentDmaBufWayland EXCEPTION", e);
                    }
                    if (fd >= 0) {
                        closeFd(fd);
                    }
                } else {
                    try { renderer.onDrawFrame(); } catch (Throwable ignore) {}
                }
            }
        }
        renderer.onSurfaceDestroyed();
    }

    private void waitNanosLocked(long nanos) {
        if (nanos <= 0) return;
        long millis = nanos / 1_000_000L;
        int extraNanos = (int) (nanos % 1_000_000L);
        try { renderLock.wait(millis, extraNanos); } catch (InterruptedException ignore) {}
    }

    // ---- Convenience accessors used by VulkanRenderer ----------------------

    public int getSurfaceWidth() { return width; }
    public int getSurfaceHeight() { return height; }

    /**
     * Returns the ANativeWindow pointer as a decimal string, or null if
     * surfaceCreated hasn't fired yet.
     */
    public String getAnativeWindowPointer() {
        return anativeWindowPointer;
    }

    /**
     * Blocks the calling thread until surfaceCreated has fired and the
     * ANativeWindow pointer is available. Used by XServerDisplayActivity
     * to delay Wine launch until the Surface is ready.
     *
     * @param timeoutMs max time to wait
     * @return the ANativeWindow pointer string, or "0" on timeout/error
     */
    public String waitForSurfaceCreated(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (surfaceCreatedLock) {
            while (anativeWindowPointer == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    android.util.Log.w("XServerSurfaceView",
                        "waitForSurfaceCreated TIMEOUT after " + timeoutMs + "ms");
                    return "0";
                }
                try {
                    surfaceCreatedLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "0";
                }
            }
        }
        return anativeWindowPointer;
    }
}
