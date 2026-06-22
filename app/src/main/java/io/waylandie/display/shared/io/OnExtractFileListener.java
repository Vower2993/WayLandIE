package io.waylandie.display.shared.io;

import java.io.File;

/**
 * Listener for file extraction progress.
 * Used by both native C++ extraction (NativeContentIO) and Java extraction
 * (TarCompressorUtils).
 */
public interface OnExtractFileListener {
    File onExtractFile(File file, long size);
    void onExtractFileProgress(File file, long size);
    default boolean mapsExtractedFiles() { return false; }
    default boolean reportsExtractedBytesOnly() { return false; }
    default void onExtractedBytes(long size) {}
}
