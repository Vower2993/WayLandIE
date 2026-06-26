package com.winlator.cmod.runtime.wine;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.util.zip.*;

/**
 * Installs winewayland.drv + winewayland.so + ntdll.dll into the proton tree.
 *
 * Two entry points:
 *   - install(): Called during container creation. Extracts the zip and
 *     (if display mode is wayland) sets GraphicsDriver in system.reg.
 *   - ensureDriverInstalled(): Called at LAUNCH TIME when display mode is
 *     Wayland. Ensures winewayland.drv is in system32 even if the container
 *     was created with X11 mode and later switched to Wayland.
 *
 * The driver zip is built by CI (.github/scripts/build-winewayland-driver.sh)
 * and shipped as an APK asset.
 */
public final class WaylandDriverInstaller {
    private static final String TAG = "WaylandDriverInstaller";
    private static final String ASSET = "winewayland-driver.zip";

    public static boolean install(Context ctx, File prefix, String displayMode) {
        Log.i(TAG, "Installing winewayland driver to " + prefix);
        try {
            extractZip(ctx, prefix);
            copyToSystem32(prefix);
            // Set GraphicsDriver=winewayland.drv only when display mode is wayland
            if (!"wayland".equals(displayMode)) {
                Log.i(TAG, "Display mode is not wayland — skipping GraphicsDriver");
                return true;
            }
            setGraphicsDriver(prefix);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Install failed", e);
            return false;
        }
    }

    /**
     * Called at launch time when display mode is Wayland.
     * Ensures winewayland.drv + libarm64ecfex.dll + ntdll.dll are in system32
     * and GraphicsDriver is set in system.reg.
     *
     * This handles the case where the container was created with X11 mode
     * (default) and the user later switched to Wayland via shortcut settings.
     * Without this, Wine would fail to load winewayland.drv with
     * STATUS_NOT_FOUND (0xc0000135) and fall back to nodrv_CreateWindow,
     * causing "The explorer process failed to start" and container crash.
     */
    public static boolean ensureDriverInstalled(Context ctx, File prefix) {
        File system32 = new File(prefix, "drive_c/windows/system32");
        File driverInSystem32 = new File(system32, "winewayland.drv");

        boolean needsInstall = !driverInSystem32.exists()
                || driverInSystem32.length() < 1000;
        if (needsInstall) {
            Log.i(TAG, "ensureDriverInstalled: winewayland.drv missing from system32 — extracting");
            try {
                extractZip(ctx, prefix);
                copyToSystem32(prefix);
            } catch (IOException e) {
                Log.e(TAG, "ensureDriverInstalled: extraction failed", e);
                return false;
            }
        } else {
            Log.i(TAG, "ensureDriverInstalled: winewayland.drv already present in system32");
        }
        // Always set GraphicsDriver (idempotent — removes old entries first)
        setGraphicsDriver(prefix);
        return true;
    }

    private static void extractZip(Context ctx, File prefix) throws IOException {
        InputStream is = ctx.getAssets().open(ASSET);
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is, 65536));
        byte[] buf = new byte[65536];
        ZipEntry e;
        int extracted = 0;
        while ((e = zis.getNextEntry()) != null) {
            File out = new File(prefix, e.getName());
            if (e.isDirectory()) { out.mkdirs(); continue; }
            out.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(out)) {
                int n;
                while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
            }
            if (e.getName().endsWith(".so") || e.getName().endsWith(".drv")) {
                out.setExecutable(true, true);
            }
            extracted++;
            Log.i(TAG, "  extracted: " + e.getName() + " (" + out.length() + " bytes)");
        }
        Log.i(TAG, "Extraction complete: " + extracted + " files");
    }

    private static void copyToSystem32(File prefix) {
        File system32 = new File(prefix, "drive_c/windows/system32");
        if (!system32.isDirectory()) {
            Log.w(TAG, "system32 not found at " + system32 + " — skipping copy");
            return;
        }
        copyIfExists(prefix, "lib/wine/aarch64-windows/winewayland.drv", system32);
        copyIfExists(prefix, "lib/wine/aarch64-windows/libarm64ecfex.dll", system32);
        copyIfExists(prefix, "lib/wine/aarch64-windows/ntdll.dll", system32);
    }

    private static void setGraphicsDriver(File prefix) {
        File systemReg = new File(prefix, "system.reg");
        if (!systemReg.exists()) {
            Log.w(TAG, "system.reg not found — skipping GraphicsDriver");
            return;
        }
        try {
            String reg = new String(java.nio.file.Files.readAllBytes(systemReg.toPath()));
            String videoKey = "[System\\\\CurrentControlSet\\\\Control\\\\Video\\\\{00000000-0000-0000-0000-000000000000}\\\\0000]";
            String graphicsValue = "\"GraphicsDriver\"=\"winewayland.drv\"";

            // Remove old GraphicsDriver entries (simple string search, no regex)
            int gIdx = reg.indexOf("\"GraphicsDriver\"");
            while (gIdx >= 0) {
                int eol = reg.indexOf("\n", gIdx);
                if (eol < 0) eol = reg.length();
                reg = reg.substring(0, gIdx) + reg.substring(eol);
                gIdx = reg.indexOf("\"GraphicsDriver\"", gIdx);
            }

            if (reg.contains(videoKey)) {
                reg = reg.replace(videoKey, videoKey + "\n" + graphicsValue);
            } else {
                reg += "\n" + videoKey + "\n" + graphicsValue + "\n";
            }
            java.nio.file.Files.write(systemReg.toPath(), reg.getBytes());
            Log.i(TAG, "Set system.reg: GraphicsDriver=winewayland.drv");
        } catch (Exception e) {
            Log.w(TAG, "Failed to set GraphicsDriver: " + e.getMessage());
        }
    }

    private static void copyIfExists(File srcDir, String srcPath, File dstDir) {
        File src = new File(srcDir, srcPath);
        if (!src.exists()) return;
        File dst = new File(dstDir, src.getName());
        try {
            InputStream is = new FileInputStream(src);
            FileOutputStream fos = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            is.close();
            fos.close();
            Log.i(TAG, "  copied to system32: " + src.getName() + " (" + dst.length() + " bytes)");
        } catch (IOException e) {
            Log.w(TAG, "  copy failed: " + src.getName() + " — " + e.getMessage());
        }
    }
}
