package io.waylandie.display;

import android.app.Application;
import android.content.Intent;
import android.os.Build;
import android.os.FileObserver;
import android.util.Log;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import io.waylandie.display.shared.util.LogRingBuffer;
import io.waylandie.display.shared.util.CrashHandler;

/**
 * WayLandIEApplication — process-wide setup.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Initialize the {@link LogRingBuffer} (process-wide, 500 lines).</li>
 *   <li>Install {@link CrashHandler} as the global
 *       {@link Thread.UncaughtExceptionHandler}. The native side
 *       (sigaction + sigaltstack) is installed lazily from
 *       {@code JNI_OnLoad} of {@code libwaylandie_display_native.so}.</li>
 *   <li>On every launch, check for a fresh
 *       {@code crash-<ts>.txt} file in
 *       {@code getExternalFilesDir(null)/logs/}. If present, set a
 *       static flag so {@code EnvironmentInitializer} and
 *       {@code HomeActivity} route to {@link CrashReportActivity}
 *       instead of running setup or entering the compositor.</li>
 *   <li>Ship a {@code README.txt} in {@code getExternalFilesDir(null)/}
 *       on first launch, telling the user where to find the logs.</li>
 * </ul>
 *
 * <p>Registered in {@code AndroidManifest.xml} via
 * {@code android:name=".WayLandIEApplication"}.
 */
public final class WayLandIEApplication extends Application {

    private static final String TAG = "WayLandIE/App";

    private static final AtomicBoolean freshCrashFilePresent = new AtomicBoolean(false);
    private static volatile File mostRecentCrashFile = null;
    private static volatile io.waylandie.display.WayLandIEApplication instance = null;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        LogRingBuffer.init();
        Log.i(TAG, "WayLandIEApplication.onCreate — installing CrashHandler");

        CrashHandler handler = new CrashHandler(this,
                Thread.getDefaultUncaughtExceptionHandler());
        Thread.setDefaultUncaughtExceptionHandler(handler);

        File logsDir = new File(getExternalFilesDir(null), "logs");
        if (!logsDir.exists()) logsDir.mkdirs();
        shipReadme(getExternalFilesDir(null));

        File freshCrash = findMostRecentCrashFile(logsDir);
        if (freshCrash != null) {
            mostRecentCrashFile = freshCrash;
            freshCrashFilePresent.set(true);
            Log.w(TAG, "Fresh crash file detected: " + freshCrash.getAbsolutePath()
                    + " — routing to CrashReportActivity on next activity onCreate.");
        }
    }

    /** Returns true iff onCreate detected a crash file newer than 24h. */
    public static boolean freshCrashFilePresent() {
        return freshCrashFilePresent.get();
    }

    /**
     * Returns the absolute path to the crash-log directory, for use by
     * the native crash handler (called from JNI_OnLoad). Always returns
     * a non-null path; if the app Context isn't available yet (early
     * load), returns the fallback app-private path.
     */
    public static String getNativeCrashDir() {
        try {
            File f = new File(instance.getExternalFilesDir(null), "logs");
            if (!f.exists()) f.mkdirs();
            return f.getAbsolutePath();
        } catch (Exception e) {
            return "/data/data/io.waylandie.display/files/logs";
        }
    }

    /** Returns the most recent crash file, or null. */
    public static File getMostRecentCrashFile() {
        return mostRecentCrashFile;
    }

    /** Called by CrashReportActivity once the user has acknowledged the crash. */
    public static void clearFreshCrashFlag() {
        freshCrashFilePresent.set(false);
        mostRecentCrashFile = null;
    }

    /**
     * Scans {@code logsDir} for files named {@code crash-*.txt}, returns
     * the most recent one if its mtime is within the last 24h.
     */
    private static File findMostRecentCrashFile(File logsDir) {
        if (logsDir == null || !logsDir.isDirectory()) return null;
        File[] kids = logsDir.listFiles((d, name) ->
                name.startsWith("crash-") && name.endsWith(".txt"));
        if (kids == null || kids.length == 0) return null;
        File newest = null;
        long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        for (File f : kids) {
            if (f.lastModified() < cutoff) continue;
            if (newest == null || f.lastModified() > newest.lastModified()) {
                newest = f;
            }
        }
        return newest;
    }

    private static void shipReadme(File baseDir) {
        if (baseDir == null) return;
        File readme = new File(baseDir, "README.txt");
        if (readme.exists()) return;
        try {
            java.io.PrintWriter out = new java.io.PrintWriter(new java.io.FileWriter(readme));
            out.println("WayLandIE — where to find your logs");
            out.println();
            out.println("Crash tombstones and diagnostic logs are written to:");
            out.println("  " + new File(baseDir, "logs").getAbsolutePath());
            out.println();
            out.println("To view them on-device: open the Files app →");
            out.println("  Internal storage → Android → data → io.waylandie.display → files → logs");
            out.println();
            out.println("To share a crash report: open the app, tap Continue,");
            out.println("if a crash is detected you'll see a CrashReportActivity");
            out.println("with a Share button.");
            out.flush();
            out.close();
        } catch (java.io.IOException ignored) {
            // Non-fatal — README is just a UX nicety.
        }
    }
}
