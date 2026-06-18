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
 * SetupWizard can share them to Termux.
 *
 * <p>The extracted scripts live at {@code getFilesDir()/linux-runtime/}. The
 * SetupWizard then asks Android to make them readable by Termux via a
 * FileProvider content URI, or — for the simpler path — copies them into
 * the public Downloads folder which Termux can read after
 * {@code termux-setup-storage}.
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

        // Also push the scripts to the public Downloads folder so Termux can
        // see them after termux-setup-storage has been run.
        File publicRoot = new File(
                android.os.Environment.getExternalStorageDirectory(),
                "Download/WayLandIE/linux-runtime");
        if (!publicRoot.exists() && !publicRoot.mkdirs()) {
            Log.w(TAG, "Failed to mkdir public " + publicRoot);
        } else {
            copyAssetTree(am, ASSET_ROOT, publicRoot);
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
        // shell scripts in assets/ may have lost their +x bit — restore it
        if (fileName.endsWith(".sh") || fileName.startsWith("waylandie-")
                || fileName.equals("install.sh")) {
            // best-effort; failure is non-fatal
            try {
                new ProcessBuilder("chmod", "+x", outFile.getAbsolutePath())
                        .redirectErrorStream(true).start().waitFor();
            } catch (Exception ignored) {
            }
        }
    }
}
