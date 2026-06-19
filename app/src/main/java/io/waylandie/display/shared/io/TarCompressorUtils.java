package io.waylandie.display.shared.io;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * TarCompressorUtils — extract .tar.xz, .tar.gz, and .tar archives
 * from APK assets to a target directory.
 *
 * <p><b>Pure Java</b> — no shell commands. Android doesn't ship an
 * {@code xz} binary, so we use the {@code org.tukaani:xz} library for
 * XZ decompression and a hand-rolled tar reader for extraction.
 *
 * <p>This is the only reliable way to extract 100+ MB .tar.xz files
 * on Android. Shell-based approaches ({@code tar -xJf}, {@code xz -dc
 * | tar}) all fail because Android's toybox doesn't include {@code xz}.
 */
public final class TarCompressorUtils {

    private static final String TAG = "WayLandIE/Tar";

    public enum Type {
        XZ,      // .tar.xz / .txz
        GZIP,    // .tar.gz / .tgz
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

        // Step 1: Copy asset to a temp file (can't read from AssetManager in Java tar parser)
        File tmpArchive = File.createTempFile("waylandie-extract-", ".tar", context.getCacheDir());
        try {
            long totalBytes = copyAssetToFile(context, assetName, tmpArchive, listener);
            Log.i(TAG, "Copied " + assetName + " → " + tmpArchive
                    + " (" + totalBytes + " bytes)");

            // Step 2: Create the decompression stream
            InputStream fileIn = new BufferedInputStream(new FileInputStream(tmpArchive), 65536);
            InputStream decompressed;
            if (type == Type.XZ) {
                decompressed = new org.tukaani.xz.XZInputStream(fileIn);
            } else if (type == Type.GZIP) {
                decompressed = new GZIPInputStream(fileIn);
            } else {
                decompressed = fileIn;
            }

            // Step 3: Parse tar stream and extract files
            extractTarStream(decompressed, outDir, listener, totalBytes);
            decompressed.close();

            Log.i(TAG, "Extracted " + assetName + " → " + outDir);

            if (listener != null) {
                listener.onExtractedBytes(totalBytes);
            }
        } finally {
            tmpArchive.delete();
        }
    }

    /**
     * Parses a tar stream and extracts all entries to outDir.
     * Pure Java — no shell commands.
     */
    private static void extractTarStream(InputStream in, File outDir,
                                          OnExtractFileListener listener,
                                          long totalCompressedBytes) throws IOException {
        byte[] header = new byte[512];
        long totalExtracted = 0;

        while (true) {
            // Read 512-byte header
            int read = readFully(in, header, 0, 512);
            if (read == 0) break;  // EOF
            if (read < 512) {
                throw new IOException("Incomplete tar header (read " + read + " bytes)");
            }

            // Check for end-of-archive (two zero blocks)
            boolean allZero = true;
            for (int i = 0; i < 512; i++) {
                if (header[i] != 0) { allZero = false; break; }
            }
            if (allZero) break;

            // Parse header fields (POSIX ustar format)
            String name = readString(header, 0, 100);
            // Skip if name is empty
            if (name.isEmpty()) continue;

            long size = parseOctal(header, 124, 12);
            int typeFlag = header[156];

            // Handle prefix (ustar extension)
            String prefix = readString(header, 345, 155);
            String fullName;
            if (prefix != null && !prefix.isEmpty()) {
                fullName = prefix + "/" + name;
            } else {
                fullName = name;
            }

            // Strip leading "./"
            if (fullName.startsWith("./")) fullName = fullName.substring(2);

            // Calculate number of data blocks
            int dataBlocks = (int) ((size + 511) / 512);
            long remaining = size;

            // Only extract regular files, directories, and symlinks
            if (typeFlag == '5') {
                // Directory
                File dir = new File(outDir, fullName);
                dir.mkdirs();
                // No data blocks for directories
            } else if (typeFlag == '2') {
                // Symlink
                String linkTarget = readString(header, 157, 100);
                File linkFile = new File(outDir, fullName);
                linkFile.getParentFile().mkdirs();
                try {
                    java.nio.file.Files.createSymbolicLink(
                            linkFile.toPath(),
                            new File(linkTarget).toPath());
                } catch (Exception e) {
                    // Symlinks may fail on Android — create a regular file as fallback
                    Log.w(TAG, "Symlink failed for " + fullName + ": " + e.getMessage());
                    linkFile.createNewFile();
                }
            } else if (typeFlag == '0' || typeFlag == 0 || typeFlag == '7') {
                // Regular file
                File outFile = new File(outDir, fullName);
                outFile.getParentFile().mkdirs();

                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), 65536)) {
                    byte[] buf = new byte[65536];
                    while (remaining > 0) {
                        int toRead = (int) Math.min(remaining, buf.length);
                        int n = in.read(buf, 0, toRead);
                        if (n < 0) throw new IOException("Unexpected EOF in tar data for " + fullName);
                        out.write(buf, 0, n);
                        remaining -= n;
                        totalExtracted += n;

                        // Report progress
                        if (listener != null && totalExtracted % (1024 * 1024) < 65536) {
                            final long te = totalExtracted;
                            listener.onExtractedBytes(te);
                        }
                    }
                }

                // Set executable bit for files in bin/ or that have the exec flag
                if (fullName.contains("/bin/") || fullName.startsWith("bin/")) {
                    outFile.setExecutable(true, false);
                }
            } else {
                // Skip other types (hardlinks, char devices, etc.)
                Log.w(TAG, "Skipping tar entry type " + (char) typeFlag + ": " + fullName);
            }

            // Skip remaining data blocks (padding to 512-byte boundary)
            if (remaining > 0) {
                long toSkip = remaining;
                while (toSkip > 0) {
                    long skipped = in.skip(toSkip);
                    if (skipped <= 0) break;
                    toSkip -= skipped;
                }
            }

            // Skip padding (already handled by reading in 512-byte blocks above for files,
            // but for non-file entries we need to skip the data blocks)
            if (typeFlag != '0' && typeFlag != 0 && typeFlag != '7' && typeFlag != '5' && typeFlag != '2') {
                long padBytes = dataBlocks * 512L;
                while (padBytes > 0) {
                    long skipped = in.skip(padBytes);
                    if (skipped <= 0) break;
                    padBytes -= skipped;
                }
            }
        }

        Log.i(TAG, "Tar extraction complete. Total extracted: " + totalExtracted + " bytes");
    }

    /**
     * Reads exactly {@code len} bytes or returns 0 at EOF.
     */
    private static int readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, off + total, len - total);
            if (n < 0) {
                if (total == 0) return 0;
                break;
            }
            total += n;
        }
        return total;
    }

    /**
     * Reads a null-terminated string from a tar header field.
     */
    private static String readString(byte[] buf, int offset, int length) {
        int end = offset;
        while (end < offset + length && buf[end] != 0) end++;
        return new String(buf, offset, end - offset).trim();
    }

    /**
     * Parses an octal number from a tar header field.
     */
    private static long parseOctal(byte[] buf, int offset, int length) {
        String s = readString(buf, offset, length);
        if (s.isEmpty()) return 0;
        try {
            return Long.parseLong(s, 8);
        } catch (NumberFormatException e) {
            // Some tar implementations use binary format for large files
            // (high bit set in first byte). Fall back to binary parse.
            long value = 0;
            for (int i = offset; i < offset + length; i++) {
                value = (value << 8) | (buf[i] & 0xFF);
            }
            return value;
        }
    }

    private static long copyAssetToFile(Context context, String assetName,
                                        File outFile, OnExtractFileListener listener)
            throws IOException {
        long total = 0;
        try (InputStream in = context.getAssets().open(assetName);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), 65536)) {
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
}
