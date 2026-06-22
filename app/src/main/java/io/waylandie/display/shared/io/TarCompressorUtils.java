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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

    /** I/O buffer size for extraction — 1MB reduces syscall overhead for large files. */
    private static final int BUFFER_SIZE = 1024 * 1024;  // 1MB

    public enum Type {
        XZ,      // .tar.xz / .txz
        GZIP,    // .tar.gz / .tgz
        TAR,     // uncompressed .tar
        ZIP,     // .zip
        ZSTD     // .tar.zst / .tzst / .wcp (Winlator Container Package)
    }

    // OnExtractFileListener is now a standalone class in the same package.
    // See OnExtractFileListener.java

    private TarCompressorUtils() {}

    // ------------------------------------------------------------------
    // Magic-byte detection — detects archive format from file content,
    // not extension. Used by SettingsActivity to auto-detect .wcp (gzip),
    // .zip, .tar.xz, etc. without relying on proot/shell.
    // ------------------------------------------------------------------

    /**
     * Detects archive type by reading the first 6 bytes (magic bytes).
     * Returns the detected Type, or null if unrecognized.
     *
     * Magic bytes reference:
     *   1f 8b           → gzip (tar.gz, .tgz)
     *   fd 37 7a 58 5a  → xz (tar.xz)
     *   50 4b 03 04     → zip
     *   28 b5 2f fd     → zstd (.tzst, .wcp, .tar.zst)
     *   75 73 74 61 72  → ustar (plain tar, at offset 257)
     */
    public static Type detectArchiveType(File archiveFile) {
        if (archiveFile == null || !archiveFile.isFile()) return null;
        try (InputStream in = new BufferedInputStream(new FileInputStream(archiveFile), 1048576)) {
            byte[] magic = new byte[6];
            int read = in.read(magic);
            if (read < 4) return null;
            // gzip: 1f 8b
            if ((magic[0] & 0xFF) == 0x1f && (magic[1] & 0xFF) == 0x8b) return Type.GZIP;
            // xz: fd 37 7a 58 5a
            if ((magic[0] & 0xFF) == 0xfd && (magic[1] & 0xFF) == 0x37
                    && (magic[2] & 0xFF) == 0x7a && (magic[3] & 0xFF) == 0x58
                    && (magic[4] & 0xFF) == 0x5a) return Type.XZ;
            // zstd: 28 b5 2f fd
            if ((magic[0] & 0xFF) == 0x28 && (magic[1] & 0xFF) == 0xb5
                    && (magic[2] & 0xFF) == 0x2f && (magic[3] & 0xFF) == 0xfd) return Type.ZSTD;
            // zip: 50 4b 03 04
            if ((magic[0] & 0xFF) == 0x50 && (magic[1] & 0xFF) == 0x4b
                    && (magic[2] & 0xFF) == 0x03 && (magic[3] & 0xFF) == 0x04) return Type.ZIP;
            // Check for ustar magic at offset 257 (plain tar)
            if (archiveFile.length() > 263) {
                try (InputStream in2 = new BufferedInputStream(new FileInputStream(archiveFile), 1048576)) {
                    in2.skip(257);
                    byte[] ustar = new byte[5];
                    int r2 = in2.read(ustar);
                    if (r2 == 5 && new String(ustar).equals("ustar")) return Type.TAR;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "detectArchiveType failed for " + archiveFile, e);
        }
        return null;
    }

    /**
     * Detects the archive type from an APK asset's magic bytes.
     * Reads the first 6 bytes of the asset to determine the format.
     */
    public static Type detectAssetType(Context context, String assetName) {
        if (context == null || assetName == null) return null;
        try (InputStream in = new BufferedInputStream(
                context.getAssets().open(assetName), 64)) {
            byte[] magic = new byte[6];
            int read = in.read(magic);
            if (read < 4) return null;
            // gzip: 1f 8b
            if ((magic[0] & 0xFF) == 0x1f && (magic[1] & 0xFF) == 0x8b) return Type.GZIP;
            // xz: fd 37 7a 58 5a
            if ((magic[0] & 0xFF) == 0xfd && (magic[1] & 0xFF) == 0x37
                    && (magic[2] & 0xFF) == 0x7a && (magic[3] & 0xFF) == 0x58
                    && (magic[4] & 0xFF) == 0x5a) return Type.XZ;
            // zstd: 28 b5 2f fd
            if ((magic[0] & 0xFF) == 0x28 && (magic[1] & 0xFF) == 0xb5
                    && (magic[2] & 0xFF) == 0x2f && (magic[3] & 0xFF) == 0xfd) return Type.ZSTD;
            // zip: 50 4b 03 04
            if ((magic[0] & 0xFF) == 0x50 && (magic[1] & 0xFF) == 0x4b
                    && (magic[2] & 0xFF) == 0x03 && (magic[3] & 0xFF) == 0x04) return Type.ZIP;
            return Type.TAR; // assume plain tar as fallback
        } catch (IOException e) {
            Log.e(TAG, "detectAssetType failed for " + assetName, e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // File-based extraction (for user-picked driver archives).
    // These are the pure-Java equivalents of the shell script's
    // extract_archive() — no proot, no bash, no shell commands.
    // ------------------------------------------------------------------

    /**
     * Extracts an archive file (auto-detected type) to outDir.
     * Pure Java — no shell commands. Returns true on success.
     */
    public static boolean extractFile(File archiveFile, File outDir) {
        return extractFile(archiveFile, outDir, null);
    }

    /**
     * Extracts an archive file (auto-detected type) to outDir with
     * progress callbacks. Pure Java.
     */
    public static boolean extractFile(File archiveFile, File outDir, OnExtractFileListener listener) {
        Type type = detectArchiveType(archiveFile);
        if (type == null) {
            Log.e(TAG, "Unrecognized archive format: " + archiveFile);
            return false;
        }
        Log.i(TAG, "Detected archive type: " + type + " for " + archiveFile);
        return extractFileWithType(archiveFile, outDir, type, listener);
    }

    /**
     * Extracts an archive file with a known type to outDir.
     */
    public static boolean extractFileWithType(File archiveFile, File outDir, Type type,
                                               OnExtractFileListener listener) {
        if (archiveFile == null || !archiveFile.isFile()) return false;
        if (!outDir.exists() && !outDir.mkdirs()) {
            Log.e(TAG, "Failed to mkdir " + outDir);
            return false;
        }

        // Try NATIVE extraction first (5-10x faster than Java)
        if (NativeContentIO.isAvailable() && (type == Type.XZ || type == Type.ZSTD)) {
            int nativeType = (type == Type.ZSTD) ? NativeContentIO.TYPE_ZSTD : NativeContentIO.TYPE_XZ;
            Log.i(TAG, "Using native extraction (C++) for " + archiveFile);
            if (NativeContentIO.extractArchive(nativeType, archiveFile, outDir, listener)) {
                Log.i(TAG, "Native extraction succeeded for " + archiveFile);
                return true;
            }
            Log.w(TAG, "Native extraction failed, falling back to Java for " + archiveFile);
        }

        // Retry loop — transient I/O errors (EOFException, read timeouts)
        // can occur when reading from external storage (sdcard). These are
        // especially common with large XZ archives on Samsung devices.
        // Retry up to 3 times with a short delay between attempts.
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (type == Type.ZIP) {
                    extractZip(archiveFile, outDir, listener);
                } else if (type == Type.ZSTD) {
                    extractZstdTar(archiveFile, outDir, listener);
                } else if (type == Type.XZ) {
                    extractXzTar(archiveFile, outDir, listener);
                } else if (type == Type.GZIP) {
                    extractGzipTar(archiveFile, outDir, listener);
                } else {
                    extractPlainTar(archiveFile, outDir, listener);
                }
                Log.i(TAG, "Extracted " + archiveFile + " → " + outDir
                        + (attempt > 1 ? " (attempt " + attempt + ")" : ""));
                return true;
            } catch (java.io.EOFException eof) {
                // Transient XZ/LZMA stream corruption — retry
                Log.w(TAG, "Extraction EOFException on attempt " + attempt
                        + "/" + maxRetries + " for " + archiveFile + ": " + eof.getMessage());
                if (attempt < maxRetries) {
                    try { Thread.sleep(1000 * attempt); } catch (InterruptedException ignored) {}
                    // Clean up partial extraction before retrying
                    deleteRecursive(outDir);
                    outDir.mkdirs();
                } else {
                    Log.e(TAG, "Extraction failed after " + maxRetries + " attempts: " + archiveFile, eof);
                    return false;
                }
            } catch (Exception e) {
                Log.e(TAG, "Extraction failed for " + archiveFile, e);
                return false;
            }
        }
        return false;
    }

    /**
     * Extracts a .tar.xz archive using Apache Commons Compress.
     * Handles PAX/GNU/ustar tar variants correctly — the hand-rolled
     * extractTarStream fails on PAX headers in prefixPack.txz.
     */
    private static void extractXzTar(File archiveFile, File outDir, OnExtractFileListener listener)
            throws IOException {
        extractCompressedTar(archiveFile, outDir, listener,
                new org.apache.commons.compress.compressors.xz.XZCompressorInputStream(
                        new BufferedInputStream(new FileInputStream(archiveFile), BUFFER_SIZE)));
    }

    /**
     * Extracts a .tar.gz archive using Apache Commons Compress.
     */
    private static void extractGzipTar(File archiveFile, File outDir, OnExtractFileListener listener)
            throws IOException {
        extractCompressedTar(archiveFile, outDir, listener,
                new GZIPInputStream(new BufferedInputStream(new FileInputStream(archiveFile), BUFFER_SIZE)));
    }

    /**
     * Extracts a plain .tar archive using Apache Commons Compress.
     */
    private static void extractPlainTar(File archiveFile, File outDir, OnExtractFileListener listener)
            throws IOException {
        extractCompressedTar(archiveFile, outDir, listener,
                new BufferedInputStream(new FileInputStream(archiveFile), BUFFER_SIZE));
    }

    /**
     * Common extraction logic using Apache Commons Compress TarArchiveInputStream.
     * Handles all tar variants (ustar, PAX, GNU) correctly.
     */
    private static void extractCompressedTar(File archiveFile, File outDir,
            OnExtractFileListener listener, InputStream decompressed) throws IOException {
        long totalExtracted = 0;
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream tarIn =
                new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(decompressed);
        // Parallel file writer — 4 threads overlap tar reading with file writing
        ExecutorService writerPool = Executors.newFixedThreadPool(4);
        java.util.List<Future<?>> futures = new java.util.ArrayList<>();
        org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
        while ((entry = tarIn.getNextTarEntry()) != null) {
            String name = entry.getName();
            if (name == null || name.isEmpty()) continue;
            if (name.startsWith("./")) name = name.substring(2);
            File outFile = new File(outDir, name);
            String outDirCanonical = outDir.getCanonicalPath();
            String outFileCanonical = outFile.getCanonicalPath();
            if (!outFileCanonical.startsWith(outDirCanonical + File.separator)
                    && !outFileCanonical.equals(outDirCanonical)) {
                Log.w(TAG, "Skipping tar entry with path traversal: " + name);
                continue;
            }
            if (entry.isDirectory()) {
                outFile.mkdirs();
            } else if (entry.isSymbolicLink()) {
                outFile.getParentFile().mkdirs();
                if (outFile.exists()) outFile.delete();
                try {
                    java.nio.file.Files.createSymbolicLink(
                            outFile.toPath(), new File(entry.getLinkName()).toPath());
                } catch (Exception e) {
                    Log.w(TAG, "Symlink failed for " + name + ": " + e.getMessage());
                    try { outFile.createNewFile(); } catch (Exception ignored) {}
                }
            } else {
                outFile.getParentFile().mkdirs();
                long entrySize = entry.getSize();
                if (entrySize > 0 && entrySize < 8 * 1024 * 1024) {
                    // Small/medium file — buffer in memory, write via thread pool
                    byte[] data = new byte[(int) entrySize];
                    int off = 0;
                    while (off < data.length) {
                        int n = tarIn.read(data, off, data.length - off);
                        if (n < 0) break;
                        off += n;
                    }
                    totalExtracted += off;
                    if (listener != null && (totalExtracted % (1024 * 1024)) < 1048576) {
                        listener.onExtractedBytes(totalExtracted);
                    }
                    final byte[] fileData = data;
                    final int fileLen = off;
                    final File f = outFile;
                    final String fname = name;
                    futures.add(writerPool.submit(() -> {
                        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(f), 1048576)) {
                            out.write(fileData, 0, fileLen);
                        } catch (IOException e) {
                            Log.e(TAG, "Parallel write failed for " + fname + ": " + e.getMessage());
                        }
                        if (fname.contains("/bin/") || fname.startsWith("bin/")) {
                            f.setExecutable(true, false);
                        }
                    }));
                } else {
                    // Large file (>8MB) or unknown size — write directly
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), 1048576)) {
                        byte[] buf = new byte[1048576];
                        int n;
                        while ((n = tarIn.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            totalExtracted += n;
                            if (listener != null && (totalExtracted % (1024 * 1024)) < 1048576) {
                                listener.onExtractedBytes(totalExtracted);
                            }
                        }
                    }
                    if (name.contains("/bin/") || name.startsWith("bin/")) {
                        outFile.setExecutable(true, false);
                    }
                }
            }
        }
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception e) { Log.w(TAG, "Write future failed: " + e.getMessage()); }
        }
        writerPool.shutdown();
        tarIn.close();
        decompressed.close();
        Log.i(TAG, "Apache Commons tar extraction complete (parallel). Total: " + totalExtracted + " bytes");
    }

    /**
     * Extracts a .tar.zst / .tzst / .wcp archive using Apache Commons Compress.
     * This is the format winlator uses for all its .tzst packages.
     * Pure Java — no shell zstd needed.
     */
    private static void extractZstdTar(File archiveFile, File outDir, OnExtractFileListener listener)
            throws IOException {
        long totalExtracted = 0;
        try (InputStream fis = new BufferedInputStream(new FileInputStream(archiveFile), BUFFER_SIZE)) {
            // ZstdCompressorInputStream wraps the zstd-compressed stream
            org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream zstdIn =
                    new org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream(fis);
            // TarArchiveInputStream wraps the decompressed tar stream
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream tarIn =
                    new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(zstdIn);
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            while ((entry = tarIn.getNextTarEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isEmpty()) continue;
                // Strip leading ./
                if (name.startsWith("./")) name = name.substring(2);
                File outFile = new File(outDir, name);
                // Security: prevent path traversal
                String outDirCanonical = outDir.getCanonicalPath();
                String outFileCanonical = outFile.getCanonicalPath();
                if (!outFileCanonical.startsWith(outDirCanonical + File.separator)
                        && !outFileCanonical.equals(outDirCanonical)) {
                    Log.w(TAG, "Skipping tar entry with path traversal: " + name);
                    continue;
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else if (entry.isSymbolicLink()) {
                    outFile.getParentFile().mkdirs();
                    if (outFile.exists()) outFile.delete();
                    try {
                        java.nio.file.Files.createSymbolicLink(
                                outFile.toPath(),
                                new File(entry.getLinkName()).toPath());
                    } catch (Exception e) {
                        Log.w(TAG, "Symlink failed for " + name + ": " + e.getMessage());
                        try { outFile.createNewFile(); } catch (Exception ignored) {}
                    }
                } else {
                    outFile.getParentFile().mkdirs();
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), BUFFER_SIZE)) {
                        byte[] buf = new byte[BUFFER_SIZE];
                        int n;
                        while ((n = tarIn.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            totalExtracted += n;
                            if (listener != null && (totalExtracted % (1024 * 1024)) < 1048576) {
                                listener.onExtractedBytes(totalExtracted);
                            }
                        }
                    }
                    if (name.contains("/bin/") || name.startsWith("bin/")) {
                        outFile.setExecutable(true, false);
                    }
                }
            }
            tarIn.close();
            zstdIn.close();
        }
        Log.i(TAG, "Zstd tar extraction complete. Total extracted: " + totalExtracted + " bytes");
    }

    /**
     * Extracts a .zip archive using java.util.zip.ZipInputStream.
     * Pure Java — no shell unzip needed.
     */
    private static void extractZip(File zipFile, File outDir, OnExtractFileListener listener)
            throws IOException {
        long totalExtracted = 0;
        try (InputStream fis = new BufferedInputStream(new FileInputStream(zipFile), BUFFER_SIZE);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isEmpty()) continue;
                // Security: prevent path traversal (../../etc/passwd)
                File outFile = new File(outDir, name);
                String outDirCanonical = outDir.getCanonicalPath();
                String outFileCanonical = outFile.getCanonicalPath();
                if (!outFileCanonical.startsWith(outDirCanonical + File.separator)
                        && !outFileCanonical.equals(outDirCanonical)) {
                    Log.w(TAG, "Skipping zip entry with path traversal: " + name);
                    continue;
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), BUFFER_SIZE)) {
                        byte[] buf = new byte[BUFFER_SIZE];
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            totalExtracted += n;
                            if (listener != null && (totalExtracted % (1024 * 1024)) < 1048576) {
                                listener.onExtractedBytes(totalExtracted);
                            }
                        }
                    }
                    // Set executable for bin/ entries
                    if (name.contains("/bin/") || name.startsWith("bin/")) {
                        outFile.setExecutable(true, false);
                    }
                }
                zis.closeEntry();
            }
        }
        Log.i(TAG, "Zip extraction complete. Total extracted: " + totalExtracted + " bytes");
    }

    /**
     * Fast extraction using Apache Commons Compress — streams directly from
     * the asset, no temp file copy. This is 2-3x faster than the old
     * copy-to-temp-then-extract approach. Used for large rootfs archives.
     */
    public static boolean extractFast(Type type, Context context, String assetName,
                                      File outDir, OnExtractFileListener listener) {
        if (!outDir.exists() && !outDir.mkdirs()) {
            Log.e(TAG, "Failed to mkdir " + outDir);
            return false;
        }
        try (InputStream assetIn = context.getAssets().open(assetName);
             BufferedInputStream bis = new BufferedInputStream(assetIn, BUFFER_SIZE)) {

            InputStream decompressed;
            if (type == Type.XZ) {
                decompressed = new org.tukaani.xz.XZInputStream(bis);
            } else if (type == Type.GZIP) {
                decompressed = new GZIPInputStream(bis);
            } else if (type == Type.ZSTD) {
                decompressed = new org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream(bis);
            } else {
                decompressed = bis;
            }

            org.apache.commons.compress.archivers.tar.TarArchiveInputStream tar =
                    new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(decompressed);
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            long totalExtracted = 0;

            while ((entry = tar.getNextTarEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isEmpty()) continue;
                if (name.startsWith("./")) name = name.substring(2);

                File outFile = new File(outDir, name);
                String outDirCanonical = outDir.getCanonicalPath();
                String outFileCanonical = outFile.getCanonicalPath();
                if (!outFileCanonical.startsWith(outDirCanonical + File.separator)
                        && !outFileCanonical.equals(outDirCanonical)) {
                    continue; // skip path traversal
                }

                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else if (entry.isSymbolicLink()) {
                    outFile.getParentFile().mkdirs();
                    if (outFile.exists()) outFile.delete();
                    try {
                        java.nio.file.Files.createSymbolicLink(
                                outFile.toPath(), new File(entry.getLinkName()).toPath());
                    } catch (Exception e) {
                        Log.w(TAG, "Symlink failed for " + name + ": " + e.getMessage());
                        try { outFile.createNewFile(); } catch (Exception ignored) {}
                    }
                } else {
                    outFile.getParentFile().mkdirs();
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), BUFFER_SIZE)) {
                        byte[] buf = new byte[BUFFER_SIZE];
                        int n;
                        while ((n = tar.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            totalExtracted += n;
                            if (listener != null && (totalExtracted % (1024 * 1024)) < 1048576) {
                                listener.onExtractedBytes(totalExtracted);
                            }
                        }
                    }
                    if (name.contains("/bin/") || name.startsWith("bin/")) {
                        outFile.setExecutable(true, false);
                    }
                }
            }
            tar.close();
            Log.i(TAG, "Fast extraction complete. Total: " + totalExtracted + " bytes");
            if (listener != null) listener.onExtractedBytes(totalExtracted);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Fast extraction failed for " + assetName, e);
            return false;
        }
    }

    public static boolean extractSync(Type type, Context context, String assetName,
                                      File outDir, OnExtractFileListener listener) {
        // Auto-detect format from asset magic bytes if type is null
        if (type == null) {
            type = detectAssetType(context, assetName);
            if (type == null) {
                Log.e(TAG, "Could not detect archive type for " + assetName);
                return false;
            }
            Log.i(TAG, "Auto-detected archive type: " + type + " for " + assetName);
        }

        // Try NATIVE extraction first (5-10x faster than Java)
        if (NativeContentIO.isAvailable() && (type == Type.XZ || type == Type.ZSTD)) {
            int nativeType = (type == Type.ZSTD) ? NativeContentIO.TYPE_ZSTD : NativeContentIO.TYPE_XZ;
            Log.i(TAG, "Using native extraction (C++) for " + assetName);
            if (NativeContentIO.extractAsset(nativeType, context.getAssets(),
                    assetName, outDir, listener)) {
                Log.i(TAG, "Native extraction succeeded for " + assetName);
                return true;
            }
            Log.w(TAG, "Native extraction failed, falling back to Java for " + assetName);
        }

        // Fallback: Java extraction (Apache Commons Compress — no temp file)
        if (type == Type.XZ || type == Type.GZIP || type == Type.ZSTD) {
            Log.i(TAG, "Using Java extraction (Apache Commons Compress) for " + assetName);
            return extractFast(type, context, assetName, outDir, listener);
        }
        // Fallback to old method for plain TAR
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
            InputStream fileIn = new BufferedInputStream(new FileInputStream(tmpArchive), BUFFER_SIZE);
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

            // Only extract regular files, directories, and symlinks.
            //
            // Per POSIX 1003.1 ustar:
            //   '0' or '\0' or '7'  regular file
            //   '1'                  hard link (skip — has size 0)
            //   '2'                  symlink (skip — has size 0)
            //   '3'                  char device (skip — has size 0)
            //   '4'                  block device (skip — has size 0)
            //   '5'                  directory (skip — has size 0)
            //   '6'                  FIFO (skip — has size 0)
            //   'x'                  PAX header (HAS data — must skip)
            //   'g'                  PAX global header (HAS data — must skip)
            //   'L'                  GNU long name (HAS data — must skip)
            //   'K'                  GNU long link (HAS data — must skip)
            //
            // PRE-BUG: only '5', '2', '0', '\0', '7' were handled.
            // 'x', 'g', 'L', 'K' fell into the else branch at line ~213
            // which logged "Skipping tar entry type X" — but the skip-data
            // logic was duplicated (one skip in the if/else, one at the
            // bottom), causing the parser to skip the NEXT entry's header
            // as if it were data. Result: total misalignment, every
            // subsequent entry was misread as a tar header (type 't' is
            // not a real tar type — that was file content being parsed
            // as a header). All extraction was silently dropped.
            //
            // FIX: extract regular files / dirs / symlinks inline (consuming
            // their data), then for ALL OTHER types ('x', 'g', 'L', 'K',
            // and any unknown type) skip exactly `size` bytes of data
            // ONCE at the end. No double-skip.
            if (typeFlag == '5') {
                // Directory
                File dir = new File(outDir, fullName);
                dir.mkdirs();
                // No data blocks for directories (size is 0)
            } else if (typeFlag == '2') {
                // Symlink
                String linkTarget = readString(header, 157, 100);
                File linkFile = new File(outDir, fullName);
                linkFile.getParentFile().mkdirs();
                if (linkFile.exists()) linkFile.delete();
                try {
                    java.nio.file.Files.createSymbolicLink(
                            linkFile.toPath(),
                            new File(linkTarget).toPath());
                } catch (Exception e) {
                    Log.w(TAG, "Symlink failed for " + fullName + ": " + e.getMessage());
                    try { linkFile.createNewFile(); } catch (Exception ignored) {}
                }
            } else if (typeFlag == '0' || typeFlag == 0 || typeFlag == '7') {
                // Regular file
                File outFile = new File(outDir, fullName);
                outFile.getParentFile().mkdirs();

                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), BUFFER_SIZE)) {
                    byte[] buf = new byte[BUFFER_SIZE];
                    while (remaining > 0) {
                        int toRead = (int) Math.min(remaining, buf.length);
                        int n = in.read(buf, 0, toRead);
                        if (n < 0) throw new IOException("Unexpected EOF in tar data for " + fullName);
                        out.write(buf, 0, n);
                        remaining -= n;
                        totalExtracted += n;

                        // Report progress
                        if (listener != null && totalExtracted % (1024 * 1024) < 1048576) {
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
                // PAX headers ('x', 'g'), GNU long-name ('L', 'K'), and any
                // unknown type. We don't extract these to disk, but we MUST
                // skip their data blocks so the parser stays aligned with
                // the next tar entry header.
                if (typeFlag != '1' && typeFlag != '3' && typeFlag != '4'
                        && typeFlag != '6' && typeFlag != 'x' && typeFlag != 'g'
                        && typeFlag != 'L' && typeFlag != 'K') {
                    Log.w(TAG, "Skipping unknown tar entry type "
                            + (char) typeFlag + " (0x" + Integer.toHexString(typeFlag & 0xFF)
                            + "): " + fullName);
                }
            }

            // Skip any remaining data bytes (for non-file entries, this is
            // the entire data payload; for files this is 0 because the
            // extraction loop consumed everything).
            if (remaining > 0) {
                long toSkip = remaining;
                while (toSkip > 0) {
                    long skipped = in.skip(toSkip);
                    if (skipped <= 0) {
                        // If skip returns 0, fall back to a single-byte read
                        // to make progress (some InputStreams don't support
                        // skip natively).
                        int b = in.read();
                        if (b < 0) break;
                        toSkip--;
                    } else {
                        toSkip -= skipped;
                    }
                }
            }

            // Skip padding to 512-byte boundary. For files this is the
            // (dataBlocks * 512 - size) bytes of zero-padding at the end
            // of the last data block. For non-file entries, same math.
            // PRE-BUG: this block was conditionally skipped for files
            // (because the extraction loop was assumed to consume full
            // blocks), but the loop actually consumed exactly `size` bytes,
            // not `dataBlocks * 512`. So padding was never skipped for
            // ANY entry, causing misalignment on the next header.
            long padding = (dataBlocks * 512L) - size;
            while (padding > 0) {
                long skipped = in.skip(padding);
                if (skipped <= 0) {
                    int b = in.read();
                    if (b < 0) break;
                    padding--;
                } else {
                    padding -= skipped;
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
             OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), BUFFER_SIZE)) {
            byte[] buf = new byte[BUFFER_SIZE];
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

    /** Recursively deletes a file or directory. Used for cleanup on retry. */
    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File kid : kids) deleteRecursive(kid);
            }
        }
        f.delete();
    }
}
