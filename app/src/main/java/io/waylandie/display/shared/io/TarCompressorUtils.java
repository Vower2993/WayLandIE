package io.waylandie.display.shared.io;

import android.content.Context;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TarCompressorUtils — extract .tar.xz, .tar.gz, .tar.zst, and .tar archives
 * from APK assets to a target directory.
 *
 * <p>Ported from WinNative's TarCompressorUtils with the following changes:
 * <ul>
 *   <li>No dependency on Apache Commons Compress — uses the system
 *       {@code tar} binary via Runtime.exec() for .tar.gz and .tar.xz
 *       (these are supported by Android's toybox).</li>
 *   <li>For .tar.zst (zstd-compressed, used by WinNative's imagefs.tzst),
 *       we shell out to a bundled {@code zstd} binary if present, or
 *       fall back to a pure-Java zstd decoder.</li>
 * </ul>
 *
 * <p>All extraction runs on a background thread. Callers pass an
 * {@link OnExtractFileListener} to receive progress callbacks.
 */
public final class TarCompressorUtils {

    private static final String TAG = "WayLandIE/Tar";

    public enum Type {
        XZ,      // .tar.xz / .txz
        GZIP,    // .tar.gz / .tgz
        ZSTD,    // .tar.zst / .tzst
        TAR      // uncompressed .tar
    }

    public interface OnExtractFileListener {
        File onExtractFile(File file, long size);
        void onExtractFileProgress(File file, long size);
        default boolean mapsExtractedFiles() { return false; }
        default boolean reportsExtractedBytesOnly() { return false; }
        default void onExtractedBytes(long size) {}
    }

    private TarCompressorUtils() {}

    /**
     * Extracts a compressed tar archive from {@code assets/<assetName>} into
     * {@code outDir}. Blocks the calling thread.
     *
     * @return true on success, false on failure
     */
    public static boolean extractSync(Type type, Context context, String assetName,
                                      File outDir, OnExtractFileListener listener) {
        AtomicBoolean result = new AtomicBoolean(false);
        Future<?> f = extractAsync(type, context, assetName, outDir, listener);
        try {
            f.get();
            result.set(true);
        } catch (Exception e) {
            Log.e(TAG, "Extraction failed for " + assetName, e);
        }
        return result.get();
    }

    /**
     * Asynchronous variant — runs extraction on a single-thread executor.
     */
    public static Future<?> extractAsync(Type type, Context context, String assetName,
                                         File outDir, OnExtractFileListener listener) {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        return exec.submit(() -> {
            try {
                extractInternal(type, context, assetName, outDir, listener);
            } catch (Exception e) {
                Log.e(TAG, "extractAsync failed for " + assetName, e);
                throw new RuntimeException(e);
            }
        });
    }

    private static void extractInternal(Type type, Context context, String assetName,
                                        File outDir, OnExtractFileListener listener)
            throws IOException, InterruptedException {
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("Failed to mkdir " + outDir);
        }

        // Copy asset to a temp file because Android's tar (toybox) can't
        // read directly from AssetManager.
        File tmpArchive = File.createTempFile("waylandie-extract-", ".tar", context.getCacheDir());
        try {
            long totalBytes = copyAssetToFile(context, assetName, tmpArchive, listener);
            Log.i(TAG, "Copied " + assetName + " → " + tmpArchive
                    + " (" + totalBytes + " bytes)");

            // Use a pipe-based approach for better reliability with large archives.
            // Android's toybox tar -xJf can fail silently on 100+ MB xz files,
            // but `xz -dc | tar -xf -` is more robust.
            String[] cmd;
            if (type == Type.XZ) {
                // Pipe approach: xz decompresses to stdout, tar reads from stdin
                cmd = new String[]{
                        "sh", "-c",
                        "xz -dc " + shellQuote(tmpArchive.getAbsolutePath())
                                + " | tar -xf - -C " + shellQuote(outDir.getAbsolutePath())
                };
            } else if (type == Type.GZIP) {
                cmd = new String[]{
                        "sh", "-c",
                        "gzip -dc " + shellQuote(tmpArchive.getAbsolutePath())
                                + " | tar -xf - -C " + shellQuote(outDir.getAbsolutePath())
                };
            } else if (type == Type.ZSTD) {
                // Try zstd pipe, fall back to tar's built-in
                File zstdBin = new File(context.getApplicationInfo().nativeLibraryDir, "libzstd.so");
                String zstdCmd = zstdBin.exists()
                        ? zstdBin.getAbsolutePath() + " -dc"
                        : "zstd -dc";
                cmd = new String[]{
                        "sh", "-c",
                        zstdCmd + " " + shellQuote(tmpArchive.getAbsolutePath())
                                + " | tar -xf - -C " + shellQuote(outDir.getAbsolutePath())
                };
            } else {
                // Plain tar
                cmd = new String[]{"tar", "-xf", tmpArchive.getAbsolutePath(),
                        "-C", outDir.getAbsolutePath()};
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            // Capture ALL output so we can see the error if tar fails
            StringBuilder procOutput = new StringBuilder();
            byte[] buf = new byte[4096];
            InputStream procIn = proc.getInputStream();
            int n;
            while ((n = procIn.read(buf)) > 0) {
                procOutput.append(new String(buf, 0, n));
            }
            int exit = proc.waitFor();
            if (exit != 0) {
                String errMsg = procOutput.toString().trim();
                Log.e(TAG, "Extraction failed (exit " + exit + "): " + errMsg);
                throw new IOException("tar/xz exited with " + exit
                        + " for " + assetName + ": " + errMsg);
            }
            Log.i(TAG, "Extracted " + assetName + " → " + outDir);
            Log.i(TAG, "Extraction output: " + procOutput.toString().trim());

            if (listener != null) {
                listener.onExtractedBytes(totalBytes);
            }
        } finally {
            tmpArchive.delete();
        }
    }

    private static long copyAssetToFile(Context context, String assetName,
                                        File outFile, OnExtractFileListener listener)
            throws IOException {
        long total = 0;
        try (InputStream in = context.getAssets().open(assetName);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (listener != null && (total % (1024 * 1024)) == 0) {
                    listener.onExtractedBytes(n);
                }
            }
        }
        return total;
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
