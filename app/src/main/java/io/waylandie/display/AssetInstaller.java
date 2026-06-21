package io.waylandie.display;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * AssetInstaller extracts the bundled {@code linux-runtime/} shell scripts
 * from the APK's {@code assets/} directory into app-private storage so the
 * SetupWizard can share them to bundled rootfs.
 *
 * <p>The extracted scripts live at {@code getFilesDir()/linux-runtime/}. The
 * SetupWizard then asks Android to make them readable by bundled rootfs via a
 * FileProvider content URI, or — for the simpler path — copies them into
 * the public Downloads folder which bundled rootfs can read after
 * {@code app launch}.
 */
final class AssetInstaller {

    private static final String TAG = "WayLandIE/Assets";
    private static final String ASSET_ROOT = "linux-runtime";

    private AssetInstaller() {
    }

    /**
     * Returns the directory the scripts are extracted to. The directory is
     * created if it doesn't exist.
     */
    static File getInstallRoot(Context context) {
        File root = new File(context.getFilesDir(), ASSET_ROOT);
        if (!root.exists() && !root.mkdirs()) {
            Log.w(TAG, "Failed to mkdir " + root);
        }
        return root;
    }

    /**
     * Extracts (or refreshes) every file under assets/linux-runtime/ into
     * app-private storage. Returns the root directory.
     *
     * <p>This is idempotent — safe to call on every app start.
     */
    static File installAssets(Context context) throws IOException {
        File root = getInstallRoot(context);
        AssetManager am = context.getAssets();
        copyAssetTree(am, ASSET_ROOT, root);

        // Also push the scripts to a location the bundled rootfs can read.
        //
        // CRITICAL: On Android 13+, writing to /storage/emulated/0/Download/
        // requires MANAGE_EXTERNAL_STORAGE. The old code tried mkdirs() first
        // and used the result to decide — but mkdirs() can return TRUE even
        // when the directory isn't writable (e.g. dir already exists from a
        // previous install, but app lost permission). This caused EACCES
        // crashes in copyFile() when trying to write waylandie-audio etc.
        //
        // Fix: explicitly check MANAGE_EXTERNAL_STORAGE first. If not granted,
        // skip public storage entirely and use app-private external storage
        // (getExternalFilesDir) which needs NO permission and is always writable.
        boolean hasManageStorage = android.os.Environment.isExternalStorageManager();
        if (hasManageStorage) {
            // Permission granted — use public Downloads folder (visible to rootfs
            // via /sdcard bind mount).
            File publicRoot = new File(
                    android.os.Environment.getExternalStorageDirectory(),
                    "Download/WayLandIE/linux-runtime");
            try {
                if (!publicRoot.exists() && !publicRoot.mkdirs()) {
                    Log.w(TAG, "mkdirs failed for " + publicRoot);
                } else {
                    copyAssetTree(am, ASSET_ROOT, publicRoot);
                    Log.i(TAG, "Assets also written to public: " + publicRoot);
                }
            } catch (IOException e) {
                Log.w(TAG, "Public storage write failed (permission revoked?): " + e.getMessage());
            }
        } else {
            // No MANAGE_EXTERNAL_STORAGE — use app-private external storage.
            // This is always writable, no permission needed.
            // Path: /sdcard/Android/data/io.waylandie.display/files/Download/WayLandIE/linux-runtime
            File privateRoot = new File(
                    context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
                    "WayLandIE/linux-runtime");
            try {
                if (!privateRoot.exists() && !privateRoot.mkdirs()) {
                    Log.w(TAG, "mkdirs failed for " + privateRoot);
                } else {
                    copyAssetTree(am, ASSET_ROOT, privateRoot);
                    Log.i(TAG, "Assets written to app-private external (no MANAGE_EXTERNAL_STORAGE): "
                            + privateRoot);
                }
            } catch (IOException e) {
                Log.w(TAG, "App-private external write failed: " + e.getMessage());
            }
        }
        return root;
    }

    private static void copyAssetTree(AssetManager am, String assetPath, File outDir)
            throws IOException {
        String[] children = am.list(assetPath);
        if (children == null) {
            return;
        }
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("mkdirs failed for " + outDir);
        }
        if (children.length == 0) {
            // It's a file, not a directory.
            copyFile(am, assetPath, outDir);
            return;
        }
        for (String child : children) {
            String childAssetPath = assetPath + "/" + child;
            String[] grandChildren = am.list(childAssetPath);
            if (grandChildren != null && grandChildren.length > 0) {
                // Recurse.
                File childOutDir = new File(outDir, child);
                copyAssetTree(am, childAssetPath, childOutDir);
            } else {
                copyFile(am, childAssetPath, outDir);
            }
        }
    }

    private static void copyFile(AssetManager am, String assetPath, File outDir)
            throws IOException {
        String fileName = assetPath.substring(assetPath.lastIndexOf('/') + 1);
        File outFile = new File(outDir, fileName);
        try (InputStream in = am.open(assetPath);
             OutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
        // shell scripts in assets/ may have lost their +x bit — restore it.
        // CRITICAL: without +x, bash's PATH lookup skips the file and
        // waylandie-install-driver is "command not found". We verify
        // success and retry once. If it still fails, log to LogRingBuffer
        // so the error is visible in diagnostic logs.
        if (fileName.endsWith(".sh") || fileName.startsWith("waylandie-")
                || fileName.equals("install.sh")) {
            boolean chmodOk = false;
            for (int attempt = 0; attempt < 2 && !chmodOk; attempt++) {
                try {
                    Process p = new ProcessBuilder("chmod", "+x",
                            outFile.getAbsolutePath())
                            .redirectErrorStream(true).start();
                    int exit = p.waitFor();
                    chmodOk = (exit == 0);
                } catch (Exception e) {
                    Log.w(TAG, "chmod attempt " + (attempt + 1) + " failed for "
                            + outFile + ": " + e.getMessage());
                }
            }
            if (!chmodOk) {
                String msg = "chmod +x FAILED for " + outFile.getAbsolutePath()
                        + " — script will not be executable in proot."
                        + " Driver installs will fail with 'command not found'.";
                Log.e(TAG, msg);
                io.waylandie.display.shared.util.LogRingBuffer.append(
                        "[Assets] " + msg);
            }
            // Final verification — check canExecute()
            if (!outFile.canExecute()) {
                String msg = "Script not executable after chmod: "
                        + outFile.getAbsolutePath()
                        + " — proot PATH lookup will skip it.";
                Log.e(TAG, msg);
                io.waylandie.display.shared.util.LogRingBuffer.append(
                        "[Assets] " + msg);
            }
        }
    }
}
