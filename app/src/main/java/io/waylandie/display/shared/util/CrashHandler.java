package io.waylandie.display.shared.util;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.waylandie.display.runtime.environment.ImageFsManager;

/**
 * CrashHandler — global Java-side uncaught-exception handler.
 *
 * <p>Installed from {@link io.waylandie.display.WayLandIEApplication#onCreate()}
 * via {@link Thread#setDefaultUncaughtExceptionHandler(Thread.UncaughtExceptionHandler)}.
 *
 * <p>When an uncaught exception reaches us, we write a tombstone to
 * {@code getExternalFilesDir(null)/logs/crash-<timestamp>.txt}
 * containing:
 * <ul>
 *   <li>Java stack trace of the uncaught throwable (full causal chain)</li>
 *   <li>Last 500 lines of {@link LogRingBuffer}</li>
 *   <li>{@code /proc/self/maps} excerpt for
 *       {@code libwaylandie_display_native.so}</li>
 *   <li>Build info, package version, ImageFs status</li>
 * </ul>
 *
 * <p>After writing, we delegate to the previous handler (typically the
 * default JVM handler that calls {@code Process.killProcess}) so the
 * OS still records the standard ANR/crash dialog. The native side
 * ({@code sigaction} for SIGSEGV/SIGABRT/SIGBUS in
 * {@code libwaylandie_display_native.c}'s {@code JNI_OnLoad}) writes
 * to the same file path before calling {@code _exit(11)} — those
 * tombstones carry a {@code signal=SIGSEGV} header so we can tell them
 * apart from Java-side crashes.
 *
 * <p>Reference patterns:
 * <ul>
 *   <li>GameNative {@code app/src/main/java/app/gamenative/CrashHandler.kt:33,49}
 *       — logcat dump for crash reports.</li>
 *   <li>WinNative {@code LogManager.kt:15-20} — log dir at
 *       {@code getExternalFilesDir(null)/logs/}.</li>
 * </ul>
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "WayLandIE/CrashHandler";
    private static final String LOGS_SUBDIR = "logs";
    private static final String NATIVE_LIB_NAME = "libwaylandie_display_native.so";

    private final Context context;
    private final Thread.UncaughtExceptionHandler previous;

    public CrashHandler(Context context, Thread.UncaughtExceptionHandler previous) {
        this.context = context.getApplicationContext();
        this.previous = previous;
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            File tombstone = writeTombstone(t, e);
            if (tombstone != null) {
                Log.e(TAG, "Tombstone written: " + tombstone.getAbsolutePath(), e);
            }
        } catch (Throwable secondary) {
            // Never let the crash handler itself throw — it would mask the original crash.
            Log.e(TAG, "CrashHandler failed to write tombstone", secondary);
        }
        if (previous != null) {
            previous.uncaughtException(t, e);
        }
    }

    private File writeTombstone(Thread t, Throwable e) {
        File logsDir = new File(context.getExternalFilesDir(null), LOGS_SUBDIR);
        if (!logsDir.exists() && !logsDir.mkdirs()) return null;

        String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                .format(new Date());
        File tombstone = new File(logsDir, "crash-" + ts + ".txt");

        try (PrintWriter out = new PrintWriter(tombstone)) {
            out.println("=== WayLandIE Crash Tombstone ===");
            out.println("Time: " + new Date());
            out.println("Thread: " + t.getName() + " (id=" + t.getId() + ")");
            out.println("===========================================");
            out.println();

            // --- Java stack (full causal chain) ---
            out.println("## Java Stack (uncaught exception)");
            Throwable cur = e;
            while (cur != null) {
                StringWriter sw = new StringWriter();
                cur.printStackTrace(new PrintWriter(sw));
                out.println(sw.toString());
                cur = cur.getCause();
                if (cur != null) out.println("--- caused by ---");
            }
            out.println();

            // --- In-app log ring buffer ---
            out.println("## In-App Log Ring Buffer (last 500 lines)");
            out.println("-------------------------------------------");
            List<String> snapshot = LogRingBuffer.snapshot();
            if (snapshot.isEmpty()) {
                out.println("(empty — no log lines recorded before crash)");
            } else {
                for (String line : snapshot) out.println(line);
            }
            out.println("-------------------------------------------");
            out.println();

            // --- /proc/self/maps excerpt for the native lib ---
            out.println("## Native Library Maps (" + NATIVE_LIB_NAME + ")");
            out.println("-------------------------------------------");
            out.println(extractMapsFor(NATIVE_LIB_NAME));
            out.println("-------------------------------------------");
            out.println();

            // --- Build info ---
            out.println("## Build Info");
            out.println("  Android:  " + Build.VERSION.RELEASE
                    + " (API " + Build.VERSION.SDK_INT + ")");
            out.println("  Device:   " + Build.MODEL + " (" + Build.DEVICE + ")");
            out.println("  Manufacturer: " + Build.MANUFACTURER);
            out.println("  ABI:      " + Build.SUPPORTED_ABIS[0]);
            out.println("  Kernel:   " + System.getProperty("os.version"));
            out.println();

            // --- App info ---
            out.println("## App Info");
            try {
                out.println("  Package:  " + context.getPackageName());
                out.println("  Version:  " + context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionName);
                out.println("  APK:      " + context.getApplicationInfo().sourceDir);
                out.println("  Data dir: " + context.getFilesDir());
                out.println("  Native lib dir: "
                        + context.getApplicationInfo().nativeLibraryDir);
            } catch (Exception ex) {
                out.println("  ERROR: " + ex.getMessage());
            }
            out.println();

            // --- ImageFs status (this is what setup writes before crash) ---
            out.println("## ImageFs (Rootfs) Status");
            try {
                ImageFsManager imgFs = new ImageFsManager(context);
                out.println("  Valid:    " + imgFs.isValid());
                out.println("  Version:  " + imgFs.getVersion()
                        + " (latest=" + ImageFsManager.LATEST_VERSION + ")");
                out.println("  Root dir: " + imgFs.getRootDir()
                        + " (exists=" + imgFs.getRootDir().exists() + ")");
                if (imgFs.getLastError() != null) {
                    out.println("  LAST ERROR:");
                    for (String line : imgFs.getLastError().split("\n")) {
                        out.println("    " + line);
                    }
                }
            } catch (Exception ex) {
                out.println("  ERROR: " + ex.getMessage());
            }
            out.println();
            out.println("=== End of Tombstone ===");
            out.flush();
        } catch (IOException ioe) {
            Log.e(TAG, "Failed to write tombstone", ioe);
            return null;
        }
        return tombstone;
    }

    /** Returns only the /proc/self/maps lines that mention {@code libName}. */
    private static String extractMapsFor(String libName) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains(libName)) {
                    sb.append(line).append('\n');
                }
            }
        } catch (Exception e) {
            sb.append("(failed to read /proc/self/maps: ")
              .append(e.getMessage()).append(")\n");
        }
        if (sb.length() == 0) {
            sb.append("(").append(libName)
              .append(" not found in /proc/self/maps — lib not loaded)\n");
        }
        return sb.toString();
    }
}
