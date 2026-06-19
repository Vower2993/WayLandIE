package io.waylandie.display.shared.util;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.waylandie.display.runtime.environment.ImageFsManager;
import io.waylandie.display.runtime.environment.ProotRunner;
import io.waylandie.display.runtime.content.AdrenotoolsManager;

/**
 * LogCollector — captures all diagnostic info into a single text file
 * that the user can share for debugging.
 *
 * <p>Collects:
 * <ul>
 *   <li>System info (device, Android version, ABI, etc.)</li>
 *   <li>App version + install path</li>
 *   <li>ImageFs status (rootfs extracted? version? size?)</li>
 *   <li>ProotRunner status (proot binary present? size?)</li>
 *   <li>Adrenotools status (driver installed? which one?)</li>
 *   <li>Bridge socket status (abstract + TCP probe)</li>
 *   <li>Full logcat output (last 5000 lines, filtered to WayLandIE tags)</li>
 *   <li>Proot process output (if any Wine processes were launched)</li>
 * </ul>
 *
 * <p>Output: /sdcard/Download/WayLandIE/logs/waylandie-log-<timestamp>.txt
 */
public final class LogCollector {

    private static final String TAG = "WayLandIE/LogCollector";
    private static final String LOG_DIR = "WayLandIE/logs";

    private LogCollector() {}

    /**
     * Collects all logs + system state and writes to a timestamped file.
     * Returns the file path, or null on failure.
     */
    public static File collect(Context context, String inAppLogBuffer) {
        File logDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                LOG_DIR);
        if (!logDir.exists() && !logDir.mkdirs()) {
            Log.e(TAG, "Failed to mkdir " + logDir);
            return null;
        }

        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                .format(new Date());
        File logFile = new File(logDir, "waylandie-log-" + timestamp + ".txt");

        try (PrintWriter out = new PrintWriter(new FileOutputStream(logFile))) {
            out.println("=== WayLandIE Diagnostic Log ===");
            out.println("Generated: " + new Date());
            out.println("====================================");
            out.println();

            // --- System info ---
            out.println("## System Info");
            out.println("  Android:  " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            out.println("  Device:   " + Build.MODEL + " (" + Build.DEVICE + ")");
            out.println("  Manufacturer: " + Build.MANUFACTURER);
            out.println("  SoC:      " + getProp("ro.soc.manufacturer") + " " + getProp("ro.soc.model"));
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
                out.println("  Native lib dir: " + context.getApplicationInfo().nativeLibraryDir);
            } catch (Exception e) {
                out.println("  ERROR: " + e.getMessage());
            }
            out.println();

            // --- ImageFs status ---
            out.println("## ImageFs (Rootfs)");
            ImageFsManager imageFs = new ImageFsManager(context);
            out.println("  Valid:    " + imageFs.isValid());
            out.println("  Version:  " + imageFs.getVersion() + " (latest=" + ImageFsManager.LATEST_VERSION + ")");
            out.println("  Root dir: " + imageFs.getRootDir());
            out.println("  Root dir exists: " + imageFs.getRootDir().exists());
            if (imageFs.getRootDir().exists()) {
                out.println("  Root dir size: " + getDirSize(imageFs.getRootDir()) + " bytes");
            }
            out.println("  Bin dir:  " + imageFs.getBinDir() + " (exists=" + imageFs.getBinDir().exists() + ")");
            out.println("  Lib dir:  " + imageFs.getLibDir() + " (exists=" + imageFs.getLibDir().exists() + ")");
            out.println("  Wine dir: " + imageFs.getWineDir() + " (exists=" + imageFs.getWineDir().exists() + ")");
            if (imageFs.getLastError() != null) {
                out.println("  LAST ERROR:");
                for (String line : imageFs.getLastError().split("\n")) {
                    out.println("    " + line);
                }
            }
            out.println();

            // --- ProotRunner status ---
            out.println("## ProotRunner");
            ProotRunner proot = new ProotRunner(context);
            out.println("  Ready:    " + proot.isReady());
            File prootBin = new File(context.getApplicationInfo().nativeLibraryDir, "libproot.so");
            out.println("  Binary:   " + prootBin.getAbsolutePath());
            out.println("  Exists:   " + prootBin.exists());
            out.println("  Size:     " + (prootBin.exists() ? prootBin.length() : 0) + " bytes");
            out.println("  Executable: " + prootBin.canExecute());
            out.println();

            // --- Adrenotools status ---
            out.println("## Adrenotools (Driver Slots)");
            AdrenotoolsManager atm = new AdrenotoolsManager(context);
            out.println("  Content dir: " + atm.getContentDir());
            out.println("  Active driver: " + atm.getActiveDriver());
            for (String id : atm.listInstalledDrivers()) {
                out.println("  Slot: " + id);
                out.println("    Name:    " + atm.getDriverName(id));
                out.println("    Version: " + atm.getDriverVersion(id));
                out.println("    Library: " + atm.getLibraryName(id));
                out.println("    Path:    " + atm.getDriverPath(id));
            }
            out.println();

            // --- Proton/DXVK/Turnip/FEX bind-mount dirs ---
            out.println("## User-Installed Components");
            String[] componentNames = {"proton", "dxvk", "turnip", "fex"};
            for (String name : componentNames) {
                File activeDir = new File(context.getFilesDir(), "contents/" + name + "/active");
                out.println("  " + name + ": " + activeDir.getAbsolutePath()
                        + " (exists=" + activeDir.exists() + ")");
                if (activeDir.exists()) {
                    File[] kids = activeDir.listFiles();
                    if (kids != null) {
                        for (File kid : kids) {
                            out.println("    " + kid.getName()
                                    + (kid.isDirectory() ? "/" : "")
                                    + " (" + (kid.isDirectory()
                                            ? kids.length + " items"
                                            : kid.length() + " bytes") + ")");
                        }
                    }
                }
            }
            out.println();

            // --- Bridge socket status ---
            out.println("## Bridge Socket");
            out.println("  Abstract socket:");
            out.println("    " + grepUnixSockets("waylandie"));
            out.println("  TCP port 57391:");
            out.println("    " + probeTcpPort(57391));
            out.println("  TCP port 57392 (audio):");
            out.println("    " + probeTcpPort(57392));
            out.println();

            // --- Storage permissions ---
            out.println("## Storage Permissions");
            out.println("  MANAGE_EXTERNAL_STORAGE granted: "
                    + Environment.isExternalStorageManager());
            out.println("  External storage dir: " + Environment.getExternalStorageDirectory());
            out.println();

            // --- In-app log buffer ---
            if (inAppLogBuffer != null && !inAppLogBuffer.isEmpty()) {
                out.println("## In-App Log Buffer");
                out.println("----------------------------------------");
                out.println(inAppLogBuffer);
                out.println("----------------------------------------");
                out.println();
            }

            // --- logcat (last 5000 lines, WayLandIE tags) ---
            out.println("## Logcat (filtered to WayLandIE tags, last 5000 lines)");
            out.println("----------------------------------------");
            out.println(collectLogcat());
            out.println("----------------------------------------");

        } catch (IOException e) {
            Log.e(TAG, "Failed to write log file", e);
            return null;
        }

        Log.i(TAG, "Log saved to " + logFile.getAbsolutePath());
        return logFile;
    }

    /**
     * Collects logcat output. Tries multiple tag filters.
     */
    private static String collectLogcat() {
        StringBuilder sb = new StringBuilder();
        // Try to get logcat — may fail on Android 16 without permission
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "logcat", "-d", "-t", "5000",
                    "WayLandIE:V", "WayLandIEDisplay:V", "WayLandIEBridge:V",
                    "WayLandIEHome:V", "WayLandIE/ImageFs:V", "WayLandIE/Proot:V",
                    "WayLandIE/Tar:V", "WayLandIE/Adrenotools:V",
                    "WayLandIE/LogCollector:V",
                    "AndroidRuntime:E", "System.err:W",
                    "*:S");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            proc.waitFor();
        } catch (Exception e) {
            sb.append("  (logcat failed: ").append(e.getMessage()).append(")\n");
            // Fallback: try unfiltered logcat
            try {
                ProcessBuilder pb = new ProcessBuilder("logcat", "-d", "-t", "2000");
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.toLowerCase().contains("waylandie")
                            || line.toLowerCase().contains("io.waylandie")
                            || line.contains("AndroidRuntime")
                            || line.contains("FATAL")) {
                        sb.append(line).append('\n');
                    }
                }
                proc.waitFor();
            } catch (Exception e2) {
                sb.append("  (unfiltered logcat also failed: ")
                        .append(e2.getMessage()).append(")\n");
            }
        }
        if (sb.length() == 0) {
            sb.append("  (no logcat output — may need logcat permission)\n");
        }
        return sb.toString();
    }

    private static String grepUnixSockets(String pattern) {
        try {
            ProcessBuilder pb = new ProcessBuilder("grep", pattern, "/proc/net/unix");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append("    ").append(line).append('\n');
            }
            proc.waitFor();
            return sb.length() > 0 ? sb.toString() : "    (no matching sockets)";
        } catch (Exception e) {
            return "    (grep failed: " + e.getMessage() + ")";
        }
    }

    private static String probeTcpPort(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 200);
            return "    LISTENING ✓";
        } catch (Exception e) {
            return "    not listening (" + e.getMessage() + ")";
        }
    }

    private static String getProp(String name) {
        try {
            ProcessBuilder pb = new ProcessBuilder("getprop", name);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()));
            String value = reader.readLine();
            proc.waitFor();
            return value != null ? value : "";
        } catch (Exception e) {
            return "(error: " + e.getMessage() + ")";
        }
    }

    private static long getDirSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long size = 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        for (File f : kids) {
            if (f.isDirectory()) {
                size += getDirSize(f);
            } else {
                size += f.length();
            }
        }
        return size;
    }
}
