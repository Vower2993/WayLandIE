package io.waylandie.display;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Locale;

public final class LinuxWindowActivity extends Activity implements SurfaceHolder.Callback {
    private String windowId;
    private String appId;
    private String title;
    private SurfaceView surfaceView;
    private TextView overlayView;
    private SurfaceControl presentLayer;
    private int presentWidth;
    private int presentHeight;
    private long inputSequence;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setDecorFitsSystemWindows(true);

        windowId = getIntent().getStringExtra(WindowBridgeRegistry.EXTRA_WINDOW_ID);
        appId = getIntent().getStringExtra(WindowBridgeRegistry.EXTRA_APP_ID);
        title = getIntent().getStringExtra(WindowBridgeRegistry.EXTRA_TITLE);
        if (windowId == null || windowId.isEmpty()) {
            windowId = "window-" + System.identityHashCode(this);
        }
        if (appId == null || appId.isEmpty()) {
            appId = windowId;
        }
        if (title == null || title.isEmpty()) {
            title = appId;
        }
        setTitle(title);

        FrameLayout rootView = new FrameLayout(this);
        rootView.setBackgroundColor(Color.BLACK);
        rootView.setFocusable(true);
        rootView.setFocusableInTouchMode(true);

        surfaceView = new SurfaceView(this);
        surfaceView.setBackgroundColor(Color.BLACK);
        surfaceView.getHolder().addCallback(this);
        rootView.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        overlayView = new TextView(this);
        overlayView.setTextColor(Color.WHITE);
        overlayView.setTextSize(11.0f);
        overlayView.setIncludeFontPadding(false);
        overlayView.setPadding(10, 6, 10, 6);
        overlayView.setMaxLines(3);
        overlayView.setBackgroundColor(Color.argb(90, 0, 0, 0));
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        overlayParams.gravity = Gravity.START | Gravity.TOP;
        overlayParams.setMargins(8, 8, 8, 8);
        rootView.addView(overlayView, overlayParams);

        setContentView(rootView);
        rootView.requestFocus();

        WindowBridgeRegistry.WindowSpec spec = new WindowBridgeRegistry.WindowSpec(
                windowId,
                appId,
                title,
                getIntent().getIntExtra(WindowBridgeRegistry.EXTRA_X, 80),
                getIntent().getIntExtra(WindowBridgeRegistry.EXTRA_Y, 80),
                getIntent().getIntExtra(WindowBridgeRegistry.EXTRA_WIDTH, 960),
                getIntent().getIntExtra(WindowBridgeRegistry.EXTRA_HEIGHT, 600));
        WindowBridgeRegistry.registerActivity(this, spec);
        updateOverlay("activity-created");
    }

    @Override
    protected void onStart() {
        super.onStart();
        WindowBridgeRegistry.onAppActivityStarted(this);
    }

    @Override
    protected void onStop() {
        WindowBridgeRegistry.onAppActivityStopped();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        releasePresentLayer();
        WindowBridgeRegistry.unregisterActivity(windowId, this);
        super.onDestroy();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        publishSurface(holder, "surface-created");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        publishSurface(holder, "surface-changed");
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releasePresentLayer();
        WindowBridgeRegistry.surfaceDestroyed(windowId);
        updateOverlay("surface-destroyed");
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        recordInput("touch", event.getActionMasked());
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        recordInput("motion", event.getActionMasked());
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        recordInput("key", event.getKeyCode());
        return super.dispatchKeyEvent(event);
    }

    private void publishSurface(SurfaceHolder holder, String state) {
        Rect frame = holder.getSurfaceFrame();
        int width = Math.max(1, frame.width());
        int height = Math.max(1, frame.height());
        Surface surface = holder.getSurface();
        SurfaceControl targetLayer = ensurePresentLayer(width, height);
        WindowBridgeRegistry.surfaceChanged(windowId, surface, targetLayer, width, height);
        updateOverlay(state + " " + width + "x" + height);
    }

    private SurfaceControl ensurePresentLayer(int width, int height) {
        if (surfaceView == null || surfaceView.getSurfaceControl() == null) {
            return null;
        }
        if (presentLayer != null && presentWidth == width && presentHeight == height) {
            return presentLayer;
        }
        releasePresentLayer();
        presentWidth = width;
        presentHeight = height;
        presentLayer = new SurfaceControl.Builder()
                .setName("WayLandIELinuxWindowLayer:" + windowId)
                .setParent(surfaceView.getSurfaceControl())
                .setBufferSize(width, height)
                .setFormat(PixelFormat.RGBA_8888)
                .setOpaque(true)
                .setHidden(false)
                .build();
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction
                    .setLayer(presentLayer, 10)
                    .setVisibility(presentLayer, true)
                    .setAlpha(presentLayer, 1.0f)
                    .setPosition(presentLayer, 0.0f, 0.0f)
                    .setBufferSize(presentLayer, width, height)
                    .setCrop(presentLayer, new Rect(0, 0, width, height))
                    .apply();
        }
        return presentLayer;
    }

    private void releasePresentLayer() {
        SurfaceControl layer = presentLayer;
        presentLayer = null;
        presentWidth = 0;
        presentHeight = 0;
        if (layer != null) {
            layer.release();
        }
    }

    private void recordInput(String kind, int code) {
        inputSequence++;
        updateOverlay(String.format(
                Locale.US,
                "input %s code=%d seq=%d",
                kind,
                code,
                inputSequence));
    }

    private void updateOverlay(String state) {
        if (overlayView == null) {
            return;
        }
        String text = String.format(
                Locale.US,
                "%s\n%s\n%s",
                title,
                windowId,
                state);
        overlayView.setText(text);
        overlayView.setVisibility(View.VISIBLE);
    }
}
