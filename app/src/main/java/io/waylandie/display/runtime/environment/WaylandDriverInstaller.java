package io.waylandie.display.runtime.environment;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.util.zip.*;

/**
 * Extracts winewayland-driver.zip from APK assets into the proton tree.
 * Logs to THREE places:
 *   1. logcat (Log.i/Log.w)
 *   2. WineRunner.preLaunchDiagnostics (captured by GameLaunchTracer)
 *   3. System.err (captured if running under a stderr-redirecting shell)
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
            if (assets != null && assets.length < 20) {
                StringBuilder sb = new StringBuilder("  assets: ");
                for (int i = 0; i < assets.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(assets[i]);
                }
                log(sb.toString());
            }
            log("  " + ASSET + " in assets: " + found);
            if (!found) {
                log("  SKIP: asset not found in APK");
                return false;
            }
        } catch (IOException ioe) {
            log("  FAILED to list assets: " + ioe.getMessage());
            return false;
        }

        // Always re-extract the driver — the old cached version may be from
        // a previous build (e.g. without FreeType) and won't be overwritten
        // if we skip. Delete old files first to ensure clean install.
        File drvCheck = new File(prefix, "lib/wine/aarch64-windows/winewayland.drv");
        File soCheck = new File(prefix, "lib/wine/aarch64-unix/winewayland.so");
        if (drvCheck.exists()) {
            log("  deleting old winewayland.drv (" + drvCheck.length() + " bytes)");
            drvCheck.delete();
        }
        if (soCheck.exists()) {
            log("  deleting old winewayland.so (" + soCheck.length() + " bytes)");
            soCheck.delete();
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

    /**
     * Log to three places:
     *   1. logcat (Log.i)
     *   2. WineRunner.installerDiagnostics (separate from preLaunchDiagnostics,
     *      which gets cleared by the diagnostics section)
     *   3. System.err (best-effort)
     */
    private static void log(String msg) {
        Log.i(TAG, msg);
        try {
            WineRunner.installerDiagnostics.append("[wayland-installer] ")
                .append(msg).append('\n');
        } catch (Throwable t) {
            // Ignore if static init order issues
        }
        System.err.println("[WaylandDriverInstaller] " + msg);
    }

    private static File safePath(File prefix, String name) {
        if (name == null || name.isEmpty() || name.startsWith("/")) return null;
        // Reject entries containing .. (path traversal)
        if (name.contains("..")) return null;
        File f = new File(prefix, name);
        // Use getCanonicalPath() to resolve any remaining .. or symlinks
        try {
            String p = f.getCanonicalPath();
            String pp = prefix.getCanonicalPath();
            if (!p.equals(pp) && !p.startsWith(pp + File.separator)) return null;
        } catch (IOException e) {
            return null;
        }
        return f;
    }
}
