package io.waylandie.display.runtime.environment;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.util.zip.*;

/**
 * Extracts winewayland-driver.zip from APK assets into the proton tree.
 * Logs to BOTH logcat AND System.err (so GameLaunchTracer captures it).
 */
public final class WaylandDriverInstaller {
    private static final String TAG = "WaylandDriverInstaller";
    private static final String ASSET = "winewayland-driver.zip";

    public static boolean install(Context ctx, File prefix) {
        log("=== WaylandDriverInstaller starting ===");
        log("  prefix=" + prefix);
        log("  prefix exists=" + prefix.exists());

        // Check if asset exists in APK
        try {
            String[] assets = ctx.getAssets().list("");
            boolean found = false;
            if (assets != null) {
                for (String a : assets) {
                    if (ASSET.equals(a)) { found = true; break; }
                }
            }
            log("  APK assets listed: " + (assets != null ? assets.length : 0) + " items");
            log("  " + ASSET + " in assets: " + found);
            if (!found) {
                log("  SKIP: asset not found in APK");
                return false;
            }
        } catch (IOException ioe) {
            log("  FAILED to list assets: " + ioe.getMessage());
            return false;
        }

        // Check if already installed (idempotent)
        File drvCheck = new File(prefix, "lib/wine/aarch64-windows/winewayland.drv");
        if (drvCheck.exists() && drvCheck.length() > 1000) {
            log("  already installed: " + drvCheck + " (" + drvCheck.length() + " bytes)");
            return true;
        }

        // Extract
        long extracted = 0;
        try (InputStream is = ctx.getAssets().open(ASSET);
             ZipInputStream z = new ZipInputStream(new BufferedInputStream(is, 65536))) {
            byte[] buf = new byte[65536];
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                File out = safePath(prefix, e.getName());
                if (out == null) { log("  skip " + e.getName()); continue; }
                if (e.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = z.read(buf)) > 0) fos.write(buf, 0, n);
                }
                if (e.getName().endsWith(".so") || e.getName().endsWith(".drv")) {
                    out.setExecutable(true, true);
                }
                extracted++;
                log("  extracted: " + e.getName() + " (" + out.length() + " bytes)");
            }
            log("=== Install complete: " + extracted + " files extracted ===");
            log("  winewayland.drv at: " + drvCheck);
            log("  exists=" + drvCheck.exists() + " size=" + (drvCheck.exists() ? drvCheck.length() : 0));
            return true;
        } catch (IOException ioe) {
            log("  INSTALL FAILED: " + ioe.getClass().getSimpleName() + ": " + ioe.getMessage());
            Log.e(TAG, "install failed", ioe);
            return false;
        }
    }

    /** Log to both logcat AND System.err (tracer captures System.err). */
    private static void log(String msg) {
        Log.i(TAG, msg);
        System.err.println("[WaylandDriverInstaller] " + msg);
    }

    private static File safePath(File prefix, String name) {
        if (name == null || name.isEmpty() || name.startsWith("/")) return null;
        File f = new File(prefix, name);
        String p = f.getAbsolutePath();
        String pp = prefix.getAbsolutePath();
        if (!p.equals(pp) && !p.startsWith(pp + File.separator)) return null;
        return f;
    }
}
