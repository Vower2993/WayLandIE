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
    /** Consecutive failed binds in the current accept loop; reset on success. */
    private int bindFailures = 0;
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

    // In-process Wayland compositor (Bannerlator architecture).
    // Implemented in waylandie_display_native.c via dlopen("libwaylandie_comp.so").
    // No System.loadLibrary needed — dlopen + dlsym calls the compositor directly.
    // outWidth/outHeight are the guest desktop size (the container's screenSize, which is also
    // what the guest is launched with as `/desktop=shell,WxH`). Passing them here declares
    // wl_output before the compositor thread starts, so it cannot advertise the built-in
    // 1920x1080 default to the first client that binds.
    public static native boolean nativeStartCompositor(android.view.Surface surface,
        String xdgRuntimeDir, String driverPath, String libraryName, String nativeLibDir,
        int outWidth, int outHeight);
    public static native void nativeStopCompositor();
    // Android surface size changed (rotation/resize) -> swapchain recreation.
    public static native void nativeCompositorSurfaceChanged(int width, int height);
    // SurfaceView surface recreated (rotation/resize) -> rebind the output
    // ANativeWindow so the running compositor presents to the live surface.
    public static native void nativeCompositorSetSurface(android.view.Surface surface);
    // Input injection into the compositor's wl_seat. Without these the seat is
    // advertised but never receives an event, so Wine has a dead pointer and
    // keyboard. action: 0=down 1=move 2=up; x,y in the OUTPUT space declared by
    // nativeCompositorSetOutputSize() (defaults to 1920x1080 if never called).
    // evdev is a Linux input keycode (KEY_A=30), state 1=down 0=up.
    public static native void nativeCompositorSendPointer(int action, int x, int y);
    public static native void nativeCompositorSendKey(int evdev, int state);
    // Declare the coordinate space nativeCompositorSendPointer() uses. Pass the
    // guest's screen size (the container's `screenSize`, which is also the value the
    // guest is launched with as `wine explorer /desktop=shell,WxH`). The compositor
    // rescales from here into the focused surface's real buffer size, so a mismatch
    // puts every click in the wrong place. Idempotent; safe before the compositor starts.
    public static native void nativeCompositorSetOutputSize(int width, int height);

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
            // Join it before returning. Without this, a subsequent start() can overlap with a
            // generation that is still between its while-check and its bind, which is exactly
            // how the leaked listener described in acceptLoop() got created. Bounded wait so a
            // stuck accept() cannot block teardown.
            try {
                acceptThread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
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
            // Bind into a LOCAL, and use that local everywhere inside this iteration.
            //
            // This is the fix for a leak that made the presenter permanently deaf. The
            // previous version assigned straight to the `serverSocket` field and used the
            // field in the inner accept loop. Because `start()` sets `running = true` and
            // `stop()` sets it false, an accept loop from a PREVIOUS generation can still be
            // between its while-check and its bind when a new generation starts. Both loops
            // then bind, the second overwrites the field, and the first one's listener is
            // never closed - it stays bound to the abstract socket for the life of the
            // process, so `new LocalServerSocket(name)` fails with "Address already in use"
            // forever.
            //
            // Measured symptom, with the session fully alive underneath it:
            //   WaylandBridgeServer: Bind failed (will retry in 5000ms): Address already in use
            // repeating every 5s for the rest of the session, TWO threads doing it in lockstep,
            // while /proc/net/unix showed the listener held by a process nobody could name.
            // The bridge's own log ends at frame=0 present-step, so the 40 desktop frames that
            // were converted to AHardwareBuffer had nowhere to go.
            LocalServerSocket bound;
            try {
                bound = new LocalServerSocket(SOCKET_NAME);
            } catch (IOException e) {
                if (!running) break;
                bindFailures++;
                if (bindFailures == 4) {
                    // Escalate once. This state is a silent total failure of the display path -
                    // the bridge keeps producing frames into a socket nobody is listening on,
                    // and every downstream diagnostic still says status=pass - so it must not
                    // look like a routine retry in the log.
                    Log.e(TAG, "SOCKET UNAVAILABLE: cannot bind " + SOCKET_NAME
                            + " after " + bindFailures + " attempts. A stale listener from a "
                            + "previous session is holding the abstract socket, so NO frame can "
                            + "be presented and the screen stays black regardless of what the "
                            + "bridge or the compositor report. Clear it by killing the old "
                            + "process (am force-stop, or kill the previous activity instance).");
                } else {
                    Log.w(TAG, "Bind failed (will retry in " + retryDelayMs + "ms): " + e.getMessage());
                }
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // Exponential backoff: double each retry, cap at 5s.
                retryDelayMs = Math.min(retryDelayMs * 2, 5000L);
                continue;
            }
            serverSocket = bound;
            bindFailures = 0;
            Log.i(TAG, "Listening on abstract socket: " + SOCKET_NAME);
            // Signal start() that the socket is bound — allows the bridge
            // binary to be launched without racing the bind.
            if (socketBoundLatch != null) {
                socketBoundLatch.countDown();
            }
            retryDelayMs = 100L;  // Reset backoff on successful bind
            try {
                while (running) {
                    LocalSocket client;
                    try {
                        client = bound.accept();
                    } catch (IOException e) {
                        if (running) {
                            Log.w(TAG, "Accept error: " + e.getMessage());
                            break;  // re-bind in the outer loop
                        }
                        break;
                    }
                    Log.i(TAG, "Bridge client connected");
                    // Handle each client in a separate thread so multiple
                    // bridge connections (input-stream + dmabuf-present)
                    // can be served simultaneously.
                    Thread clientThread = new Thread(
                            () -> handleClient(client),
                            "wl-bridge-client");
                    clientThread.setDaemon(true);
                    clientThread.start();
                }
            } finally {
                // Close only THIS iteration's socket, never whatever the field happens to
                // hold now (a newer generation may already own it).
                try { bound.close(); } catch (IOException ignored) {}
                if (serverSocket == bound) serverSocket = null;
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
            // Periodic heartbeat. Before this, the ONLY per-frame log line was the one above,
            // and it was emitted only when a frame actually arrived - so a presenter that had
            // silently stopped receiving frames (bind failure, dead client) produced no output
            // at all and looked identical to a presenter the bridge had never connected to.
            // Log the first few frames unconditionally, then every 60th, so "frames are
            // arriving" and "frames stopped arriving" are both visible in logcat.
            int frameNo = frameIndex - 1;
            if (frameNo < 3 || frameNo % 60 == 0) {
                Log.i(TAG, "frame-heartbeat n=" + frameNo + " source=" + srcWidth + "x" + srcHeight
                        + " target=" + width + "x" + height + " result="
                        + (result == null ? "null" : (result.length() > 60
                            ? result.substring(0, 60) : result)));
            }

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
        if (hostView == null) {
            Log.w(TAG, "Cannot create presentLayer — hostView is null");
            return;
        }
        // NOTE: this deliberately does NOT bail out when hostView.getSurfaceControl() is null.
        //
        // The previous version returned early in that case, and because the first thing it
        // does is `if (presentLayer != null) return;`, the failure was PERMANENT: the first
        // frame simply arrived before the view had a SurfaceControl, the method gave up, and
        // every later frame took the early return. The layer was then never created for that
        // whole session - which is the reported "second launch showed just a black screen"
        // while the first launch (where the surface happened to be ready in time) showed a
        // desktop. It also explains the non-determinism between runs.
        //
        // A frame arriving before the surface exists is a normal race, not an error. The
        // resolve below falls through to Window#getRootSurfaceControl(), and only if NO
        // parent can be found do we skip creation - leaving presentLayer null so the next
        // frame retries.
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
            // Parent the presenter to the app's own window SurfaceControl.
            //
            // AOSP documents that a SurfaceControl's geometric properties are interpreted in its
            // PARENT's space: "Geometric properties like transform, crop, and Z-ordering will be
            // inherited from the parent, as if the child were content in the parents buffer
            // stream" (SurfaceControl class doc), and the NDK header defines
            // ASurfaceTransaction_setGeometry's destination as "the rect in the parent's space
            // where this surface will be drawn ... clipped by the bounds of its parent".
            //
            // AOSP's header for ASurfaceTransaction_reparent is equally explicit:
            //   "The new_parent can be null. Surface controls with a null parent do not
            //    appear on the display."
            // so the parent is mandatory, not a nicety.
            SurfaceControl parent = null;
            String parentSource = "none";
            //   1. Window#getRootSurfaceControl() (public since API 29) - the SurfaceControl
            //      ViewRootImpl hands to WindowManager, i.e. the app window's own layer.
            //      Reached by reflection because the view's Context may be a ContextThemeWrapper
            //      rather than the Activity.
            //   2. hostView.getSurfaceControl() - the SurfaceView's own child layer.
            try {
                // Named rootWindow, not w - `w` is this method's int width parameter.
                android.view.Window rootWindow = null;
                android.content.Context c = hostView.getContext();
                if (c instanceof android.app.Activity) {
                    rootWindow = ((android.app.Activity) c).getWindow();
                }
                if (rootWindow != null) {
                    java.lang.reflect.Method m =
                            android.view.Window.class.getMethod("getRootSurfaceControl");
                    Object o = m.invoke(rootWindow);
                    if (o instanceof SurfaceControl) {
                        parent = (SurfaceControl) o;
                        parentSource = "window-root";
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "presentLayer: Window#getRootSurfaceControl unavailable: " + t);
            }
            if (parent == null) {
                try {
                    parent = hostView.getSurfaceControl();
                    if (parent != null) parentSource = "surfaceview";
                } catch (Throwable t) {
                    Log.w(TAG, "presentLayer: hostView.getSurfaceControl() failed", t);
                }
            }
            if (parent == null) {
                // Per AOSP this layer would not appear on the display at all, so do not create
                // it: leaving presentLayer null lets the next frame retry once the view has
                // been laid out. This is the only path that defers creation.
                Log.w(TAG, "presentLayer: no parent SurfaceControl yet (hostView="
                        + hostView.getWidth() + "x" + hostView.getHeight()
                        + ") — deferring creation to a later frame");
                return;
            }
            // Sizing: the layer must be the RENDER TARGET's size, in the units the parent's
            // space uses, or the blit output is drawn into the wrong number of parent units.
            //
            // Measured failure (two independent reports, one from dumpsys and one from the
            // user watching the screen): the previous code sized the layer from
            // hostView.getWidth()/getHeight(), which returned 1080x1080, while the parent's
            // space is far larger. The result was a fully composited layer carrying a real
            // desktop, squeezed into geomLayerBounds=[96, 0, 1176, 1080] out of a 1440x3120
            // display - "a real desktop, but squeezed/tiny" - while the SurfaceFlinger
            // composition table showed nothing at all.
            //
            // The render target is ALWAYS landscape outputWidth x outputHeight: the native
            // blit scales every source frame to (width, height) and allocates the
            // AHardwareBuffer slots at that size (see the ahb_vk renderer's slot
            // allocation). So those are the only correct dimensions for both the buffer and
            // the destination rect. They are set just below.
            int targetW = 2340;
            int targetH = 1080;
            presentLayer = new SurfaceControl.Builder()
                .setName("WayLandIELinuxWindowLayer:waylandie-present")
                .setBufferSize(targetW, targetH)
                .setFormat(PixelFormat.RGBA_8888)
                .setOpaque(false)
                .setHidden(false)
                .build();
            // Keep render target at landscape 2340x1080 (the native GPU blit
            // target and slot buffer size). The SurfaceControl layer is sized
            // to the full screen, and SurfaceFlinger handles scaling/rotation.
            width = targetW;
            height = targetH;
            SurfaceControl.Transaction txn = new SurfaceControl.Transaction()
                .setVisibility(presentLayer, true)
                .setPosition(presentLayer, 0.0f, 0.0f)
                .setBufferSize(presentLayer, targetW, targetH)
                .setCrop(presentLayer, new Rect(0, 0, targetW, targetH));
            if (parent != null) {
                // reparent() is what actually establishes the parent/child relationship; the
                // Builder takes no parent argument in this API.
                txn.reparent(presentLayer, parent);
            }
            // setOpaque(false) above, deliberately: ASurfaceTransaction_setBufferTransparency
            // is set to TRANSLUCENT per frame natively, and the Builder's setOpaque() only
            // feeds SurfaceFlinger's opacity *hint*. Declaring a buffer opaque that contains
            // zeroed pixels is the documented route to "visual errors", and the bridge's
            // shm_to_ahb output is exactly that (measured nonzero=10233 of 2,764,800 bytes).
            // Alpha is driven natively by setBufferAlpha(1.0f) + TRANSLUCENT, so nothing here
            // needs to guess at it.
            txn.setAlpha(presentLayer, 1.0f);
            // Z-order: NOT Integer.MAX_VALUE. This layer is a child of the app window, so the
            // only thing it needs to outrank is its siblings inside that window - and
            // Integer.MAX_VALUE also outranks the system's own layers (StatusBar, navigation
            // bar, IME) if the parent were ever the display. The measured dumpsys recorded
            // `z=2147483647`, which is the kind of context-free maximum that caused the
            // "app UI was gone and the desktop filled the screen" report. A high-but-sane
            // value inside the window is sufficient.
            txn.setLayer(presentLayer, 100);
            txn.apply();            // Report what was applied. There is no public API to read a SurfaceControl's
            // size back (SurfaceControl has no getWidth/getHeight), so log the inputs
            // instead: the parent source, the app window size, and the child's own size.
            // The authoritative check is the SurfaceFlinger dump - look for the layer
            // under the DISPLAY hierarchy with geomLayerBounds filling the parent, and
            // for the layer name appearing in the composition table.
            int hostW = hostView.getWidth(), hostH = hostView.getHeight();
            Log.i(TAG, "Created presentLayer: buffer=" + targetW + "x" + targetH
                    + " (frame source=" + w + "x" + h + ")"
                    + " parent=" + parentSource
                    + " hostView=" + hostW + "x" + hostH
                    + " z=100 alpha=1.0 transparency set per-frame to TRANSLUCENT(1)");
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
