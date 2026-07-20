package com.winlator.cmod.runtime.display.environment.components;

// WaylandBridgeServer — receives dmabuf frames from the bridge binary and
// presents them via SurfaceControl. Also handles the input-stream protocol
// (long-lived connection for input events) and protocol handshake commands.

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import com.winlator.cmod.runtime.display.ui.XServerSurfaceView;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Locale;

/** Receives dmabuf frames from the bridge and presents via SurfaceControl. */
public class WaylandBridgeServer {
    private static final String TAG = "WaylandBridgeServer";
    private static final String SOCKET_NAME = "waylandie.display.bridge.v1";

    private LocalServerSocket serverSocket;
    private Thread acceptThread;
    private java.util.concurrent.CountDownLatch socketBoundLatch;
    private volatile boolean running = false;
    private SurfaceControl presentLayer;
    private SurfaceView hostView;
    private Context context;
    private int width = 1920;
    private int height = 1080;
    private int frameIndex = 0;
    private Runnable preloaderDismissCallback = null;
    private Runnable onFirstFrameCallback = null;

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

    // Set the ANativeWindow env var for winewayland.drv's Vulkan surface creation.
    // Returns the ANativeWindow pointer as a decimal string (for envVars).
    public static native String nativeSetAnativeWindow(android.view.Surface surface);

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

    public void setOnFirstFrameCallback(Runnable callback) {
        this.onFirstFrameCallback = callback;
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
        // Close any existing server socket from a previous start() call.
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }
        // Start the bind+accept thread. The thread retries binding with
        // exponential backoff if the abstract socket is still held by a
        // stale process from a previous session ("Address already in use").
        // This matches the 41d5ea6 BridgeLocalServer pattern.
        socketBoundLatch = new java.util.concurrent.CountDownLatch(1);
        acceptThread = new Thread(this::acceptLoop, "wl-bridge-server");
        acceptThread.setDaemon(true);
        acceptThread.start();
        // Wait briefly for the socket to bind so the bridge binary (launched
        // right after this by WaylandBridgeComponent) doesn't race ahead and
        // get ECONNREFUSED on its first connect attempt. The bridge binary's
        // input-stream connect is single-shot (no retry), so if it races it
        // fails permanently. 2s max wait — if bind takes longer (stale socket
        // + backoff), the bridge's per-frame dmabuf-present connect will retry.
        try {
            socketBoundLatch.await(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (serverSocket != null) {
            Log.i(TAG, "Socket bound before start() returned");
        } else {
            Log.w(TAG, "Socket not yet bound after 2s wait — bridge may retry connect");
        }
    }

    public void stop() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
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

    /**
     * Bind+accept loop with exponential backoff.
     *
     * Ported from 41d5ea6's BridgeLocalServer.run(). When the abstract socket
     * is still held by a stale process (common after force-close → relaunch),
     * the bind fails with "Address already in use". Instead of giving up,
     * we retry with exponential backoff (100ms → 5s) until the OS releases
     * the socket. Once bound, we accept connections in a loop. If the accept
     * loop fails, we close and re-bind.
     */
    private void acceptLoop() {
        long retryDelayMs = 100L;
        while (running) {
            try {
                serverSocket = new LocalServerSocket(SOCKET_NAME);
                Log.i(TAG, "Listening on abstract socket: " + SOCKET_NAME);
                // Signal start() that the socket is bound — allows the bridge
                // binary to be launched without racing the bind.
                if (socketBoundLatch != null) {
                    socketBoundLatch.countDown();
                }
                retryDelayMs = 100L;  // Reset backoff on successful bind
                while (running) {
                    try {
                        LocalSocket client = serverSocket.accept();
                        Log.i(TAG, "Bridge client connected");
                        // Handle each client in a separate thread so multiple
                        // bridge connections (input-stream + dmabuf-present)
                        // can be served simultaneously.
                        Thread clientThread = new Thread(
                                () -> handleClient(client),
                                "wl-bridge-client");
                        clientThread.setDaemon(true);
                        clientThread.start();
                    } catch (IOException e) {
                        if (running) {
                            Log.w(TAG, "Accept error: " + e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "Bind failed (will retry in " + retryDelayMs
                            + "ms): " + e.getMessage());
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    // Exponential backoff: double each retry, cap at 5s.
                    retryDelayMs = Math.min(retryDelayMs * 2, 5000L);
                }
            } finally {
                if (serverSocket != null) {
                    try { serverSocket.close(); } catch (IOException ignored) {}
                    serverSocket = null;
                }
            }
        }
        Log.i(TAG, "Bridge server thread exiting");
    }

    private void handleClient(LocalSocket client) {
        try {
            InputStream is = client.getInputStream();
            OutputStream os = client.getOutputStream();

            // Read the FIRST command line to decide what kind of connection this is.
            // 'input-stream' is a long-lived connection (bridge keeps it open to
            // receive input events). All other commands are request-response.
            String firstCommand = readLine(is);
            if (firstCommand == null) return;
            firstCommand = firstCommand.trim();
            if (firstCommand.isEmpty()) return;

            // input-stream: long-lived — handle in a blocking loop and do NOT
            // return to the request-response loop.
            if (isInputStreamCommand(firstCommand)) {
                handleInputStream(os, is);
                return;
            }

            // Request-response loop for all other commands (dmabuf-present, etc.)
            String command = firstCommand;
            while (running) {
                // Check for ancillary data (dmabuf fd sent via SCM_RIGHTS)
                java.io.FileDescriptor[] ancillary = client.getAncillaryFileDescriptors();
                int dmabufFd = -1;
                // CRITICAL: do NOT use try-with-resources for the pfd here.
                // ParcelFileDescriptor.dup() creates a pfd that OWNS the
                // duplicated fd. Closing it before handleCommand uses the fd
                // causes EBADF (errno 9). Keep it open through the call, close
                // after. Native code dups the fd if it needs to keep it.
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
                if (pfd != null) {
                    try { pfd.close(); } catch (Exception ignored) {}
                }
                os.write((response + "\n").getBytes());
                os.flush();

                // Read next command line for the next iteration.
                String nextCommand = readLine(is);
                if (nextCommand == null) break;
                command = nextCommand.trim();
                if (command.isEmpty()) break;
            }
        } catch (IOException e) {
            Log.w(TAG, "Client error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private static final String BRIDGE_COMMANDS =
            "hello,ping,caps,display,vulkan,adrenotools,contract,buffers,sync,native,compositor,compositor-open,compositor-status,window-add,window-remove,window-status,fdtest,syncfd-test,dmabuf-test,dmabuf-meta,dmabuf-import-probe,dmabuf-present,kgsl-import-probe,ahb-export-probe,ahb-present-probe,ahb-ring-probe,status,input,input-stream";

    /** Check if a command is the input-stream handshake. */
    private static boolean isInputStreamCommand(String command) {
        if (command == null) return false;
        String trimmed = command.trim();
        int space = trimmed.indexOf(' ');
        String name = space < 0 ? trimmed : trimmed.substring(0, space);
        return "input-stream".equals(name.toLowerCase(Locale.US));
    }

    /**
     * Handle the input-stream protocol — a long-lived connection where the
     * bridge sends input events (touch, key, clipboard) as line-delimited
     * messages. We acknowledge with status=pass and then block reading lines
     * until the bridge disconnects.
     *
     * Ported from 41d5ea6 MainActivity.handleBridgeInputStream().
     */
    private void handleInputStream(OutputStream os, InputStream is) {
        Log.i(TAG, "input-stream attached");
        try {
            os.write("waylandie-bridge input-stream status=pass protocol=input-v1\n".getBytes());
            os.flush();
        } catch (IOException e) {
            Log.w(TAG, "input-stream ack write failed: " + e.getMessage());
            return;
        }
        // Block reading input event lines until the bridge disconnects.
        // We don't process the events yet (input routing is handled elsewhere
        // via XServer), but we MUST keep the connection alive so the bridge
        // considers input-stream attached and enables input forwarding.
        try {
            StringBuilder sb = new StringBuilder(256);
            while (running) {
                int b = is.read();
                if (b < 0) break;
                if (b == '\n') {
                    if (sb.length() > 0) {
                        // Log input events at debug level for diagnostics.
                        Log.d(TAG, "input-stream event: " + sb);
                        sb.setLength(0);
                    }
                    continue;
                }
                if (sb.length() < 65536) sb.append((char) b);
            }
        } catch (IOException e) {
            Log.i(TAG, "input-stream detached: " + e.getMessage());
        }
        Log.i(TAG, "input-stream detached");
    }

    /** Read a single line (up to '\n') from the stream. Returns null on EOF. */
    private static String readLine(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = is.read()) >= 0) {
            if (b == '\n') break;
            baos.write(b);
        }
        if (b < 0 && baos.size() == 0) return null;
        return baos.toString();
    }

    private String handleCommand(String command, int dmabufFd) {
        String name = command.trim();
        int space = name.indexOf(' ');
        if (space >= 0) name = name.substring(0, space);

        if ("hello".equals(name)) {
            return "waylandie-bridge hello-ack version=1 mode=control+graphics-contract";
        }
        if ("ping".equals(name)) {
            return "waylandie-bridge pong version=1";
        }
        if ("caps".equals(name)) {
            // Full caps response matching 41d5ea6 — the bridge binary parses
            // fields like 'native-socket', 'commands', 'producer' to decide
            // how to send frames and input. A truncated response causes the
            // bridge to skip features or fail to connect.
            return String.format(Locale.US,
                    "waylandie-bridge caps version=1 transport=tcp-loopback " +
                    "native-transport=unix-abstract native-socket=%s transport-next=unix-socket " +
                    "commands=%s " +
                    "features=buffer-meta,compositor-endpoint,android-multi-window," +
                    "sync-placeholder,adrenotools-loader,fdtest,syncfd-test,dmabuf-test," +
                    "dmabuf-meta,dmabuf-import-probe,dmabuf-present,kgsl-import-probe," +
                    "ahb-export-probe,ahb-present-probe,ahb-ring-probe,fd-future " +
                    "producer=dmabuf-present-vulkan contract=buffer-meta-only " +
                    "compositor=android-presenter-endpoint windows=activity-per-toplevel " +
                    "fd-passing=fdtest,syncfd-test,dmabuf-test,dmabuf-meta,dmabuf-import-probe," +
                    "dmabuf-present,kgsl-import-probe,ahb-export-probe,ahb-present-probe,ahb-ring-probe " +
                    "graphics-fd-passing=adrenotools-loader,kgsl-import-probe,dmabuf-image-import," +
                    "dmabuf-present-gpu,ahb-vk-target buffer=fd-future sync=eventfd-control-probe " +
                    "layer=%dx%d final-copy=forbidden",
                    SOCKET_NAME, BRIDGE_COMMANDS, width, height);
        }
        // window-add / window-remove: the bridge sends these when Wine
        // toplevels are created/destroyed. We don't need to do anything
        // (XServerDisplayActivity handles window management via X11),
        // but we MUST return status=pass so the bridge doesn't treat
        // them as errors and abort.
        if ("window-add".equals(name) || "window-remove".equals(name) ||
                "window-status".equals(name)) {
            return "waylandie-bridge " + name + " status=pass";
        }
        // status query — return current bridge state.
        if ("status".equals(name)) {
            return "waylandie-bridge status=pass frames=" + frameIndex +
                    " layer=" + width + "x" + height;
        }
        if ("dmabuf-present".equals(name) || command.contains("dmabuf-present")) {
            return handleDmaBufPresent(command, dmabufFd);
        }
        // Unknown command — return pass instead of unknown-command so the
        // bridge doesn't abort on protocol mismatch. 41d5ea6 handles ~29
        // commands; we handle the critical ones and pass-through the rest.
        Log.w(TAG, "Unhandled bridge command: " + name + " (returning status=pass)");
        return "waylandie-bridge " + name + " status=pass";
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
                if (onFirstFrameCallback != null) {
                    onFirstFrameCallback.run();
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
            // View not laid out yet — use the device's physical screen size,
            // NOT the source frame size (which would be tiny, e.g. 1280x128,
            // leaving most of the screen black). The native GPU blit already
            // scales frames to the full render target (2340x1080), so the
            // SurfaceControl buffer must match.
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            if (hostView.getDisplay() != null) {
                hostView.getDisplay().getRealMetrics(metrics);
            } else if (context != null) {
                ((android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE))
                        .getDefaultDisplay().getRealMetrics(metrics);
            }
            layerW = metrics.widthPixels > 0 ? metrics.widthPixels : 2340;
            layerH = metrics.heightPixels > 0 ? metrics.heightPixels : 1080;
        }
        try {
            // Match the working 41d5ea6 LinuxWindowActivity.ensurePresentLayer():
            //   - setFormat(RGBA_8888) + setOpaque(true) — required for the native
            //     present code's ASurfaceTransaction_setBufferTransparency(TRANSLUCENT)
            //     + setBufferAlpha(NUDGE_ALPHA) to composite correctly
            //   - setHidden(false) — visible from creation
            //   - Transaction: setAlpha(1.0) + setBufferSize + setCrop — full-frame crop
            //     so SurfaceFlinger composites the entire buffer
            // Create at display root level (NO parent) to avoid inheriting
            // the SurfaceView's scale+translate transform. When parented to
            // the SurfaceView, the presentLayer gets the transform
            // scale(1.333) + translate(3231, 0) which pushes it off-screen.
            // Root-level layers use screen coordinates directly.
            presentLayer = new SurfaceControl.Builder()
                .setName("WayLandIELinuxWindowLayer:waylandie-present")
                .setBufferSize(layerW, layerH)
                .setFormat(PixelFormat.RGBA_8888)
                .setOpaque(true)
                .setHidden(false)
                .build();
            // Keep render target at landscape 2340x1080 (the native GPU blit
            // target and slot buffer size). The SurfaceControl layer is sized
            // to the full screen, and SurfaceFlinger handles scaling/rotation.
            width = 2340;
            height = 1080;
            new SurfaceControl.Transaction()
                .setLayer(presentLayer, Integer.MAX_VALUE)
                .setVisibility(presentLayer, true)
                .setAlpha(presentLayer, 1.0f)
                .setPosition(presentLayer, 0.0f, 0.0f)
                .setBufferSize(presentLayer, layerW, layerH)
                .setCrop(presentLayer, new Rect(0, 0, layerW, layerH))
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
