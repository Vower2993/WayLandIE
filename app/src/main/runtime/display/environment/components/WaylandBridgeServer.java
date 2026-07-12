package com.winlator.cmod.runtime.display.environment.components;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import com.winlator.cmod.runtime.display.ui.XServerSurfaceView;
import java.io.*;
import java.nio.ByteBuffer;

/** Receives dmabuf frames from the bridge and presents via SurfaceControl. */
public class WaylandBridgeServer {
    private static final String TAG = "WaylandBridgeServer";
    private static final String SOCKET_NAME = "waylandie.display.bridge.v1";

    private LocalServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running = false;
    private SurfaceControl presentLayer;
    private SurfaceView hostView;
    private Context context;
    private int width = 1920;
    private int height = 1080;
    private int frameIndex = 0;
    private Runnable preloaderDismissCallback = null;

    // Native methods — implemented in waylandie_display_native.c
    private static native String nativePresentAhbVkDmaBufFrame(
            SurfaceControl surfaceControl,
            int dmabufFd,
            int sourceWidth, int sourceHeight,
            long drmFormat, long modifier, int planes,
            long stride0, long offset0, long size,
            int targetWidth, int targetHeight,
            long frameIndex,
            String tmpDir, String hookLibDir,
            String driverDir, String driverName);

    // Set the ANativeWindow env var for winewayland.drv's Vulkan surface creation
    public static native void nativeSetAnativeWindow(android.view.Surface surface);

    static {
        try {
            System.loadLibrary("waylandie_display_native");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load waylandie_display_native", e);
        }
    }

    public void setPreloaderDismissCallback(Runnable callback) {
        this.preloaderDismissCallback = callback;
    }

    public void start(SurfaceView view) {
        start(view, null);
    }

    public void start(SurfaceView view, Context ctx) {
        this.hostView = view;
        this.context = ctx != null ? ctx.getApplicationContext() : null;
        running = true;
        // Release stale presentLayer from previous session — its parent
        // SurfaceControl may have been destroyed, making it orphaned.
        if (presentLayer != null) {
            try { presentLayer.release(); } catch (Exception ignored) {}
            presentLayer = null;
        }
        frameIndex = 0;
        try {
            if (serverSocket != null) {
                try { serverSocket.close(); } catch (IOException ignored) {}
                serverSocket = null;
            }
            serverSocket = new LocalServerSocket(SOCKET_NAME);
            Log.i(TAG, "Listening on abstract socket: " + SOCKET_NAME);
        } catch (IOException e) {
            Log.e(TAG, "Failed to create server socket", e);
            return;
        }
        acceptThread = new Thread(this::acceptLoop, "wl-bridge-server");
        acceptThread.start();
    }

    public void stop() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }
        if (presentLayer != null) {
            try {
                new SurfaceControl.Transaction()
                    .setVisibility(presentLayer, false).apply();
            } catch (Exception ignored) {}
            presentLayer.release();
            presentLayer = null;
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                LocalSocket client = serverSocket.accept();
                Log.i(TAG, "Bridge client connected");
                handleClient(client);
            } catch (IOException e) {
                if (running) Log.w(TAG, "Accept error: " + e.getMessage());
            }
        }
    }

    private void handleClient(LocalSocket client) {
        try {
            // Use the socket's FileDescriptor for ancillary data (SCM_RIGHTS)
            java.io.FileDescriptor fd = client.getFileDescriptor();
            InputStream is = client.getInputStream();
            OutputStream os = client.getOutputStream();

            while (running) {
                // Read command line
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                int b;
                while ((b = is.read()) >= 0) {
                    if (b == '\n') break;
                    baos.write(b);
                }
                if (b < 0) break;

                String command = baos.toString().trim();
                if (command.isEmpty()) continue;

                // Check for ancillary data (dmabuf fd sent via SCM_RIGHTS)
                // LocalSocket ancillary data is available via getAncillaryFileDescriptors
                java.io.FileDescriptor[] ancillary = client.getAncillaryFileDescriptors();
                int dmabufFd = -1;
                // CRITICAL: do NOT use try-with-resources for the pfd here.
                // ParcelFileDescriptor.dup() creates a pfd that OWNS the
                // duplicated fd. The OLD code used try-with-resources, which
                // called pfd.close() at block exit — BEFORE handleCommand
                // used the fd. This left dmabufFd as a dangling integer,
                // causing EBADF (errno 9) in the native fstat()/dup() calls.
                //
                // Instead, we keep the pfd open through handleCommand, then
                // close it AFTER the native present returns. The native code
                // dups the fd internally if it needs to keep it, so closing
                // the pfd here after the call is safe and prevents fd leaks.
                android.os.ParcelFileDescriptor pfd = null;
                if (ancillary != null && ancillary.length > 0) {
                    try {
                        pfd = android.os.ParcelFileDescriptor.dup(ancillary[0]);
                        dmabufFd = pfd.getFd();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to get ancillary fd", e);
                        if (pfd != null) {
                            try { pfd.close(); } catch (Exception ignored) {}
                            pfd = null;
                        }
                    }
                    Log.i(TAG, "Received dmabuf fd=" + dmabufFd);
                }

                String response = handleCommand(command, dmabufFd);
                // Close the pfd NOW — after handleCommand returned. The native
                // present code has already dup'd the fd if it needs to keep it.
                if (pfd != null) {
                    try { pfd.close(); } catch (Exception ignored) {}
                }
                os.write((response + "\n").getBytes());
                os.flush();
            }
        } catch (IOException e) {
            Log.w(TAG, "Client error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private String handleCommand(String command, int dmabufFd) {
        if (command.startsWith("waylandie-bridge hello") || command.startsWith("hello")) {
            return "waylandie-bridge hello-ack version=1 features=dmabuf-present";
        }
        if (command.startsWith("waylandie-bridge ping") || command.startsWith("ping")) {
            return "waylandie-bridge pong version=1";
        }
        if (command.startsWith("waylandie-bridge caps") || command.startsWith("caps")) {
            return "waylandie-bridge caps version=1 features=dmabuf-present,fdtest";
        }
        if (command.contains("dmabuf-present")) {
            return handleDmaBufPresent(command, dmabufFd);
        }
        return "waylandie-bridge unknown-command";
    }

    private String handleDmaBufPresent(String command, int dmabufFd) {
        try {
            int srcWidth = parseIntField(command, "width=");
            int srcHeight = parseIntField(command, "height=");
            long format = parseLongField(command, "format=");
            long modifier = parseLongField(command, "modifier=");
            int stride0 = parseIntField(command, "stride0=");
            long offset0 = parseLongField(command, "offset0=");
            long size = parseLongField(command, "size=");
            String driverName = parseStringField(command, "driver=");

            Log.i(TAG, "dmabuf-present: " + srcWidth + "x" + srcHeight +
                    " fd=" + dmabufFd + " format=0x" + Long.toHexString(format) +
                    " stride=" + stride0 + " size=" + size);

            if (dmabufFd < 0) {
                return "waylandie-bridge dmabuf-present status=fail reason=no-fd";
            }

            // Ensure presentLayer exists
            ensurePresentLayer(srcWidth, srcHeight);
            if (presentLayer == null) {
                return "waylandie-bridge dmabuf-present status=fail reason=no-surfacecontrol";
            }

            // Get paths for native present
            // Use app-private dirs that always exist + are writable.
            String pkgDataDir = (context != null)
                    ? context.getFilesDir().getAbsolutePath()
                    : "/data/user/0/com.winnative.cmod/files";
            String tmpDir = context != null
                    ? context.getCacheDir().getAbsolutePath()
                    : pkgDataDir + "/cache";
            String hookLibDir = "/system/lib64";
            // libadrenotools.so is NOT in /system/lib64 — it's in the app's native lib dir.
            // The native present code dlopens libadrenotools.so from hookLibDir.
            if (context != null) {
                String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
                if (nativeLibDir != null && !nativeLibDir.isEmpty()) {
                    hookLibDir = nativeLibDir;
                }
            }

            // Find the Turnip driver for AHB→Vulkan import.
            // The bridge's native present code needs a Vulkan driver to import
            // dmabuf fds as VkImages via vkGetMemoryFdPropertiesKHR. We look
            // for the Turnip driver in the adrenotools content directory.
            String driverDir = pkgDataDir + "/adrenotools-driver";
            String effectiveDriverName = driverName;
            if (effectiveDriverName == null || effectiveDriverName.isEmpty()) {
                // Probe for the Turnip driver in contents/adrenotools/*/
                File adrenotoolsDir = new File(pkgDataDir, "contents/adrenotools");
                if (adrenotoolsDir.isDirectory()) {
                    File[] driverDirs = adrenotoolsDir.listFiles();
                    if (driverDirs != null) {
                        for (File d : driverDirs) {
                            File freedreno = new File(d, "libvulkan_freedreno.so");
                            if (freedreno.exists() && freedreno.length() > 1000) {
                                driverDir = d.getAbsolutePath();
                                effectiveDriverName = "libvulkan_freedreno.so";
                                Log.i(TAG, "dmabuf-present: found Turnip driver at " + freedreno.getAbsolutePath());
                                break;
                            }
                        }
                    }
                }
                if (effectiveDriverName == null || effectiveDriverName.isEmpty()) {
                    // Fallback: copy the Turnip driver to the expected location
                    effectiveDriverName = "vulkan.waylandie.a8xx.so";
                    Log.w(TAG, "dmabuf-present: Turnip driver not found, using default: " + effectiveDriverName);
                }
            }

            // Present via ASurfaceTransaction — Turnip swapchain doesn't reach SurfaceFlinger.
            String result = nativePresentAhbVkDmaBufFrame(
                    presentLayer,
                    dmabufFd,
                    srcWidth, srcHeight,
                    format, modifier, 1,
                    stride0, offset0, size,
                    width, height,
                    frameIndex,
                    tmpDir, hookLibDir,
                    driverDir, effectiveDriverName);
            frameIndex++;

            Log.i(TAG, "Present result: " + result + " frame=" + (frameIndex - 1) +
                    " source=" + srcWidth + "x" + srcHeight);

            // Dismiss the preloader dialog on the first successful frame.
            if (frameIndex == 1) {
                Log.i(TAG, "First frame presented — dismissing preloader");
                if (preloaderDismissCallback != null) {
                    preloaderDismissCallback.run();
                }
            }
            return "waylandie-bridge dmabuf-present status=pass";
        } catch (Exception e) {
            Log.e(TAG, "dmabuf-present error", e);
            return "waylandie-bridge dmabuf-present status=fail reason=" + e.getMessage();
        }
    }

    private void ensurePresentLayer(int w, int h) {
        if (presentLayer != null) return;
        if (hostView == null || hostView.getSurfaceControl() == null) {
            Log.w(TAG, "Cannot create presentLayer — hostView SurfaceControl is null");
            return;
        }
        int layerW = hostView.getWidth();
        int layerH = hostView.getHeight();
        if (layerW <= 0 || layerH <= 0) {
            layerW = w;
            layerH = h;
        }
        try {
            // Child of SurfaceView's SurfaceControl. The VulkanRenderer runs
            // in CONTINUOUS mode to keep the parent active, so the child
            // presentLayer composites on top.
            presentLayer = new SurfaceControl.Builder()
                .setName("waylandie-present")
                .setParent(hostView.getSurfaceControl())
                .setBufferSize(layerW, layerH)
                .build();
            width = layerW;
            height = layerH;
            new SurfaceControl.Transaction()
                .setLayer(presentLayer, 10)
                .setVisibility(presentLayer, true)
                .setPosition(presentLayer, 0, 0)
                .apply();
            Log.i(TAG, "Created presentLayer: " + layerW + "x" + layerH + " (source=" + w + "x" + h + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create presentLayer", e);
        }
    }

    private static int parseIntField(String s, String key) {
        int idx = s.indexOf(key);
        if (idx < 0) return 0;
        int start = idx + key.length();
        int end = start;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '-')) end++;
        try { return Integer.parseInt(s.substring(start, end)); } catch (Exception e) { return 0; }
    }

    private static long parseLongField(String s, String key) {
        int idx = s.indexOf(key);
        if (idx < 0) return 0;
        int start = idx + key.length();
        int end = start;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '-' ||
                s.charAt(end) == 'x' || (s.charAt(end) >= 'a' && s.charAt(end) <= 'f') ||
                (s.charAt(end) >= 'A' && s.charAt(end) <= 'F'))) end++;
        String num = s.substring(start, end);
        try {
            if (num.startsWith("0x")) return Long.parseUnsignedLong(num.substring(2), 16);
            return Long.parseLong(num);
        } catch (Exception e) { return 0; }
    }

    private static String parseStringField(String s, String key) {
        int idx = s.indexOf(key);
        if (idx < 0) return "";
        int start = idx + key.length();
        int end = start;
        while (end < s.length() && s.charAt(end) != ' ' && s.charAt(end) != '\n') end++;
        return s.substring(start, end);
    }
}
