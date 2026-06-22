package io.waylandie.display.shared.io;

import android.content.res.AssetManager;
import android.util.Log;
import java.io.File;

/**
 * JNI bridge to native C++ extraction (native_content_io.cpp).
 * Provides 5-10x faster extraction than pure Java for large archives.
 * Supports both XZ (.tar.xz) and ZSTD (.tar.zst/.tzst) formats.
 *
 * If the native library fails to load, all methods return false and
 * TarCompressorUtils falls back to Java extraction automatically.
 */
public final class NativeContentIO {
    private static final String TAG = "NativeContentIO";

    public static final int TYPE_XZ = 0;
    public static final int TYPE_ZSTD = 1;

    private static volatile boolean nativeLoaded = false;

    static {
        try {
            System.loadLibrary("waylandie_native_io");
            nativeLoaded = true;
            Log.i(TAG, "Native extraction library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Native extraction library not available, using Java fallback: " + e.getMessage());
            nativeLoaded = false;
        }
    }

    private NativeContentIO() {}

    /**
     * Extracts an archive FILE to a destination directory using native C++ code.
     * Supports XZ and ZSTD formats.
     *
     * @param type TYPE_XZ or TYPE_ZSTD
     * @param source the archive file to extract
     * @param destination the directory to extract into
     * @param listener optional progress listener (may be null)
     * @return true if extraction succeeded, false if native not available or failed
     */
    public static boolean extractArchive(int type, File source, File destination,
                                         OnExtractFileListener listener) {
        if (!nativeLoaded || source == null || destination == null || !source.isFile()) return false;
        return nativeExtractArchive(type, source.getAbsolutePath(),
                destination.getAbsolutePath(), listener);
    }

    /**
     * Extracts an APK ASSET directly to a destination directory using native C++.
     * This is the fastest path — no temp file copy needed.
     *
     * @param type TYPE_XZ or TYPE_ZSTD
     * @param assetManager the Android AssetManager
     * @param assetFile the asset path (e.g. "imagefs/imagefs.tar.zst")
     * @param destination the directory to extract into
     * @param listener optional progress listener (may be null)
     * @return true if extraction succeeded, false if native not available or failed
     */
    public static boolean extractAsset(int type, AssetManager assetManager,
                                       String assetFile, File destination,
                                       OnExtractFileListener listener) {
        if (!nativeLoaded || assetManager == null || assetFile == null
                || assetFile.isEmpty() || destination == null) {
            return false;
        }
        return nativeExtractAsset(type, assetManager, assetFile,
                destination.getAbsolutePath(), listener);
    }

    /**
     * Check if the native library is loaded and available.
     */
    public static boolean isAvailable() {
        return nativeLoaded;
    }

    private static native boolean nativeExtractArchive(
            int type, String sourcePath, String destinationPath, Object listener);

    private static native boolean nativeExtractAsset(
            int type, AssetManager assetManager, String assetFile,
            String destinationPath, Object listener);
}
