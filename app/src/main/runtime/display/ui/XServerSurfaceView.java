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

/**
 * SurfaceView that drives a {@link VulkanRenderer} on a dedicated render thread.
 *
 * <p>This is the Vulkan replacement for the previous {@code GLSurfaceView}-backed
 * {@code XServerView}. It preserves the public API the rest of the codebase relies on:
 * {@link #queueEvent(Runnable)}, {@link #requestRender()}, {@link #setRenderMode(int)},
 * {@link #onResume()}, {@link #onPause()}, {@link #getRenderer()}.
 */
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

    public XServerSurfaceView(Context context, XServer xServer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        renderer = new VulkanRenderer(this, xServer);
        getHolder().addCallback(this);
    }

    public void setWaylandMode(boolean wayland) {
        this.waylandMode = wayland;
        if (wayland) {
            setZOrderMediaOverlay(true);
            getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        }
    }

    /** Called by WaylandBridgeServer when a new dmabuf frame is ready.
     *  The caller will close the original fd after this returns, so we
     *  must dup it to keep it alive for the render thread. */
    public void setWaylandDmaBufFrame(int fd, int w, int h, int stride, int drmFormat) {
        // Close previous fd if not consumed
        if (wlDmabufFd >= 0) {
            try { closeFd(wlDmabufFd); } catch (Exception ignored) {}
            wlDmabufFd = -1;
        }
        // Dup the fd — the caller closes the original after we return
        int dupFd = -1;
        try {
            android.os.ParcelFileDescriptor srcPfd = android.os.ParcelFileDescriptor.adoptFd(fd);
            android.os.ParcelFileDescriptor dupPfd = android.os.ParcelFileDescriptor.dup(srcPfd.getFileDescriptor());
            srcPfd.detachFd(); // don't close the original — caller owns it
            dupFd = dupPfd.detachFd(); // take ownership of the dup'd fd
        } catch (Exception e) {
            android.util.Log.w("XServerSurfaceView", "Failed to dup dmabuf fd: " + e.getMessage());
        }
        wlDmabufFd = dupFd;
        wlWidth = w;
        wlHeight = h;
        wlStride = stride;
        wlDrmFormat = drmFormat;
        wlFrameAvailable = true;
        requestRender();
    }

    private static void closeFd(int fd) {
        if (fd < 0) return;
        try {
            android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor.adoptFd(fd);
            pfd.close();
        } catch (Exception ignored) {}
    }

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
        renderer.attachSurface(holder.getSurface());
        // In Wayland mode, pass the ANativeWindow to winewayland.drv for
        // vkCreateXlibSurfaceKHR (adrenotools wrapper supports xlib_surface)
        try {
            com.winlator.cmod.runtime.display.environment.components.WaylandBridgeServer
                    .nativeSetAnativeWindow(holder.getSurface());
        } catch (UnsatisfiedLinkError e) {
            // waylandie_display_native not loaded — X11 mode, ignore
        }

        // Dummy frame removed — was causing 'stuck at wine starting' on some
        // devices because lockCanvas conflicts with setZOrderMediaOverlay.
        // setZOrderMediaOverlay doesn't require the parent to have content.

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

        renderer.notifySurfaceChanged(w, h);
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
            // In Wayland mode, start the render thread. Instead of rendering
            // X11 scenes, it will present dmabuf frames from the bridge via
            // nativePresentDmaBufWayland (blits dmabuf → swapchain → presents
            // via BLASTBufferQueue which SurfaceFlinger always composites).
            android.util.Log.i("XServerSurfaceView", "Wayland mode — starting render thread for dmabuf→swapchain blit");
            running = true;
            renderMode = RENDERMODE_WHEN_DIRTY;
            renderThread = new Thread(this::renderLoop, "VkRenderer");
            renderThread.start();
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
                    // Wayland mode: blit dmabuf → swapchain → present via BLASTBufferQueue
                    int fd = wlDmabufFd;
                    wlDmabufFd = -1;
                    wlFrameAvailable = false;
                    try {
                        VulkanRenderer.nativePresentDmaBufWayland(
                            renderer.getNativeHandle(),
                            fd, wlWidth, wlHeight, wlStride, wlDrmFormat);
                    } catch (Throwable ignore) {}
                    // Close the dup'd fd after the native code has consumed it
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
}
