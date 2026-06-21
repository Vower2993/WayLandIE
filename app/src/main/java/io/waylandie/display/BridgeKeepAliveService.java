package io.waylandie.display;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public final class BridgeKeepAliveService extends Service {
    private static final String CHANNEL_ID = "bridge_keepalive";
    private static final int NOTIFICATION_ID = 1001;

    /**
     * Pre-creates the notification channel. Call this BEFORE
     * {@link #start(Context)} to ensure the channel exists when the service's
     * onCreate() runs — saves precious milliseconds in the 5-second
     * startForeground() window.
     */
    static void preCreateChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Wayland bridge",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the Wayland bridge alive in the background.");
        manager.createNotificationChannel(channel);
    }

    static void start(Context context) {
        // Pre-create the channel BEFORE starting the service so onCreate()
        // doesn't have to do it inside the 5-second startForeground() window.
        preCreateChannel(context);
        Intent intent = new Intent(context, BridgeKeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, BridgeKeepAliveService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // CRITICAL: Call startForeground() IMMEDIATELY — Android 14+ gives
        // us 5 seconds from startForegroundService() to startForeground().
        // If we miss the window, the app crashes with
        // ForegroundServiceDidNotStartInTimeException.
        //
        // Build a MINIMAL notification (no PendingIntent, no heavy work)
        // and upgrade it later in onStartCommand().
        Notification minimal = buildMinimalNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: explicitly pass the foreground service type.
            // On Android 14+ this must match the manifest's foregroundServiceType.
            startForeground(NOTIFICATION_ID, minimal,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, minimal);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Upgrade to the full notification (with PendingIntent for tap-to-open).
        // This is safe to call after the minimal startForeground() in onCreate().
        Notification full = buildFullNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, full,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, full);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Minimal notification — used in onCreate() for the fastest possible
     * startForeground() call. No PendingIntent, no heavy builder options.
     */
    private Notification buildMinimalNotification() {
        // Channel is pre-created by start() → preCreateChannel() before the
        // service starts, so we can safely use it here without checking.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_bridge)
                    .setContentTitle("Wayland bridge running")
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .build();
        } else {
            return new Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_bridge)
                    .setContentTitle("Wayland bridge running")
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .build();
        }
    }

    /**
     * Full notification — used in onStartCommand() after the minimal
     * startForeground() has already satisfied the 5-second requirement.
     * Adds PendingIntent for tap-to-open-HomeActivity.
     */
    private Notification buildFullNotification() {
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_bridge)
                .setContentTitle("Wayland bridge running")
                .setContentText("Bridge stays active while you use other apps.")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}
