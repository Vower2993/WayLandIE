package io.waylandie.display.runtime.environment;

import android.content.Context;
import android.util.Log;

import io.waylandie.display.shared.io.TarCompressorUtils;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ImageFsManager — manages the bundled rootfs (ImageFs) that ships inside
 * the APK's assets directory.
 *
 * <p>On first launch, the app extracts {@code assets/imagefs/imagefs.tar.xz}
 * (~150 MB compressed → ~500 MB extracted) into
 * {@code getFilesDir()/imagefs/}. This rootfs contains:
 *
 * <ul>
 *   <li>Wine (bionic, with Armec patches)</li>
 *   <li>DXVK (bionic)</li>
 *   <li>Mesa Turnip (KGSL bionic variant — no libhardware dependency)</li>
 *   <li>box86 + box64 (bionic)</li>
 *   <li>FEX-Emu (optional)</li>
 *   <li>Wayland client libraries</li>
 *   <li>PulseAudio (for audio bridge)</li>
 *   <li>gamescope (optional, for nested compositor mode)</li>
 * </ul>
 *
 * <p>After extraction, {@link #isValid()} returns true and the runtime
 * can exec processes inside the rootfs via {@link ProotRunner}.
 *
 * <p>Inspired by WinNative's ImageFs + ImageFsInstaller.
 */
public final class ImageFsManager {

    private static final String TAG = "WayLandIE/ImageFs";

    public static final int LATEST_VERSION = 1;
    private static final String IMAGEFS_ARCHIVE = "imagefs/imagefs.tar.xz";
    private static final long IMAGEFS_EXTRACTED_BYTES = 500_000_000L;

    private final Context context;
    private final File rootDir;

    public ImageFsManager(Context context) {
        this.context = context;
        this.rootDir = new File(context.getFilesDir(), "imagefs");
    }

    public File getRootDir() { return rootDir; }
    public File getBinDir() { return new File(rootDir, "usr/bin"); }
    public File getLibDir() { return new File(rootDir, "usr/lib"); }
    public File getEtcDir() { return new File(rootDir, "usr/etc"); }
    public File getShareDir() { return new File(rootDir, "usr/share"); }
    public File getTmpDir() { return new File(rootDir, "usr/tmp"); }
    public File getOptDir() { return new File(rootDir, "opt"); }
    public File getHomeDir() { return new File(rootDir, "home/xuser"); }
    public File getWineDir() { return new File(rootDir, "opt/wine"); }
    public File getWinePrefix() { return new File(getHomeDir(), ".wine"); }

    public File getConfigDir() { return new File(rootDir, ".waylandie"); }
    public File getVersionFile() { return new File(getConfigDir(), ".img_version"); }

    public boolean isValid() {
        return rootDir.isDirectory()
                && getVersionFile().exists()
                && getBinDir().isDirectory()
                && getLibDir().isDirectory()
                && getEtcDir().isDirectory()
                && getShareDir().isDirectory()
                && getOptDir().isDirectory();
    }

    public boolean isUpToDate() {
        return isValid() && getVersion() >= LATEST_VERSION;
    }

    public int getVersion() {
        if (!getVersionFile().exists()) return 0;
        try {
            java.util.ArrayList<String> lines = readLines(getVersionFile());
            return lines.isEmpty() ? 0 : Integer.parseInt(lines.get(0).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public String getFormattedVersion() {
        return String.format(Locale.US, "%.1f", (float) getVersion());
    }

    public void createVersionFile(int version) throws IOException {
        getConfigDir().mkdirs();
        if (!getVersionFile().exists()) getVersionFile().createNewFile();
        writeString(getVersionFile(), String.valueOf(version));
    }

    /**
     * Extracts the bundled rootfs from APK assets. Calls {@code listener}
     * with progress updates (0-100).
     *
     * @return true on success, false on failure
     */
    public boolean install(ProgressListener listener) {
        if (listener != null) listener.onProgress(0);

        if (!rootDir.exists() && !rootDir.mkdirs()) {
            lastError = "Failed to create root dir: " + rootDir;
            Log.e(TAG, lastError);
            if (listener != null) listener.onFinished(false);
            return false;
        }

        // Verify the asset exists before trying to extract
        try {
            java.io.InputStream testStream = context.getAssets().open(IMAGEFS_ARCHIVE);
            testStream.close();
        } catch (IOException e) {
            lastError = "Rootfs asset not found in APK: " + IMAGEFS_ARCHIVE
                    + "\n  The APK may not have been built with the rootfs bundled."
                    + "\n  Check the GitHub Actions build log — the 'Build imagefs rootfs' step"
                    + " should produce a 100+ MB tarball.";
            Log.e(TAG, lastError);
            if (listener != null) listener.onFinished(false);
            return false;
        }

        // Extract the imagefs tarball
        AtomicLong totalBytes = new AtomicLong(0);
        TarCompressorUtils.OnExtractFileListener extractListener =
                new TarCompressorUtils.OnExtractFileListener() {
                    @Override
                    public File onExtractFile(File file, long size) { return file; }
                    @Override
                    public void onExtractFileProgress(File file, long size) {}
                    @Override
                    public void onExtractedBytes(long size) {
                        long total = totalBytes.addAndGet(size);
                        int percent = (int) Math.min(99,
                                (total * 100L) / IMAGEFS_EXTRACTED_BYTES);
                        if (listener != null) listener.onProgress(percent);
                    }
                };

        boolean ok = TarCompressorUtils.extractSync(
                TarCompressorUtils.Type.XZ,
                context,
                IMAGEFS_ARCHIVE,
                rootDir,
                extractListener);

        if (!ok) {
            lastError = "Rootfs tarball extraction failed."
                    + "\n  This is likely an xz decompression error."
                    + "\n  Tap 'Save Logs' to see the detailed error.";
            Log.e(TAG, lastError);
            if (listener != null) listener.onFinished(false);
            return false;
        }

        // Mark valid by writing version file
        try {
            createVersionFile(LATEST_VERSION);
        } catch (IOException e) {
            lastError = "Failed to write version file: " + e.getMessage();
            Log.e(TAG, lastError);
        }

        // Make all binaries executable
        makeBinariesExecutable(getBinDir());

        if (listener != null) {
            listener.onProgress(100);
            listener.onFinished(true);
        }
        return true;
    }

    /**
     * Returns the last error message from {@link #install}, or null if
     * no error occurred. Used by LogCollector.
     */
    public String getLastError() {
        return lastError;
    }
    private String lastError = null;

    private void makeBinariesExecutable(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                makeBinariesExecutable(f);
            } else {
                // best-effort chmod
                try {
                    new ProcessBuilder("chmod", "+x", f.getAbsolutePath())
                            .redirectErrorStream(true).start().waitFor();
                } catch (Exception ignored) {}
            }
        }
    }

    public interface ProgressListener {
        void onProgress(int percent);
        void onFinished(boolean success);
    }

    // ---- tiny file helpers ----
    private static java.util.ArrayList<String> readLines(File f) throws IOException {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    private static void writeString(File f, String s) throws IOException {
        try (java.io.FileWriter w = new java.io.FileWriter(f)) {
            w.write(s);
        }
    }
}
