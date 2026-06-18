package io.waylandie.display;

import android.app.ActivityOptions;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceControl;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class WindowBridgeRegistry {
    static final String EXTRA_WINDOW_ID = "waylandie_window_id";
    static final String EXTRA_APP_ID = "waylandie_app_id";
    static final String EXTRA_TITLE = "waylandie_title";
    static final String EXTRA_X = "waylandie_x";
    static final String EXTRA_Y = "waylandie_y";
    static final String EXTRA_WIDTH = "waylandie_width";
    static final String EXTRA_HEIGHT = "waylandie_height";

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Map<String, WindowRecord> WINDOWS = new ConcurrentHashMap<>();
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final Object FOREGROUND_LOCK = new Object();
    private static int foregroundActivityCount;

    private WindowBridgeRegistry() {
    }

    static String openWindow(Context context, WindowSpec spec) {
        if (context == null) {
            return "status=fail reason=no-context";
        }
        if (spec == null || spec.id == null || spec.id.isEmpty()) {
            return "status=fail reason=missing-id";
        }

        WindowRecord record = WINDOWS.compute(spec.id, (id, existing) -> {
            WindowRecord next = existing == null ? new WindowRecord(id) : existing;
            next.appId = spec.appId;
            next.title = spec.title;
            next.bounds = spec.bounds();
            next.status = "launching";
            next.lastError = "";
            return next;
        });

        Context launchContext = context instanceof Application
                ? context
                : context.getApplicationContext();
        LinuxWindowActivity liveActivity = findLiveActivity(record);
        if (liveActivity != null) {
            record.pendingSpec = null;
            record.status = "activity-live";
            return String.format(
                    Locale.US,
                    "status=active id=%s app-id=%s title=%s bounds=%s count=%d",
                    token(spec.id),
                    token(spec.appId),
                    token(spec.title),
                    boundsToken(spec.bounds()),
                    WINDOWS.size());
        }
        if (!isAppForeground()) {
            record.pendingSpec = spec;
            record.status = "deferred-background";
            record.launchMode = "deferred";
            return String.format(
                    Locale.US,
                    "status=deferred reason=background id=%s app-id=%s title=%s bounds=%s count=%d",
                    token(spec.id),
                    token(spec.appId),
                    token(spec.title),
                    boundsToken(spec.bounds()),
                    WINDOWS.size());
        }
        record.pendingSpec = null;
        MAIN_HANDLER.post(() -> launchWindowOnMain(launchContext, spec, record));
        return String.format(
                Locale.US,
                "status=launching id=%s app-id=%s title=%s bounds=%s count=%d",
                token(spec.id),
                token(spec.appId),
                token(spec.title),
                boundsToken(spec.bounds()),
                WINDOWS.size());
    }

    static String closeWindow(String id) {
        if (id == null || id.isEmpty()) {
            return "status=fail reason=missing-id";
        }
        WindowRecord record = WINDOWS.get(id);
        if (record == null) {
            return String.format(Locale.US, "status=missing id=%s count=%d", token(id), WINDOWS.size());
        }

        record.status = "closing";
        LinuxWindowActivity activity = record.activityRef == null ? null : record.activityRef.get();
        if (activity != null) {
            MAIN_HANDLER.post(activity::finish);
        } else {
            WINDOWS.remove(id);
        }
        return String.format(Locale.US, "status=closing id=%s count=%d", token(id), WINDOWS.size());
    }

    static String status() {
        ArrayList<WindowRecord> records = new ArrayList<>(WINDOWS.values());
        Collections.sort(records, Comparator.comparing(record -> record.id));
        StringBuilder builder = new StringBuilder();
        builder.append("status=pass count=").append(records.size());
        int emitted = 0;
        for (WindowRecord record : records) {
            if (emitted >= 12) {
                builder.append(" more=").append(records.size() - emitted);
                break;
            }
            builder.append(" window").append(emitted).append('=')
                    .append(token(record.id))
                    .append(',')
                    .append(token(record.status))
                    .append(',')
                    .append(record.surfaceWidth)
                    .append('x')
                    .append(record.surfaceHeight)
                    .append(',')
                    .append(boundsToken(record.bounds))
                    .append(',')
                    .append(token(record.launchMode))
                    .append(',')
                    .append(token(record.title));
            emitted++;
        }
        return builder.toString();
    }

    static WindowRecord findWindow(String id) {
        return id == null ? null : WINDOWS.get(id);
    }

    static void onAppActivityStarted(Context context) {
        boolean becameForeground;
        synchronized (FOREGROUND_LOCK) {
            foregroundActivityCount++;
            becameForeground = foregroundActivityCount == 1;
        }
        if (becameForeground) {
            launchDeferredWindows(context);
        }
    }

    static void onAppActivityStopped() {
        synchronized (FOREGROUND_LOCK) {
            if (foregroundActivityCount > 0) {
                foregroundActivityCount--;
            }
        }
    }

    static void registerActivity(LinuxWindowActivity activity, WindowSpec spec) {
        if (activity == null || spec == null || spec.id == null || spec.id.isEmpty()) {
            return;
        }
        WindowRecord record = WINDOWS.computeIfAbsent(spec.id, WindowRecord::new);
        record.activityRef = new WeakReference<>(activity);
        record.appId = spec.appId;
        record.title = spec.title;
        record.bounds = spec.bounds();
        record.status = "activity-created";
        record.lastError = "";
    }

    static void unregisterActivity(String id, LinuxWindowActivity activity) {
        WindowRecord record = findWindow(id);
        if (record == null) {
            return;
        }
        LinuxWindowActivity current = record.activityRef == null ? null : record.activityRef.get();
        if (current == null || current == activity) {
            record.status = "activity-destroyed";
            record.activityRef = null;
            record.surface = null;
            record.surfaceControl = null;
            WINDOWS.remove(id);
        }
    }

    static void surfaceCreated(
            String id,
            Surface surface,
            SurfaceControl surfaceControl,
            int width,
            int height) {
        WindowRecord record = findWindow(id);
        if (record == null) {
            return;
        }
        record.surface = surface;
        record.surfaceControl = surfaceControl;
        record.surfaceWidth = Math.max(0, width);
        record.surfaceHeight = Math.max(0, height);
        record.status = "surface-ready";
        record.lastError = "";
    }

    static void surfaceChanged(String id, Surface surface, SurfaceControl surfaceControl, int width, int height) {
        surfaceCreated(id, surface, surfaceControl, width, height);
    }

    static void surfaceDestroyed(String id) {
        WindowRecord record = findWindow(id);
        if (record == null) {
            return;
        }
        record.surface = null;
        record.surfaceControl = null;
        record.surfaceWidth = 0;
        record.surfaceHeight = 0;
        record.status = "surface-destroyed";
    }

    private static void launchWindowOnMain(Context context, WindowSpec spec, WindowRecord record) {
        try {
            if (!isAppForeground()) {
                record.pendingSpec = spec;
                record.status = "deferred-background";
                record.launchMode = "deferred";
                return;
            }
            LinuxWindowActivity liveActivity = findLiveActivity(record);
            if (liveActivity != null) {
                record.pendingSpec = null;
                record.status = "activity-live";
                return;
            }
            Intent intent = new Intent(context, LinuxWindowActivity.class);
            intent.putExtra(EXTRA_WINDOW_ID, spec.id);
            intent.putExtra(EXTRA_APP_ID, spec.appId);
            intent.putExtra(EXTRA_TITLE, spec.title);
            intent.putExtra(EXTRA_X, spec.x);
            intent.putExtra(EXTRA_Y, spec.y);
            intent.putExtra(EXTRA_WIDTH, spec.width);
            intent.putExtra(EXTRA_HEIGHT, spec.height);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);

            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchBounds(spec.bounds());
            requestFreeformWindowing(options, record);
            context.startActivity(intent, options.toBundle());
        } catch (RuntimeException error) {
            record.status = "launch-failed";
            record.lastError = error.getClass().getSimpleName();
        }
    }

    private static void launchDeferredWindows(Context context) {
        if (context == null) {
            return;
        }
        Context launchContext = context instanceof Application
                ? context
                : context.getApplicationContext();
        ArrayList<PendingWindowLaunch> pendingLaunches = new ArrayList<>();
        for (WindowRecord record : WINDOWS.values()) {
            WindowSpec spec = record.pendingSpec;
            if (spec == null || findLiveActivity(record) != null) {
                continue;
            }
            record.pendingSpec = null;
            record.status = "launching";
            pendingLaunches.add(new PendingWindowLaunch(spec, record));
        }
        for (PendingWindowLaunch pendingLaunch : pendingLaunches) {
            MAIN_HANDLER.post(() -> launchWindowOnMain(
                    launchContext,
                    pendingLaunch.spec,
                    pendingLaunch.record));
        }
    }

    private static boolean isAppForeground() {
        synchronized (FOREGROUND_LOCK) {
            return foregroundActivityCount > 0;
        }
    }

    private static LinuxWindowActivity findLiveActivity(WindowRecord record) {
        if (record == null || record.activityRef == null) {
            return null;
        }
        LinuxWindowActivity activity = record.activityRef.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return null;
        }
        return activity;
    }

    private static void requestFreeformWindowing(ActivityOptions options, WindowRecord record) {
        try {
            ActivityOptions.class
                    .getMethod("setLaunchWindowingMode", int.class)
                    .invoke(options, WINDOWING_MODE_FREEFORM);
            record.launchMode = "freeform-requested";
        } catch (ReflectiveOperationException | RuntimeException error) {
            record.launchMode = "bounds-only";
            record.lastError = error.getClass().getSimpleName();
        }
    }

    private static String boundsToken(Rect bounds) {
        if (bounds == null) {
            return "none";
        }
        return bounds.left + "," + bounds.top + "," + bounds.width() + "x" + bounds.height();
    }

    private static String token(String value) {
        if (value == null || value.isEmpty()) {
            return "empty";
        }
        StringBuilder builder = new StringBuilder(Math.min(value.length(), 96));
        for (int i = 0; i < value.length() && builder.length() < 96; i++) {
            char c = value.charAt(i);
            if (c <= 0x20 || c == '"' || c == '\'' || c == ',' || c == '=') {
                builder.append('_');
            } else {
                builder.append(c);
            }
        }
        return builder.length() == 0 ? "empty" : builder.toString();
    }

    static final class WindowSpec {
        final String id;
        final String appId;
        final String title;
        final int x;
        final int y;
        final int width;
        final int height;

        WindowSpec(String id, String appId, String title, int x, int y, int width, int height) {
            this.id = id;
            this.appId = appId == null || appId.isEmpty() ? id : appId;
            this.title = title == null || title.isEmpty() ? this.appId : title;
            this.x = Math.max(0, x);
            this.y = Math.max(0, y);
            this.width = Math.max(160, width);
            this.height = Math.max(120, height);
        }

        Rect bounds() {
            return new Rect(x, y, x + width, y + height);
        }
    }

    static final class WindowRecord {
        final String id;
        volatile String appId = "";
        volatile String title = "";
        volatile Rect bounds = new Rect(80, 80, 1040, 680);
        volatile String status = "created";
        volatile String launchMode = "pending";
        volatile String lastError = "";
        volatile WindowSpec pendingSpec;
        volatile WeakReference<LinuxWindowActivity> activityRef;
        volatile Surface surface;
        volatile SurfaceControl surfaceControl;
        volatile int surfaceWidth;
        volatile int surfaceHeight;

        WindowRecord(String id) {
            this.id = id;
        }
    }

    private static final class PendingWindowLaunch {
        final WindowSpec spec;
        final WindowRecord record;

        PendingWindowLaunch(WindowSpec spec, WindowRecord record) {
            this.spec = spec;
            this.record = record;
        }
    }
}
