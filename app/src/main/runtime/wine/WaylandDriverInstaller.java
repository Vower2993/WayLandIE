package com.winlator.cmod.runtime.wine;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.util.zip.*;

/**
 * Installs winewayland.drv + winewayland.so + ntdll.dll into the proton tree.
 * Called during container creation to enable Wayland display output.
 *
 * The driver zip is built by CI (.github/scripts/build-winewayland-driver.sh)
 * and shipped as an APK asset.
 */
public final class WaylandDriverInstaller {
    private static final String TAG = "WaylandDriverInstaller";
    private static final String ASSET = "winewayland-driver.zip";

    public static boolean install(Context ctx, File prefix) {
        Log.i(TAG, "Installing winewayland driver to " + prefix);
        try {
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
            Log.i(TAG, "Install complete: " + extracted + " files");
            
            // Also copy libarm64ecfex.dll and winewayland.drv to prefix system32
            File system32 = new File(prefix, "drive_c/windows/system32");
            if (system32.isDirectory()) {
                copyIfExists(prefix, "lib/wine/aarch64-windows/winewayland.drv", system32);
                copyIfExists(prefix, "lib/wine/aarch64-windows/libarm64ecfex.dll", system32);
                copyIfExists(prefix, "lib/wine/aarch64-windows/ntdll.dll", system32);
            }
            // Set GraphicsDriver=winewayland.drv in system.reg
            // Wine reads this to decide which display driver to load.
            // Without this, Wine defaults to winex11.drv even if
            // winewayland.drv is installed in system32/.
            setGraphicsDriver(prefix);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Install failed", e);
            return false;
        }
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
