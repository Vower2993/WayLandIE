package io.waylandie.display;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.HardwareBuffer;
import android.hardware.SyncFence;
import android.media.Image;
import android.media.ImageReader;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.util.Log;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Choreographer;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;

public final class MainActivity extends Activity
        implements SurfaceHolder.Callback, Choreographer.FrameCallback {
    private static final BigInteger BRIDGE_META_ZERO = BigInteger.ZERO;
    private static final BigInteger BRIDGE_META_ONE = BigInteger.ONE;
    private static final BigInteger BRIDGE_META_FOUR = BigInteger.valueOf(4L);
    private static final BigInteger BRIDGE_META_UINT32_MAX =
            BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
    private static final BigInteger BRIDGE_META_UINT64_MAX =
            BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final String TAG_LAYER = "WayLandIEDisplayLayer";
    private static final String TAG_LOG = "WayLandIEDisplay";
    private static final String NATIVE_LIBRARY = "waylandie_display_native";
    private static final String EXTRA_AHB_CPU_PRODUCER = "waylandie_ahb_cpu_producer";
    private static final String EXTRA_VULKAN_PRODUCER = "waylandie_vulkan_producer";
    private static final String EXTRA_BRIDGE_SERVER = "waylandie_bridge_server";
    private static final String EXTRA_EXTERNAL_PRESENT_ONLY = "waylandie_external_present_only";
    private static final String EXTRA_DIAGNOSTIC_PRODUCER = "waylandie_diagnostic_producer";
    private static final String EXTRA_HIDE_OVERLAY = "waylandie_hide_overlay";
    private static final boolean DEFAULT_AHB_CPU_PRODUCER_ENABLED = false;
    private static final boolean DEFAULT_VULKAN_PRODUCER_ENABLED = false;
    private static final int BRIDGE_CONTROL_PORT = 57391;
    private static final String BRIDGE_LOCAL_SOCKET_NAME = "waylandie.display.bridge.v1";
    private static final String PREFS_NAME = "waylandie-display-launch";
    private static final String PREF_BRIDGE_SERVER = "bridge_server";
    private static final String PREF_EXTERNAL_PRESENT_ONLY = "external_present_only";
    private static final String PREF_HIDE_OVERLAY = "hide_overlay";
    private static final String ADRENOTOOLS_DRIVER_DIR_NAME = "adrenotools-driver";
    private static final String DEFAULT_ADRENOTOOLS_DRIVER_NAME = "vulkan.waylandie.a8xx.so";
    private static final int BRIDGE_PROTOCOL_VERSION = 1;
    private static final int BRIDGE_AHB_PRESENT_WRITE_DELAY_MS = 500;
    private static final int BRIDGE_AHB_PRESENT_HOLD_MS = 1500;
    private static final int BRIDGE_AHB_RING_DEFAULT_FRAMES = 12;
    private static final int BRIDGE_AHB_RING_MAX_FRAMES = 120;
    private static final String BRIDGE_FDTEST_MAGIC = "waylandie-fdtest-v1";
    private static final String BRIDGE_COMMANDS =
            "hello,ping,caps,display,vulkan,adrenotools,contract,buffers,sync,native,compositor,compositor-open,compositor-status,window-add,window-remove,window-status,fdtest,syncfd-test,dmabuf-test,dmabuf-meta,dmabuf-import-probe,dmabuf-present,kgsl-import-probe,ahb-export-probe,ahb-present-probe,ahb-ring-probe,status,input,input-stream";
    private static final String BRIDGE_PIXEL_FORMAT_NAME = "RGBA_8888";
    private static final int BRIDGE_PIXEL_FORMAT = PixelFormat.RGBA_8888;
    private static final long BRIDGE_CURRENT_AHB_USAGE =
            HardwareBuffer.USAGE_CPU_WRITE_OFTEN
                    | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                    | HardwareBuffer.USAGE_COMPOSER_OVERLAY;
    private static final long BRIDGE_TARGET_GPU_AHB_USAGE =
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                    | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                    | HardwareBuffer.USAGE_COMPOSER_OVERLAY;
    private static final int IMAGE_READER_MAX_IMAGES = 8;
    private static final int MAX_IN_FLIGHT_IMAGES = IMAGE_READER_MAX_IMAGES - 1;
    private static final float PRESENT_LAYER_COMPOSITION_NUDGE_ALPHA = 0.999f;
    private static final float MIN_PRESENTATION_FRAME_RATE_HZ = 30.0f;
    private static final long EXTERNAL_PRESENT_UI_UPDATE_INTERVAL_NANOS = 500_000_000L;
    private static final long BRIDGE_PRESENTER_WAIT_TIMEOUT_MS = 60L * 60L * 1000L;
    private static final long BRIDGE_WINDOW_WAIT_TIMEOUT_MS = 5000L;
    private static final float BRIDGE_CURSOR_SIZE_PX = 32.0f;
    private static final long BRIDGE_TOUCH_CURSOR_HIDE_DELAY_MS = 800L;
    private static final long BRIDGE_CONTROLLER_CURSOR_HIDE_DELAY_MS = 1400L;
    private static final float CONTROLLER_MOUSE_DEADZONE = 0.18f;
    private static final float CONTROLLER_MOUSE_SPEED_PX_PER_SECOND = 1850.0f;
    private static volatile boolean nativeLibraryAvailable;
    // Lazy-initialized: previously these were eager `static final` fields
    // (lines 140-141 in the pre-fix source) that triggered
    // `loadNativeStatusText()` + `loadNativeVulkanStatusText()` at class
    // load time. If `nativeVulkanProbe()` threw anything outside
    // `RuntimeException | UnsatisfiedLinkError` (e.g.
    // `ExceptionInInitializerError` from a chained `NoClassDefFoundError`,
    // or `OutOfMemoryError`), the class init failed, propagating to
    // `HomeActivity.updateNativeStatus()` which only caught
    // `UnsatisfiedLinkError` → process kill → force-close.
    //
    // Now they are computed on first access via `getNativeStatusText()`
    // / `getVulkanStatusText()` with double-checked locking, and the
    // load methods catch `Throwable` so no failure can poison the class.
    private static volatile String nativeStatusText = null;
    private static volatile String vulkanStatusText = null;
    private static final Object statusTextLock = new Object();
    private static final Object ACTIVE_BRIDGE_OWNER_LOCK = new Object();
    private static WeakReference<MainActivity> activeBridgeOwnerRef =
            new WeakReference<>(null);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayDeque<Image> inFlightImages = new ArrayDeque<>();
    private final ArrayDeque<AhbInFlightFrame> inFlightAhbFrames = new ArrayDeque<>();
    private final Object ahbInFlightLock = new Object();
    private final Object vulkanRenderLock = new Object();
    // CRITICAL: bridgeLock + bridgeLocalServer must be STATIC because the
    // abstract socket 'waylandie.display.bridge.v1' is a kernel-global resource.
    // If these were instance fields, a new MainActivity instance would try to
    // create a second BridgeLocalServer and fail with 'Address already in use'.
    // Making them static ensures all instances share the same server.
    private static final Object bridgeLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideBridgeCursorRunnable = () -> setBridgeCursorVisible(false);

    private SurfaceView hostView;
    private TextView overlayView;
    private SurfaceView cursorView;
    private BridgeImeView bridgeImeView;
    private volatile SurfaceControl presentLayer;
    private ImageReader imageReader;
    private Surface producerSurface;
    private HandlerThread vulkanRenderThread;
    private Handler vulkanRenderHandler;

    private int width;
    private int height;
    private int vulkanPipelineGeneration;
    private long frameIndex;
    private long lastStatsNanos;
    private long framesSinceStats;
    private float measuredFps;
    private long lastBridgeStatsNanos;
    private long bridgeFramesSinceStats;
    private float bridgeMeasuredFps;
    private float lastBridgePresentMs;
    private volatile String lastBridgeDriverName = "waiting";
    private volatile String lastBridgeSourceSize = "waiting";
    private volatile String lastBridgeTargetSize = "waiting";
    private volatile String lastBridgeZeroCopyMode = "waiting";
    private boolean running;
    private boolean inputFrameRunning;
    private boolean vulkanRenderInProgress;
    private boolean ahbCpuProducerEnabled;
    private boolean vulkanProducerEnabled;
    private boolean bridgeServerEnabled;
    private boolean externalPresentOnlyEnabled;
    private boolean diagnosticProducerEnabled;
    private boolean hideOverlayEnabled;
    private boolean launchExtrasApplied;
    private volatile boolean activityResumed;
    private BridgeControlServer bridgeControlServer;
    private static BridgeLocalServer bridgeLocalServer;
    private OnBackInvokedCallback bridgeBackCallback;
    private volatile String producerStatusText = "producer: waiting";
    private volatile String lastVulkanProducerFrameStatus;
    private String ahbCpuFallbackReason;
    private volatile String vulkanFallbackReason;
    private volatile String lastAhbReleaseStatus = "ahb release: none";
    private final Object bridgeInputStreamLock = new Object();
    private final ArrayList<BridgeInputStream> bridgeInputStreams = new ArrayList<>();
    private boolean controllerMouseModeEnabled = false;
    private float bridgeCursorX;
    private float bridgeCursorY;
    private long lastControllerMouseEventTimeMs;
    private float controllerMouseAxisX;
    private float controllerMouseAxisY;
    private long lastControllerMouseFrameNanos;
    private boolean controllerMouseButtonDown;
    private int bridgeInputGestureMaxPointers;
    private boolean bridgeInputGestureHandled;
    private boolean suppressBridgeImeTextChange;
    private long inputSequence;
    private long bridgeSequence;
    private volatile String inputStatusText = "input: waiting";
    private volatile String bridgeStatusText = "bridge: off";
    private volatile String bridgeNativeStatusText = "native-bridge: off";
    private volatile String bridgeContractStatusText = "contract: waiting";
    private volatile String bridgeBufferStatusText = "buffers: waiting";
    private volatile String bridgeFdStatusText = "fdtest: waiting";
    private volatile String bridgeSyncFdStatusText = "syncfd-test: waiting";
    private volatile String bridgeDmaBufStatusText = "dmabuf-test: waiting";
    private volatile String bridgeDmaBufMetaStatusText = "dmabuf-meta: waiting";
    private volatile String bridgeDmaBufImportProbeStatusText = "dmabuf-import-probe: waiting";
    private volatile String bridgeDmaBufPresentStatusText = "dmabuf-present: waiting";
    private volatile String bridgeDmaBufDriverStatusText = "dmabuf-driver: waiting";
    private volatile String bridgePresenterStatusText = "presenter: waiting";
    private volatile String bridgeKgslImportProbeStatusText = "kgsl-import-probe: waiting";
    private volatile String bridgeAdrenoToolsStatusText = "adrenotools: waiting";
    private volatile String bridgeAhbExportProbeStatusText = "ahb-export-probe: waiting";
    private volatile String bridgeAhbPresentProbeStatusText = "ahb-present-probe: waiting";
    private volatile String bridgeCompositorStatusText = "compositor: waiting";
    private volatile String bridgeWindowStatusText = "windows: waiting";
    private volatile String lastDiagnosticSummary = "diagnostic: waiting";
    private volatile long bridgePresentHoldUntilNanos;
    private long lastExternalPresentOverlayNanos;
    private volatile String bridgeDmaBufReadyDriverCacheKey = "";

    private static final class AhbCpuFrame {
        final HardwareBuffer buffer;
        final int slot;
        final long generation;
        final String status;

        AhbCpuFrame(HardwareBuffer buffer, int slot, long generation, String status) {
            this.buffer = buffer;
            this.slot = slot;
            this.generation = generation;
            this.status = status;
        }
    }

    private static final class AhbInFlightFrame {
        final HardwareBuffer buffer;
        final int slot;
        final long generation;
        final boolean vulkan;
        boolean wrapperClosed;

        AhbInFlightFrame(HardwareBuffer buffer, int slot, long generation, boolean vulkan) {
            this.buffer = buffer;
            this.slot = slot;
            this.generation = generation;
            this.vulkan = vulkan;
        }
    }

    private static final class BridgeInputStream {
        private final OutputStreamWriter writer;

        BridgeInputStream(OutputStreamWriter writer) {
            this.writer = writer;
        }

        synchronized boolean writeLine(String line) {
            try {
                writer.write(line);
                writer.write('\n');
                writer.flush();
                return true;
            } catch (IOException ignored) {
                return false;
            }
        }
    }

    private final class BridgeImeView extends EditText {
        BridgeImeView() {
            super(MainActivity.this);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setAlpha(0.01f);
            setTextColor(Color.TRANSPARENT);
            setHintTextColor(Color.TRANSPARENT);
            setBackgroundColor(Color.TRANSPARENT);
            setCursorVisible(false);
            setSingleLine(false);
            setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            setImeOptions(EditorInfo.IME_ACTION_NONE
                    | EditorInfo.IME_FLAG_NO_FULLSCREEN
                    | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable editable) {
                    if (suppressBridgeImeTextChange || editable.length() == 0) {
                        return;
                    }
                    publishBridgeTextInput(editable.toString());
                    suppressBridgeImeTextChange = true;
                    editable.clear();
                    suppressBridgeImeTextChange = false;
                }
            });
        }

        @Override
        public boolean onCheckIsTextEditor() {
            return bridgeOwnsExternalSession();
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            InputConnection connection = super.onCreateInputConnection(outAttrs);
            outAttrs.inputType = getInputType();
            outAttrs.imeOptions = getImeOptions();
            return new BaseInputConnection(this, true) {
                @Override
                public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                    publishBridgeSpecialKeyInput(KeyEvent.KEYCODE_DEL);
                    return true;
                }

                @Override
                public boolean sendKeyEvent(KeyEvent event) {
                    int action = event.getAction();
                    int unicode = event.getUnicodeChar();
                    if (action == KeyEvent.ACTION_UP && unicode > 0) {
                        publishBridgeTextInput(new String(Character.toChars(unicode)));
                        return true;
                    }
                    if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) {
                        publishBridgeKeyInput(++inputSequence, event);
                        return true;
                    }
                    return true;
                }

                @Override
                public boolean commitText(CharSequence text, int newCursorPosition) {
                    if (text != null && text.length() > 0) {
                        publishBridgeTextInput(text.toString());
                    }
                    return connection == null || connection.commitText("", newCursorPosition);
                }

                @Override
                public boolean performEditorAction(int actionCode) {
                    publishBridgeSpecialKeyInput(KeyEvent.KEYCODE_ENTER);
                    return true;
                }
            };
        }
    }

    private final class BridgeControlServer implements Runnable {
        private final Thread thread;
        private volatile boolean stopRequested;
        private volatile ServerSocket serverSocket;

        BridgeControlServer() {
            thread = new Thread(this, "WayLandIEBridgeControl");
            thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        boolean isAlive() {
            return thread.isAlive();
        }

        void stop() {
            stopRequested = true;
            closeServerSocket();
            if (Thread.currentThread() == thread) {
                return;
            }
            boolean interrupted = false;
            while (thread.isAlive()) {
                try {
                    thread.join(1000L);
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            try (ServerSocket server = new ServerSocket()) {
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(
                        InetAddress.getByName("127.0.0.1"),
                        BRIDGE_CONTROL_PORT), 4);
                serverSocket = server;
                bridgeStatusText = String.format(
                        Locale.US,
                        "bridge: tcp 127.0.0.1:%d listening",
                        BRIDGE_CONTROL_PORT);

                while (!stopRequested) {
                    try {
                        handleBridgeClient(server.accept());
                    } catch (IOException error) {
                        if (!stopRequested) {
                            bridgeStatusText = "bridge: accept error "
                                    + compactBridgeText(error.getClass().getSimpleName());
                        }
                    }
                }
            } catch (IOException error) {
                if (!stopRequested) {
                    bridgeStatusText = "bridge: error "
                            + compactBridgeText(error.getClass().getSimpleName());
                }
            } finally {
                serverSocket = null;
                synchronized (bridgeLock) {
                    if (bridgeControlServer == this) {
                        bridgeControlServer = null;
                    }
                }
                if (stopRequested) {
                    bridgeStatusText = bridgeServerEnabled
                            ? "bridge: enabled stopped"
                            : "bridge: off";
                }
            }
        }

        private void closeServerSocket() {
            ServerSocket socket = serverSocket;
            if (socket == null) {
                return;
            }
            try {
                socket.close();
            } catch (IOException ignored) {
                // Shutdown is best-effort; a stale control socket must not block
                // the compositor lifecycle.
            }
        }
    }

    private final class BridgeLocalServer implements Runnable {
        private final Thread thread;
        private volatile boolean stopRequested;
        private volatile LocalServerSocket serverSocket;
        private long retryDelayMs = 100L;  // Exponential backoff for socket retry

        BridgeLocalServer() {
            thread = new Thread(this, "WayLandIEBridgeLocal");
            thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        boolean isAlive() {
            return thread.isAlive();
        }

        void stop() {
            stopRequested = true;
            closeServerSocket();
            if (Thread.currentThread() == thread) {
                return;
            }
            boolean interrupted = false;
            while (thread.isAlive()) {
                try {
                    thread.join(1000L);
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            while (!stopRequested) {
                try (LocalServerSocket server = new LocalServerSocket(BRIDGE_LOCAL_SOCKET_NAME)) {
                    serverSocket = server;
                    retryDelayMs = 100L;  // Reset backoff on successful bind
                    bridgeNativeStatusText = String.format(
                            Locale.US,
                            "native-bridge: unix-abstract %s listening",
                            BRIDGE_LOCAL_SOCKET_NAME);

                    while (!stopRequested) {
                        try {
                            LocalSocket client = server.accept();
                            Thread clientThread = new Thread(
                                    () -> handleBridgeLocalClient(client),
                                    "WayLandIEBridgeLocalClient");
                            clientThread.setDaemon(true);
                            clientThread.start();
                        } catch (IOException error) {
                            if (!stopRequested) {
                                bridgeNativeStatusText = "native-bridge: accept error "
                                        + compactBridgeThrowable(error);
                            }
                        } catch (RuntimeException error) {
                            if (!stopRequested) {
                                bridgeNativeStatusText = "native-bridge: client-thread error "
                                        + compactBridgeThrowable(error);
                            }
                        }
                    }
                } catch (IOException error) {
                    if (!stopRequested) {
                        bridgeNativeStatusText = "native-bridge: error "
                                + compactBridgeThrowable(error)
                                + " restarting";
                        sleepBeforeLocalServerRetry();
                    }
                } finally {
                    serverSocket = null;
                }
            }
            synchronized (bridgeLock) {
                if (bridgeLocalServer == this) {
                    bridgeLocalServer = null;
                }
            }
            bridgeNativeStatusText = bridgeServerEnabled
                    ? "native-bridge: enabled stopped"
                    : "native-bridge: off";
        }

        private void closeServerSocket() {
            LocalServerSocket socket = serverSocket;
            if (socket == null) {
                return;
            }
            try {
                socket.close();
            } catch (IOException ignored) {
                // Shutdown is best-effort; a stale local socket must not block
                // the compositor lifecycle.
            }
        }

        private void sleepBeforeLocalServerRetry() {
            // Exponential backoff: start at 100ms, double each retry, cap at 5s.
            // The old 25ms retry was too aggressive — when the previous Wine
            // process hasn't released the socket yet, the retry loop hammers
            // it every 25ms and fills the log with "Address already in use".
            // With backoff, we give the OS time to actually release the socket.
            retryDelayMs = Math.min(retryDelayMs * 2, 5000L);
            if (retryDelayMs < 100L) retryDelayMs = 100L;
            try {
                Thread.sleep(retryDelayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                stopRequested = true;
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setDecorFitsSystemWindows(false);
        requestHighestRefreshRateMode(window);

        FrameLayout rootView = new FrameLayout(this);
        rootView.setBackgroundColor(Color.BLACK);
        rootView.setFocusable(true);
        rootView.setFocusableInTouchMode(true);

        hostView = new SurfaceView(this);
        hostView.setBackgroundColor(Color.BLACK);
        hostView.setFocusable(true);
        hostView.setFocusableInTouchMode(true);
        hostView.setZOrderOnTop(true);
        hostView.getHolder().addCallback(this);
        rootView.addView(hostView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        overlayView = new TextView(this);
        overlayView.setTextColor(Color.WHITE);
        overlayView.setTextSize(11.0f);
        overlayView.setIncludeFontPadding(false);
        overlayView.setPadding(12, 8, 12, 8);
        overlayView.setMaxLines(10);
        overlayView.setBackgroundColor(Color.argb(70, 0, 10, 18));
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        overlayParams.gravity = Gravity.END | Gravity.TOP;
        overlayParams.setMargins(10, 10, 10, 10);
        rootView.addView(overlayView, overlayParams);

        cursorView = new SurfaceView(this);
        cursorView.setZOrderOnTop(true);
        cursorView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        cursorView.setVisibility(View.INVISIBLE);
        cursorView.setClickable(false);
        cursorView.setFocusable(false);
        FrameLayout.LayoutParams cursorParams = new FrameLayout.LayoutParams(
                (int) BRIDGE_CURSOR_SIZE_PX,
                (int) BRIDGE_CURSOR_SIZE_PX);
        rootView.addView(cursorView, cursorParams);
        cursorView.setZ(1000.0f);

        bridgeImeView = new BridgeImeView();
        FrameLayout.LayoutParams imeParams = new FrameLayout.LayoutParams(1, 1);
        imeParams.gravity = Gravity.START | Gravity.BOTTOM;
        rootView.addView(bridgeImeView, imeParams);
        bridgeImeView.setZ(999.0f);

        setContentView(rootView);
        rootView.requestFocus();

        paint.setColor(Color.WHITE);
        paint.setTextSize(58.0f);
        smallPaint.setColor(Color.WHITE);
        smallPaint.setTextSize(34.0f);

        applyLaunchExtras(getIntent());
        becomeActiveBridgeOwner("create");
        registerBridgeBackCallbackIfNeeded();

        hideSystemBars();
        claimBridgeInputFocus();
        updateOverlay();
    }

    @Override
    protected void onStart() {
        super.onStart();
        WindowBridgeRegistry.onAppActivityStarted(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        restoreStickyExternalBridgeSessionIfNeeded("resume");
        synchronized (vulkanRenderLock) {
            activityResumed = true;
            vulkanRenderLock.notifyAll();
        }
        becomeActiveBridgeOwner("resume");
        hideSystemBars();
        claimBridgeInputFocus();
        requestHighestRefreshRateMode(getWindow());
        if (hostView != null && hostView.getHolder().getSurface().isValid()) {
            configurePipeline(hostView.getHolder());
        }
        startBridgeControlServerIfNeeded();
        if (presentLayer != null && !running && !externalPresentOnlyEnabled) {
            setBridgePresenterStatus("ready");
            startFrameLoop();
        } else {
            setBridgePresenterStatus(presentLayer == null ? "wait-layer" : "ready");
        }
        updateOverlay();
    }

    @Override
    protected void onPause() {
        synchronized (vulkanRenderLock) {
            activityResumed = false;
            vulkanRenderLock.notifyAll();
        }
        if (bridgeOwnsExternalSession()) {
            setBridgePresenterStatus(
                    presentLayer == null ? "background-wait-layer" : "background-ready");
        } else {
            setBridgePresenterStatus(presentLayer == null ? "paused-no-layer" : "paused");
        }
        stopFrameLoop();
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (bridgeOwnsExternalSession()) {
            setBridgePresenterStatus(
                    presentLayer == null ? "background-wait-layer" : "background-ready");
        } else {
            setBridgePresenterStatus("stopped");
        }
        synchronized (vulkanRenderLock) {
            vulkanRenderLock.notifyAll();
        }
        stopFrameLoop();
        WindowBridgeRegistry.onAppActivityStopped();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (shouldKeepExternalBridgeSession()) {
            unregisterBridgeBackCallback();
            synchronized (vulkanRenderLock) {
                activityResumed = false;
                vulkanRenderLock.notifyAll();
            }
            setBridgePresenterStatus("destroying-keep-bridge");
            stopFrameLoop();
            releasePipeline(false);
            super.onDestroy();
            return;
        }
        unregisterBridgeBackCallback();
        synchronized (vulkanRenderLock) {
            activityResumed = false;
            bridgeServerEnabled = false;
            vulkanRenderLock.notifyAll();
        }
        updateBridgeKeepAliveService();
        setBridgePresenterStatus("destroying");
        clearActiveBridgeOwnerIfThis();
        stopBridgeControlServer();
        stopFrameLoop();
        releasePipeline(true);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        boolean wasBridgeServerEnabled = bridgeServerEnabled;
        setIntent(intent);
        applyLaunchExtras(intent);
        restoreStickyExternalBridgeSessionIfNeeded("new-intent");
        becomeActiveBridgeOwner("new-intent");
        registerBridgeBackCallbackIfNeeded();
        if (wasBridgeServerEnabled && !bridgeServerEnabled) {
            stopBridgeControlServer();
        } else if (!wasBridgeServerEnabled && bridgeServerEnabled) {
            startBridgeControlServerIfNeeded();
        }
        claimBridgeInputFocus();
        updateOverlay();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
            claimBridgeInputFocus();
        }
    }

    @Override
    public void onBackPressed() {
        if (moveExternalBridgeTaskToBack("back")) {
            return;
        }
        super.onBackPressed();
    }

    private void registerBridgeBackCallbackIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || bridgeBackCallback != null
                || !shouldKeepExternalBridgeSession()) {
            return;
        }
        bridgeBackCallback = new OnBackInvokedCallback() {
            @Override
            public void onBackInvoked() {
                if (!moveExternalBridgeTaskToBack("back-invoked")) {
                    finish();
                }
            }
        };
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                bridgeBackCallback);
    }

    private void unregisterBridgeBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || bridgeBackCallback == null) {
            return;
        }
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(bridgeBackCallback);
        bridgeBackCallback = null;
    }

    private boolean moveExternalBridgeTaskToBack(String reason) {
        if (!shouldKeepExternalBridgeSession()) {
            return false;
        }
        restoreStickyExternalBridgeSessionIfNeeded(reason);
        setBridgePresenterStatus(
                presentLayer == null ? "background-wait-layer" : "background-ready");
        // Instead of moveTaskToBack (quits to Android home), launch HomeActivity
        Intent homeIntent = new Intent(this, HomeActivity.class);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(homeIntent);
        return true;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        setBridgePresenterStatus("surface-created");
        becomeActiveBridgeOwner("surface-created");
        configurePipeline(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int newWidth, int newHeight) {
        setBridgePresenterStatus("surface-changed");
        becomeActiveBridgeOwner("surface-changed");
        configurePipeline(holder);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        setBridgePresenterStatus("surface-destroyed");
        stopFrameLoop();
        releasePipeline();
        setBridgePresenterStatus("surface-destroyed");
    }

    private void applyLaunchExtras(Intent intent) {
        SharedPreferences launchPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean explicitBridgeSessionLaunch = intent != null
                && intent.hasExtra(EXTRA_BRIDGE_SERVER)
                && intent.getBooleanExtra(EXTRA_BRIDGE_SERVER, false)
                && intent.hasExtra(EXTRA_EXTERNAL_PRESENT_ONLY)
                && intent.getBooleanExtra(EXTRA_EXTERNAL_PRESENT_ONLY, false);
        boolean hasLaunchModeExtras = intent != null
                && (intent.hasExtra(EXTRA_AHB_CPU_PRODUCER)
                || intent.hasExtra(EXTRA_VULKAN_PRODUCER)
                || intent.hasExtra(EXTRA_BRIDGE_SERVER)
                || intent.hasExtra(EXTRA_EXTERNAL_PRESENT_ONLY)
                || intent.hasExtra(EXTRA_DIAGNOSTIC_PRODUCER)
                || intent.hasExtra(EXTRA_HIDE_OVERLAY));
        boolean preserveExisting = launchExtrasApplied && hasLaunchModeExtras;
        boolean stickyBridgeServerEnabled = launchPrefs.getBoolean(PREF_BRIDGE_SERVER, false);
        boolean stickyExternalPresentOnlyEnabled =
                launchPrefs.getBoolean(PREF_EXTERNAL_PRESENT_ONLY, false);
        boolean stickyHideOverlayEnabled = launchPrefs.getBoolean(PREF_HIDE_OVERLAY, false);
        ahbCpuProducerEnabled = readLaunchBooleanExtra(
                intent,
                EXTRA_AHB_CPU_PRODUCER,
                DEFAULT_AHB_CPU_PRODUCER_ENABLED,
                ahbCpuProducerEnabled,
                preserveExisting);
        vulkanProducerEnabled = readLaunchBooleanExtra(
                intent,
                EXTRA_VULKAN_PRODUCER,
                DEFAULT_VULKAN_PRODUCER_ENABLED,
                vulkanProducerEnabled,
                preserveExisting);
        bridgeServerEnabled = readLaunchBooleanExtra(
                intent,
                EXTRA_BRIDGE_SERVER,
                stickyBridgeServerEnabled,
                bridgeServerEnabled,
                preserveExisting);
        externalPresentOnlyEnabled = readLaunchBooleanExtra(
                intent,
                EXTRA_EXTERNAL_PRESENT_ONLY,
                stickyExternalPresentOnlyEnabled,
                externalPresentOnlyEnabled,
                preserveExisting);
        diagnosticProducerEnabled = readLaunchBooleanExtra(
                intent,
                EXTRA_DIAGNOSTIC_PRODUCER,
                false,
                diagnosticProducerEnabled,
                preserveExisting);
        hideOverlayEnabled = readLaunchBooleanExtra(
                intent,
                EXTRA_HIDE_OVERLAY,
                stickyHideOverlayEnabled,
                hideOverlayEnabled,
                preserveExisting);
        launchExtrasApplied = true;
        if (intent != null
                && (intent.hasExtra(EXTRA_BRIDGE_SERVER)
                || intent.hasExtra(EXTRA_EXTERNAL_PRESENT_ONLY)
                || intent.hasExtra(EXTRA_HIDE_OVERLAY))) {
            SharedPreferences.Editor editor = launchPrefs.edit();
            if (intent.hasExtra(EXTRA_BRIDGE_SERVER)) {
                editor.putBoolean(PREF_BRIDGE_SERVER, bridgeServerEnabled);
            }
            if (intent.hasExtra(EXTRA_EXTERNAL_PRESENT_ONLY)) {
                editor.putBoolean(PREF_EXTERNAL_PRESENT_ONLY, externalPresentOnlyEnabled);
            }
            if (intent.hasExtra(EXTRA_HIDE_OVERLAY)) {
                editor.putBoolean(PREF_HIDE_OVERLAY, hideOverlayEnabled);
            }
            editor.apply();
        }
        if (externalPresentOnlyEnabled) {
            bridgeServerEnabled = true;
            ahbCpuProducerEnabled = false;
            vulkanProducerEnabled = false;
            stopFrameLoop();
            producerStatusText = "producer: external-present-only waiting";
            if (explicitBridgeSessionLaunch) {
                resetExternalPresentSessionState("launch");
            }
        }
        if (!externalPresentOnlyEnabled
                && !ahbCpuProducerEnabled
                && !vulkanProducerEnabled
                && !diagnosticProducerEnabled) {
            producerStatusText = "producer: idle waiting for Wayland";
        }
        if (overlayView != null) {
            overlayView.setVisibility(hideOverlayEnabled ? View.GONE : View.VISIBLE);
        }
        if (!bridgeServerEnabled) {
            bridgeStatusText = "bridge: off";
            bridgeNativeStatusText = "native-bridge: off";
        }
        updateBridgeKeepAliveService();
    }

    private boolean readLaunchBooleanExtra(
            Intent intent,
            String name,
            boolean defaultValue,
            boolean currentValue,
            boolean preserveExisting) {
        if (intent != null && intent.hasExtra(name)) {
            return intent.getBooleanExtra(name, defaultValue);
        }
        return preserveExisting ? currentValue : defaultValue;
    }

    private void updateBridgeKeepAliveService() {
        try {
            if (bridgeOwnsExternalSession()) {
                BridgeKeepAliveService.start(this);
            } else {
                BridgeKeepAliveService.stop(this);
            }
        } catch (RuntimeException error) {
            bridgeStatusText = "bridge: keepalive error "
                    + compactBridgeText(error.getClass().getSimpleName());
        }
    }

    private static MainActivity getActiveBridgeOwner() {
        synchronized (ACTIVE_BRIDGE_OWNER_LOCK) {
            return activeBridgeOwnerRef.get();
        }
    }

    private boolean isActiveBridgeOwner() {
        return getActiveBridgeOwner() == this;
    }

    private void becomeActiveBridgeOwner(String reason) {
        MainActivity previousOwner;
        synchronized (ACTIVE_BRIDGE_OWNER_LOCK) {
            previousOwner = activeBridgeOwnerRef.get();
            if (previousOwner == this) {
                synchronized (vulkanRenderLock) {
                    vulkanRenderLock.notifyAll();
                }
                return;
            }
            activeBridgeOwnerRef = new WeakReference<>(this);
        }

        if (previousOwner != null) {
            previousOwner.onBridgeOwnerSuperseded(reason);
        }
        synchronized (vulkanRenderLock) {
            vulkanRenderLock.notifyAll();
        }
        if (bridgeOwnsExternalSession() || shouldKeepExternalBridgeSession()) {
            restoreStickyExternalBridgeSessionIfNeeded("owner-" + reason);
            setBridgePresenterStatus(
                    presentLayer == null ? "active-wait-layer" : "active-ready");
        }
    }

    private void onBridgeOwnerSuperseded(String reason) {
        setBridgePresenterStatus("superseded-" + reason);
        synchronized (vulkanRenderLock) {
            vulkanRenderLock.notifyAll();
        }
    }

    private void clearActiveBridgeOwnerIfThis() {
        synchronized (ACTIVE_BRIDGE_OWNER_LOCK) {
            if (activeBridgeOwnerRef.get() == this) {
                activeBridgeOwnerRef = new WeakReference<>(null);
            }
        }
        synchronized (vulkanRenderLock) {
            vulkanRenderLock.notifyAll();
        }
    }

    private void resetExternalPresentSessionState(String reason) {
        String releaseStatusBeforeReset = lastAhbReleaseStatus;
        releaseOutstandingAhbFramesForTeardown();
        resetNativeAhbVkProducer();
        if (lastAhbReleaseStatus.equals(releaseStatusBeforeReset)
                || lastAhbReleaseStatus.startsWith("ahb-vk release ")
                || lastAhbReleaseStatus.startsWith("ahb-cpu release ")) {
            lastAhbReleaseStatus = "ahb-vk reset " + reason;
        }
        String fields = String.format(
                Locale.US,
                "status=reset reason=%s presenter=%s driver-status=%s",
                bridgeValueToken(reason),
                bridgeValueToken(bridgePresenterStatusText),
                bridgeValueToken(bridgeDmaBufDriverStatusText));
        bridgeDmaBufPresentStatusText = "dmabuf-present: " + fields;
        bridgeAhbPresentProbeStatusText = "dmabuf-present: " + fields;
    }

    private void startBridgeControlServerIfNeeded() {
        if (!bridgeServerEnabled) {
            bridgeStatusText = "bridge: off";
            bridgeNativeStatusText = "native-bridge: off";
            return;
        }

        synchronized (bridgeLock) {
            if (bridgeControlServer != null) {
                if (bridgeLocalServer == null || !bridgeLocalServer.isAlive()) {
                    bridgeLocalServer = new BridgeLocalServer();
                    bridgeLocalServer.start();
                }
                return;
            }
            bridgeStatusText = String.format(
                    Locale.US,
                    "bridge: tcp 127.0.0.1:%d starting",
                    BRIDGE_CONTROL_PORT);
            bridgeNativeStatusText = String.format(
                    Locale.US,
                    "native-bridge: unix-abstract %s starting",
                    BRIDGE_LOCAL_SOCKET_NAME);
            bridgeControlServer = new BridgeControlServer();
            bridgeControlServer.start();
            bridgeLocalServer = new BridgeLocalServer();
            bridgeLocalServer.start();
        }
    }

    private void startBridgeLocalServerIfNeeded() {
        if (!bridgeServerEnabled) {
            return;
        }
        synchronized (bridgeLock) {
            if (bridgeControlServer == null) {
                return;
            }
            if (bridgeLocalServer == null || !bridgeLocalServer.isAlive()) {
                bridgeLocalServer = new BridgeLocalServer();
                bridgeLocalServer.start();
            }
        }
    }

    private void stopBridgeControlServer() {
        BridgeControlServer serverToStop;
        BridgeLocalServer localServerToStop;
        synchronized (bridgeLock) {
            serverToStop = bridgeControlServer;
            localServerToStop = bridgeLocalServer;
            bridgeControlServer = null;
            bridgeLocalServer = null;
            bridgeStatusText = bridgeServerEnabled ? "bridge: enabled stopped" : "bridge: off";
            bridgeNativeStatusText = bridgeServerEnabled
                    ? "native-bridge: enabled stopped"
                    : "native-bridge: off";
        }
        if (serverToStop != null) {
            serverToStop.stop();
        }
        if (localServerToStop != null) {
            localServerToStop.stop();
        }
    }

    private void handleBridgeClient(Socket socket) {
        try (Socket client = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     client.getInputStream(),
                     StandardCharsets.UTF_8));
             OutputStreamWriter writer = new OutputStreamWriter(
                     client.getOutputStream(),
                     StandardCharsets.UTF_8)) {
            client.setSoTimeout(2000);
            String command = reader.readLine();
            String response = handleBridgeCommand(
                    command,
                    String.valueOf(client.getRemoteSocketAddress()),
                    null,
                    null,
                    null);
            writer.write(response);
            writer.write('\n');
            writer.flush();
        } catch (IOException error) {
            bridgeStatusText = "bridge: client error "
                    + compactBridgeText(error.getClass().getSimpleName());
        }
    }

    private void handleBridgeLocalClient(LocalSocket socket) {
        try (LocalSocket client = socket;
             OutputStreamWriter writer = new OutputStreamWriter(
                     client.getOutputStream(),
                     StandardCharsets.UTF_8)) {
            client.setSoTimeout(0);
            InputStream input = client.getInputStream();
            while (true) {
                String command = readBridgeCommandLine(input);
                if (command == null) {
                    break;
                }
                if (isBridgeInputStreamCommand(command)) {
                    handleBridgeInputStream(writer, input);
                    break;
                }
                FileDescriptor localSocketFileDescriptor = client.getFileDescriptor();
                FileDescriptor[] ancillaryFileDescriptors = client.getAncillaryFileDescriptors();
                FileInputStream[] ancillaryStreams =
                        openAncillaryFileDescriptorStreams(ancillaryFileDescriptors);
                String response;
                try {
                    response = handleBridgeCommand(
                            command,
                            "unix-abstract:" + BRIDGE_LOCAL_SOCKET_NAME,
                            ancillaryStreams,
                            localSocketFileDescriptor,
                            input);
                } finally {
                    closeAncillaryFileDescriptorStreams(ancillaryStreams);
                    closeAncillaryFileDescriptors(ancillaryFileDescriptors);
                }
                writer.write(response);
                writer.write('\n');
                writer.flush();
            }
        } catch (IOException error) {
            bridgeNativeStatusText = "native-bridge: client error "
                    + compactBridgeText(error.getClass().getSimpleName());
        }
    }

    private static boolean isBridgeInputStreamCommand(String command) {
        if (command == null) {
            return false;
        }
        String trimmed = command.trim();
        int space = trimmed.indexOf(' ');
        String commandName = space < 0 ? trimmed : trimmed.substring(0, space);
        return "input-stream".equals(commandName.toLowerCase(Locale.US));
    }

    private void handleBridgeInputStream(OutputStreamWriter writer, InputStream input)
            throws IOException {
        BridgeInputStream stream = new BridgeInputStream(writer);
        synchronized (bridgeInputStreamLock) {
            bridgeInputStreams.add(stream);
        }
        bridgeNativeStatusText = "native-bridge: input-stream attached";
        writer.write("waylandie-bridge input-stream status=pass protocol=input-v1\n");
        writer.flush();
        try {
            while (true) {
                String line = readBridgeInputStreamLine(input);
                if (line == null) {
                    break;
                }
                handleBridgeInputStreamMessage(line);
            }
        } finally {
            synchronized (bridgeInputStreamLock) {
                bridgeInputStreams.remove(stream);
            }
            bridgeNativeStatusText = "native-bridge: input-stream detached";
        }
    }

    private String readBridgeInputStreamLine(InputStream input) throws IOException {
        StringBuilder builder = new StringBuilder(256);
        boolean truncated = false;
        while (true) {
            int next = input.read();
            if (next < 0) {
                if (builder.length() == 0 && !truncated) {
                    return null;
                }
                break;
            }
            if (next == '\n') {
                break;
            }
            if (next == '\r') {
                continue;
            }
            if (builder.length() < 65536) {
                builder.append((char) next);
            } else {
                truncated = true;
            }
        }
        return truncated ? "" : builder.toString();
    }

    private String readBridgeCommandLine(InputStream input) throws IOException {
        StringBuilder builder = new StringBuilder(64);
        while (builder.length() < 512) {
            int next = input.read();
            if (next < 0) {
                if (builder.length() == 0) {
                    return null;
                }
                break;
            }
            if (next == '\n') {
                break;
            }
            if (next != '\r') {
                builder.append((char) next);
            }
        }
        return builder.toString();
    }

    private void handleBridgeInputStreamMessage(String line) {
        if (line == null || !line.startsWith("input-v1 ")) {
            return;
        }
        String kind = bridgeLineToken(line, "kind");
        if (!"clipboard".equals(kind)) {
            return;
        }
        String action = bridgeLineToken(line, "action");
        if ("set".equals(action)) {
            String textHex = bridgeLineToken(line, "text_hex");
            String selection = bridgeLineToken(line, "selection");
            String text = decodeBridgeUtf8Hex(textHex);
            if (text == null || text.isEmpty()) {
                inputStatusText = "input: clipboard empty";
                return;
            }
            mainHandler.post(() -> setAndroidClipboardFromBridge(text, selection));
        } else if ("empty".equals(action)) {
            inputStatusText = "input: clipboard empty";
        } else if ("fail".equals(action)) {
            String reason = bridgeLineToken(line, "reason");
            inputStatusText = "input: clipboard fail "
                    + compactBridgeText(reason == null ? "unknown" : reason);
        }
    }

    private void setAndroidClipboardFromBridge(String text, String selection) {
        ClipboardManager clipboardManager =
                (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboardManager == null) {
            inputStatusText = "input: clipboard fail no-manager";
            return;
        }
        clipboardManager.setPrimaryClip(ClipData.newPlainText("Steam", text));
        inputStatusText = String.format(
                Locale.US,
                "input: clipboard copied chars=%d selection=%s",
                text.length(),
                selection == null || selection.isEmpty() ? "auto" : selection);
    }

    private static String bridgeLineToken(String line, String key) {
        if (line == null || key == null || key.isEmpty()) {
            return null;
        }
        int keyLength = key.length();
        int index = 0;
        while (index < line.length()) {
            while (index < line.length()) {
                char current = line.charAt(index);
                if (current != ' ' && current != '\t') {
                    break;
                }
                index++;
            }
            if (index + keyLength < line.length()
                    && line.startsWith(key, index)
                    && line.charAt(index + keyLength) == '=') {
                int valueStart = index + keyLength + 1;
                int valueEnd = valueStart;
                while (valueEnd < line.length()) {
                    char current = line.charAt(valueEnd);
                    if (current == ' ' || current == '\t') {
                        break;
                    }
                    valueEnd++;
                }
                return line.substring(valueStart, valueEnd);
            }
            while (index < line.length()) {
                char current = line.charAt(index);
                if (current == ' ' || current == '\t') {
                    break;
                }
                index++;
            }
        }
        return null;
    }

    private static String decodeBridgeUtf8Hex(String hex) {
        if (hex == null || hex.isEmpty() || (hex.length() & 1) != 0) {
            return null;
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String handleBridgeCommand(
            String command,
            String remoteAddress,
            FileInputStream[] ancillaryStreams,
            FileDescriptor localSocketFileDescriptor,
            InputStream localSocketInput) {
        String rawCommand = command == null ? "" : command.trim();
        if (!rawCommand.isEmpty() && rawCommand.charAt(0) == '\ufeff') {
            rawCommand = rawCommand.substring(1).trim();
        }
        String normalized = rawCommand.toLowerCase(Locale.US);
        int commandNameEnd = normalized.indexOf(' ');
        String commandName = commandNameEnd < 0
                ? normalized
                : normalized.substring(0, commandNameEnd);
        MainActivity activeOwner = getActiveBridgeOwner();
        if (activeOwner != null && activeOwner != this) {
            return activeOwner.handleBridgeCommand(
                    command,
                    remoteAddress,
                    ancillaryStreams,
                    localSocketFileDescriptor,
                    localSocketInput);
        }
        if (!String.valueOf(remoteAddress).startsWith("unix-abstract:")
                && ("native".equals(commandName)
                        || "status".equals(commandName)
                        || "caps".equals(commandName)
                        || "contract".equals(commandName)
                        || "buffers".equals(commandName))) {
            startBridgeLocalServerIfNeeded();
        }
        long sequence;
        synchronized (bridgeLock) {
            sequence = ++bridgeSequence;
        }

        String response;
        if ("hello".equals(commandName)) {
            response = String.format(
                    Locale.US,
                    "waylandie-bridge hello version=%d mode=control+graphics-contract",
                    BRIDGE_PROTOCOL_VERSION);
        } else if ("caps".equals(commandName)) {
            response = String.format(
                    Locale.US,
                    "waylandie-bridge caps version=%d transport=tcp-loopback native-transport=unix-abstract native-socket=%s transport-next=unix-socket commands=%s features=buffer-meta,compositor-endpoint,android-multi-window,sync-placeholder,adrenotools-loader,fdtest,syncfd-test,dmabuf-test,dmabuf-meta,dmabuf-import-probe,dmabuf-present,kgsl-import-probe,ahb-export-probe,ahb-present-probe,ahb-ring-probe,fd-future producer=dmabuf-present-vulkan contract=buffer-meta-only compositor=android-presenter-endpoint windows=activity-per-toplevel fd-passing=fdtest,syncfd-test,dmabuf-test,dmabuf-meta,dmabuf-import-probe,dmabuf-present,kgsl-import-probe,ahb-export-probe,ahb-present-probe,ahb-ring-probe graphics-fd-passing=adrenotools-loader,kgsl-import-probe,dmabuf-image-import,dmabuf-present-gpu,ahb-vk-target buffer=fd-future sync=eventfd-control-probe layer=%dx%d final-copy=forbidden",
                    BRIDGE_PROTOCOL_VERSION,
                    BRIDGE_LOCAL_SOCKET_NAME,
                    BRIDGE_COMMANDS,
                    width,
                    height);
        } else if ("display".equals(commandName)) {
            response = String.format(
                    Locale.US,
                    "waylandie-bridge display layer=%dx%d refresh=%.1f fps=%.1f frame=%d format=%s current-usage=0x%x target-usage=0x%x producer=%s release=%s presenter=%s driver=%s dmabuf-present=%s",
                    width,
                    height,
                    getDisplayRefreshRate(),
                    measuredFps,
                    frameIndex,
                    BRIDGE_PIXEL_FORMAT_NAME,
                    BRIDGE_CURRENT_AHB_USAGE,
                    BRIDGE_TARGET_GPU_AHB_USAGE,
                    bridgeValueToken(producerStatusText),
                    bridgeValueToken(lastAhbReleaseStatus),
                    bridgeValueToken(bridgePresenterStatusText),
                    bridgeValueToken(bridgeDmaBufDriverStatusText),
                    bridgeValueToken(bridgeDmaBufPresentStatusText));
        } else if ("vulkan".equals(commandName)) {
            response = "waylandie-bridge vulkan " + compactBridgeText(getVulkanStatusText());
        } else if ("adrenotools".equals(commandName)) {
            response = "waylandie-bridge adrenotools " + handleBridgeAdrenoToolsProbe(rawCommand);
        } else if ("contract".equals(commandName)) {
            String contractFields = buildBridgeContractFields();
            bridgeContractStatusText = "contract: " + contractFields;
            response = "waylandie-bridge contract " + contractFields;
        } else if ("buffers".equals(commandName)) {
            String bufferFields = buildBridgeBufferFields();
            bridgeBufferStatusText = "buffers: " + bufferFields;
            response = "waylandie-bridge buffers " + bufferFields;
        } else if ("sync".equals(commandName)) {
            response = "waylandie-bridge sync " + buildBridgeSyncFields();
        } else if ("native".equals(commandName)) {
            response = "waylandie-bridge native " + buildBridgeNativeFields();
        } else if ("compositor".equals(commandName)) {
            String fields = buildBridgeCompositorFields("advertise", rawCommand);
            bridgeCompositorStatusText = "compositor: " + fields;
            response = "waylandie-bridge compositor " + fields;
        } else if ("compositor-open".equals(commandName)) {
            String fields = buildBridgeCompositorFields("open", rawCommand);
            bridgeCompositorStatusText = "compositor: " + fields;
            response = "waylandie-bridge compositor-open " + fields;
        } else if ("compositor-status".equals(commandName)) {
            response = "waylandie-bridge compositor-status "
                    + compactBridgeText(bridgeCompositorStatusText);
        } else if ("window-add".equals(commandName)) {
            response = "waylandie-bridge window-add "
                    + handleBridgeWindowAdd(rawCommand);
        } else if ("window-remove".equals(commandName)) {
            response = "waylandie-bridge window-remove "
                    + handleBridgeWindowRemove(rawCommand);
        } else if ("window-status".equals(commandName)) {
            String fields = WindowBridgeRegistry.status();
            bridgeWindowStatusText = "windows: " + fields;
            response = "waylandie-bridge window-status " + fields;
        } else if ("fdtest".equals(commandName)) {
            response = "waylandie-bridge fdtest "
                    + handleBridgeFdTest(ancillaryStreams);
        } else if ("syncfd-test".equals(commandName)) {
            response = "waylandie-bridge syncfd-test "
                    + handleBridgeSyncFdTest(ancillaryStreams);
        } else if ("dmabuf-test".equals(commandName)) {
            response = "waylandie-bridge dmabuf-test "
                    + handleBridgeDmaBufTest(ancillaryStreams);
        } else if ("dmabuf-meta".equals(commandName)) {
            response = "waylandie-bridge dmabuf-meta "
                    + handleBridgeDmaBufMeta(rawCommand, ancillaryStreams);
        } else if ("dmabuf-import-probe".equals(commandName)) {
            response = "waylandie-bridge dmabuf-import-probe "
                    + handleBridgeDmaBufImportProbe(rawCommand, ancillaryStreams);
        } else if ("dmabuf-present".equals(commandName)) {
            response = "waylandie-bridge dmabuf-present "
                    + handleBridgeDmaBufPresent(rawCommand, ancillaryStreams);
        } else if ("kgsl-import-probe".equals(commandName)) {
            response = "waylandie-bridge kgsl-import-probe "
                    + handleBridgeKgslImportProbe(ancillaryStreams);
        } else if ("ahb-export-probe".equals(commandName)) {
            response = "waylandie-bridge ahb-export-probe "
                    + handleBridgeAhbExportProbe(localSocketFileDescriptor, ancillaryStreams);
        } else if ("ahb-present-probe".equals(commandName)) {
            response = "waylandie-bridge ahb-present-probe "
                    + handleBridgeAhbPresentProbe(
                            rawCommand,
                            localSocketFileDescriptor,
                            localSocketInput,
                            ancillaryStreams);
        } else if ("ahb-ring-probe".equals(commandName)) {
            response = "waylandie-bridge ahb-ring-probe "
                    + handleBridgeAhbRingProbe(
                            rawCommand,
                            localSocketFileDescriptor,
                            localSocketInput,
                            ancillaryStreams);
        } else if ("ping".equals(commandName)) {
            response = String.format(
                    Locale.US,
                    "waylandie-bridge pong version=%d frame=%d layer=%dx%d fps=%.1f",
                    BRIDGE_PROTOCOL_VERSION,
                    frameIndex,
                    width,
                    height,
                    measuredFps);
        } else if ("status".equals(commandName)) {
            response = String.format(
                    Locale.US,
                    "waylandie-bridge status %s presenter=%s dmabuf-driver=%s dmabuf-present=%s windows=%s",
                    compactBridgeText(lastDiagnosticSummary),
                    bridgeValueToken(bridgePresenterStatusText),
                    bridgeValueToken(bridgeDmaBufDriverStatusText),
                    bridgeValueToken(bridgeDmaBufPresentStatusText),
                    bridgeValueToken(bridgeWindowStatusText));
        } else if ("input".equals(commandName)) {
            response = "waylandie-bridge input " + compactBridgeText(inputStatusText);
        } else {
            response = "waylandie-bridge error unknown-command";
        }

        bridgeStatusText = String.format(
                Locale.US,
                "bridge: seq=%d cmd=%s remote=%s",
                sequence,
                rawCommand.isEmpty() ? "empty" : compactBridgeText(rawCommand),
                compactBridgeText(remoteAddress));
        return response;
    }

    private static FileInputStream[] openAncillaryFileDescriptorStreams(
            FileDescriptor[] ancillaryFileDescriptors) {
        if (ancillaryFileDescriptors == null || ancillaryFileDescriptors.length == 0) {
            return null;
        }

        FileInputStream[] ancillaryStreams = new FileInputStream[ancillaryFileDescriptors.length];
        for (int i = 0; i < ancillaryFileDescriptors.length; i++) {
            ancillaryStreams[i] = new FileInputStream(ancillaryFileDescriptors[i]);
        }
        return ancillaryStreams;
    }

    private static void closeAncillaryFileDescriptorStreams(FileInputStream[] ancillaryStreams) {
        if (ancillaryStreams == null) {
            return;
        }

        for (FileInputStream ancillaryStream : ancillaryStreams) {
            if (ancillaryStream == null) {
                continue;
            }
            try {
                ancillaryStream.close();
            } catch (IOException ignored) {
                // The app owns all received ancillary descriptors, so shutdown
                // is best-effort even if one close reports an I/O error.
            }
        }
    }

    private static void closeAncillaryFileDescriptors(FileDescriptor[] ancillaryFileDescriptors) {
        if (ancillaryFileDescriptors == null) {
            return;
        }

        for (FileDescriptor ancillaryFileDescriptor : ancillaryFileDescriptors) {
            if (ancillaryFileDescriptor == null) {
                continue;
            }
            try {
                Os.close(ancillaryFileDescriptor);
            } catch (ErrnoException ignored) {
                // The stream wrapper may already have closed this received fd.
            }
        }
    }

    private String handleBridgeWindowAdd(String rawCommand) {
        String id = findBridgeMetaValue(rawCommand, "id");
        if (id == null || id.isEmpty()) {
            id = findBridgeMetaValue(rawCommand, "window");
        }
        if (id == null || id.isEmpty()) {
            id = "win-" + bridgeSequence;
        }
        if (!isBridgeWindowIdSafe(id)) {
            String fields = String.format(
                    Locale.US,
                    "status=fail reason=bad-id id=%s",
                    bridgeValueToken(id));
            bridgeWindowStatusText = "windows: " + fields;
            return fields;
        }

        int defaultOffset = (int) ((bridgeSequence % 5L) * 48L);
        int x = parseBridgeIntOption(rawCommand, "x", 80 + defaultOffset, 0, 4096);
        int y = parseBridgeIntOption(rawCommand, "y", 80 + defaultOffset, 0, 4096);
        int windowWidth = parseBridgeIntOption(rawCommand, "width", 960, 160, 8192);
        int windowHeight = parseBridgeIntOption(rawCommand, "height", 600, 120, 8192);
        String appId = decodeBridgeWindowText(findBridgeMetaValue(rawCommand, "app-id"));
        if (appId == null || appId.isEmpty()) {
            appId = decodeBridgeWindowText(findBridgeMetaValue(rawCommand, "app"));
        }
        String title = decodeBridgeWindowText(findBridgeMetaValue(rawCommand, "title"));
        if (title == null || title.isEmpty()) {
            title = appId == null || appId.isEmpty() ? id : appId;
        }
        WindowBridgeRegistry.WindowSpec spec = new WindowBridgeRegistry.WindowSpec(
                id,
                appId,
                title,
                x,
                y,
                windowWidth,
                windowHeight);
        String fields = WindowBridgeRegistry.openWindow(this, spec);
        bridgeWindowStatusText = "windows: " + fields;
        return fields;
    }

    private String handleBridgeWindowRemove(String rawCommand) {
        String id = findBridgeMetaValue(rawCommand, "id");
        if (id == null || id.isEmpty()) {
            id = findBridgeMetaValue(rawCommand, "window");
        }
        String fields = WindowBridgeRegistry.closeWindow(id);
        bridgeWindowStatusText = "windows: " + fields;
        return fields;
    }

    private WindowBridgeRegistry.WindowRecord waitForBridgeWindowReady(String id, long timeoutMs) {
        long deadlineMillis = System.currentTimeMillis() + timeoutMs;
        while (bridgeServerEnabled) {
            WindowBridgeRegistry.WindowRecord record = WindowBridgeRegistry.findWindow(id);
            if (record != null
                    && record.surfaceControl != null
                    && record.surfaceWidth > 0
                    && record.surfaceHeight > 0) {
                bridgeWindowStatusText = "windows: " + WindowBridgeRegistry.status();
                return record;
            }
            long remainingMillis = deadlineMillis - System.currentTimeMillis();
            if (remainingMillis <= 0L) {
                break;
            }
            try {
                Thread.sleep(Math.min(50L, remainingMillis));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        bridgeWindowStatusText = "windows: " + WindowBridgeRegistry.status();
        return WindowBridgeRegistry.findWindow(id);
    }

    private static boolean isBridgeWindowIdSafe(String id) {
        if (id == null || id.isEmpty() || id.length() > 96) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_'
                    || c == '.'
                    || c == ':')) {
                return false;
            }
        }
        return true;
    }

    private static String decodeBridgeWindowText(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '_') {
                builder.append(' ');
            } else if (c == '%' && i + 2 < value.length()) {
                int hi = Character.digit(value.charAt(i + 1), 16);
                int lo = Character.digit(value.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    builder.append((char) ((hi << 4) | lo));
                    i += 2;
                } else {
                    builder.append(c);
                }
            } else {
                builder.append(c);
            }
        }
        return compactBridgeText(builder.toString());
    }

    private String handleBridgeFdTest(FileInputStream[] ancillaryStreams) {
        int received = ancillaryStreams == null ? 0 : ancillaryStreams.length;
        if (received != 1) {
            String fields = String.format(
                    Locale.US,
                    "received=%d bytes=0 magic=%s status=fail",
                    received,
                    received == 0 ? "missing" : "unexpected-count");
            bridgeFdStatusText = "fdtest: " + fields;
            return fields;
        }

        byte[] buffer = new byte[64];
        int bytesRead;
        try {
            bytesRead = ancillaryStreams[0].read(buffer);
        } catch (IOException error) {
            String fields = String.format(
                    Locale.US,
                    "received=%d bytes=0 magic=read-error status=read-error error=%s",
                    received,
                    bridgeValueToken(error.getClass().getSimpleName()));
            bridgeFdStatusText = "fdtest: " + fields;
            return fields;
        }

        if (bytesRead < 0) {
            bytesRead = 0;
        }
        String payload = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
        String magic = BRIDGE_FDTEST_MAGIC.equals(payload) ? BRIDGE_FDTEST_MAGIC : "mismatch";
        String status = BRIDGE_FDTEST_MAGIC.equals(payload) ? "pass" : "fail";
        String fields = String.format(
                Locale.US,
                "received=%d bytes=%d magic=%s status=%s",
                received,
                bytesRead,
                bridgeValueToken(magic),
                status);
        bridgeFdStatusText = "fdtest: " + fields;
        return fields;
    }

    private String handleBridgeDmaBufTest(FileInputStream[] ancillaryStreams) {
        int received = ancillaryStreams == null ? 0 : ancillaryStreams.length;
        if (received != 1) {
            String kind = received == 0 ? "missing" : "unexpected-count";
            String fields = String.format(
                    Locale.US,
                    "received=%d kind=%s status=fail",
                    received,
                    kind);
            bridgeDmaBufStatusText = "dmabuf-test: " + fields;
            return fields;
        }

        BridgeFdInspection inspection = inspectBridgeFd(ancillaryStreams[0]);
        String fdTarget = inspection.fdTarget;
        long size = inspection.size;
        int mode = inspection.mode;
        String kind = classifyBridgeFdKind(fdTarget);
        String status = "dmabuf".equals(kind) ? "pass" : "fail";
        String fields = String.format(
                Locale.US,
                "received=1 kind=%s fd-target=%s size=%d mode=0x%x status=%s",
                kind,
                bridgeValueToken(fdTarget),
                size,
                mode,
                status);
        bridgeDmaBufStatusText = "dmabuf-test: " + fields;
        return fields;
    }

    private String handleBridgeSyncFdTest(FileInputStream[] ancillaryStreams) {
        int received = ancillaryStreams == null ? 0 : ancillaryStreams.length;
        if (received != 1) {
            String fields = String.format(
                    Locale.US,
                    "received=%d kind=%s status=fail",
                    received,
                    received == 0 ? "missing" : "unexpected-count");
            bridgeSyncFdStatusText = "syncfd-test: " + fields;
            return fields;
        }

        BridgeFdInspection inspection = inspectBridgeFd(ancillaryStreams[0]);
        String kind = classifyBridgeSyncFdKind(inspection.fdTarget);
        String status = "eventfd".equals(kind) || "sync-file".equals(kind) ? "pass" : "fail";
        String fields = String.format(
                Locale.US,
                "received=1 kind=%s fd-target=%s size=%d mode=0x%x status=%s",
                kind,
                bridgeValueToken(inspection.fdTarget),
                inspection.size,
                inspection.mode,
                status);
        bridgeSyncFdStatusText = "syncfd-test: " + fields;
        return fields;
    }

    private String handleBridgeDmaBufMeta(String command, FileInputStream[] ancillaryStreams) {
        int received = ancillaryStreams == null ? 0 : ancillaryStreams.length;
        if (received != 1) {
            String fields = String.format(
                    Locale.US,
                    "received=%d kind=%s status=fail",
                    received,
                    received == 0 ? "missing" : "unexpected-count");
            bridgeDmaBufMetaStatusText = "dmabuf-meta: " + fields;
            return fields;
        }

        BridgeFdInspection inspection = inspectBridgeFd(ancillaryStreams[0]);
        String kind = classifyBridgeFdKind(inspection.fdTarget);
        BridgeMetaNumber width = parseBridgeMetaNumber(command, "width");
        BridgeMetaNumber height = parseBridgeMetaNumber(command, "height");
        BridgeMetaNumber format = parseBridgeMetaNumber(command, "format");
        BridgeMetaNumber modifier = parseBridgeMetaNumber(command, "modifier");
        BridgeMetaNumber planes = parseBridgeMetaNumber(command, "planes");
        BridgeMetaNumber stride0 = parseBridgeMetaNumber(command, "stride0");
        BridgeMetaNumber offset0 = parseBridgeMetaNumber(command, "offset0");
        BridgeMetaNumber size = parseBridgeMetaNumber(command, "size");
        String status = "dmabuf".equals(kind)
                && isBridgeMetaGreaterThan(width, BRIDGE_META_ZERO)
                && isBridgeMetaGreaterThan(height, BRIDGE_META_ZERO)
                && isBridgeMetaInRangeInclusive(format, BRIDGE_META_ZERO, BRIDGE_META_UINT32_MAX)
                && isBridgeMetaInRangeInclusive(
                        modifier,
                        BRIDGE_META_ZERO,
                        BRIDGE_META_UINT64_MAX)
                && isBridgeMetaInRangeInclusive(planes, BRIDGE_META_ONE, BRIDGE_META_FOUR)
                && isBridgeMetaGreaterThan(stride0, BRIDGE_META_ZERO)
                && isBridgeMetaGreaterThanOrEqual(offset0, BRIDGE_META_ZERO)
                && isBridgeMetaGreaterThan(size, BRIDGE_META_ZERO)
                && isBridgeMetaSizeSufficient(size, offset0, stride0, height)
                ? "pass"
                : "fail";
        String fields = String.format(
                Locale.US,
                "received=1 kind=%s width=%s height=%s format=%s modifier=%s planes=%s stride0=%s offset0=%s size=%s fd-target=%s status=%s",
                kind,
                width.token,
                height.token,
                format.token,
                modifier.token,
                planes.token,
                stride0.token,
                offset0.token,
                size.token,
                bridgeValueToken(inspection.fdTarget),
                status);
        bridgeDmaBufMetaStatusText = "dmabuf-meta: " + fields;
        return fields;
    }

    private String handleBridgeDmaBufImportProbe(String command, FileInputStream[] ancillaryStreams) {
        int received = ancillaryStreams == null ? 0 : ancillaryStreams.length;
        if (received != 1) {
            String fields = String.format(
                    Locale.US,
                    "received=%d kind=%s status=fail",
                    received,
                    received == 0 ? "missing" : "unexpected-count");
            bridgeDmaBufImportProbeStatusText = "dmabuf-import-probe: " + fields;
            return fields;
        }

        BridgeFdInspection inspection = inspectBridgeFd(ancillaryStreams[0]);
        String kind = classifyBridgeFdKind(inspection.fdTarget);
        BridgeMetaNumber width = parseBridgeMetaNumber(command, "width");
        BridgeMetaNumber height = parseBridgeMetaNumber(command, "height");
        BridgeMetaNumber format = parseBridgeMetaNumber(command, "format");
        BridgeMetaNumber modifier = parseBridgeMetaNumber(command, "modifier");
        BridgeMetaNumber planes = parseBridgeMetaNumber(command, "planes");
        BridgeMetaNumber stride0 = parseBridgeMetaNumber(command, "stride0");
        BridgeMetaNumber offset0 = parseBridgeMetaNumber(command, "offset0");
        BridgeMetaNumber size = parseBridgeMetaNumber(command, "size");
        String requestedLoader = findBridgeMetaValue(command, "loader");
        String loader = requestedLoader == null || requestedLoader.isEmpty()
                ? null
                : requestedLoader;
        String driverName = findBridgeMetaValue(command, "driver");
        if (driverName == null || driverName.isEmpty()) {
            driverName = DEFAULT_ADRENOTOOLS_DRIVER_NAME;
        }
        File filesDir = getFilesDir();
        File tmpDir = new File(filesDir, "adrenotools-tmp");
        File driverDir = new File(filesDir, ADRENOTOOLS_DRIVER_DIR_NAME);
        File defaultDriver = new File(driverDir, driverName);
        if (loader == null) {
            loader = defaultDriver.isFile() ? "adrenotools" : "system";
        }
        boolean validMeta = isBridgeMetaGreaterThan(width, BRIDGE_META_ZERO)
                && isBridgeMetaGreaterThan(height, BRIDGE_META_ZERO)
                && isBridgeMetaInRangeInclusive(format, BRIDGE_META_ZERO, BRIDGE_META_UINT32_MAX)
                && isBridgeMetaInRangeInclusive(
                        modifier,
                        BRIDGE_META_ZERO,
                        BRIDGE_META_UINT64_MAX)
                && isBridgeMetaInRangeInclusive(planes, BRIDGE_META_ONE, BRIDGE_META_FOUR)
                && isBridgeMetaGreaterThan(stride0, BRIDGE_META_ZERO)
                && isBridgeMetaGreaterThanOrEqual(offset0, BRIDGE_META_ZERO)
                && isBridgeMetaGreaterThan(size, BRIDGE_META_ZERO)
                && isBridgeMetaSizeSufficient(size, offset0, stride0, height);
        String probeFields;
        if (!"dmabuf".equals(kind)) {
            probeFields = "probe=vulkan-dmabuf-import api=vkGetMemoryFdPropertiesKHR"
                    + " status=fail reason=not-dmabuf";
        } else if (!"system".equals(loader) && !"adrenotools".equals(loader)) {
            probeFields = String.format(
                    Locale.US,
                    "probe=vulkan-dmabuf-import api=vkGetMemoryFdPropertiesKHR loader=%s status=fail reason=bad-loader",
                    bridgeValueToken(loader));
        } else if (driverName.indexOf('/') >= 0 || driverName.indexOf('\\') >= 0) {
            probeFields = String.format(
                    Locale.US,
                    "probe=vulkan-dmabuf-import api=vkGetMemoryFdPropertiesKHR loader=%s status=fail reason=bad-driver-name driver=%s",
                    bridgeValueToken(loader),
                    bridgeValueToken(driverName));
        } else if (!validMeta) {
            probeFields = "probe=vulkan-dmabuf-import api=vkGetMemoryFdPropertiesKHR"
                    + " status=fail reason=invalid-meta";
        } else if (!bridgeMetaFitsSignedLong(width)
                || !bridgeMetaFitsSignedLong(height)
                || !bridgeMetaFitsSignedLong(format)
                || !bridgeMetaFitsSignedLong(modifier)
                || !bridgeMetaFitsSignedLong(stride0)
                || !bridgeMetaFitsSignedLong(offset0)
                || !bridgeMetaFitsSignedLong(size)) {
            probeFields = "probe=vulkan-dmabuf-import api=vkGetMemoryFdPropertiesKHR"
                    + " status=unsupported reason=meta-too-large";
        } else if (!nativeLibraryAvailable) {
            probeFields = "probe=vulkan-dmabuf-import api=vkGetMemoryFdPropertiesKHR"
                    + " status=unsupported reason=native-library";
        } else if ("adrenotools".equals(loader)
                && (!tmpDir.isDirectory() && !tmpDir.mkdirs())) {
            probeFields = String.format(
                    Locale.US,
                    "probe=vulkan-dmabuf-import api=vkGetMemoryFdPropertiesKHR loader=adrenotools status=fail reason=tmp-dir path=%s",
                    bridgeValueToken(tmpDir.getAbsolutePath()));
        } else if ("adrenotools".equals(loader)
                && (!driverDir.isDirectory() && !driverDir.mkdirs())) {
            probeFields = String.format(
                    Locale.US,
                    "probe=vulkan-dmabuf-import api=vkGetMemoryFdPropertiesKHR loader=adrenotools status=fail reason=driver-dir path=%s",
                    bridgeValueToken(driverDir.getAbsolutePath()));
        } else {
            try (ParcelFileDescriptor duplicate =
                         ParcelFileDescriptor.dup(ancillaryStreams[0].getFD())) {
                probeFields = nativeProbeDmaBufImport(
                        duplicate.getFd(),
                        bridgeMetaToSignedLong(width),
                        bridgeMetaToSignedLong(height),
                        bridgeMetaToSignedLong(format),
                        bridgeMetaToSignedLong(modifier),
                        bridgeMetaToSignedInt(planes),
                        bridgeMetaToSignedLong(stride0),
                        bridgeMetaToSignedLong(offset0),
                        bridgeMetaToSignedLong(size),
                        loader,
                        tmpDir.getAbsolutePath(),
                        getApplicationInfo().nativeLibraryDir,
                        driverDir.getAbsolutePath(),
                        driverName);
            } catch (IOException error) {
                probeFields = String.format(
                        Locale.US,
                        "probe=vulkan-dmabuf-import api=vkGetMemoryFdPropertiesKHR status=fail reason=dup-%s",
                        bridgeValueToken(error.getClass().getSimpleName()));
            } catch (RuntimeException | UnsatisfiedLinkError error) {
                if (error instanceof UnsatisfiedLinkError) {
                    nativeLibraryAvailable = false;
                }
                probeFields = String.format(
                        Locale.US,
                        "probe=vulkan-dmabuf-import api=vkGetMemoryFdPropertiesKHR status=unsupported reason=native-%s",
                        bridgeValueToken(error.getClass().getSimpleName()));
            }
        }

        String fields = String.format(
                Locale.US,
                "received=1 kind=%s width=%s height=%s format=%s modifier=%s planes=%s stride0=%s offset0=%s size=%s fd-target=%s %s",
                kind,
                width.token,
                height.token,
                format.token,
                modifier.token,
                planes.token,
                stride0.token,
                offset0.token,
                size.token,
                bridgeValueToken(inspection.fdTarget),
                probeFields);
        bridgeDmaBufImportProbeStatusText = "dmabuf-import-probe: " + fields;
        return fields;
    }

    private String handleBridgeDmaBufPresent(String command, FileInputStream[] ancillaryStreams) {
        MainActivity activeOwner = getActiveBridgeOwner();
        if (activeOwner != null && activeOwner != this) {
            return activeOwner.handleBridgeDmaBufPresent(command, ancillaryStreams);
        }
        restoreStickyExternalBridgeSessionIfNeeded("dmabuf-present");
        long bridgePresentStartNanos = System.nanoTime();
        int received = ancillaryStreams == null ? 0 : ancillaryStreams.length;
        if (received != 1) {
            String fields = String.format(
                    Locale.US,
                    "received=%d kind=%s status=fail",
                    received,
                    received == 0 ? "missing" : "unexpected-count");
            bridgeDmaBufPresentStatusText = "dmabuf-present: " + fields;
            return fields;
        }

        boolean fastDmaBuf = "1".equals(findBridgeMetaValue(command, "fast"));
        BridgeFdInspection inspection = fastDmaBuf
                ? new BridgeFdInspection("dmabuf-fast", -1L, 0)
                : inspectBridgeFd(ancillaryStreams[0]);
        String kind = fastDmaBuf ? "dmabuf" : classifyBridgeFdKind(inspection.fdTarget);
        BridgeMetaNumber sourceWidth = parseBridgeMetaNumber(command, "width");
        BridgeMetaNumber sourceHeight = parseBridgeMetaNumber(command, "height");
        BridgeMetaNumber format = parseBridgeMetaNumber(command, "format");
        BridgeMetaNumber modifier = parseBridgeMetaNumber(command, "modifier");
        BridgeMetaNumber planes = parseBridgeMetaNumber(command, "planes");
        BridgeMetaNumber stride0 = parseBridgeMetaNumber(command, "stride0");
        BridgeMetaNumber offset0 = parseBridgeMetaNumber(command, "offset0");
        BridgeMetaNumber size = parseBridgeMetaNumber(command, "size");
        String driverName = findBridgeMetaValue(command, "driver");
        if (driverName == null || driverName.isEmpty()) {
            driverName = DEFAULT_ADRENOTOOLS_DRIVER_NAME;
        }
        String targetWindowId = findBridgeMetaValue(command, "window");
        if (targetWindowId == null || targetWindowId.isEmpty()) {
            targetWindowId = findBridgeMetaValue(command, "target-window");
        }
        boolean windowTarget = targetWindowId != null
                && !targetWindowId.isEmpty()
                && !"0".equals(targetWindowId)
                && !"fullscreen".equals(targetWindowId);
        File filesDir = getFilesDir();
        File tmpDir = new File(filesDir, "adrenotools-tmp");
        File driverDir = new File(filesDir, ADRENOTOOLS_DRIVER_DIR_NAME);

        boolean validMeta = isBridgeMetaGreaterThan(sourceWidth, BRIDGE_META_ZERO)
                && isBridgeMetaGreaterThan(sourceHeight, BRIDGE_META_ZERO)
                && isBridgeMetaInRangeInclusive(format, BRIDGE_META_ZERO, BRIDGE_META_UINT32_MAX)
                && isBridgeMetaInRangeInclusive(
                        modifier,
                        BRIDGE_META_ZERO,
                        BRIDGE_META_UINT64_MAX)
                && isBridgeMetaInRangeInclusive(planes, BRIDGE_META_ONE, BRIDGE_META_ONE)
                && isBridgeMetaGreaterThan(stride0, BRIDGE_META_ZERO)
                && isBridgeMetaGreaterThanOrEqual(offset0, BRIDGE_META_ZERO)
                && isBridgeMetaGreaterThan(size, BRIDGE_META_ZERO)
                && isBridgeMetaSizeSufficient(size, offset0, stride0, sourceHeight);
        SurfaceControl targetLayer;
        int renderWidth;
        int renderHeight;
        int pipelineGeneration;
        WindowBridgeRegistry.WindowRecord targetWindow = null;
        if (windowTarget && !isBridgeWindowIdSafe(targetWindowId)) {
            targetLayer = null;
            renderWidth = 0;
            renderHeight = 0;
            pipelineGeneration = -1;
        } else if (windowTarget) {
            targetWindow = waitForBridgeWindowReady(targetWindowId, BRIDGE_WINDOW_WAIT_TIMEOUT_MS);
            targetLayer = targetWindow == null ? null : targetWindow.surfaceControl;
            renderWidth = targetWindow == null ? 0 : targetWindow.surfaceWidth;
            renderHeight = targetWindow == null ? 0 : targetWindow.surfaceHeight;
            pipelineGeneration = -1;
        } else {
            synchronized (vulkanRenderLock) {
                targetLayer = presentLayer;
                renderWidth = width;
                renderHeight = height;
                pipelineGeneration = vulkanPipelineGeneration;
            }
        }
        String failReason = null;
        if (!"dmabuf".equals(kind)) {
            failReason = "not-dmabuf";
        } else if (!validMeta
                || !bridgeMetaFitsSignedLong(sourceWidth)
                || !bridgeMetaFitsSignedLong(sourceHeight)
                || !bridgeMetaFitsSignedLong(format)
                || !bridgeMetaFitsSignedLong(modifier)
                || !bridgeMetaFitsSignedLong(stride0)
                || !bridgeMetaFitsSignedLong(offset0)
                || !bridgeMetaFitsSignedLong(size)) {
            failReason = "invalid-meta";
        } else if (!nativeLibraryAvailable) {
            failReason = "native-library";
        } else if (windowTarget && !isBridgeWindowIdSafe(targetWindowId)) {
            failReason = "bad-window-id";
        } else if (windowTarget
                && (targetWindow == null
                || targetLayer == null
                || renderWidth <= 0
                || renderHeight <= 0)) {
            failReason = targetWindow == null ? "window-missing" : "window-surface-wait-timeout";
        }
        if (failReason == null
                && !windowTarget
                && (!isBridgePresenterUsable()
                        || targetLayer == null
                        || renderWidth <= 0
                        || renderHeight <= 0)) {
            String waitState = activityResumed ? "wait-layer" : "background-wait";
            if (waitForBridgePresenterReady(waitState, BRIDGE_PRESENTER_WAIT_TIMEOUT_MS)) {
                synchronized (vulkanRenderLock) {
                    targetLayer = presentLayer;
                    renderWidth = width;
                    renderHeight = height;
                    pipelineGeneration = vulkanPipelineGeneration;
                }
            } else {
                activeOwner = getActiveBridgeOwner();
                if (activeOwner != null && activeOwner != this) {
                    return activeOwner.handleBridgeDmaBufPresent(command, ancillaryStreams);
                }
                failReason = activityResumed || bridgeOwnsExternalSession()
                        ? "presenter-wait-timeout"
                        : "presenter-paused-timeout";
            }
        }
        if (failReason == null) {
            failReason = ensureBridgeDmaBufDriverReady(tmpDir, driverDir, driverName);
        }
        if (failReason != null) {
            String fields = String.format(
                    Locale.US,
                    "received=1 kind=%s window=%s width=%s height=%s format=%s modifier=%s planes=%s stride0=%s offset0=%s size=%s fd-target=%s status=fail reason=%s presenter=%s windows=%s driver-status=%s driver=%s",
                    kind,
                    bridgeValueToken(windowTarget ? targetWindowId : "fullscreen"),
                    sourceWidth.token,
                    sourceHeight.token,
                    format.token,
                    modifier.token,
                    planes.token,
                    stride0.token,
                    offset0.token,
                    size.token,
                    bridgeValueToken(inspection.fdTarget),
                    failReason,
                    bridgeValueToken(bridgePresenterStatusText),
                    bridgeValueToken(bridgeWindowStatusText),
                    bridgeValueToken(bridgeDmaBufDriverStatusText),
                    bridgeValueToken(driverName));
            bridgeDmaBufPresentStatusText = "dmabuf-present: " + fields;
            return fields;
        }

        long renderFrameIndex = frameIndex;
        bridgeDmaBufPresentStatusText = String.format(
                Locale.US,
                "dmabuf-present: received=1 kind=%s window=%s status=importing source=%sx%s target=%dx%d format=%s modifier=%s planes=%s stride0=%s offset0=%s size=%s fd-target=%s presenter=%s windows=%s driver-status=%s driver=%s",
                kind,
                bridgeValueToken(windowTarget ? targetWindowId : "fullscreen"),
                sourceWidth.token,
                sourceHeight.token,
                renderWidth,
                renderHeight,
                format.token,
                modifier.token,
                planes.token,
                stride0.token,
                offset0.token,
                size.token,
                bridgeValueToken(inspection.fdTarget),
                bridgeValueToken(bridgePresenterStatusText),
                bridgeValueToken(bridgeWindowStatusText),
                bridgeValueToken(bridgeDmaBufDriverStatusText),
                bridgeValueToken(driverName));
        String nativeProducerFields;
        try (ParcelFileDescriptor duplicate =
                     ParcelFileDescriptor.dup(ancillaryStreams[0].getFD())) {
            nativeProducerFields = nativePresentAhbVkDmaBufFrame(
                    targetLayer,
                    duplicate.getFd(),
                    bridgeMetaToSignedInt(sourceWidth),
                    bridgeMetaToSignedInt(sourceHeight),
                    bridgeMetaToSignedLong(format),
                    bridgeMetaToSignedLong(modifier),
                    bridgeMetaToSignedInt(planes),
                    bridgeMetaToSignedLong(stride0),
                    bridgeMetaToSignedLong(offset0),
                    bridgeMetaToSignedLong(size),
                    renderWidth,
                    renderHeight,
                    renderFrameIndex,
                    tmpDir.getAbsolutePath(),
                    getApplicationInfo().nativeLibraryDir,
                    driverDir.getAbsolutePath(),
                    driverName);
        } catch (IOException error) {
            String fields = String.format(
                    Locale.US,
                    "received=1 kind=%s window=%s status=fail reason=dup-%s fd-target=%s presenter=%s windows=%s driver-status=%s",
                    kind,
                    bridgeValueToken(windowTarget ? targetWindowId : "fullscreen"),
                    bridgeValueToken(error.getClass().getSimpleName()),
                    bridgeValueToken(inspection.fdTarget),
                    bridgeValueToken(bridgePresenterStatusText),
                    bridgeValueToken(bridgeWindowStatusText),
                    bridgeValueToken(bridgeDmaBufDriverStatusText));
            bridgeDmaBufPresentStatusText = "dmabuf-present: " + fields;
            return fields;
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            if (error instanceof UnsatisfiedLinkError) {
                nativeLibraryAvailable = false;
            }
            String fields = String.format(
                    Locale.US,
                    "received=1 kind=%s window=%s status=unsupported reason=native-present-%s fd-target=%s presenter=%s windows=%s driver-status=%s",
                    kind,
                    bridgeValueToken(windowTarget ? targetWindowId : "fullscreen"),
                    bridgeValueToken(error.getClass().getSimpleName()),
                    bridgeValueToken(inspection.fdTarget),
                    bridgeValueToken(bridgePresenterStatusText),
                    bridgeValueToken(bridgeWindowStatusText),
                    bridgeValueToken(bridgeDmaBufDriverStatusText));
            bridgeDmaBufPresentStatusText = "dmabuf-present: " + fields;
            return fields;
        }
        if (nativeProducerFields != null
                && nativeProducerFields.startsWith(
                "producer: dmabuf-vk-native-present frame ")) {
            frameIndex = renderFrameIndex + 1;
            setBridgePresenterStatus("presented-explicit-sync");
            updateBridgePresentStats(
                    bridgePresentStartNanos,
                    driverName,
                    sourceWidth.token + "x" + sourceHeight.token,
                    renderWidth + "x" + renderHeight,
                    windowTarget ? "gpu-dmabuf-window-explicit-sync" : "gpu-dmabuf-explicit-sync");
            String fields = String.format(
                    Locale.US,
                    "received=1 kind=%s window=%s status=pass source=%sx%s target=%dx%d format=%s modifier=%s planes=%s stride0=%s offset0=%s size=%s fd-target=%s producer=%s present=surfacecontrol-vulkan-native zero-copy=gpu explicit-sync=surfacecontrol-acquire-fence%s presenter=%s windows=%s driver-status=%s driver=%s",
                    kind,
                    bridgeValueToken(windowTarget ? targetWindowId : "fullscreen"),
                    sourceWidth.token,
                    sourceHeight.token,
                    renderWidth,
                    renderHeight,
                    format.token,
                    modifier.token,
                    planes.token,
                    stride0.token,
                    offset0.token,
                    size.token,
                    bridgeValueToken(inspection.fdTarget),
                    bridgeValueToken(nativeProducerFields),
                    buildBridgeTimingFields(nativeProducerFields),
                    bridgeValueToken(bridgePresenterStatusText),
                    bridgeValueToken(bridgeWindowStatusText),
                    bridgeValueToken(bridgeDmaBufDriverStatusText),
                    bridgeValueToken(driverName));
            bridgeDmaBufPresentStatusText = "dmabuf-present: " + fields;
            bridgeAhbPresentProbeStatusText = "dmabuf-present: " + fields;
            producerStatusText = nativeProducerFields;
            return fields;
        }
        if (nativeProducerFields != null
                && !nativeProducerFields.startsWith(
                "producer: dmabuf-vk-native-present unsupported ")) {
            String fields = String.format(
                    Locale.US,
                    "received=1 kind=%s window=%s status=fail reason=native-present producer=%s fd-target=%s presenter=%s windows=%s driver-status=%s",
                    kind,
                    bridgeValueToken(windowTarget ? targetWindowId : "fullscreen"),
                    bridgeValueToken(nativeProducerFields),
                    bridgeValueToken(inspection.fdTarget),
                    bridgeValueToken(bridgePresenterStatusText),
                    bridgeValueToken(bridgeWindowStatusText),
                    bridgeValueToken(bridgeDmaBufDriverStatusText));
            bridgeDmaBufPresentStatusText = "dmabuf-present: " + fields;
            producerStatusText = nativeProducerFields;
            return fields;
        }
        if (fastDmaBuf || windowTarget) {
            String fields = String.format(
                    Locale.US,
                    "received=1 kind=%s window=%s status=fail reason=native-present-unsupported producer=%s fd-target=%s presenter=%s windows=%s driver-status=%s",
                    kind,
                    bridgeValueToken(windowTarget ? targetWindowId : "fullscreen"),
                    bridgeValueToken(nativeProducerFields),
                    bridgeValueToken(inspection.fdTarget),
                    bridgeValueToken(bridgePresenterStatusText),
                    bridgeValueToken(bridgeWindowStatusText),
                    bridgeValueToken(bridgeDmaBufDriverStatusText));
            bridgeDmaBufPresentStatusText = "dmabuf-present: " + fields;
            producerStatusText = nativeProducerFields;
            return fields;
        }

        AhbCpuFrame frame;
        try (ParcelFileDescriptor duplicate =
                     ParcelFileDescriptor.dup(ancillaryStreams[0].getFD())) {
            frame = nativeAcquireAhbVkDmaBufFrame(
                    duplicate.getFd(),
                    bridgeMetaToSignedInt(sourceWidth),
                    bridgeMetaToSignedInt(sourceHeight),
                    bridgeMetaToSignedLong(format),
                    bridgeMetaToSignedLong(modifier),
                    bridgeMetaToSignedInt(planes),
                    bridgeMetaToSignedLong(stride0),
                    bridgeMetaToSignedLong(offset0),
                    bridgeMetaToSignedLong(size),
                    renderWidth,
                    renderHeight,
                    renderFrameIndex,
                    tmpDir.getAbsolutePath(),
                    getApplicationInfo().nativeLibraryDir,
                    driverDir.getAbsolutePath(),
                    driverName);
        } catch (IOException error) {
            String fields = String.format(
                    Locale.US,
                    "received=1 kind=%s status=fail reason=dup-%s fd-target=%s presenter=%s driver-status=%s",
                    kind,
                    bridgeValueToken(error.getClass().getSimpleName()),
                    bridgeValueToken(inspection.fdTarget),
                    bridgeValueToken(bridgePresenterStatusText),
                    bridgeValueToken(bridgeDmaBufDriverStatusText));
            bridgeDmaBufPresentStatusText = "dmabuf-present: " + fields;
            return fields;
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            if (error instanceof UnsatisfiedLinkError) {
                nativeLibraryAvailable = false;
            }
            String fields = String.format(
                    Locale.US,
                    "received=1 kind=%s status=unsupported reason=native-%s fd-target=%s presenter=%s driver-status=%s",
                    kind,
                    bridgeValueToken(error.getClass().getSimpleName()),
                    bridgeValueToken(inspection.fdTarget),
                    bridgeValueToken(bridgePresenterStatusText),
                    bridgeValueToken(bridgeDmaBufDriverStatusText));
            bridgeDmaBufPresentStatusText = "dmabuf-present: " + fields;
            return fields;
        }

        String producerFields = frame == null || frame.status == null
                ? "producer:null"
                : frame.status;
        boolean validFrame = frame != null
                && producerFields.startsWith("producer: dmabuf-vk frame ")
                && frame.buffer != null
                && !frame.buffer.isClosed()
                && frame.slot >= 0;
        if (!validFrame) {
            if (frame != null) {
                closeUnsubmittedAhbFrame(frame, true);
            }
            String fields = String.format(
                    Locale.US,
                    "received=1 kind=%s status=fail reason=producer producer=%s fd-target=%s presenter=%s driver-status=%s",
                    kind,
                    bridgeValueToken(producerFields),
                    bridgeValueToken(inspection.fdTarget),
                    bridgeValueToken(bridgePresenterStatusText),
                    bridgeValueToken(bridgeDmaBufDriverStatusText));
            bridgeDmaBufPresentStatusText = "dmabuf-present: " + fields;
            return fields;
        }

        AhbInFlightFrame inFlightFrame = new AhbInFlightFrame(
                frame.buffer,
                frame.slot,
                frame.generation,
                true);
        synchronized (ahbInFlightLock) {
            inFlightAhbFrames.addLast(inFlightFrame);
        }
        bridgeDmaBufPresentStatusText = String.format(
                Locale.US,
                "dmabuf-present: received=1 kind=%s status=presenting slot=%d generation=%d producer=%s presenter=%s driver-status=%s driver=%s",
                kind,
                frame.slot,
                frame.generation,
                bridgeValueToken(producerFields),
                bridgeValueToken(bridgePresenterStatusText),
                bridgeValueToken(bridgeDmaBufDriverStatusText),
                bridgeValueToken(driverName));

        boolean stalePresenter = false;
        try {
            boolean presenterNeedsRefresh;
            synchronized (vulkanRenderLock) {
                presenterNeedsRefresh = !isBridgePresenterUsableLocked()
                        || isStaleVulkanRenderLocked(targetLayer, pipelineGeneration);
            }
            if (presenterNeedsRefresh) {
                String waitState = activityResumed ? "stale-wait" : "background-stale-wait";
                if (waitForBridgePresenterReady(waitState, BRIDGE_PRESENTER_WAIT_TIMEOUT_MS)) {
                    synchronized (vulkanRenderLock) {
                        targetLayer = presentLayer;
                        renderWidth = width;
                        renderHeight = height;
                        pipelineGeneration = vulkanPipelineGeneration;
                    }
                } else {
                    activeOwner = getActiveBridgeOwner();
                    if (activeOwner != null && activeOwner != this) {
                        return activeOwner.handleBridgeDmaBufPresent(command, ancillaryStreams);
                    }
                    stalePresenter = true;
                }
            }
            synchronized (vulkanRenderLock) {
                stalePresenter = !isBridgePresenterUsableLocked()
                        || isStaleVulkanRenderLocked(targetLayer, pipelineGeneration);
                if (!stalePresenter) {
                    try (SurfaceControl.Transaction transaction =
                                 new SurfaceControl.Transaction()) {
                        transaction
                                .setBuffer(
                                        targetLayer,
                                        frame.buffer,
                                        null,
                                        fence -> releaseAhbFrameWhenSafe(inFlightFrame, fence))
                                .setLayer(targetLayer, 10)
                                .setVisibility(targetLayer, true)
                                .setOpaque(targetLayer, false)
                                .setAlpha(targetLayer, PRESENT_LAYER_COMPOSITION_NUDGE_ALPHA)
                                .setPosition(targetLayer, 0.0f, 0.0f)
                                .setBufferSize(targetLayer, renderWidth, renderHeight)
                                .setCrop(targetLayer, new Rect(0, 0, renderWidth, renderHeight))
                                .setDamageRegion(
                                        targetLayer,
                                        new Region(new Rect(0, 0, renderWidth, renderHeight)))
                                .apply();
                    }
                    frameIndex = renderFrameIndex + 1;
                }
            }
        } catch (RuntimeException error) {
            releaseUnsubmittedAhbFrame(inFlightFrame);
            String fields = String.format(
                    Locale.US,
                    "received=1 kind=%s status=fail reason=present-%s producer=%s fd-target=%s presenter=%s driver-status=%s",
                    kind,
                    bridgeValueToken(error.getClass().getSimpleName()),
                    bridgeValueToken(producerFields),
                    bridgeValueToken(inspection.fdTarget),
                    bridgeValueToken(bridgePresenterStatusText),
                    bridgeValueToken(bridgeDmaBufDriverStatusText));
            bridgeDmaBufPresentStatusText = "dmabuf-present: " + fields;
            return fields;
        }
        if (stalePresenter) {
            releaseUnsubmittedAhbFrame(inFlightFrame);
            setBridgePresenterStatus(activityResumed ? "stale-layer" : "paused");
            String fields = String.format(
                    Locale.US,
                    "received=1 kind=%s status=fail reason=stale-presenter producer=%s fd-target=%s presenter=%s driver-status=%s",
                    kind,
                    bridgeValueToken(producerFields),
                    bridgeValueToken(inspection.fdTarget),
                    bridgeValueToken(bridgePresenterStatusText),
                    bridgeValueToken(bridgeDmaBufDriverStatusText));
            bridgeDmaBufPresentStatusText = "dmabuf-present: " + fields;
            return fields;
        }

        setBridgePresenterStatus("presented");
        updateBridgePresentStats(
                bridgePresentStartNanos,
                driverName,
                sourceWidth.token + "x" + sourceHeight.token,
                renderWidth + "x" + renderHeight,
                "gpu-dmabuf");
        String fields = String.format(
                Locale.US,
                "received=1 kind=%s status=pass slot=%d generation=%d source=%sx%s target=%dx%d format=%s modifier=%s planes=%s stride0=%s offset0=%s size=%s fd-target=%s producer=%s present=surfacecontrol-vulkan zero-copy=gpu%s presenter=%s driver-status=%s driver=%s",
                kind,
                frame.slot,
                frame.generation,
                sourceWidth.token,
                sourceHeight.token,
                renderWidth,
                renderHeight,
                format.token,
                modifier.token,
                planes.token,
                stride0.token,
                offset0.token,
                size.token,
                bridgeValueToken(inspection.fdTarget),
                bridgeValueToken(producerFields),
                buildBridgeTimingFields(producerFields),
                bridgeValueToken(bridgePresenterStatusText),
                bridgeValueToken(bridgeDmaBufDriverStatusText),
                bridgeValueToken(driverName));
        bridgeDmaBufPresentStatusText = "dmabuf-present: " + fields;
        bridgeAhbPresentProbeStatusText = "dmabuf-present: " + fields;
        producerStatusText = producerFields;
        return fields;
    }

    private String handleBridgeKgslImportProbe(FileInputStream[] ancillaryStreams) {
        int received = ancillaryStreams == null ? 0 : ancillaryStreams.length;
        if (received != 1) {
            String fields = String.format(
                    Locale.US,
                    "received=%d kind=%s status=fail",
                    received,
                    received == 0 ? "missing" : "unexpected-count");
            bridgeKgslImportProbeStatusText = "kgsl-import-probe: " + fields;
            return fields;
        }

        BridgeFdInspection inspection = inspectBridgeFd(ancillaryStreams[0]);
        String kind = classifyBridgeFdKind(inspection.fdTarget);
        String probeFields;
        if (!"dmabuf".equals(kind)) {
            probeFields = "probe=kgsl-dmabuf-import status=fail reason=not-dmabuf";
        } else if (!nativeLibraryAvailable) {
            probeFields = "probe=kgsl-dmabuf-import status=unsupported reason=native-library";
        } else {
            try (ParcelFileDescriptor duplicate =
                         ParcelFileDescriptor.dup(ancillaryStreams[0].getFD())) {
                probeFields = nativeProbeKgslDmaBufImport(duplicate.getFd());
            } catch (IOException error) {
                probeFields = String.format(
                        Locale.US,
                        "probe=kgsl-dmabuf-import status=fail reason=dup-%s",
                        bridgeValueToken(error.getClass().getSimpleName()));
            } catch (RuntimeException | UnsatisfiedLinkError error) {
                if (error instanceof UnsatisfiedLinkError) {
                    nativeLibraryAvailable = false;
                }
                probeFields = String.format(
                        Locale.US,
                        "probe=kgsl-dmabuf-import status=unsupported reason=native-%s",
                        bridgeValueToken(error.getClass().getSimpleName()));
            }
        }

        String fields = String.format(
                Locale.US,
                "received=1 kind=%s fd-target=%s %s",
                kind,
                bridgeValueToken(inspection.fdTarget),
                probeFields);
        bridgeKgslImportProbeStatusText = "kgsl-import-probe: " + fields;
        return fields;
    }

    private String handleBridgeAdrenoToolsProbe(String rawCommand) {
        String driverName = findBridgeMetaValue(rawCommand, "driver");
        if (driverName == null || driverName.isEmpty()) {
            driverName = DEFAULT_ADRENOTOOLS_DRIVER_NAME;
        }
        File filesDir = getFilesDir();
        File tmpDir = new File(filesDir, "adrenotools-tmp");
        File driverDir = new File(filesDir, ADRENOTOOLS_DRIVER_DIR_NAME);
        if (driverName.indexOf('/') >= 0 || driverName.indexOf('\\') >= 0) {
            String fields = String.format(
                    Locale.US,
                    "probe=adrenotools-loader status=fail reason=bad-driver-name driver=%s",
                    bridgeValueToken(driverName));
            setBridgeDmaBufDriverStatus("fail", "bad-driver-name", driverName, driverDir);
            bridgeAdrenoToolsStatusText = "adrenotools: " + fields;
            return fields;
        }
        if (!nativeLibraryAvailable) {
            String fields = "probe=adrenotools-loader status=unsupported reason=native-library";
            setBridgeDmaBufDriverStatus("blocked", "native-library", driverName, driverDir);
            bridgeAdrenoToolsStatusText = "adrenotools: " + fields;
            return fields;
        }

        if (!tmpDir.isDirectory() && !tmpDir.mkdirs()) {
            String fields = String.format(
                    Locale.US,
                    "probe=adrenotools-loader status=fail reason=tmp-dir path=%s",
                    bridgeValueToken(tmpDir.getAbsolutePath()));
            setBridgeDmaBufDriverStatus("fail", "tmp-dir", driverName, driverDir);
            bridgeAdrenoToolsStatusText = "adrenotools: " + fields;
            return fields;
        }
        if (!driverDir.isDirectory() && !driverDir.mkdirs()) {
            String fields = String.format(
                    Locale.US,
                    "probe=adrenotools-loader status=fail reason=driver-dir path=%s",
                    bridgeValueToken(driverDir.getAbsolutePath()));
            setBridgeDmaBufDriverStatus("fail", "driver-dir", driverName, driverDir);
            bridgeAdrenoToolsStatusText = "adrenotools: " + fields;
            return fields;
        }
        if (!new File(driverDir, driverName).isFile()) {
            setBridgeDmaBufDriverStatus("fail", "driver-missing", driverName, driverDir);
        } else {
            setBridgeDmaBufDriverStatus("ready", null, driverName, driverDir);
        }

        try {
            String fields = nativeProbeAdrenoTools(
                    tmpDir.getAbsolutePath(),
                    getApplicationInfo().nativeLibraryDir,
                    driverDir.getAbsolutePath(),
                    driverName);
            bridgeAdrenoToolsStatusText = "adrenotools: " + fields;
            return fields;
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            if (error instanceof UnsatisfiedLinkError) {
                nativeLibraryAvailable = false;
            }
            String fields = String.format(
                    Locale.US,
                    "probe=adrenotools-loader status=unsupported reason=native-%s",
                    bridgeValueToken(error.getClass().getSimpleName()));
            setBridgeDmaBufDriverStatus("blocked", "native-" + error.getClass().getSimpleName(),
                    driverName, driverDir);
            bridgeAdrenoToolsStatusText = "adrenotools: " + fields;
            return fields;
        }
    }

    private String handleBridgeAhbExportProbe(
            FileDescriptor localSocketFileDescriptor,
            FileInputStream[] ancillaryStreams) {
        int received = ancillaryStreams == null ? 0 : ancillaryStreams.length;
        if (received != 0) {
            String fields = String.format(
                    Locale.US,
                    "received=%d status=fail reason=unexpected-ancillary",
                    received);
            bridgeAhbExportProbeStatusText = "ahb-export-probe: " + fields;
            return fields;
        }
        if (localSocketFileDescriptor == null) {
            String fields = "received=0 status=unsupported reason=missing-local-socket-fd";
            bridgeAhbExportProbeStatusText = "ahb-export-probe: " + fields;
            return fields;
        }
        if (!nativeLibraryAvailable) {
            String fields = "received=0 status=unsupported reason=native-library";
            bridgeAhbExportProbeStatusText = "ahb-export-probe: " + fields;
            return fields;
        }

        String probeFields;
        try (ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(localSocketFileDescriptor)) {
            probeFields = nativeProbeAhbExport(duplicate.getFd());
        } catch (IOException error) {
            probeFields = String.format(
                    Locale.US,
                    "status=fail reason=dup-%s",
                    bridgeValueToken(error.getClass().getSimpleName()));
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            if (error instanceof UnsatisfiedLinkError) {
                nativeLibraryAvailable = false;
            }
            probeFields = String.format(
                    Locale.US,
                    "status=unsupported reason=native-%s",
                    bridgeValueToken(error.getClass().getSimpleName()));
        }

        String fields = "received=0 " + probeFields;
        bridgeAhbExportProbeStatusText = "ahb-export-probe: " + fields;
        return fields;
    }

    private String handleBridgeAhbPresentProbe(
            String rawCommand,
            FileDescriptor localSocketFileDescriptor,
            InputStream localSocketInput,
            FileInputStream[] ancillaryStreams) {
        int received = ancillaryStreams == null ? 0 : ancillaryStreams.length;
        if (received != 0) {
            String fields = String.format(
                    Locale.US,
                    "received=%d status=fail reason=unexpected-ancillary",
                    received);
            bridgeAhbPresentProbeStatusText = "ahb-present-probe: " + fields;
            return fields;
        }
        if (localSocketFileDescriptor == null) {
            String fields = "received=0 status=unsupported reason=missing-local-socket-fd";
            bridgeAhbPresentProbeStatusText = "ahb-present-probe: " + fields;
            return fields;
        }
        if (!nativeLibraryAvailable) {
            String fields = "received=0 status=unsupported reason=native-library";
            bridgeAhbPresentProbeStatusText = "ahb-present-probe: " + fields;
            return fields;
        }

        SurfaceControl targetLayer = presentLayer;
        int renderWidth = width;
        int renderHeight = height;
        if (targetLayer == null || renderWidth <= 0 || renderHeight <= 0) {
            String fields = String.format(
                    Locale.US,
                    "received=0 status=fail reason=no-present-layer layer=%dx%d",
                    renderWidth,
                    renderHeight);
            bridgeAhbPresentProbeStatusText = "ahb-present-probe: " + fields;
            return fields;
        }

        AhbCpuFrame frame;
        long renderFrameIndex = frameIndex;
        try {
            frame = nativeAcquireAhbCpuFrame(
                    renderWidth,
                    renderHeight,
                    renderFrameIndex,
                    System.nanoTime());
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            if (error instanceof UnsatisfiedLinkError) {
                nativeLibraryAvailable = false;
            }
            String fields = String.format(
                    Locale.US,
                    "received=0 status=unsupported reason=acquire-%s",
                    bridgeValueToken(error.getClass().getSimpleName()));
            bridgeAhbPresentProbeStatusText = "ahb-present-probe: " + fields;
            return fields;
        }

        String producerFields = frame == null || frame.status == null
                ? "producer:null"
                : frame.status;
        boolean validFrame = frame != null
                && producerFields.startsWith("producer: ahb-cpu frame ")
                && frame.buffer != null
                && !frame.buffer.isClosed()
                && frame.slot >= 0;
        if (!validFrame) {
            if (frame != null) {
                closeUnsubmittedAhbFrame(frame);
            }
            String fields = String.format(
                    Locale.US,
                    "received=0 status=fail reason=acquire-frame producer=%s",
                    bridgeValueToken(producerFields));
            bridgeAhbPresentProbeStatusText = "ahb-present-probe: " + fields;
            return fields;
        }

        String exportFields;
        try (ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(localSocketFileDescriptor)) {
            exportFields = nativeExportAhbCpuSlot(
                    duplicate.getFd(),
                    frame.slot,
                    frame.generation);
        } catch (IOException error) {
            closeUnsubmittedAhbFrame(frame);
            String fields = String.format(
                    Locale.US,
                    "received=0 status=fail reason=dup-%s producer=%s",
                    bridgeValueToken(error.getClass().getSimpleName()),
                    bridgeValueToken(producerFields));
            bridgeAhbPresentProbeStatusText = "ahb-present-probe: " + fields;
            return fields;
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            if (error instanceof UnsatisfiedLinkError) {
                nativeLibraryAvailable = false;
            }
            closeUnsubmittedAhbFrame(frame);
            String fields = String.format(
                    Locale.US,
                    "received=0 status=unsupported reason=export-%s producer=%s",
                    bridgeValueToken(error.getClass().getSimpleName()),
                    bridgeValueToken(producerFields));
            bridgeAhbPresentProbeStatusText = "ahb-present-probe: " + fields;
            return fields;
        }

        if (exportFields == null || !exportFields.contains("status=pass")) {
            closeUnsubmittedAhbFrame(frame);
            String fields = String.format(
                    Locale.US,
                    "received=0 status=fail reason=export export=%s producer=%s",
                    bridgeValueToken(exportFields),
                    bridgeValueToken(producerFields));
            bridgeAhbPresentProbeStatusText = "ahb-present-probe: " + fields;
            return fields;
        }

        boolean ackRequested = rawCommand != null
                && rawCommand.toLowerCase(Locale.US).contains("ack=1");
        long writeWaitStartNanos = System.nanoTime();
        String writeWaitFields;
        if (ackRequested && localSocketInput != null) {
            try {
                String ackLine = readBridgeCommandLine(localSocketInput).trim();
                long writeWaitMs = (System.nanoTime() - writeWaitStartNanos) / 1_000_000L;
                if (!"present".equalsIgnoreCase(ackLine)) {
                    closeUnsubmittedAhbFrame(frame);
                    String fields = String.format(
                            Locale.US,
                            "received=0 status=fail reason=bad-ack ack=%s wait-ms=%d export=%s producer=%s",
                            bridgeValueToken(ackLine),
                            writeWaitMs,
                            bridgeValueToken(exportFields),
                            bridgeValueToken(producerFields));
                    bridgeAhbPresentProbeStatusText = "ahb-present-probe: " + fields;
                    return fields;
                }
                writeWaitFields = String.format(
                        Locale.US,
                        "ack=present wait-ms=%d",
                        writeWaitMs);
            } catch (IOException error) {
                closeUnsubmittedAhbFrame(frame);
                String fields = String.format(
                        Locale.US,
                        "received=0 status=fail reason=ack-%s export=%s producer=%s",
                        bridgeValueToken(error.getClass().getSimpleName()),
                        bridgeValueToken(exportFields),
                        bridgeValueToken(producerFields));
                bridgeAhbPresentProbeStatusText = "ahb-present-probe: " + fields;
                return fields;
            }
        } else {
            try {
                Thread.sleep(BRIDGE_AHB_PRESENT_WRITE_DELAY_MS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                closeUnsubmittedAhbFrame(frame);
                String fields = String.format(
                        Locale.US,
                        "received=0 status=fail reason=interrupted export=%s producer=%s",
                        bridgeValueToken(exportFields),
                        bridgeValueToken(producerFields));
                bridgeAhbPresentProbeStatusText = "ahb-present-probe: " + fields;
                return fields;
            }
            writeWaitFields = String.format(
                    Locale.US,
                    "ack=delay wait-ms=%d",
                    BRIDGE_AHB_PRESENT_WRITE_DELAY_MS);
        }

        AhbInFlightFrame inFlightFrame = new AhbInFlightFrame(
                frame.buffer,
                frame.slot,
                frame.generation,
                false);
        synchronized (ahbInFlightLock) {
            inFlightAhbFrames.addLast(inFlightFrame);
        }

        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction
                    .setBuffer(
                            targetLayer,
                            frame.buffer,
                            null,
                            fence -> releaseAhbFrameWhenSafe(inFlightFrame, fence))
                    .setLayer(targetLayer, 10)
                    .setVisibility(targetLayer, true)
                    .setOpaque(targetLayer, false)
                    .setAlpha(targetLayer, PRESENT_LAYER_COMPOSITION_NUDGE_ALPHA)
                    .setPosition(targetLayer, 0.0f, 0.0f)
                    .setBufferSize(targetLayer, renderWidth, renderHeight)
                    .setCrop(targetLayer, new Rect(0, 0, renderWidth, renderHeight))
                    .setDamageRegion(
                            targetLayer,
                            new Region(new Rect(0, 0, renderWidth, renderHeight)));
            setDesiredPresentTimeNanosReflectively(transaction, System.nanoTime());
            transaction.apply();
            bridgePresentHoldUntilNanos = System.nanoTime()
                    + ((long)BRIDGE_AHB_PRESENT_HOLD_MS * 1_000_000L);
            frameIndex = renderFrameIndex + 1;
        } catch (RuntimeException error) {
            releaseUnsubmittedAhbFrame(inFlightFrame);
            String fields = String.format(
                    Locale.US,
                    "received=0 status=fail reason=present-%s export=%s producer=%s",
                    bridgeValueToken(error.getClass().getSimpleName()),
                    bridgeValueToken(exportFields),
                    bridgeValueToken(producerFields));
            bridgeAhbPresentProbeStatusText = "ahb-present-probe: " + fields;
            return fields;
        }

        String fields = String.format(
                Locale.US,
                "received=0 status=pass slot=%d generation=%d %s hold-ms=%d export=%s producer=%s present=surfacecontrol",
                frame.slot,
                frame.generation,
                writeWaitFields,
                BRIDGE_AHB_PRESENT_HOLD_MS,
                bridgeValueToken(exportFields),
                bridgeValueToken(producerFields));
        bridgeAhbPresentProbeStatusText = "ahb-present-probe: " + fields;
        return fields;
    }

    private String handleBridgeAhbRingProbe(
            String rawCommand,
            FileDescriptor localSocketFileDescriptor,
            InputStream localSocketInput,
            FileInputStream[] ancillaryStreams) {
        int received = ancillaryStreams == null ? 0 : ancillaryStreams.length;
        if (received != 0) {
            String fields = String.format(
                    Locale.US,
                    "received=%d status=fail reason=unexpected-ancillary",
                    received);
            bridgeAhbPresentProbeStatusText = "ahb-ring-probe: " + fields;
            return fields;
        }
        if (localSocketFileDescriptor == null || localSocketInput == null) {
            String fields = "received=0 status=unsupported reason=missing-local-socket";
            bridgeAhbPresentProbeStatusText = "ahb-ring-probe: " + fields;
            return fields;
        }
        if (!nativeLibraryAvailable) {
            String fields = "received=0 status=unsupported reason=native-library";
            bridgeAhbPresentProbeStatusText = "ahb-ring-probe: " + fields;
            return fields;
        }

        SurfaceControl targetLayer = presentLayer;
        int renderWidth = width;
        int renderHeight = height;
        if (targetLayer == null || renderWidth <= 0 || renderHeight <= 0) {
            String fields = String.format(
                    Locale.US,
                    "received=0 status=fail reason=no-present-layer layer=%dx%d",
                    renderWidth,
                    renderHeight);
            bridgeAhbPresentProbeStatusText = "ahb-ring-probe: " + fields;
            return fields;
        }

        int requestedFrames = parseBridgeIntOption(
                rawCommand,
                "frames",
                BRIDGE_AHB_RING_DEFAULT_FRAMES,
                1,
                BRIDGE_AHB_RING_MAX_FRAMES);
        int passedFrames = 0;
        long totalAckWaitMs = 0L;
        long totalSessionStartNanos = System.nanoTime();
        String lastExportFields = "none";
        String lastProducerFields = "none";

        for (int frameNumber = 0; frameNumber < requestedFrames; frameNumber++) {
            AhbCpuFrame frame = null;
            long renderFrameIndex = frameIndex;
            String producerFields = "producer:null";
            boolean validFrame = false;
            int acquireAttempts = 0;
            long acquireDeadlineNanos = System.nanoTime() + 250_000_000L;
            while (!validFrame) {
                renderFrameIndex = frameIndex;
                acquireAttempts++;
                try {
                    frame = nativeAcquireAhbCpuFrame(
                            renderWidth,
                            renderHeight,
                            renderFrameIndex,
                            System.nanoTime());
                } catch (RuntimeException | UnsatisfiedLinkError error) {
                    if (error instanceof UnsatisfiedLinkError) {
                        nativeLibraryAvailable = false;
                    }
                    String fields = String.format(
                            Locale.US,
                            "received=0 status=unsupported reason=acquire-%s frames=%d/%d attempts=%d",
                            bridgeValueToken(error.getClass().getSimpleName()),
                            passedFrames,
                            requestedFrames,
                            acquireAttempts);
                    bridgeAhbPresentProbeStatusText = "ahb-ring-probe: " + fields;
                    return fields;
                }

                producerFields = frame == null || frame.status == null
                        ? "producer:null"
                        : frame.status;
                lastProducerFields = producerFields;
                validFrame = frame != null
                        && producerFields.startsWith("producer: ahb-cpu frame ")
                        && frame.buffer != null
                        && !frame.buffer.isClosed()
                        && frame.slot >= 0;
                if (validFrame) {
                    break;
                }

                if (frame != null) {
                    closeUnsubmittedAhbFrame(frame);
                    frame = null;
                }
                boolean canRetry = producerFields.contains("ring-busy")
                        && System.nanoTime() < acquireDeadlineNanos;
                if (!canRetry) {
                    String fields = String.format(
                            Locale.US,
                            "received=0 status=fail reason=acquire-frame frames=%d/%d attempts=%d producer=%s",
                            passedFrames,
                            requestedFrames,
                            acquireAttempts,
                            bridgeValueToken(producerFields));
                    bridgeAhbPresentProbeStatusText = "ahb-ring-probe: " + fields;
                    return fields;
                }
                try {
                    Thread.sleep(4L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    String fields = String.format(
                            Locale.US,
                            "received=0 status=fail reason=interrupted frames=%d/%d attempts=%d producer=%s",
                            passedFrames,
                            requestedFrames,
                            acquireAttempts,
                            bridgeValueToken(producerFields));
                    bridgeAhbPresentProbeStatusText = "ahb-ring-probe: " + fields;
                    return fields;
                }
            }

            String exportFields;
            try (ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(localSocketFileDescriptor)) {
                exportFields = nativeExportAhbCpuSlot(
                        duplicate.getFd(),
                        frame.slot,
                        frame.generation);
            } catch (IOException error) {
                closeUnsubmittedAhbFrame(frame);
                String fields = String.format(
                        Locale.US,
                        "received=0 status=fail reason=dup-%s frames=%d/%d producer=%s",
                        bridgeValueToken(error.getClass().getSimpleName()),
                        passedFrames,
                        requestedFrames,
                        bridgeValueToken(producerFields));
                bridgeAhbPresentProbeStatusText = "ahb-ring-probe: " + fields;
                return fields;
            } catch (RuntimeException | UnsatisfiedLinkError error) {
                if (error instanceof UnsatisfiedLinkError) {
                    nativeLibraryAvailable = false;
                }
                closeUnsubmittedAhbFrame(frame);
                String fields = String.format(
                        Locale.US,
                        "received=0 status=unsupported reason=export-%s frames=%d/%d producer=%s",
                        bridgeValueToken(error.getClass().getSimpleName()),
                        passedFrames,
                        requestedFrames,
                        bridgeValueToken(producerFields));
                bridgeAhbPresentProbeStatusText = "ahb-ring-probe: " + fields;
                return fields;
            }

            lastExportFields = exportFields;
            if (exportFields == null || !exportFields.contains("status=pass")) {
                closeUnsubmittedAhbFrame(frame);
                String fields = String.format(
                        Locale.US,
                        "received=0 status=fail reason=export frames=%d/%d export=%s producer=%s",
                        passedFrames,
                        requestedFrames,
                        bridgeValueToken(exportFields),
                        bridgeValueToken(producerFields));
                bridgeAhbPresentProbeStatusText = "ahb-ring-probe: " + fields;
                return fields;
            }

            long ackWaitStartNanos = System.nanoTime();
            String ackLine;
            try {
                ackLine = readBridgeCommandLine(localSocketInput).trim();
            } catch (IOException error) {
                closeUnsubmittedAhbFrame(frame);
                String fields = String.format(
                        Locale.US,
                        "received=0 status=fail reason=ack-%s frames=%d/%d export=%s producer=%s",
                        bridgeValueToken(error.getClass().getSimpleName()),
                        passedFrames,
                        requestedFrames,
                        bridgeValueToken(exportFields),
                        bridgeValueToken(producerFields));
                bridgeAhbPresentProbeStatusText = "ahb-ring-probe: " + fields;
                return fields;
            }
            long ackWaitMs = (System.nanoTime() - ackWaitStartNanos) / 1_000_000L;
            totalAckWaitMs += ackWaitMs;
            String normalizedAck = ackLine.toLowerCase(Locale.US);
            if (!normalizedAck.startsWith("present")) {
                closeUnsubmittedAhbFrame(frame);
                String fields = String.format(
                        Locale.US,
                        "received=0 status=fail reason=bad-ack ack=%s wait-ms=%d frames=%d/%d export=%s producer=%s",
                        bridgeValueToken(ackLine),
                        ackWaitMs,
                        passedFrames,
                        requestedFrames,
                        bridgeValueToken(exportFields),
                        bridgeValueToken(producerFields));
                bridgeAhbPresentProbeStatusText = "ahb-ring-probe: " + fields;
                return fields;
            }

            AhbInFlightFrame inFlightFrame = new AhbInFlightFrame(
                    frame.buffer,
                    frame.slot,
                    frame.generation,
                    false);
            synchronized (ahbInFlightLock) {
                inFlightAhbFrames.addLast(inFlightFrame);
            }

            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                transaction
                        .setBuffer(
                                targetLayer,
                                frame.buffer,
                                null,
                                fence -> releaseAhbFrameWhenSafe(inFlightFrame, fence))
                        .setDamageRegion(
                                targetLayer,
                                new Region(new Rect(0, 0, renderWidth, renderHeight)));
                        setDesiredPresentTimeNanosReflectively(transaction, System.nanoTime());
                        transaction.apply();
                bridgePresentHoldUntilNanos = System.nanoTime()
                        + ((long)BRIDGE_AHB_PRESENT_HOLD_MS * 1_000_000L);
                frameIndex = renderFrameIndex + 1;
            } catch (RuntimeException error) {
                releaseUnsubmittedAhbFrame(inFlightFrame);
                String fields = String.format(
                        Locale.US,
                        "received=0 status=fail reason=present-%s frames=%d/%d export=%s producer=%s",
                        bridgeValueToken(error.getClass().getSimpleName()),
                        passedFrames,
                        requestedFrames,
                        bridgeValueToken(exportFields),
                        bridgeValueToken(producerFields));
                bridgeAhbPresentProbeStatusText = "ahb-ring-probe: " + fields;
                return fields;
            }

            passedFrames++;
        }

        long elapsedMs = (System.nanoTime() - totalSessionStartNanos) / 1_000_000L;
        long avgAckWaitMs = passedFrames == 0 ? 0L : totalAckWaitMs / passedFrames;
        String fields = String.format(
                Locale.US,
                "received=0 status=pass frames=%d requested=%d avg-ack-wait-ms=%d elapsed-ms=%d last-export=%s last-producer=%s present=surfacecontrol session=single-socket",
                passedFrames,
                requestedFrames,
                avgAckWaitMs,
                elapsedMs,
                bridgeValueToken(lastExportFields),
                bridgeValueToken(lastProducerFields));
        bridgeAhbPresentProbeStatusText = "ahb-ring-probe: " + fields;
        return fields;
    }

    private String buildBridgeContractFields() {
        return String.format(
                Locale.US,
                "version=%d role=android-presenter producer=linux-wayland-compositor path=dmabuf-present-vulkan contract=buffer-meta-only compositor-endpoint=unix-abstract:%s compositor-command=compositor-open windows=activity-per-toplevel window-commands=window-add,window-remove,window-status transport-now=tcp-loopback transport-next=unix-socket-scm-rights fd-passing=fdtest,syncfd-test,dmabuf-test,dmabuf-meta,dmabuf-import-probe,dmabuf-present,kgsl-import-probe,ahb-export-probe,ahb-present-probe,ahb-ring-probe graphics-fd-passing=adrenotools-loader,kgsl-import-probe,dmabuf-image-import,dmabuf-present-gpu,ahb-vk-target final-copy=forbidden format=%s android-format=%d target-usage=0x%x current-usage=0x%x max-buffers=%d layer=%dx%d refresh=%.1f",
                BRIDGE_PROTOCOL_VERSION,
                BRIDGE_LOCAL_SOCKET_NAME,
                BRIDGE_PIXEL_FORMAT_NAME,
                BRIDGE_PIXEL_FORMAT,
                BRIDGE_TARGET_GPU_AHB_USAGE,
                BRIDGE_CURRENT_AHB_USAGE,
                IMAGE_READER_MAX_IMAGES,
                width,
                height,
                getDisplayRefreshRate());
    }

    private String buildBridgeBufferFields() {
        return String.format(
                Locale.US,
                "state=contract-only owner=android-presenter import=dmabuf-image-import-pass present=dmabuf-present-vulkan target=ahb-vk-pass linux-present=dmabuf-present fd-passing=fdtest,syncfd-test,dmabuf-test,dmabuf-meta,dmabuf-import-probe,dmabuf-present,kgsl-import-probe,ahb-export-probe,ahb-present-probe,ahb-ring-probe graphics-fd-passing=adrenotools-loader,kgsl-import-probe,dmabuf-image-import,dmabuf-present-gpu,ahb-vk-target active-layer=%s producer-surface=%s image-reader=%s max=%d inflight-ahb=%d inflight-image=%d format=%s target-usage=0x%x release=%s presenter=%s driver=%s dmabuf-present=%s",
                presentLayer == null ? "missing" : "ready",
                producerSurface == null ? "missing" : "ready",
                imageReader == null ? "missing" : "ready",
                IMAGE_READER_MAX_IMAGES,
                getAhbInFlightCount(),
                getImageInFlightCount(),
                BRIDGE_PIXEL_FORMAT_NAME,
                BRIDGE_TARGET_GPU_AHB_USAGE,
                bridgeValueToken(lastAhbReleaseStatus),
                bridgeValueToken(bridgePresenterStatusText),
                bridgeValueToken(bridgeDmaBufDriverStatusText),
                bridgeValueToken(bridgeDmaBufPresentStatusText));
    }

    private String buildBridgeSyncFields() {
        return String.format(
                Locale.US,
                "version=%d ready=eventfd-control-probe done=eventfd-import-future release=surfacecontrol-callback input=data-channel-future pacing=display-refresh final-copy=forbidden frame=%d fps=%.1f",
                BRIDGE_PROTOCOL_VERSION,
                frameIndex,
                measuredFps);
    }

    private String buildBridgeNativeFields() {
        return String.format(
                Locale.US,
                "version=%d transport=unix-abstract socket=%s state=%s compositor-endpoint=yes compositor-command=compositor-open window-commands=window-add,window-remove,window-status fd-passing=fdtest,syncfd-test,dmabuf-test,dmabuf-meta,dmabuf-import-probe,dmabuf-present,kgsl-import-probe,ahb-export-probe,ahb-present-probe,ahb-ring-probe graphics-fd-passing=adrenotools-loader,kgsl-import-probe,dmabuf-image-import,dmabuf-present-gpu,ahb-vk-target protocol=text-line contract=buffer-meta-only next=sync-fd-import-future presenter=%s dmabuf-driver=%s fdtest=%s syncfd-test=%s dmabuf-test=%s dmabuf-meta=%s dmabuf-import-probe=%s dmabuf-present=%s kgsl-import-probe=%s adrenotools=%s ahb-export-probe=%s ahb-present-or-ring-probe=%s compositor=%s windows=%s",
                BRIDGE_PROTOCOL_VERSION,
                BRIDGE_LOCAL_SOCKET_NAME,
                bridgeValueToken(bridgeNativeStatusText),
                bridgeValueToken(bridgePresenterStatusText),
                bridgeValueToken(bridgeDmaBufDriverStatusText),
                bridgeValueToken(bridgeFdStatusText),
                bridgeValueToken(bridgeSyncFdStatusText),
                bridgeValueToken(bridgeDmaBufStatusText),
                bridgeValueToken(bridgeDmaBufMetaStatusText),
                bridgeValueToken(bridgeDmaBufImportProbeStatusText),
                bridgeValueToken(bridgeDmaBufPresentStatusText),
                bridgeValueToken(bridgeKgslImportProbeStatusText),
                bridgeValueToken(bridgeAdrenoToolsStatusText),
                bridgeValueToken(bridgeAhbExportProbeStatusText),
                bridgeValueToken(bridgeAhbPresentProbeStatusText),
                bridgeValueToken(bridgeCompositorStatusText),
                bridgeValueToken(bridgeWindowStatusText));
    }

    private String buildBridgeCompositorFields(String phase, String rawCommand) {
        String client = findBridgeMetaValue(rawCommand, "client");
        if (client == null || client.isEmpty()) {
            client = "unknown";
        }
        String requestedProtocol = findBridgeMetaValue(rawCommand, "protocol");
        if (requestedProtocol == null || requestedProtocol.isEmpty()) {
            requestedProtocol = "bridge-ahb-v1";
        }
        String status = presentLayer != null && width > 0 && height > 0 ? "pass" : "wait-layer";
        return String.format(
                Locale.US,
                "phase=%s status=%s version=%d endpoint=unix-abstract:%s client=%s role=android-presenter producer=linux-wayland-compositor protocol=%s wayland-listener=linux-backend-presenter app-wayland-server=no windows=activity-per-toplevel frame-transport=dmabuf-present present=surfacecontrol-vulkan render=adrenotools-turnip-dmabuf layer=%dx%d refresh=%.1f fps=%.1f current-usage=0x%x target-usage=0x%x final-copy=forbidden presenter=%s driver=%s window-status=%s next=sync-fd-import",
                bridgeValueToken(phase),
                status,
                BRIDGE_PROTOCOL_VERSION,
                BRIDGE_LOCAL_SOCKET_NAME,
                bridgeValueToken(client),
                bridgeValueToken(requestedProtocol),
                width,
                height,
                getDisplayRefreshRate(),
                measuredFps,
                BRIDGE_CURRENT_AHB_USAGE,
                BRIDGE_TARGET_GPU_AHB_USAGE,
                bridgeValueToken(bridgePresenterStatusText),
                bridgeValueToken(bridgeDmaBufDriverStatusText),
                bridgeValueToken(bridgeWindowStatusText));
    }

    private void setBridgePresenterStatus(String state) {
        synchronized (vulkanRenderLock) {
            bridgePresenterStatusText = "presenter: " + buildBridgePresenterFields(state);
            vulkanRenderLock.notifyAll();
        }
    }

    private boolean waitForBridgePresenterReady(String state, long timeoutMs) {
        setBridgePresenterStatus(state);
        long deadlineMillis = System.currentTimeMillis() + timeoutMs;
        while (isBridgeRuntimeActive()) {
            MainActivity activeOwner = getActiveBridgeOwner();
            if (activeOwner != null && activeOwner != this) {
                return false;
            }
            synchronized (vulkanRenderLock) {
                if (isBridgePresenterUsableLocked()) {
                    setBridgePresenterStatus(activityResumed ? "ready" : "background-ready");
                    return true;
                }
                boolean waitIndefinitely = bridgeOwnsExternalSession();
                long remainingMillis = waitIndefinitely
                        ? 100L
                        : deadlineMillis - System.currentTimeMillis();
                if (!waitIndefinitely && remainingMillis <= 0L) {
                    return false;
                }
                try {
                    vulkanRenderLock.wait(Math.min(remainingMillis, 100L));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private boolean isBridgeRuntimeActive() {
        synchronized (bridgeLock) {
            return bridgeServerEnabled
                    && (bridgeControlServer != null || bridgeLocalServer != null);
        }
    }

    private boolean shouldKeepExternalBridgeSession() {
        if (bridgeOwnsExternalSession()) {
            return true;
        }
        SharedPreferences launchPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // Keep bridge alive if bridge_server was enabled via Intent extra,
        // even without external_present_only. This allows the user to
        // navigate back to HomeActivity (to run diagnostics, install
        // components, etc.) without the bridge TCP listener dying.
        return launchPrefs.getBoolean(PREF_BRIDGE_SERVER, false);
    }

    private void restoreStickyExternalBridgeSessionIfNeeded(String reason) {
        if (bridgeOwnsExternalSession()) {
            startBridgeControlServerIfNeeded();
            return;
        }
        SharedPreferences launchPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!launchPrefs.getBoolean(PREF_BRIDGE_SERVER, false)
                || !launchPrefs.getBoolean(PREF_EXTERNAL_PRESENT_ONLY, false)) {
            return;
        }
        bridgeServerEnabled = true;
        externalPresentOnlyEnabled = true;
        ahbCpuProducerEnabled = false;
        vulkanProducerEnabled = false;
        producerStatusText = "producer: external-present-only restored-" + reason;
        startBridgeControlServerIfNeeded();
        setBridgePresenterStatus(
                presentLayer == null ? "external-restored-wait-layer" : "external-restored-ready");
    }

    private boolean bridgeOwnsExternalSession() {
        // Input forwarding is enabled when:
        // 1. Bridge server is enabled (Wine is running and connected)
        // 2. AND any of:
        //    a. externalPresentOnlyEnabled (legacy mode)
        //    b. dmabuf-present has status=pass (Wine is actively presenting frames)
        //    c. bridge native status shows input-stream attached (Wine connected
        //       to bridge and is ready for input, even if no frame presented yet)
        // This allows touch/mouse input to be forwarded to Wine as soon as Wine
        // connects to the bridge, without waiting for the first successful present.
        return bridgeServerEnabled && (externalPresentOnlyEnabled
                || (bridgeDmaBufPresentStatusText != null
                    && bridgeDmaBufPresentStatusText.contains("status=pass"))
                || (bridgeNativeStatusText != null
                    && bridgeNativeStatusText.contains("input-stream")));
    }

    private boolean isBridgePresenterUsable() {
        synchronized (vulkanRenderLock) {
            return isBridgePresenterUsableLocked();
        }
    }

    private boolean isBridgePresenterUsableLocked() {
        return presentLayer != null
                && width > 0
                && height > 0
                && (activityResumed || bridgeOwnsExternalSession());
    }

    private void setBridgePresenterFailureStatus(String operation, RuntimeException error) {
        bridgePresenterStatusText = String.format(
                Locale.US,
                "presenter: %s operation=%s error=%s",
                buildBridgePresenterFields("error"),
                bridgeValueToken(operation),
                bridgeValueToken(error.getClass().getSimpleName()));
    }

    private String buildBridgePresenterFields(String state) {
        return String.format(
                Locale.US,
                "state=%s resumed=%s owner=%s layer=%s size=%dx%d present=surfacecontrol-vulkan final-copy=forbidden",
                bridgeValueToken(state),
                activityResumed ? "yes" : "no",
                bridgeOwnsExternalSession() ? "external-present" : "activity",
                presentLayer == null ? "missing" : "ready",
                width,
                height);
    }

    private void setBridgeDmaBufDriverStatus(
            String status,
            String reason,
            String driverName,
            File driverDir) {
        String reasonField = reason == null || reason.isEmpty()
                ? ""
                : " reason=" + bridgeValueToken(reason);
        bridgeDmaBufDriverStatusText = String.format(
                Locale.US,
                "dmabuf-driver: status=%s%s driver=%s required=%s dir=%s loader=adrenotools",
                bridgeValueToken(status),
                reasonField,
                bridgeValueToken(driverName),
                bridgeValueToken(DEFAULT_ADRENOTOOLS_DRIVER_NAME),
                bridgeValueToken(driverDir.getAbsolutePath()));
    }

    private int getAhbInFlightCount() {
        synchronized (ahbInFlightLock) {
            return inFlightAhbFrames.size();
        }
    }

    private int getImageInFlightCount() {
        return inFlightImages.size();
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running && !inputFrameRunning) {
            return;
        }

        updateControllerMouseCursor(frameTimeNanos);
        if (running) {
            renderAndPresent(frameTimeNanos);
        }
        if (running || wantsInputFrameLoop()) {
            inputFrameRunning = !running;
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            inputFrameRunning = false;
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (bridgeOwnsExternalSession() && handleBridgeImeTouchGesture(event)) {
            return true;
        }
        if (bridgeOwnsExternalSession()) {
            resetControllerMouseForTouch(event);
        }
        recordMotionInput("touch", event);
        if (bridgeOwnsExternalSession()) {
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        String type = classifyGenericMotionEvent(event);
        recordMotionInput(type, event);
        if (bridgeOwnsExternalSession()
                && isGameControllerSource(event.getSource())
                && handleControllerMouseMotion(event)) {
            return true;
        }
        if (bridgeOwnsExternalSession()
                && (isGameControllerSource(event.getSource()) || isPointerSource(event.getSource()))) {
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) {
            recordKeyInput(event);
        }
        if (bridgeOwnsExternalSession() && handleControllerMouseKey(event)) {
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && action == KeyEvent.ACTION_UP
                && moveExternalBridgeTaskToBack("back-key")) {
            return true;
        }
        if (bridgeOwnsExternalSession()
                && (isGameControllerSource(event.getSource())
                || isGameControllerKeyCode(event.getKeyCode()))) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void claimBridgeInputFocus() {
        if (!bridgeOwnsExternalSession()) {
            return;
        }
        View decor = getWindow().getDecorView();
        decor.setFocusable(true);
        decor.setFocusableInTouchMode(true);
        decor.requestFocus();
        if (hostView != null) {
            hostView.requestFocus();
        }
    }

    private boolean handleBridgeImeTouchGesture(MotionEvent event) {
        int action = event.getActionMasked();
        if (event.getPointerCount() < 3) {
            if (bridgeInputGestureMaxPointers >= 3) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    bridgeInputGestureMaxPointers = 0;
                    bridgeInputGestureHandled = false;
                }
                return true;
            }
            return false;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() == 3) {
            bridgeInputGestureMaxPointers = 0;
            bridgeInputGestureHandled = false;
            cancelBridgeTouchForGesture(event, "multi-touch-gesture");
        }
        bridgeInputGestureMaxPointers =
                Math.max(bridgeInputGestureMaxPointers, event.getPointerCount());
        if (!bridgeInputGestureHandled
                && (action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL)) {
            bridgeInputGestureHandled = true;
            if (bridgeInputGestureMaxPointers >= 5) {
                publishBridgeClipboardPaste("touch-five-finger");
            } else if (bridgeInputGestureMaxPointers >= 4) {
                publishBridgeClipboardRequest("touch-four-finger");
            } else {
                toggleBridgeIme();
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            bridgeInputGestureMaxPointers = 0;
            bridgeInputGestureHandled = false;
        }
        return true;
    }

    private void toggleBridgeIme() {
        BridgeImeView ime = bridgeImeView;
        if (ime == null) {
            return;
        }
        InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (inputMethodManager == null) {
            return;
        }
        ime.requestFocus();
        inputMethodManager.toggleSoftInputFromWindow(
                ime.getWindowToken(),
                InputMethodManager.SHOW_FORCED,
                0);
        inputStatusText = "input: android-ime toggle";
    }

    private static boolean isGameControllerSource(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private static boolean isPointerSource(int source) {
        return (source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (source & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                || (source & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
                || (source & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD
                || (source & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS;
    }

    private static boolean isGameControllerKeyCode(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A
                || keyCode == KeyEvent.KEYCODE_BUTTON_B
                || keyCode == KeyEvent.KEYCODE_BUTTON_C
                || keyCode == KeyEvent.KEYCODE_BUTTON_X
                || keyCode == KeyEvent.KEYCODE_BUTTON_Y
                || keyCode == KeyEvent.KEYCODE_BUTTON_Z
                || keyCode == KeyEvent.KEYCODE_BUTTON_L1
                || keyCode == KeyEvent.KEYCODE_BUTTON_R1
                || keyCode == KeyEvent.KEYCODE_BUTTON_L2
                || keyCode == KeyEvent.KEYCODE_BUTTON_R2
                || keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL
                || keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR
                || keyCode == KeyEvent.KEYCODE_BUTTON_START
                || keyCode == KeyEvent.KEYCODE_BUTTON_SELECT
                || keyCode == KeyEvent.KEYCODE_BUTTON_MODE
                || keyCode == KeyEvent.KEYCODE_BUTTON_1
                || keyCode == KeyEvent.KEYCODE_BUTTON_2
                || keyCode == KeyEvent.KEYCODE_BUTTON_3
                || keyCode == KeyEvent.KEYCODE_BUTTON_4
                || keyCode == KeyEvent.KEYCODE_BUTTON_5
                || keyCode == KeyEvent.KEYCODE_BUTTON_6
                || keyCode == KeyEvent.KEYCODE_BUTTON_7
                || keyCode == KeyEvent.KEYCODE_BUTTON_8
                || keyCode == KeyEvent.KEYCODE_BUTTON_9
                || keyCode == KeyEvent.KEYCODE_BUTTON_10
                || keyCode == KeyEvent.KEYCODE_BUTTON_11
                || keyCode == KeyEvent.KEYCODE_BUTTON_12
                || keyCode == KeyEvent.KEYCODE_BUTTON_13
                || keyCode == KeyEvent.KEYCODE_BUTTON_14
                || keyCode == KeyEvent.KEYCODE_BUTTON_15
                || keyCode == KeyEvent.KEYCODE_BUTTON_16;
    }

    private void configurePipeline(SurfaceHolder holder) {
        Rect frame = holder.getSurfaceFrame();
        int newWidth = Math.max(1, frame.width());
        int newHeight = Math.max(1, frame.height());
        float targetRefreshRate = getHighestSameSizeDisplayRefreshRate();

        if (presentLayer != null && newWidth == width && newHeight == height) {
            requestPresentationFrameRate(holder.getSurface(), presentLayer, targetRefreshRate);
            setBridgePresenterStatus(activityResumed ? "ready" : "surface-ready");
            if (activityResumed && !running && !externalPresentOnlyEnabled) {
                startFrameLoop();
            }
            return;
        }

        setBridgePresenterStatus("configuring");
        stopFrameLoop();
        releasePipeline();

        width = newWidth;
        height = newHeight;
        requestPresentationFrameRate(holder.getSurface(), null, targetRefreshRate);

        long usage = HardwareBuffer.USAGE_CPU_WRITE_OFTEN
                | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                | HardwareBuffer.USAGE_COMPOSER_OVERLAY;

        imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                IMAGE_READER_MAX_IMAGES,
                usage);
        producerSurface = imageReader.getSurface();

        SurfaceControl newPresentLayer = null;
        try {
            newPresentLayer = new SurfaceControl.Builder()
                    .setName(TAG_LAYER)
                    .setParent(hostView.getSurfaceControl())
                    .setBufferSize(width, height)
                    .setFormat(PixelFormat.RGBA_8888)
                    .setOpaque(false)
                    .setHidden(false)
                    .build();

            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                transaction
                        .setLayer(newPresentLayer, 10)
                        .setVisibility(newPresentLayer, true)
                        .setOpaque(newPresentLayer, false)
                        .setAlpha(newPresentLayer, PRESENT_LAYER_COMPOSITION_NUDGE_ALPHA)
                        .setPosition(newPresentLayer, 0.0f, 0.0f)
                        .setBufferSize(newPresentLayer, width, height)
                        .setCrop(newPresentLayer, new Rect(0, 0, width, height))
                        .setFrameRate(
                                newPresentLayer,
                                targetRefreshRate,
                                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                                Surface.CHANGE_FRAME_RATE_ALWAYS)
                        .apply();
            }

            synchronized (vulkanRenderLock) {
                presentLayer = newPresentLayer;
                bridgeCursorX = width * 0.5f;
                bridgeCursorY = height * 0.5f;
                vulkanRenderLock.notifyAll();
            }
            setBridgePresenterStatus(activityResumed ? "ready" : "surface-ready");
        } catch (RuntimeException error) {
            if (newPresentLayer != null) {
                newPresentLayer.release();
            }
            releasePipeline();
            setBridgePresenterFailureStatus("configure", error);
            updateOverlay();
            return;
        }

        if (activityResumed && !externalPresentOnlyEnabled) {
            startFrameLoop();
        }
    }

    private void startFrameLoop() {
        if (externalPresentOnlyEnabled) {
            running = false;
            Choreographer.getInstance().removeFrameCallback(this);
            inputFrameRunning = false;
            startInputFrameLoopIfNeeded();
            return;
        }
        running = true;
        inputFrameRunning = false;
        lastStatsNanos = 0L;
        framesSinceStats = 0L;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void requestPresentationFrameRate(
            Surface surface,
            SurfaceControl layer,
            float targetRefreshRate) {
        if (targetRefreshRate < MIN_PRESENTATION_FRAME_RATE_HZ) {
            return;
        }

        if (surface != null && surface.isValid()) {
            try {
                surface.setFrameRate(
                        targetRefreshRate,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ALWAYS);
            } catch (RuntimeException error) {
                Log.w(TAG_LOG, "Surface frame-rate vote failed", error);
            }
        }

        if (layer != null) {
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                transaction
                        .setFrameRate(
                                layer,
                                targetRefreshRate,
                                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                                Surface.CHANGE_FRAME_RATE_ALWAYS)
                        .apply();
            } catch (RuntimeException error) {
                Log.w(TAG_LOG, "SurfaceControl frame-rate vote failed", error);
            }
        }
    }

    private void stopFrameLoop() {
        running = false;
        inputFrameRunning = false;
        Choreographer.getInstance().removeFrameCallback(this);
        stopVulkanRenderWorker();
    }

    private boolean wantsInputFrameLoop() {
        return activityResumed
                && bridgeOwnsExternalSession()
                && controllerMouseModeEnabled
                && (controllerMouseAxisX != 0.0f || controllerMouseAxisY != 0.0f);
    }

    private void startInputFrameLoopIfNeeded() {
        if (running || inputFrameRunning || !wantsInputFrameLoop()) {
            return;
        }
        inputFrameRunning = true;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void stopVulkanRenderWorker() {
        HandlerThread threadToStop;
        synchronized (vulkanRenderLock) {
            vulkanPipelineGeneration++;
            threadToStop = vulkanRenderThread;
            vulkanRenderThread = null;
            vulkanRenderHandler = null;
        }

        if (threadToStop != null && Thread.currentThread() != threadToStop) {
            threadToStop.quitSafely();
            boolean interrupted = false;
            while (threadToStop.isAlive()) {
                try {
                    threadToStop.join();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized (vulkanRenderLock) {
            vulkanRenderInProgress = false;
        }
    }

    private void renderAndPresent(long frameTimeNanos) {
        if (presentLayer == null) {
            return;
        }

        updateFps(frameTimeNanos);

        if (externalPresentOnlyEnabled) {
            if (!producerStatusText.startsWith("producer: external-present-only")) {
                producerStatusText = "producer: external-present-only waiting";
            }
            if (frameTimeNanos - lastExternalPresentOverlayNanos
                    >= EXTERNAL_PRESENT_UI_UPDATE_INTERVAL_NANOS) {
                lastExternalPresentOverlayNanos = frameTimeNanos;
                updateOverlay();
            }
            return;
        }

        long holdUntilNanos = bridgePresentHoldUntilNanos;
        if (holdUntilNanos > 0L && System.nanoTime() < holdUntilNanos) {
            producerStatusText = "producer: bridge ahb-present hold";
            return;
        }

        ahbCpuFallbackReason = null;
        vulkanFallbackReason = null;
        if (scheduleAhbVkProducerFrame(frameTimeNanos)) {
            return;
        }

        if (renderAhbCpuProducerFrame(frameTimeNanos)) {
            frameIndex++;
            return;
        }

        if (!diagnosticProducerEnabled) {
            if (!producerStatusText.startsWith("producer: idle")) {
                producerStatusText = "producer: idle waiting for Wayland";
            }
            updateOverlay();
            return;
        }

        if (producerSurface == null || imageReader == null) {
            updateOverlay();
            return;
        }

        boolean produced = renderNativeProducerFrame(frameTimeNanos);
        if (!produced) {
            produced = drawFallbackFrame();
        }
        if (!produced) {
            updateOverlay();
            return;
        }

        pruneInFlightImages();

        Image image;
        try {
            image = imageReader.acquireLatestImage();
        } catch (IllegalStateException ignored) {
            pruneInFlightImages();
            producerStatusText = "producer: acquireLatestImage failed";
            updateOverlay();
            return;
        }
        if (image == null) {
            updateOverlay();
            return;
        }

        HardwareBuffer buffer = image.getHardwareBuffer();
        if (buffer == null || buffer.isClosed()) {
            image.close();
            producerStatusText = "producer: image buffer unavailable";
            updateOverlay();
            return;
        }

        inFlightImages.addLast(image);
        pruneInFlightImages();

        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction
                    .setBuffer(presentLayer, buffer, null, fence -> releaseImageWhenSafe(image, fence))
                    .setDamageRegion(presentLayer, new Region(new Rect(0, 0, width, height)));
            setDesiredPresentTimeNanosReflectively(transaction, frameTimeNanos);
            transaction.apply();
        }

        frameIndex++;
    }

    private boolean scheduleAhbVkProducerFrame(long frameTimeNanos) {
        if (!vulkanProducerEnabled) {
            vulkanFallbackReason = "ahb-vk gate off";
            return false;
        }
        if (!nativeLibraryAvailable) {
            vulkanFallbackReason = "ahb-vk native unavailable";
            return false;
        }

        Handler handler;
        SurfaceControl targetLayer;
        int renderWidth;
        int renderHeight;
        int pipelineGeneration;
        long renderFrameIndex;
        synchronized (vulkanRenderLock) {
            if (presentLayer == null) {
                vulkanFallbackReason = "ahb-vk layer unavailable";
                return false;
            }
            if (vulkanRenderInProgress) {
                updateQueuedVulkanStatusLocked(frameIndex);
                return true;
            }

            handler = ensureVulkanRenderHandlerLocked();
            targetLayer = presentLayer;
            renderWidth = width;
            renderHeight = height;
            pipelineGeneration = vulkanPipelineGeneration;
            renderFrameIndex = frameIndex;
            vulkanRenderInProgress = true;
            vulkanFallbackReason = null;
            updateQueuedVulkanStatusLocked(renderFrameIndex);
        }

        boolean posted = handler.post(() -> renderAhbVkProducerFrameOnWorker(
                targetLayer,
                renderWidth,
                renderHeight,
                renderFrameIndex,
                frameTimeNanos,
                pipelineGeneration));
        if (!posted) {
            synchronized (vulkanRenderLock) {
                vulkanRenderInProgress = false;
            }
            producerStatusText = "producer: ahb-vk fallback worker stopped";
            vulkanFallbackReason = producerStatusText;
            return false;
        }

        frameIndex++;
        return true;
    }

    private void updateQueuedVulkanStatusLocked(long renderFrameIndex) {
        if (lastVulkanProducerFrameStatus == null) {
            producerStatusText = String.format(
                    Locale.US,
                    "producer: ahb-vk queued frame %,d",
                    renderFrameIndex);
        } else {
            producerStatusText = lastVulkanProducerFrameStatus;
        }
    }

    private Handler ensureVulkanRenderHandlerLocked() {
        if (vulkanRenderHandler != null) {
            return vulkanRenderHandler;
        }

        vulkanRenderThread = new HandlerThread("WayLandIEAhbVkRender");
        vulkanRenderThread.start();
        vulkanRenderHandler = new Handler(vulkanRenderThread.getLooper());
        return vulkanRenderHandler;
    }

    private void renderAhbVkProducerFrameOnWorker(
            SurfaceControl targetLayer,
            int renderWidth,
            int renderHeight,
            long renderFrameIndex,
            long frameTimeNanos,
            int pipelineGeneration) {
        try {
            synchronized (vulkanRenderLock) {
                if (isStaleVulkanRenderLocked(targetLayer, pipelineGeneration)) {
                    producerStatusText = "producer: ahb-vk skipped stale surface";
                    vulkanFallbackReason = producerStatusText;
                    return;
                }
            }

            AhbCpuFrame frame;
            try {
                File filesDir = getFilesDir();
                File tmpDir = new File(filesDir, "adrenotools-tmp");
                File driverDir = new File(filesDir, ADRENOTOOLS_DRIVER_DIR_NAME);
                frame = nativeAcquireAhbVkFrame(
                        renderWidth,
                        renderHeight,
                        renderFrameIndex,
                        frameTimeNanos,
                        tmpDir.getAbsolutePath(),
                        getApplicationInfo().nativeLibraryDir,
                        driverDir.getAbsolutePath(),
                        DEFAULT_ADRENOTOOLS_DRIVER_NAME);
            } catch (RuntimeException | UnsatisfiedLinkError error) {
                if (error instanceof UnsatisfiedLinkError) {
                    nativeLibraryAvailable = false;
                }
                producerStatusText = String.format(
                        Locale.US,
                        "producer: ahb-vk fallback (%s)",
                        error.getClass().getSimpleName());
                vulkanFallbackReason = producerStatusText;
                return;
            }

            if (frame == null) {
                producerStatusText = "producer: ahb-vk fallback null-frame";
                vulkanFallbackReason = producerStatusText;
                return;
            }

            producerStatusText = frame.status == null
                    ? "producer: ahb-vk fallback null-status"
                    : frame.status;
            boolean validFrame = producerStatusText.startsWith("producer: ahb-vk frame ")
                    && frame.buffer != null
                    && !frame.buffer.isClosed()
                    && frame.slot >= 0;
            if (!validFrame) {
                closeUnsubmittedAhbFrame(frame, true);
                vulkanFallbackReason = producerStatusText;
                return;
            }
            lastVulkanProducerFrameStatus = producerStatusText;

            AhbInFlightFrame inFlightFrame = new AhbInFlightFrame(
                    frame.buffer,
                    frame.slot,
                    frame.generation,
                    true);
            synchronized (ahbInFlightLock) {
                inFlightAhbFrames.addLast(inFlightFrame);
            }

            boolean staleRender;
            try {
                // Hold the render lock across the transaction so teardown cannot
                // invalidate and release the layer between validation and apply.
                synchronized (vulkanRenderLock) {
                    staleRender = isStaleVulkanRenderLocked(targetLayer, pipelineGeneration);
                    if (!staleRender) {
                        try (SurfaceControl.Transaction transaction =
                                     new SurfaceControl.Transaction()) {
                            transaction
                                    .setBuffer(
                                            targetLayer,
                                            frame.buffer,
                                            null,
                                            fence -> releaseAhbFrameWhenSafe(inFlightFrame, fence))
                                    .setDamageRegion(
                                            targetLayer,
                                            new Region(new Rect(0, 0, renderWidth, renderHeight)));
                                    setDesiredPresentTimeNanosReflectively(transaction, frameTimeNanos);
                                    transaction.apply();
                        }
                        vulkanFallbackReason = null;
                    }
                }
            } catch (RuntimeException error) {
                releaseUnsubmittedAhbFrame(inFlightFrame);
                producerStatusText = String.format(
                        Locale.US,
                        "producer: ahb-vk fallback transaction %s",
                        error.getClass().getSimpleName());
                vulkanFallbackReason = producerStatusText;
                return;
            }

            if (staleRender) {
                producerStatusText = "producer: ahb-vk skipped stale surface";
                vulkanFallbackReason = producerStatusText;
                releaseUnsubmittedAhbFrame(inFlightFrame);
            }
        } finally {
            finishVulkanRenderJob();
        }
    }

    private boolean isStaleVulkanRenderLocked(
            SurfaceControl targetLayer,
            int pipelineGeneration) {
        return pipelineGeneration != vulkanPipelineGeneration
                || targetLayer != presentLayer;
    }

    private void finishVulkanRenderJob() {
        synchronized (vulkanRenderLock) {
            vulkanRenderInProgress = false;
        }
    }

    private boolean renderAhbCpuProducerFrame(long frameTimeNanos) {
        if (!ahbCpuProducerEnabled) {
            ahbCpuFallbackReason = "ahb-cpu disabled";
            return false;
        }
        if (!nativeLibraryAvailable) {
            ahbCpuFallbackReason = "ahb-cpu native unavailable";
            return false;
        }

        AhbCpuFrame frame;
        try {
            frame = nativeAcquireAhbCpuFrame(width, height, frameIndex, frameTimeNanos);
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            if (error instanceof UnsatisfiedLinkError) {
                nativeLibraryAvailable = false;
            }
            producerStatusText = String.format(
                    Locale.US,
                    "producer: ahb-cpu fallback (%s)",
                    error.getClass().getSimpleName());
            ahbCpuFallbackReason = producerStatusText;
            return false;
        }

        if (frame == null) {
            producerStatusText = "producer: ahb-cpu fallback null-frame";
            ahbCpuFallbackReason = producerStatusText;
            return false;
        }

        producerStatusText = frame.status == null
                ? "producer: ahb-cpu fallback null-status"
                : frame.status;
        boolean validFrame = producerStatusText.startsWith("producer: ahb-cpu frame ")
                && frame.buffer != null
                && !frame.buffer.isClosed()
                && frame.slot >= 0;
        if (!validFrame) {
            closeUnsubmittedAhbFrame(frame);
            ahbCpuFallbackReason = producerStatusText;
            return false;
        }

        AhbInFlightFrame inFlightFrame = new AhbInFlightFrame(
                frame.buffer,
                frame.slot,
                frame.generation,
                false);
        synchronized (ahbInFlightLock) {
            inFlightAhbFrames.addLast(inFlightFrame);
        }

        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction
                    .setBuffer(
                            presentLayer,
                            frame.buffer,
                            null,
                            fence -> releaseAhbFrameWhenSafe(inFlightFrame, fence))
                    .setDamageRegion(presentLayer, new Region(new Rect(0, 0, width, height)));
            setDesiredPresentTimeNanosReflectively(transaction, frameTimeNanos);
            transaction.apply();
            return true;
        } catch (RuntimeException error) {
            // No transaction was accepted, so no SurfaceControl release callback
            // will close this wrapper or free the native slot for us.
            releaseUnsubmittedAhbFrame(inFlightFrame);
            producerStatusText = String.format(
                    Locale.US,
                    "producer: ahb-cpu fallback transaction %s",
                    error.getClass().getSimpleName());
            ahbCpuFallbackReason = producerStatusText;
            return false;
        }
    }

    private boolean renderNativeProducerFrame(long frameTimeNanos) {
        if (!nativeLibraryAvailable) {
            producerStatusText = "producer: java fallback (native unavailable)";
            return false;
        }

        try {
            String status = nativeRenderProducerFrame(
                    producerSurface,
                    width,
                    height,
                    frameIndex,
                    frameTimeNanos);
            if (status != null && status.startsWith("producer: native-window frame ")) {
                producerStatusText = formatNativeWindowStatus(status);
                return true;
            }

            producerStatusText = String.format(
                    Locale.US,
                    "producer: java fallback (%s)",
                    status == null ? "native returned null" : status);
            return false;
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            if (error instanceof UnsatisfiedLinkError) {
                nativeLibraryAvailable = false;
            }
            producerStatusText = String.format(
                    Locale.US,
                    "producer: java fallback (%s)",
                    error.getClass().getSimpleName());
            return false;
        }
    }

    private boolean drawFallbackFrame() {
        Canvas canvas = null;
        boolean drew = false;
        try {
            canvas = producerSurface.lockCanvas(null);

            float phase = (frameIndex % 240) / 240.0f;
            int red = (int) (30 + 90 * phase);
            int blue = (int) (120 + 100 * (1.0f - phase));
            canvas.drawColor(Color.rgb(red, 18, blue));

            float barWidth = width * phase;
            paint.setColor(Color.rgb(0, 220, 180));
            canvas.drawRect(0.0f, height - 42.0f, barWidth, height, paint);

            paint.setColor(Color.WHITE);
            canvas.drawText("Gaming Compositor MVP", 46.0f, 88.0f, paint);

            smallPaint.setColor(Color.rgb(220, 238, 255));
            canvas.drawText("SurfaceControl + HardwareBuffer presentation", 48.0f, 142.0f, smallPaint);
            canvas.drawText(String.format(Locale.US, "Frame %,d", frameIndex), 48.0f, 196.0f, smallPaint);
            canvas.drawText(String.format(Locale.US, "Measured %.1f fps", measuredFps), 48.0f, 250.0f, smallPaint);
            canvas.drawText(String.format(Locale.US, "Layer %dx%d @ %.1f Hz", width, height, getDisplayRefreshRate()),
                    48.0f, 304.0f, smallPaint);
            canvas.drawText(getNativeStatusText(), 48.0f, 358.0f, smallPaint);
            canvas.drawText(producerStatusText, 48.0f, 412.0f, smallPaint);

            smallPaint.setColor(Color.rgb(255, 220, 120));
            canvas.drawText("Java fallback producer is active; native status is shown above",
                    48.0f, height - 88.0f, smallPaint);
            drew = true;
        } catch (Surface.OutOfResourcesException | IllegalArgumentException ignored) {
            producerStatusText = "producer: java fallback draw failed";
        }

        if (canvas != null) {
            try {
                producerSurface.unlockCanvasAndPost(canvas);
            } catch (IllegalArgumentException ignored) {
                producerStatusText = "producer: java fallback post failed";
                return false;
            }
        }

        return drew;
    }

    private void updateOverlay() {
        if (overlayView == null) {
            return;
        }
        if (hideOverlayEnabled) {
            overlayView.setVisibility(View.GONE);
            return;
        }
        overlayView.setVisibility(View.VISIBLE);

        int maxWidth = width > 0 ? Math.max(320, Math.min(width / 3, 560)) : 480;
        overlayView.setMaxWidth(maxWidth);
        overlayView.setText(String.format(
                Locale.US,
                "Zero-copy Wayland\nbridge %.1f fps | %.2f ms\n%s -> %s @ %.1f Hz\ndriver %s\npath %s\nframe %,d | ui %.1f fps\npresent %s\nproducer %s\ninput %s",
                bridgeMeasuredFps,
                lastBridgePresentMs,
                lastBridgeSourceSize,
                lastBridgeTargetSize,
                getDisplayRefreshRate(),
                compactOverlayText(lastBridgeDriverName, 42),
                compactOverlayText(lastBridgeZeroCopyMode, 42),
                frameIndex,
                measuredFps,
                compactOverlayText(bridgeDmaBufPresentStatusText, 52),
                compactOverlayText(producerStatusText, 52),
                compactOverlayText(inputStatusText, 52)));
    }

    private void updateBridgePresentStats(
            long startNanos,
            String driverName,
            String sourceSize,
            String targetSize,
            String zeroCopyMode) {
        long nowNanos = System.nanoTime();
        lastBridgePresentMs = (nowNanos - startNanos) / 1_000_000.0f;
        lastBridgeDriverName = driverName == null || driverName.isEmpty()
                ? DEFAULT_ADRENOTOOLS_DRIVER_NAME
                : driverName;
        lastBridgeSourceSize = sourceSize;
        lastBridgeTargetSize = targetSize;
        lastBridgeZeroCopyMode = zeroCopyMode;

        if (lastBridgeStatsNanos == 0L) {
            lastBridgeStatsNanos = nowNanos;
            bridgeFramesSinceStats = 0L;
            return;
        }

        bridgeFramesSinceStats++;
        long elapsedNanos = nowNanos - lastBridgeStatsNanos;
        if (elapsedNanos >= 500_000_000L) {
            bridgeMeasuredFps = bridgeFramesSinceStats * 1_000_000_000.0f / elapsedNanos;
            bridgeFramesSinceStats = 0L;
            lastBridgeStatsNanos = nowNanos;
            runOnUiThread(this::updateOverlay);
        }
    }

    private String producerGateSummary() {
        String vulkanGate = vulkanProducerEnabled ? "ahb-vk gate: on" : "ahb-vk gate: off";
        String cpuGate = ahbCpuProducerEnabled ? "ahb-cpu gate: on" : "ahb-cpu gate: off";
        String externalGate = externalPresentOnlyEnabled ? "; external-present-only: on" : "";
        String diagnosticGate = diagnosticProducerEnabled ? "; diagnostic-producer: on" : "";
        if (vulkanFallbackReason == null) {
            return vulkanGate + "; " + cpuGate + externalGate + diagnosticGate;
        }
        return vulkanGate + " (" + compactProducerStatus(vulkanFallbackReason) + "); " + cpuGate + externalGate
                + diagnosticGate;
    }

    private void updateFps(long frameTimeNanos) {
        if (lastStatsNanos == 0L) {
            lastStatsNanos = frameTimeNanos;
            framesSinceStats = 0L;
            return;
        }

        framesSinceStats++;
        long elapsed = frameTimeNanos - lastStatsNanos;
        if (elapsed >= 1_000_000_000L) {
            measuredFps = framesSinceStats * 1_000_000_000.0f / elapsed;
            framesSinceStats = 0L;
            lastStatsNanos = frameTimeNanos;
            logDiagnosticSummary();
            updateOverlay();
        }
    }

    private void logDiagnosticSummary() {
        String summary = String.format(
                Locale.US,
                "diagnostic fps=%.1f layer=%dx%d producer=\"%s\" release=\"%s\" input=\"%s\" bridge=\"%s\" native=\"%s\" contract=\"%s\" buffers=\"%s\" fdtest=\"%s\" syncfd=\"%s\" dmabuf=\"%s\" dmabuf-meta=\"%s\" dmabuf-import-probe=\"%s\" dmabuf-present=\"%s\" dmabuf-driver=\"%s\" presenter=\"%s\" kgsl-import-probe=\"%s\" adrenotools=\"%s\" ahb-export-probe=\"%s\" ahb-present-probe=\"%s\" compositor=\"%s\" gates=\"%s\"",
                measuredFps,
                width,
                height,
                producerStatusText,
                lastAhbReleaseStatus,
                inputStatusText,
                bridgeStatusText,
                bridgeNativeStatusText,
                bridgeContractStatusText,
                bridgeBufferStatusText,
                bridgeFdStatusText,
                bridgeSyncFdStatusText,
                bridgeDmaBufStatusText,
                bridgeDmaBufMetaStatusText,
                bridgeDmaBufImportProbeStatusText,
                bridgeDmaBufPresentStatusText,
                bridgeDmaBufDriverStatusText,
                bridgePresenterStatusText,
                bridgeKgslImportProbeStatusText,
                bridgeAdrenoToolsStatusText,
                bridgeAhbExportProbeStatusText,
                bridgeAhbPresentProbeStatusText,
                bridgeCompositorStatusText,
                producerGateSummary());
        lastDiagnosticSummary = summary;
        Log.i(
                TAG_LOG,
                summary);
        writeDiagnosticStatus(summary);
    }

    private void writeDiagnosticStatus(String summary) {
        File statusFile = new File(getFilesDir(), "waylandie-status.txt");
        byte[] statusBytes = (summary + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(statusFile, false)) {
            output.write(statusBytes);
        } catch (IOException ignored) {
            // This file is verifier-only diagnostics; rendering should continue
            // even if the status snapshot cannot be written.
        }
    }

    private void recordMotionInput(String type, MotionEvent event) {
        int pointerCount = event.getPointerCount();
        float x = pointerCount > 0 ? event.getX(0) : 0.0f;
        float y = pointerCount > 0 ? event.getY(0) : 0.0f;
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();
        long sequence = ++inputSequence;
        if (action == MotionEvent.ACTION_SCROLL) {
            inputStatusText = String.format(
                    Locale.US,
                    "input: seq=%d type=%s action=%s pointers=%d x=%.1f y=%.1f scroll=%.1f/%.1f buttons=0x%x source=0x%x",
                    sequence,
                    type,
                    MotionEvent.actionToString(action),
                    pointerCount,
                    x,
                    y,
                    event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL),
                    event.getButtonState(),
                    event.getSource());
            publishBridgeMotionInput(sequence, type, event);
            return;
        }

        // Dispatch owns capture; the stats tick owns publishing to avoid UI churn.
        inputStatusText = String.format(
                Locale.US,
                "input: seq=%d type=%s action=%s index=%d pointers=%d x=%.1f y=%.1f buttons=0x%x source=0x%x",
                sequence,
                type,
                MotionEvent.actionToString(action),
                actionIndex,
                pointerCount,
                x,
                y,
                event.getButtonState(),
                event.getSource());
        publishBridgeMotionInput(sequence, type, event);
    }

    private void recordKeyInput(KeyEvent event) {
        int action = event.getAction();
        long sequence = ++inputSequence;
        inputStatusText = String.format(
                Locale.US,
                "input: seq=%d type=%s action=%s keyCode=%d(%s) scanCode=%d repeat=%d source=0x%x",
                sequence,
                action == KeyEvent.ACTION_DOWN ? "key-down" : "key-up",
                keyActionToString(action),
                event.getKeyCode(),
                KeyEvent.keyCodeToString(event.getKeyCode()),
                event.getScanCode(),
                event.getRepeatCount(),
                event.getSource());
        publishBridgeKeyInput(sequence, event);
    }

    private boolean handleControllerMouseMotion(MotionEvent event) {
        if (!controllerMouseModeEnabled || event.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return false;
        }
        float dxAxis = strongestAxis(
                event.getAxisValue(MotionEvent.AXIS_Z),
                event.getAxisValue(MotionEvent.AXIS_RX),
                event.getAxisValue(MotionEvent.AXIS_X));
        float dyAxis = strongestAxis(
                event.getAxisValue(MotionEvent.AXIS_RZ),
                event.getAxisValue(MotionEvent.AXIS_RY),
                event.getAxisValue(MotionEvent.AXIS_Y));
        dxAxis = applyControllerDeadzone(dxAxis);
        dyAxis = applyControllerDeadzone(dyAxis);
        controllerMouseAxisX = dxAxis;
        controllerMouseAxisY = dyAxis;
        if (dxAxis == 0.0f && dyAxis == 0.0f) {
            lastControllerMouseFrameNanos = 0L;
            inputFrameRunning = false;
            if (!running) {
                Choreographer.getInstance().removeFrameCallback(this);
            }
            return true;
        }
        ensureBridgeCursorInitialized();
        inputStatusText = String.format(
                Locale.US,
                "input: type=controller-mouse action=axis enabled=1 x=%.1f y=%.1f axis=%.2f/%.2f source=0x%x",
                bridgeCursorX,
                bridgeCursorY,
                dxAxis,
                dyAxis,
                event.getSource());
        showBridgeCursor(bridgeCursorX, bridgeCursorY);
        scheduleBridgeCursorHide(BRIDGE_CONTROLLER_CURSOR_HIDE_DELAY_MS);
        startInputFrameLoopIfNeeded();
        return true;
    }

    private void updateControllerMouseCursor(long frameTimeNanos) {
        if (!controllerMouseModeEnabled
                || (controllerMouseAxisX == 0.0f && controllerMouseAxisY == 0.0f)) {
            return;
        }
        int viewWidth = hostView == null ? width : Math.max(1, hostView.getWidth());
        int viewHeight = hostView == null ? height : Math.max(1, hostView.getHeight());
        long previousFrameNanos = lastControllerMouseFrameNanos > 0L
                ? lastControllerMouseFrameNanos
                : frameTimeNanos - 8_333_333L;
        float deltaSeconds = Math.max(
                0.001f,
                Math.min(0.025f, (frameTimeNanos - previousFrameNanos) / 1_000_000_000.0f));
        lastControllerMouseFrameNanos = frameTimeNanos;
        ensureBridgeCursorInitialized();
        bridgeCursorX = clampFloat(
                bridgeCursorX + controllerMouseAxisX * CONTROLLER_MOUSE_SPEED_PX_PER_SECOND * deltaSeconds,
                0.0f,
                viewWidth - 1.0f);
        bridgeCursorY = clampFloat(
                bridgeCursorY + controllerMouseAxisY * CONTROLLER_MOUSE_SPEED_PX_PER_SECOND * deltaSeconds,
                0.0f,
                viewHeight - 1.0f);
        long sequence = ++inputSequence;
        long eventTimeMs = frameTimeNanos / 1_000_000L;
        inputStatusText = String.format(
                Locale.US,
                "input: seq=%d type=controller-mouse action=move enabled=1 x=%.1f y=%.1f axis=%.2f/%.2f",
                sequence,
                bridgeCursorX,
                bridgeCursorY,
                controllerMouseAxisX,
                controllerMouseAxisY);
        showBridgeCursor(bridgeCursorX, bridgeCursorY);
        scheduleBridgeCursorHide(BRIDGE_CONTROLLER_CURSOR_HIDE_DELAY_MS);
        publishBridgePointerMoveLine(
                sequence,
                bridgeCursorX,
                bridgeCursorY,
                viewWidth,
                viewHeight,
                0,
                InputDevice.SOURCE_JOYSTICK,
                eventTimeMs);
    }

    private boolean handleControllerMouseKey(KeyEvent event) {
        if (!isGameControllerSource(event.getSource())
                && !isGameControllerKeyCode(event.getKeyCode())) {
            return false;
        }
        int action = event.getAction();
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR && action == KeyEvent.ACTION_UP) {
            boolean nextEnabled = !controllerMouseModeEnabled;
            releaseControllerMouseButton("mode-toggle", event.getEventTime(), true);
            controllerMouseModeEnabled = nextEnabled;
            controllerMouseAxisX = 0.0f;
            controllerMouseAxisY = 0.0f;
            lastControllerMouseFrameNanos = 0L;
            inputStatusText = String.format(
                    Locale.US,
                    "input: controller-mouse %s toggle=BUTTON_THUMBR",
                    controllerMouseModeEnabled ? "on" : "off");
            if (controllerMouseModeEnabled) {
                ensureBridgeCursorInitialized();
                showBridgeCursor(bridgeCursorX, bridgeCursorY);
                scheduleBridgeCursorHide(BRIDGE_CONTROLLER_CURSOR_HIDE_DELAY_MS);
                startInputFrameLoopIfNeeded();
            } else {
                inputFrameRunning = false;
                if (!running) {
                    Choreographer.getInstance().removeFrameCallback(this);
                }
                setBridgeCursorVisible(false);
            }
            return true;
        }
        if (controllerMouseModeEnabled
                && keyCode == KeyEvent.KEYCODE_BUTTON_Y
                && action == KeyEvent.ACTION_UP) {
            toggleBridgeIme();
            return true;
        }
        if (controllerMouseModeEnabled
                && keyCode == KeyEvent.KEYCODE_BUTTON_X
                && action == KeyEvent.ACTION_UP) {
            publishBridgeClipboardRequest("controller-x");
            return true;
        }
        if (controllerMouseModeEnabled
                && keyCode == KeyEvent.KEYCODE_BUTTON_B
                && action == KeyEvent.ACTION_UP) {
            publishBridgeClipboardPaste("controller-b");
            return true;
        }
        if (!controllerMouseModeEnabled
                || (keyCode != KeyEvent.KEYCODE_BUTTON_A
                && keyCode != KeyEvent.KEYCODE_DPAD_CENTER)) {
            return false;
        }
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return true;
        }
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
            return true;
        }
        int viewWidth = hostView == null ? width : Math.max(1, hostView.getWidth());
        int viewHeight = hostView == null ? height : Math.max(1, hostView.getHeight());
        ensureBridgeCursorInitialized();
        showBridgeCursor(bridgeCursorX, bridgeCursorY);
        scheduleBridgeCursorHide(BRIDGE_CONTROLLER_CURSOR_HIDE_DELAY_MS);
        if (action == KeyEvent.ACTION_DOWN) {
            controllerMouseButtonDown = true;
        } else {
            controllerMouseButtonDown = false;
        }
        publishBridgePointerButtonLine(
                ++inputSequence,
                action == KeyEvent.ACTION_DOWN ? "down" : "up",
                bridgeCursorX,
                bridgeCursorY,
                viewWidth,
                viewHeight,
                0,
                event.getSource(),
                event.getEventTime());
        return true;
    }

    private void resetControllerMouseForTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_CANCEL) {
            return;
        }
        controllerMouseAxisX = 0.0f;
        controllerMouseAxisY = 0.0f;
        lastControllerMouseFrameNanos = 0L;
        if (controllerMouseButtonDown) {
            releaseControllerMouseButton("touch-reset", event.getEventTime(), true);
        }
    }

    private void releaseControllerMouseButton(String reason, long eventTime, boolean force) {
        if (!force && !controllerMouseButtonDown) {
            return;
        }
        int viewWidth = hostView == null ? width : Math.max(1, hostView.getWidth());
        int viewHeight = hostView == null ? height : Math.max(1, hostView.getHeight());
        ensureBridgeCursorInitialized();
        publishBridgePointerButtonLine(
                ++inputSequence,
                "up",
                bridgeCursorX,
                bridgeCursorY,
                viewWidth,
                viewHeight,
                0,
                InputDevice.SOURCE_JOYSTICK,
                eventTime);
        controllerMouseButtonDown = false;
        inputStatusText = String.format(
                Locale.US,
                "input: controller-mouse reset reason=%s",
                reason);
    }

    private void cancelBridgeTouchForGesture(MotionEvent event, String reason) {
        int viewWidth = hostView == null ? width : Math.max(1, hostView.getWidth());
        int viewHeight = hostView == null ? height : Math.max(1, hostView.getHeight());
        int pointerIndex = Math.min(Math.max(event.getActionIndex(), 0), event.getPointerCount() - 1);
        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        long now = event.getEventTime();
        long sequence = ++inputSequence;
        publishBridgePointerMoveLine(
                sequence,
                x,
                y,
                viewWidth,
                viewHeight,
                0,
                event.getSource(),
                now);
        publishBridgePointerButtonLine(
                sequence,
                "up",
                x,
                y,
                viewWidth,
                viewHeight,
                0,
                event.getSource(),
                now);
        publishBridgeInputLine(String.format(
                Locale.US,
                "input-v1 seq=%d kind=touch action=cancel width=%d height=%d source=0x%x time=%d",
                sequence,
                viewWidth,
                viewHeight,
                event.getSource(),
                now));
        inputStatusText = String.format(
                Locale.US,
                "input: touch cancel reason=%s",
                reason);
    }

    private void publishBridgeMotionInput(long sequence, String type, MotionEvent event) {
        if (!bridgeOwnsExternalSession()) {
            return;
        }
        int pointerCount = event.getPointerCount();
        if (pointerCount <= 0) {
            return;
        }
        int action = event.getActionMasked();
        int actionIndex = Math.min(Math.max(event.getActionIndex(), 0), pointerCount - 1);
        int viewWidth = hostView == null ? width : Math.max(1, hostView.getWidth());
        int viewHeight = hostView == null ? height : Math.max(1, hostView.getHeight());

        if (action == MotionEvent.ACTION_SCROLL) {
            publishBridgeInputLine(String.format(
                    Locale.US,
                    "input-v1 seq=%d kind=pointer action=scroll id=0 x=%.2f y=%.2f hscroll=%.3f vscroll=%.3f width=%d height=%d buttons=0x%x source=0x%x time=%d",
                    sequence,
                    event.getX(0),
                    event.getY(0),
                    event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL),
                    viewWidth,
                    viewHeight,
                    event.getButtonState(),
                    event.getSource(),
                    event.getEventTime()));
            return;
        }

        if ("touch".equals(type)) {
            publishTouchInput(sequence, event, action, actionIndex, pointerCount, viewWidth, viewHeight);
        }

        if ("touch".equals(type)
                || "hover".equals(type)
                || isPointerSource(event.getSource())) {
            publishPointerInput(sequence, event, action, actionIndex, viewWidth, viewHeight);
        }
    }

    private void publishTouchInput(
            long sequence,
            MotionEvent event,
            int action,
            int actionIndex,
            int pointerCount,
            int viewWidth,
            int viewHeight) {
        if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < pointerCount; i++) {
                publishTouchLine(sequence, event, "move", i, viewWidth, viewHeight);
            }
        } else if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            publishTouchLine(sequence, event, "down", actionIndex, viewWidth, viewHeight);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            publishTouchLine(sequence, event, "up", actionIndex, viewWidth, viewHeight);
        } else if (action == MotionEvent.ACTION_CANCEL) {
            publishBridgeInputLine(String.format(
                    Locale.US,
                    "input-v1 seq=%d kind=touch action=cancel width=%d height=%d source=0x%x time=%d",
                    sequence,
                    viewWidth,
                    viewHeight,
                    event.getSource(),
                    event.getEventTime()));
        }
    }

    private void publishTouchLine(
            long sequence,
            MotionEvent event,
            String action,
            int pointerIndex,
            int viewWidth,
            int viewHeight) {
        publishBridgeInputLine(String.format(
                Locale.US,
                "input-v1 seq=%d kind=touch action=%s id=%d x=%.2f y=%.2f width=%d height=%d pressure=%.3f buttons=0x%x source=0x%x time=%d",
                sequence,
                action,
                event.getPointerId(pointerIndex),
                event.getX(pointerIndex),
                event.getY(pointerIndex),
                viewWidth,
                viewHeight,
                event.getPressure(pointerIndex),
                event.getButtonState(),
                event.getSource(),
                event.getEventTime()));
    }

    private void publishPointerInput(
            long sequence,
            MotionEvent event,
            int action,
            int actionIndex,
            int viewWidth,
            int viewHeight) {
        int pointerIndex = actionIndex;
        if (action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_HOVER_MOVE
                || action == MotionEvent.ACTION_DOWN
                || action == MotionEvent.ACTION_UP) {
            pointerIndex = 0;
        }
        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        showBridgeCursor(x, y);
        scheduleBridgeCursorHide(BRIDGE_TOUCH_CURSOR_HIDE_DELAY_MS);
        publishBridgePointerMoveLine(
                sequence,
                x,
                y,
                viewWidth,
                viewHeight,
                event.getButtonState(),
                event.getSource(),
                event.getEventTime());
        if (action == MotionEvent.ACTION_DOWN) {
            publishBridgePointerButtonLine(
                    sequence,
                    "down",
                    event.getX(0),
                    event.getY(0),
                    viewWidth,
                    viewHeight,
                    event.getButtonState(),
                    event.getSource(),
                    event.getEventTime());
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            publishBridgePointerButtonLine(
                    sequence,
                    "up",
                    event.getX(0),
                    event.getY(0),
                    viewWidth,
                    viewHeight,
                    event.getButtonState(),
                    event.getSource(),
                    event.getEventTime());
        }
    }

    private void publishBridgePointerMoveLine(
            long sequence,
            float x,
            float y,
            int viewWidth,
            int viewHeight,
            int buttons,
            int source,
            long eventTime) {
        publishBridgeInputLine(String.format(
                Locale.US,
                "input-v1 seq=%d kind=pointer action=move id=0 x=%.2f y=%.2f width=%d height=%d buttons=0x%x source=0x%x time=%d",
                sequence,
                x,
                y,
                viewWidth,
                viewHeight,
                buttons,
                source,
                eventTime));
    }

    private void publishBridgePointerButtonLine(
            long sequence,
            String state,
            float x,
            float y,
            int viewWidth,
            int viewHeight,
            int buttons,
            int source,
            long eventTime) {
        publishBridgeInputLine(String.format(
                Locale.US,
                "input-v1 seq=%d kind=pointer action=button id=0 button=left state=%s x=%.2f y=%.2f width=%d height=%d buttons=0x%x source=0x%x time=%d",
                sequence,
                state,
                x,
                y,
                viewWidth,
                viewHeight,
                buttons,
                source,
                eventTime));
    }

    private void ensureBridgeCursorInitialized() {
        int viewWidth = hostView == null ? width : Math.max(1, hostView.getWidth());
        int viewHeight = hostView == null ? height : Math.max(1, hostView.getHeight());
        if (bridgeCursorX <= 0.0f && bridgeCursorY <= 0.0f) {
            bridgeCursorX = viewWidth * 0.5f;
            bridgeCursorY = viewHeight * 0.5f;
        }
    }

    private void showBridgeCursor(float x, float y) {
        int viewWidth = hostView == null ? width : Math.max(1, hostView.getWidth());
        int viewHeight = hostView == null ? height : Math.max(1, hostView.getHeight());
        bridgeCursorX = clampFloat(x, 0.0f, viewWidth - 1.0f);
        bridgeCursorY = clampFloat(y, 0.0f, viewHeight - 1.0f);
        View cursor = cursorView;
        if (cursor == null) {
            return;
        }
        // Arrow cursor hotspot is at top-left (0,0), not center.
        // Position the cursor so its top-left corner is at the touch point.
        cursor.setTranslationX(bridgeCursorX);
        cursor.setTranslationY(bridgeCursorY);
        cursor.bringToFront();
        cursor.setVisibility(View.VISIBLE);
        drawBridgeCursorSurface();
    }

    private void setBridgeCursorVisible(boolean visible) {
        View cursor = cursorView;
        if (cursor == null) {
            return;
        }
        mainHandler.removeCallbacks(hideBridgeCursorRunnable);
        cursor.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    private void scheduleBridgeCursorHide(long delayMs) {
        mainHandler.removeCallbacks(hideBridgeCursorRunnable);
        mainHandler.postDelayed(hideBridgeCursorRunnable, Math.max(0L, delayMs));
    }

    private void drawBridgeCursorSurface() {
        SurfaceView cursor = cursorView;
        if (cursor == null || !cursor.getHolder().getSurface().isValid()) {
            return;
        }
        Canvas canvas = null;
        try {
            canvas = cursor.getHolder().lockCanvas();
            if (canvas == null) {
                return;
            }
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

            // Draw a standard Windows-style arrow cursor
            Paint cursorPaint = smallPaint;
            float w = BRIDGE_CURSOR_SIZE_PX;
            float h = BRIDGE_CURSOR_SIZE_PX;

            // Arrow path: standard arrow cursor shape
            android.graphics.Path arrowPath = new android.graphics.Path();
            // Start at top-left (hotspot position)
            arrowPath.moveTo(0, 0);
            // Go right along the top edge
            arrowPath.lineTo(w * 0.65f, 0);
            // Diagonal down-right to the point
            arrowPath.lineTo(w * 0.35f, h * 0.30f);
            // Right edge of the stem
            arrowPath.lineTo(w * 0.55f, h * 0.30f);
            // Down to the bottom-right of the stem
            arrowPath.lineTo(w * 0.25f, h * 0.65f);
            // Left edge of the stem
            arrowPath.lineTo(w * 0.15f, h * 0.55f);
            // Up to the bottom-left of the arrowhead
            arrowPath.lineTo(0, h * 0.45f);
            // Close back to start
            arrowPath.close();

            // Draw white fill
            cursorPaint.setStyle(Paint.Style.FILL);
            cursorPaint.setColor(Color.WHITE);
            cursorPaint.setAntiAlias(true);
            canvas.drawPath(arrowPath, cursorPaint);

            // Draw black outline
            cursorPaint.setStyle(Paint.Style.STROKE);
            cursorPaint.setStrokeWidth(2.0f);
            cursorPaint.setColor(Color.BLACK);
            canvas.drawPath(arrowPath, cursorPaint);

            cursorPaint.setStyle(Paint.Style.FILL);
        } finally {
            if (canvas != null) {
                cursor.getHolder().unlockCanvasAndPost(canvas);
            }
        }
    }

    private static float strongestAxis(float primary, float secondary, float fallback) {
        float selected = Math.abs(primary) >= Math.abs(secondary) ? primary : secondary;
        return Math.abs(selected) >= CONTROLLER_MOUSE_DEADZONE ? selected : fallback;
    }

    private static float applyControllerDeadzone(float value) {
        float magnitude = Math.abs(value);
        if (magnitude < CONTROLLER_MOUSE_DEADZONE) {
            return 0.0f;
        }
        float normalized = (magnitude - CONTROLLER_MOUSE_DEADZONE)
                / (1.0f - CONTROLLER_MOUSE_DEADZONE);
        float shaped = normalized * normalized;
        return value < 0.0f ? -shaped : shaped;
    }

    private static float clampFloat(float value, float minimum, float maximum) {
        if (value < minimum) {
            return minimum;
        }
        if (value > maximum) {
            return maximum;
        }
        return value;
    }

    private void publishBridgeKeyInput(long sequence, KeyEvent event) {
        if (!bridgeOwnsExternalSession()) {
            return;
        }
        publishBridgeInputLine(String.format(
                Locale.US,
                "input-v1 seq=%d kind=key action=%s keycode=%d scancode=%d repeat=%d source=0x%x time=%d",
                sequence,
                event.getAction() == KeyEvent.ACTION_DOWN ? "down" : "up",
                event.getKeyCode(),
                event.getScanCode(),
                event.getRepeatCount(),
                event.getSource(),
                event.getEventTime()));
    }

    private void publishBridgeSpecialKeyInput(int keyCode) {
        long now = System.currentTimeMillis();
        long sequence = ++inputSequence;
        publishBridgeInputLine(String.format(
                Locale.US,
                "input-v1 seq=%d kind=key action=down keycode=%d scancode=0 repeat=0 source=ime time=%d",
                sequence,
                keyCode,
                now));
        publishBridgeInputLine(String.format(
                Locale.US,
                "input-v1 seq=%d kind=key action=up keycode=%d scancode=0 repeat=0 source=ime time=%d",
                sequence,
                keyCode,
                now));
    }

    private void publishBridgeTextInput(String text) {
        publishBridgeTextInput(text, "ime");
    }

    private void publishBridgeTextInput(String text, String source) {
        if (!bridgeOwnsExternalSession() || text == null || text.isEmpty()) {
            return;
        }
        long sequence = ++inputSequence;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        publishBridgeInputLine(String.format(
                Locale.US,
                "input-v1 seq=%d kind=text action=commit text_hex=%s source=%s time=%d",
                sequence,
                bytesToHex(bytes),
                source,
                System.currentTimeMillis()));
        inputStatusText = String.format(
                Locale.US,
                "input: seq=%d type=%s-text chars=%d",
                sequence,
                source,
                text.length());
    }

    private void publishBridgeClipboardPaste(String source) {
        if (!bridgeOwnsExternalSession()) {
            return;
        }
        ClipboardManager clipboardManager =
                (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboardManager == null || !clipboardManager.hasPrimaryClip()
                || clipboardManager.getPrimaryClip() == null
                || clipboardManager.getPrimaryClip().getItemCount() <= 0) {
            inputStatusText = "input: clipboard paste empty";
            return;
        }
        CharSequence text = clipboardManager.getPrimaryClip()
                .getItemAt(0)
                .coerceToText(this);
        if (text == null || text.length() == 0) {
            inputStatusText = "input: clipboard paste empty";
            return;
        }
        publishBridgeTextInput(text.toString(), "android-clipboard");
        inputStatusText = String.format(
                Locale.US,
                "input: clipboard paste source=%s chars=%d",
                source,
                text.length());
    }

    private void publishBridgeClipboardRequest(String source) {
        if (!bridgeOwnsExternalSession()) {
            return;
        }
        long sequence = ++inputSequence;
        publishBridgeInputLine(String.format(
                Locale.US,
                "input-v1 seq=%d kind=clipboard action=request selection=auto copy=1 source=%s time=%d",
                sequence,
                source,
                System.currentTimeMillis()));
        inputStatusText = String.format(
                Locale.US,
                "input: seq=%d clipboard request source=%s",
                sequence,
                source);
    }

    private static String bytesToHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        final char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            hex[i * 2] = alphabet[value >>> 4];
            hex[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(hex);
    }

    private void publishBridgeInputLine(String line) {
        ArrayList<BridgeInputStream> snapshot;
        synchronized (bridgeInputStreamLock) {
            if (bridgeInputStreams.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(bridgeInputStreams);
        }
        ArrayList<BridgeInputStream> failed = null;
        for (BridgeInputStream stream : snapshot) {
            if (!stream.writeLine(line)) {
                if (failed == null) {
                    failed = new ArrayList<>();
                }
                failed.add(stream);
            }
        }
        if (failed != null) {
            synchronized (bridgeInputStreamLock) {
                bridgeInputStreams.removeAll(failed);
            }
        }
    }

    private static String classifyGenericMotionEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_SCROLL) {
            return "scroll";
        }
        if (isGameControllerSource(event.getSource())) {
            return "controller-motion";
        }
        if (action == MotionEvent.ACTION_HOVER_ENTER
                || action == MotionEvent.ACTION_HOVER_MOVE
                || action == MotionEvent.ACTION_HOVER_EXIT) {
            return "hover";
        }
        return "generic-motion";
    }

    private static String keyActionToString(int action) {
        if (action == KeyEvent.ACTION_DOWN) {
            return "ACTION_DOWN";
        }
        if (action == KeyEvent.ACTION_UP) {
            return "ACTION_UP";
        }
        return "ACTION_" + action;
    }

    private void releaseImageWhenSafe(Image image, SyncFence fence) {
        if (fence != null) {
            fence.awaitForever();
            fence.close();
        }

        image.close();
        inFlightImages.remove(image);
    }

    private void releaseAhbFrameWhenSafe(AhbInFlightFrame frame, SyncFence fence) {
        boolean fenceSignaled = true;
        RuntimeException fenceError = null;
        if (fence != null) {
            try {
                fenceSignaled = fence.awaitForever();
            } catch (RuntimeException error) {
                fenceError = error;
            } finally {
                fence.close();
            }
        }
        // The Java HardwareBuffer wrapper is closed only after SurfaceControl's
        // release callback and release fence say this submitted buffer is safe.
        closeAhbWrapperOnce(frame);
        String releaseStatus = releaseNativeAhbSlot(frame);
        synchronized (ahbInFlightLock) {
            inFlightAhbFrames.remove(frame);
        }
        if (fenceError != null) {
            lastAhbReleaseStatus = releaseStatus + " fence-error "
                    + fenceError.getClass().getSimpleName();
        } else {
            lastAhbReleaseStatus = fenceSignaled
                    ? releaseStatus
                    : releaseStatus + " fence-wait-failed";
        }
    }

    private void releaseUnsubmittedAhbFrame(AhbInFlightFrame frame) {
        synchronized (ahbInFlightLock) {
            inFlightAhbFrames.remove(frame);
        }
        closeAhbWrapperOnce(frame);
        lastAhbReleaseStatus = releaseNativeAhbSlot(frame);
    }

    private void closeUnsubmittedAhbFrame(AhbCpuFrame frame) {
        closeUnsubmittedAhbFrame(frame, false);
    }

    private void closeUnsubmittedAhbFrame(AhbCpuFrame frame, boolean vulkan) {
        if (frame.buffer != null && !frame.buffer.isClosed()) {
            frame.buffer.close();
        }
        if (frame.slot >= 0) {
            try {
                lastAhbReleaseStatus = vulkan
                        ? nativeReleaseAhbVkSlot(frame.slot, frame.generation)
                        : nativeReleaseAhbCpuSlot(frame.slot, frame.generation);
            } catch (RuntimeException | UnsatisfiedLinkError error) {
                lastAhbReleaseStatus = (vulkan ? "ahb-vk" : "ahb-cpu") + " release failed: "
                        + error.getClass().getSimpleName();
            }
        }
    }

    private static void closeAhbWrapperOnce(AhbInFlightFrame frame) {
        if (!frame.wrapperClosed && !frame.buffer.isClosed()) {
            frame.buffer.close();
            frame.wrapperClosed = true;
        }
    }

    private String releaseNativeAhbSlot(AhbInFlightFrame frame) {
        try {
            return frame.vulkan
                    ? nativeReleaseAhbVkSlot(frame.slot, frame.generation)
                    : nativeReleaseAhbCpuSlot(frame.slot, frame.generation);
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            if (error instanceof UnsatisfiedLinkError) {
                nativeLibraryAvailable = false;
            }
            return (frame.vulkan ? "ahb-vk" : "ahb-cpu") + " release failed: "
                    + error.getClass().getSimpleName();
        }
    }

    private void pruneInFlightImages() {
        while (inFlightImages.size() > MAX_IN_FLIGHT_IMAGES) {
            Image oldImage = inFlightImages.removeFirst();
            oldImage.close();
        }
    }

    private void releasePipeline() {
        releasePipeline(false);
    }

    private void releasePipeline(boolean finalTeardown) {
        stopVulkanRenderWorker();
        boolean keepExternalPresentProducer = !finalTeardown
                && externalPresentOnlyEnabled
                && bridgeServerEnabled;
        if (!keepExternalPresentProducer) {
            releaseOutstandingAhbFramesForTeardown();
            resetNativeAhbVkProducer();
            resetNativeAhbCpuProducer();
        } else {
            lastAhbReleaseStatus = "ahb-vk kept for external-present";
        }

        for (Iterator<Image> iterator = inFlightImages.iterator(); iterator.hasNext(); ) {
            Image image = iterator.next();
            image.close();
            iterator.remove();
        }

        if (producerSurface != null) {
            producerSurface.release();
            producerSurface = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        SurfaceControl layerToRelease;
        synchronized (vulkanRenderLock) {
            layerToRelease = presentLayer;
            presentLayer = null;
            vulkanPipelineGeneration++;
        }
        boolean releaseFailed = false;
        if (layerToRelease != null) {
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                transaction.setVisibility(layerToRelease, false).apply();
            } catch (RuntimeException error) {
                releaseFailed = true;
                setBridgePresenterFailureStatus("release", error);
            }
            layerToRelease.release();
        }
        if (!releaseFailed) {
            setBridgePresenterStatus("released");
        }
    }

    private void releaseOutstandingAhbFramesForTeardown() {
        ArrayDeque<AhbInFlightFrame> framesToRelease = new ArrayDeque<>();
        synchronized (ahbInFlightLock) {
            while (!inFlightAhbFrames.isEmpty()) {
                framesToRelease.addLast(inFlightAhbFrames.removeFirst());
            }
        }

        while (!framesToRelease.isEmpty()) {
            AhbInFlightFrame frame = framesToRelease.removeFirst();
            closeAhbWrapperOnce(frame);
            lastAhbReleaseStatus = releaseNativeAhbSlot(frame);
        }
    }

    private void resetNativeAhbCpuProducer() {
        if (!nativeLibraryAvailable) {
            return;
        }

        try {
            nativeResetAhbCpuProducer();
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            if (error instanceof UnsatisfiedLinkError) {
                nativeLibraryAvailable = false;
            }
            lastAhbReleaseStatus = "ahb-cpu reset failed: "
                    + error.getClass().getSimpleName();
        }
    }

    private void resetNativeAhbVkProducer() {
        if (!nativeLibraryAvailable) {
            return;
        }

        try {
            nativeResetAhbVkProducer();
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            if (error instanceof UnsatisfiedLinkError) {
                nativeLibraryAvailable = false;
            }
            lastAhbReleaseStatus = "ahb-vk reset failed: "
                    + error.getClass().getSimpleName();
        }
    }

    private String formatNativeWindowStatus(String status) {
        if (vulkanFallbackReason == null && ahbCpuFallbackReason == null) {
            return status;
        }

        String fallbackReason = ahbCpuFallbackReason;
        if (vulkanFallbackReason != null && ahbCpuFallbackReason != null) {
            fallbackReason = compactProducerStatus(vulkanFallbackReason)
                    + "; "
                    + compactProducerStatus(ahbCpuFallbackReason);
        } else if (vulkanFallbackReason != null) {
            fallbackReason = vulkanFallbackReason;
        }

        return String.format(
                Locale.US,
                "producer: native-window fallback (%s); %s",
                compactProducerStatus(fallbackReason),
                compactProducerStatus(status));
    }

    private static String compactProducerStatus(String status) {
        if (status == null) {
            return "unknown";
        }

        String compact = status.startsWith("producer: ")
                ? status.substring("producer: ".length())
                : status;
        if (compact.length() <= 96) {
            return compact;
        }
        return compact.substring(0, 93) + "...";
    }

    private static String compactBridgeText(String text) {
        if (text == null) {
            return "null";
        }

        StringBuilder builder = new StringBuilder(Math.min(text.length(), 240));
        for (int i = 0; i < text.length() && builder.length() < 240; i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') {
                builder.append(' ');
            } else if (c < 0x20 || c == '"') {
                builder.append('_');
            } else {
                builder.append(c);
            }
        }
        if (text.length() > builder.length()) {
            builder.append("...");
        }
        return builder.toString();
    }

    private static String compactBridgeThrowable(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            return compactBridgeText(error.getClass().getSimpleName());
        }
        return compactBridgeText(error.getClass().getSimpleName() + ":" + message);
    }

    private static String bridgeValueToken(String text) {
        String compact = compactBridgeText(text);
        StringBuilder builder = new StringBuilder(compact.length());
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (c <= 0x20 || c == '"' || c == '\'') {
                builder.append('_');
            } else {
                builder.append(c);
            }
        }
        return builder.length() == 0 ? "empty" : builder.toString();
    }

    private static String buildBridgeTimingFields(String producerFields) {
        long waitUs = extractProducerMicroseconds(producerFields, "wait");
        if (waitUs < 0L) {
            waitUs = extractProducerMicroseconds(producerFields, "submit");
        }
        long slotWaitUs = extractProducerMicroseconds(producerFields, "slot-wait");
        long sourceWaitUs = extractProducerMicroseconds(producerFields, "source-wait");
        return String.format(
                Locale.US,
                " wait=%dus slot-wait=%dus source-wait=%dus",
                sanitizeMicros(waitUs),
                sanitizeMicros(slotWaitUs),
                sanitizeMicros(sourceWaitUs));
    }

    private static long sanitizeMicros(long value) {
        return value < 0L ? 0L : value;
    }

    private static long extractProducerMicroseconds(String text, String field) {
        if (text == null || text.isEmpty() || field == null || field.isEmpty()) {
            return -1L;
        }

        int fieldLength = field.length();
        int position = 0;
        while (position < text.length()) {
            int match = text.indexOf(field, position);
            if (match < 0) {
                return -1L;
            }
            if (match > 0 && text.charAt(match - 1) == '-') {
                position = match + fieldLength;
                continue;
            }

            int cursor = match + fieldLength;
            if (cursor < text.length()) {
                char separator = text.charAt(cursor);
                if (separator == '=' || separator == '_' || Character.isWhitespace(separator)) {
                    cursor++;
                    while (cursor < text.length()
                            && (text.charAt(cursor) < '0' || text.charAt(cursor) > '9')) {
                        cursor++;
                    }
                    int start = cursor;
                    while (cursor < text.length()
                            && text.charAt(cursor) >= '0'
                            && text.charAt(cursor) <= '9') {
                        cursor++;
                    }
                    if (cursor > start
                            && cursor + 1 < text.length()
                            && text.charAt(cursor) == 'u'
                            && text.charAt(cursor + 1) == 's') {
                        try {
                            return Long.parseLong(text.substring(start, cursor));
                        } catch (NumberFormatException ignored) {
                            return -1L;
                        }
                    }
                }
            }
            position = match + fieldLength;
        }
        return -1L;
    }

    private static String compactOverlayText(String text, int maxChars) {
        String compact = compactBridgeText(text);
        if (compact.startsWith("vulkan: ")) {
            compact = compact.substring("vulkan: ".length());
        } else if (compact.startsWith("native-bridge: ")) {
            compact = compact.substring("native-bridge: ".length());
        } else if (compact.startsWith("compositor: ")) {
            compact = compact.substring("compositor: ".length());
        }
        if (compact.length() <= maxChars) {
            return compact;
        }
        int keep = Math.max(8, maxChars - 3);
        return compact.substring(0, keep) + "...";
    }

    private static final class BridgeFdInspection {
        final String fdTarget;
        final long size;
        final int mode;

        BridgeFdInspection(String fdTarget, long size, int mode) {
            this.fdTarget = fdTarget;
            this.size = size;
            this.mode = mode;
        }
    }

    private static final class BridgeMetaNumber {
        final BigInteger value;
        final String token;

        BridgeMetaNumber(BigInteger value, String token) {
            this.value = value;
            this.token = token;
        }
    }

    private static BridgeFdInspection inspectBridgeFd(FileInputStream ancillaryStream) {
        String fdTarget = "unknown";
        long size = -1L;
        int mode = 0;
        try (ParcelFileDescriptor duplicate =
                     ParcelFileDescriptor.dup(ancillaryStream.getFD())) {
            int duplicatedFdNumber = duplicate.getFd();
            FileDescriptor duplicatedFd = duplicate.getFileDescriptor();
            try {
                fdTarget = Os.readlink("/proc/self/fd/" + duplicatedFdNumber);
            } catch (ErrnoException error) {
                fdTarget = "readlink-" + error.errno;
            }
            try {
                StructStat stat = Os.fstat(duplicatedFd);
                size = stat.st_size;
                mode = stat.st_mode;
            } catch (ErrnoException error) {
                size = -1L;
                mode = 0;
            }
        } catch (IOException error) {
            fdTarget = "dup-" + bridgeValueToken(error.getClass().getSimpleName());
        }
        return new BridgeFdInspection(fdTarget, size, mode);
    }

    private static BridgeMetaNumber parseBridgeMetaNumber(String command, String key) {
        String value = findBridgeMetaValue(command, key);
        if (value == null) {
            return new BridgeMetaNumber(null, "missing");
        }
        if (value.isEmpty()) {
            return new BridgeMetaNumber(null, "invalid");
        }
        try {
            BigInteger parsed = parseBridgeMetaBigInteger(value);
            return new BridgeMetaNumber(parsed, parsed.toString());
        } catch (NumberFormatException ignored) {
            return new BridgeMetaNumber(null, "invalid");
        }
    }

    private static BigInteger parseBridgeMetaBigInteger(String value) {
        if (value == null || value.isEmpty()) {
            throw new NumberFormatException("empty");
        }

        int signIndex = 0;
        if (value.charAt(0) == '+' || value.charAt(0) == '-') {
            signIndex = 1;
        }
        if (signIndex == value.length()) {
            throw new NumberFormatException("sign-only");
        }

        boolean hex = value.regionMatches(true, signIndex, "0x", 0, 2);
        if (!hex) {
            return new BigInteger(value, 10);
        }

        int digitsStart = signIndex + 2;
        if (digitsStart == value.length()) {
            throw new NumberFormatException("hex-prefix-only");
        }
        BigInteger parsed = new BigInteger(value.substring(digitsStart), 16);
        return signIndex == 1 && value.charAt(0) == '-'
                ? parsed.negate()
                : parsed;
    }

    private static boolean isBridgeMetaGreaterThan(BridgeMetaNumber number, BigInteger minimum) {
        return number.value != null && number.value.compareTo(minimum) > 0;
    }

    private static boolean isBridgeMetaGreaterThanOrEqual(
            BridgeMetaNumber number,
            BigInteger minimum) {
        return number.value != null && number.value.compareTo(minimum) >= 0;
    }

    private static boolean isBridgeMetaInRangeInclusive(
            BridgeMetaNumber number,
            BigInteger minimum,
            BigInteger maximum) {
        return number.value != null
                && number.value.compareTo(minimum) >= 0
                && number.value.compareTo(maximum) <= 0;
    }

    private static boolean isBridgeMetaSizeSufficient(
            BridgeMetaNumber size,
            BridgeMetaNumber offset0,
            BridgeMetaNumber stride0,
            BridgeMetaNumber height) {
        if (size.value == null
                || offset0.value == null
                || stride0.value == null
                || height.value == null
                || offset0.value.compareTo(BRIDGE_META_ZERO) < 0
                || stride0.value.compareTo(BRIDGE_META_ZERO) <= 0
                || height.value.compareTo(BRIDGE_META_ZERO) <= 0
                || size.value.compareTo(BRIDGE_META_ZERO) <= 0) {
            return false;
        }

        BigInteger minimumSize = offset0.value.add(stride0.value.multiply(height.value));
        return size.value.compareTo(minimumSize) >= 0;
    }

    private static boolean bridgeMetaFitsSignedLong(BridgeMetaNumber number) {
        return number.value != null && number.value.signum() >= 0 && number.value.bitLength() <= 63;
    }

    private static long bridgeMetaToSignedLong(BridgeMetaNumber number) {
        return number.value.longValueExact();
    }

    private static int bridgeMetaToSignedInt(BridgeMetaNumber number) {
        return number.value.intValueExact();
    }

    private String ensureBridgeDmaBufDriverReady(
            File tmpDir,
            File driverDir,
            String driverName) {
        if (driverName.indexOf('/') >= 0 || driverName.indexOf('\\') >= 0) {
            setBridgeDmaBufDriverStatus("fail", "bad-driver-name", driverName, driverDir);
            return "bad-driver-name";
        }

        String cacheKey = driverDir.getAbsolutePath() + File.separator + driverName;
        if (cacheKey.equals(bridgeDmaBufReadyDriverCacheKey)) {
            return null;
        }
        if (!tmpDir.isDirectory() && !tmpDir.mkdirs()) {
            setBridgeDmaBufDriverStatus("fail", "tmp-dir", driverName, driverDir);
            return "tmp-dir";
        }
        if (!driverDir.isDirectory() && !driverDir.mkdirs()) {
            setBridgeDmaBufDriverStatus("fail", "driver-dir", driverName, driverDir);
            return "driver-dir";
        }
        if (!new File(driverDir, driverName).isFile()) {
            setBridgeDmaBufDriverStatus("fail", "driver-missing", driverName, driverDir);
            return "driver-missing";
        }

        bridgeDmaBufReadyDriverCacheKey = cacheKey;
        setBridgeDmaBufDriverStatus("ready", null, driverName, driverDir);
        return null;
    }

    private static String findBridgeMetaValue(String command, String key) {
        if (command == null || command.isEmpty() || key == null || key.isEmpty()) {
            return null;
        }

        int length = command.length();
        int position = 0;
        int keyLength = key.length();
        while (position < length) {
            while (position < length && Character.isWhitespace(command.charAt(position))) {
                position++;
            }
            int tokenStart = position;
            while (position < length && !Character.isWhitespace(command.charAt(position))) {
                position++;
            }
            int tokenEnd = position;
            int equals = -1;
            for (int i = tokenStart; i < tokenEnd; i++) {
                if (command.charAt(i) == '=') {
                    equals = i;
                    break;
                }
            }
            if (equals > tokenStart
                    && equals - tokenStart == keyLength
                    && command.regionMatches(tokenStart, key, 0, keyLength)) {
                return command.substring(equals + 1, tokenEnd);
            }
        }
        return null;
    }

    private static int parseBridgeIntOption(
            String command,
            String key,
            int defaultValue,
            int minimum,
            int maximum) {
        String value = findBridgeMetaValue(command, key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum) {
                return minimum;
            }
            if (parsed > maximum) {
                return maximum;
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String classifyBridgeFdKind(String fdTarget) {
        if (fdTarget == null || fdTarget.isEmpty()) {
            return "other";
        }

        String normalized = compactBridgeText(fdTarget).toLowerCase(Locale.US);
        if (normalized.startsWith("/dmabuf")
                || normalized.contains("anon_inode:dmabuf")
                || normalized.contains("anon_inode:[dmabuf]")
                || normalized.contains("dma-buf")) {
            return "dmabuf";
        }
        if (normalized.contains("memfd")) {
            return "memfd";
        }
        return "other";
    }

    private static String classifyBridgeSyncFdKind(String fdTarget) {
        if (fdTarget == null || fdTarget.isEmpty()) {
            return "other";
        }

        String normalized = compactBridgeText(fdTarget).toLowerCase(Locale.US);
        if (normalized.contains("eventfd")) {
            return "eventfd";
        }
        if (normalized.contains("sync_file") || normalized.contains("sync-file")) {
            return "sync-file";
        }
        if (normalized.contains("eventpoll")) {
            return "eventpoll";
        }
        if (normalized.contains("anon_inode")) {
            return "anon-inode";
        }
        return "other";
    }

    private float getDisplayRefreshRate() {
        Display display = getDisplay();
        if (display == null) {
            return 60.0f;
        }

        float refreshRate = display.getRefreshRate();
        return refreshRate > 1.0f ? refreshRate : 60.0f;
    }

    private float getHighestSameSizeDisplayRefreshRate() {
        Display display = getDisplay();
        if (display == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return getDisplayRefreshRate();
        }

        Display.Mode currentMode = display.getMode();
        Display.Mode[] modes = display.getSupportedModes();
        if (currentMode == null || modes == null || modes.length == 0) {
            return getDisplayRefreshRate();
        }

        float bestRefreshRate = currentMode.getRefreshRate();
        int currentWidth = currentMode.getPhysicalWidth();
        int currentHeight = currentMode.getPhysicalHeight();
        for (Display.Mode mode : modes) {
            if (mode == null) {
                continue;
            }
            boolean sameSize = mode.getPhysicalWidth() == currentWidth
                    && mode.getPhysicalHeight() == currentHeight;
            if (sameSize && mode.getRefreshRate() > bestRefreshRate + 0.1f) {
                bestRefreshRate = mode.getRefreshRate();
            }
        }
        return bestRefreshRate > 1.0f ? bestRefreshRate : getDisplayRefreshRate();
    }

    private void requestHighestRefreshRateMode(Window window) {
        if (window == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        Display display = getDisplay();
        if (display == null) {
            return;
        }

        Display.Mode currentMode = display.getMode();
        Display.Mode[] modes = display.getSupportedModes();
        if (currentMode == null || modes == null || modes.length == 0) {
            return;
        }

        Display.Mode bestMode = currentMode;
        int currentWidth = currentMode.getPhysicalWidth();
        int currentHeight = currentMode.getPhysicalHeight();
        for (Display.Mode mode : modes) {
            if (mode == null) {
                continue;
            }
            boolean sameSize = mode.getPhysicalWidth() == currentWidth
                    && mode.getPhysicalHeight() == currentHeight;
            boolean higherRefresh = mode.getRefreshRate() > bestMode.getRefreshRate() + 0.1f;
            if (sameSize && higherRefresh) {
                bestMode = mode;
            }
        }

        WindowManager.LayoutParams params = window.getAttributes();
        if (params.preferredDisplayModeId == bestMode.getModeId()
                && Math.abs(params.preferredRefreshRate - bestMode.getRefreshRate()) < 0.1f) {
            return;
        }
        params.preferredDisplayModeId = bestMode.getModeId();
        params.preferredRefreshRate = bestMode.getRefreshRate();
        window.setAttributes(params);
    }

    /**
     * Reflection-based shim for {@code SurfaceControl.Transaction.setDesiredPresentTimeNanos(long)}.
     * The method exists in the Android framework since API 30 but is annotated {@code @hide}
     * so it's not in the public {@code android.jar}. We call it via reflection; if the method
     * isn't found (older devices, custom ROMs) the call is silently dropped — the frame still
     * gets presented, just without an explicit target timestamp.
     *
     * <p>Returns the same transaction so callers can chain.
     */
    private static SurfaceControl.Transaction setDesiredPresentTimeNanosReflectively(
            SurfaceControl.Transaction transaction, long presentTimeNanos) {
        try {
            Method method = SurfaceControl.Transaction.class
                    .getMethod("setDesiredPresentTimeNanos", long.class);
            method.invoke(transaction, presentTimeNanos);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Method not available on this device — silently drop. Frame still presents.
        }
        return transaction;
    }

    private static String loadNativeStatusText() {
        try {
            System.loadLibrary(NATIVE_LIBRARY);
            nativeLibraryAvailable = true;
            return "native: " + nativeStatus();
        } catch (Throwable error) {
            // Catches Throwable — see class-level comment. Previously
            // this caught only SecurityException | UnsatisfiedLinkError,
            // which missed ExceptionInInitializerError, NoClassDefFoundError,
            // OutOfMemoryError, StackOverflowError. Any of those would
            // propagate out of the static initializer (when this method
            // was called eagerly) and kill the process via
            // ExceptionInInitializerError at HomeActivity.updateNativeStatus.
            nativeLibraryAvailable = false;
            try {
                io.waylandie.display.shared.util.LogRingBuffer.append(
                        "[MainActivity.loadNativeStatusText] " + error);
            } catch (Throwable ignored) {}
            return "native: unavailable " + error.getClass().getSimpleName();
        }
    }

    private static String loadNativeVulkanStatusText() {
        if (!nativeLibraryAvailable) {
            return "vulkan: unavailable native-library";
        }

        try {
            return nativeVulkanProbe();
        } catch (Throwable error) {
            // Catches Throwable — see loadNativeStatusText() above.
            if (error instanceof UnsatisfiedLinkError) {
                nativeLibraryAvailable = false;
            }
            try {
                io.waylandie.display.shared.util.LogRingBuffer.append(
                        "[MainActivity.loadNativeVulkanStatusText] " + error);
            } catch (Throwable ignored) {}
            return "vulkan: unavailable " + error.getClass().getSimpleName();
        }
    }

    /** Lazy accessor — double-checked locking. Never throws. */
    public static String getNativeStatusText() {
        if (nativeStatusText != null) return nativeStatusText;
        synchronized (statusTextLock) {
            if (nativeStatusText == null) {
                nativeStatusText = loadNativeStatusText();
            }
            return nativeStatusText;
        }
    }

    /** Lazy accessor — double-checked locking. Never throws. */
    public static String getVulkanStatusText() {
        if (vulkanStatusText != null) return vulkanStatusText;
        synchronized (statusTextLock) {
            if (vulkanStatusText == null) {
                vulkanStatusText = loadNativeVulkanStatusText();
            }
            return vulkanStatusText;
        }
    }

    public static native String nativeStatus();

    /**
     * Lightweight in-process probe. Returns "ok" if all critical symbols
     * resolve and {@code vkCreateInstance} succeeds (probe instance is
     * destroyed immediately). Returns "fail: <reason>" otherwise.
     *
     * <p>Called by {@code EnvironmentInitializer} before showing the
     * {@code Continue} button. If this returns non-{@code "ok"}, the
     * user sees the reason in the visible log and Continue is refused —
     * no force-close.
     */
    public static native String nativeProbeCompositor();

    private static native String nativeVulkanProbe();

    private static native String nativeProbeDmaBufImport(
            int fd,
            long width,
            long height,
            long drmFormat,
            long modifier,
            int planes,
            long stride0,
            long offset0,
            long size,
            String loader,
            String tmpDir,
            String hookLibDir,
            String driverDir,
            String driverName);

    private static native String nativeProbeKgslDmaBufImport(int fd);

    private static native String nativeProbeAdrenoTools(
            String tmpDir,
            String hookLibDir,
            String driverDir,
            String driverName);

    private static native String nativeProbeAhbExport(int socketFd);

    private static native String nativeExportAhbCpuSlot(int socketFd, int slot, long generation);

    private static native String nativeRenderProducerFrame(
            Surface surface,
            int width,
            int height,
            long frameIndex,
            long frameTimeNanos);

    private static native AhbCpuFrame nativeAcquireAhbCpuFrame(
            int width,
            int height,
            long frameIndex,
            long frameTimeNanos);

    private static native AhbCpuFrame nativeAcquireAhbVkFrame(
            int width,
            int height,
            long frameIndex,
            long frameTimeNanos,
            String tmpDir,
            String hookLibDir,
            String driverDir,
            String driverName);

    private static native AhbCpuFrame nativeAcquireAhbVkDmaBufFrame(
            int dmabufFd,
            int sourceWidth,
            int sourceHeight,
            long drmFormat,
            long modifier,
            int planes,
            long stride0,
            long offset0,
            long size,
            int targetWidth,
            int targetHeight,
            long frameIndex,
            String tmpDir,
            String hookLibDir,
            String driverDir,
            String driverName);

    private static native String nativePresentAhbVkDmaBufFrame(
            SurfaceControl targetLayer,
            int dmabufFd,
            int sourceWidth,
            int sourceHeight,
            long drmFormat,
            long modifier,
            int planes,
            long stride0,
            long offset0,
            long size,
            int targetWidth,
            int targetHeight,
            long frameIndex,
            String tmpDir,
            String hookLibDir,
            String driverDir,
            String driverName);

    private static native String nativeReleaseAhbCpuSlot(int slot, long generation);

    private static native String nativeReleaseAhbVkSlot(int slot, long generation);

    private static native void nativeResetAhbCpuProducer();

    private static native void nativeResetAhbVkProducer();

    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        WindowInsetsController controller = decor.getWindowInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }
}
