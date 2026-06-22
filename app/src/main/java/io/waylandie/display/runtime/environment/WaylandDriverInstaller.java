package io.waylandie.display.runtime.environment;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.util.zip.*;

public final class WaylandDriverInstaller {
    private static final String TAG = "WaylandDriverInstaller";
    private static final String ASSET = "winewayland-driver.zip";

    public static boolean install(Context ctx, File prefix) {
        try (InputStream is = ctx.getAssets().open(ASSET);
             ZipInputStream z = new ZipInputStream(new BufferedInputStream(is, 65536))) {
            byte[] buf = new byte[65536];
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                File out = safePath(prefix, e.getName());
                if (out == null) { Log.w(TAG, "skip " + e.getName()); continue; }
                if (e.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = z.read(buf)) > 0) fos.write(buf, 0, n);
                }
                if (e.getName().endsWith(".so") || e.getName().endsWith(".drv")) {
                    out.setExecutable(true, true);
                }
            }
            Log.i(TAG, "winewayland driver installed into " + prefix);
            return true;
        } catch (FileNotFoundException fnf) {
            Log.w(TAG, ASSET + " not in APK assets - skipping");
            return false;
        } catch (IOException ioe) {
            Log.e(TAG, "install failed", ioe);
            return false;
        }
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
