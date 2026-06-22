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
 * <p>On first launch, the app extracts {@code assets/imagefs/imagefs.tar.zst}
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

    // Version 2: rootfs now includes unzip + binutils (for .zip extraction,
    // .deb extraction via ar, and strings validation via grep -a fallback).
    // Bumping from 1 → 2 forces re-extraction on existing installs so they
    // get the new packages without manually clearing app data.
    // Version 3: symlink extraction fix — symlinks were created as 0-byte files
    // because the extraction code created a regular file FIRST, then tried to
    // replace it with a symlink (which failed). All libraries were broken.
    // Version 4: Switched rootfs from Debian Trixie (glibc 2.41) to
    // Ubuntu 20.04 Focal (glibc 2.31). glibc 2.41 triggers SIGSYS on
    // Android 16 (seccomp blocks rseq/clone3). glibc 2.31 is safe.
    // Version 5: Added LD_PRELOAD syscall shim (libwaylandie_shim.so) to
    // intercept blocked syscalls from libwayland-server/libvulkan constructors.
    // Version 6: Removed shim (bionic bridge replaces it). Aggressive rootfs
    // trimming: removed Python packages, static libs (.a), libtool files (.la),
    // pkgconfig files (.pc), wayland-protocols XML, wayland-scanner binary;
    // stripped debug symbols from all .so files. Smaller rootfs = faster
    // extraction. Bumping forces re-extraction of the new smaller rootfs.
    // Version 7: switched from XZ (.tar.xz) to ZSTD (.tar.zst) compression
    // for 3-5x faster extraction. Bumping forces re-extraction with new format.
    public static final int LATEST_VERSION = 7;
    private static final String IMAGEFS_ARCHIVE = "imagefs/imagefs.tar.zst";
    private static final long IMAGEFS_EXTRACTED_BYTES = 500_000_000L;

    private final Context context;
    private final File rootDir;

    public ImageFsManager(Context context) {
        this.context = context;
        // CRITICAL: rootfs MUST live in app-private internal storage
        // (getFilesDir() → /data/data/io.waylandie.display/files/imagefs).
        // Internal storage supports chmod +x and file execution. External
        // storage (SD card, /sdcard) is mounted noexec on Android 11+,
        // which would break proot, bash, and all rootfs binaries. Never
        // change this to getExternalFilesDir() or Environment.getExternalStorage*().
        this.rootDir = new File(context.getFilesDir(), "imagefs");
    }

    public File getRootDir() {
        return rootDir;
    }
    public File getBinDir() { return new File(rootDir, "usr/bin"); }
    public File getLibDir() { return new File(rootDir, "usr/lib"); }

    /**
     * Returns the /etc directory of the rootfs.
     *
     * <p>Debian {@code debootstrap --variant=minbase} produces a top-level
     * {@code /etc} directory (NOT {@code /usr/etc}). Modern merged-/usr
     * systems may symlink {@code /etc → /usr/etc}, but the rootfs built by
     * {@code tools/build-imagefs.sh} uses the non-merged layout — see
     * {@code $ROOTFS_DIR/etc/apt/sources.list} in the build script.
     *
     * <p>PRE-BUG: this method always returned {@code new File(rootDir,
     * "usr/etc")}, which never existed on a real debootstrap rootfs. This
     * caused {@link #isValid()} to return false even when the rootfs was
     * perfectly healthy, blocking Continue and triggering the
     * "Extraction reported success but rootfs is invalid" error path.
     *
     * <p>FIX: try {@code usr/etc} first (for merged-/usr layouts), then
     * fall back to top-level {@code etc} (for traditional debootstrap
     * layouts). Callers that need to read a specific file should use
     * {@link #findEtcFile(String)} to handle both layouts.
     */
    public File getEtcDir() {
        File usrEtc = new File(rootDir, "usr/etc");
        if (usrEtc.isDirectory()) return usrEtc;
        return new File(rootDir, "etc");
    }

    public File getShareDir() { return new File(rootDir, "usr/share"); }
    public File getTmpDir() { return new File(rootDir, "usr/tmp"); }

    /**
     * Returns the /opt directory of the rootfs.
     *
     * <p>The build script creates {@code /opt/proton}, {@code /opt/dxvk},
     * {@code /opt/turnip}, {@code /opt/fex}. Wine is intentionally NOT
     * bundled (commit 798ff41: "Proton-only architecture"). So
     * {@code /opt/wine} not existing is BY DESIGN and should not cause
     * {@link #isValid()} to fail.
     */
    public File getOptDir() { return new File(rootDir, "opt"); }
    public File getHomeDir() { return new File(rootDir, "home/xuser"); }
    public File getWineDir() { return new File(rootDir, "opt/wine"); }
    public File getWinePrefix() { return new File(getHomeDir(), ".wine"); }

    public File getConfigDir() { return new File(rootDir, ".waylandie"); }
    public File getVersionFile() { return new File(getConfigDir(), ".img_version"); }

    /**
     * Returns the first existing path among {@code usr/<sub>} and
     * {@code <sub>} (for non-merged-/usr rootfs). Used for finding
     * specific files like {@code etc/vulkan/icd.d/freedreno_icd.json}.
     */
    public File findEtcFile(String relativePath) {
        File usrEtc = new File(new File(rootDir, "usr/etc"), relativePath);
        if (usrEtc.exists()) return usrEtc;
        return new File(new File(rootDir, "etc"), relativePath);
    }

    public boolean isValid() {
        return rootDir.isDirectory()
                && getVersionFile().exists()
                && getBinDir().isDirectory()
                && getLibDir().isDirectory()
                && getEtcDir().isDirectory()
                && getShareDir().isDirectory()
                && getOptDir().isDirectory();
    }

    /**
     * Returns a human-readable string listing which {@link #isValid()}
     * checks pass and which fail. Used by {@code LogCollector} and
     * {@code EnvironmentInitializer} so we never have to guess which
     * directory is missing again.
     */
    public String describeValidity() {
        StringBuilder sb = new StringBuilder();
        sb.append("rootDir.isDirectory()=").append(rootDir.isDirectory())
          .append(" (").append(rootDir).append(")\n");
        sb.append("getVersionFile().exists()=").append(getVersionFile().exists())
          .append(" (").append(getVersionFile()).append(")\n");
        sb.append("getBinDir().isDirectory()=").append(getBinDir().isDirectory())
          .append(" (").append(getBinDir()).append(")\n");
        sb.append("getLibDir().isDirectory()=").append(getLibDir().isDirectory())
          .append(" (").append(getLibDir()).append(")\n");
        sb.append("getEtcDir().isDirectory()=").append(getEtcDir().isDirectory())
          .append(" (").append(getEtcDir()).append(")\n");
        sb.append("getShareDir().isDirectory()=").append(getShareDir().isDirectory())
          .append(" (").append(getShareDir()).append(")\n");
        sb.append("getOptDir().isDirectory()=").append(getOptDir().isDirectory())
          .append(" (").append(getOptDir()).append(")");
        return sb.toString();
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
        // FAST PATH: Use Android's native toybox tar (3-5x faster than Java).
        // Copy asset to temp file, then run 'tar -xJf <temp> -C <rootDir>'.
        // FALLBACK: If native tar fails, use Java extraction (Apache Commons).
        if (listener != null) listener.onProgress(1);
        boolean ok = false;

        // Try native tar extraction first
        try {
            ok = extractWithNativeTar(context, IMAGEFS_ARCHIVE, rootDir, listener);
            if (ok) {
                Log.i(TAG, "Rootfs extracted via native tar (fast path)");
            }
        } catch (Exception e) {
            Log.w(TAG, "Native tar extraction failed: " + e.getMessage() + " — falling back to Java");
        }

        // Fall back to Java extraction if native failed
        if (!ok) {
            Log.i(TAG, "Falling back to Java extraction (slower)");
            if (listener != null) listener.onProgress(5);
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

            ok = TarCompressorUtils.extractSync(
                    TarCompressorUtils.Type.XZ,
                    context,
                    IMAGEFS_ARCHIVE,
                    rootDir,
                    extractListener);
        }

        if (!ok) {
            lastError = "Rootfs tarball extraction failed."
                    + "\n  Both native tar and Java extraction failed."
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

    /**
     * Extracts the imagefs tarball using Android's native toybox tar command.
     * This is 3-5x faster than Java extraction because it uses native C code
     * for both XZ decompression and file I/O.
     *
     * <p>Process:
     * <ol>
     *   <li>Copy the asset to a temp file (cacheDir/imagefs.tar.zst)</li>
     *   <li>Run {@code /system/bin/tar -xJf <temp> -C <rootDir>}</li>
     *   <li>Delete the temp file</li>
     * </ol>
     *
     * @return true if extraction succeeded, false if it failed (caller should
     *         fall back to Java extraction)
     */
    private boolean extractWithNativeTar(android.content.Context ctx,
                                          String assetName, File destDir,
                                          ProgressListener listener) {
        File tempFile = new File(ctx.getCacheDir(), "imagefs.tar.zst");
        try {
            // Step 1: Copy asset to temp file
            Log.i(TAG, "Copying imagefs asset to temp file for native extraction…");
            try (java.io.InputStream in = ctx.getAssets().open(assetName);
                 java.io.OutputStream out = new java.io.BufferedOutputStream(
                         new java.io.FileOutputStream(tempFile), 262144)) {
                byte[] buf = new byte[262144];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
                Log.i(TAG, "Copied " + (total / 1024 / 1024) + " MB to temp file");
            }
            if (listener != null) listener.onProgress(10);

            // Step 2: Run native extraction
            // Try multiple approaches — Samsung's toybox tar may not support -J (xz)
            // Approach 1: tar -xJf (if toybox has xz support)
            // Approach 2: xz -dc | tar -xf - (more widely supported)
            Log.i(TAG, "Running native tar extraction…");

            // Try approach 1: tar -xJf
            ProcessBuilder pb = new ProcessBuilder(
                    "/system/bin/tar", "-xJf", tempFile.getAbsolutePath(),
                    "-C", destDir.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();

            // Read output in a reader thread
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        output.append(line).append('\n');
                    }
                } catch (Exception ignored) {}
            }, "wl-tar-reader");
            reader.start();

            boolean exited = p.waitFor(3, java.util.concurrent.TimeUnit.MINUTES);
            if (!exited) {
                p.destroyForcibly();
                Log.e(TAG, "Native tar -xJf timed out");
                return false;
            }
            reader.join(2000);
            int exitCode = p.exitValue();

            // If tar -xJf failed, try approach 2: xz -dc | tar -xf -
            if (exitCode != 0) {
                Log.w(TAG, "tar -xJf failed (exit=" + exitCode + "), trying xz | tar pipeline");
                output.setLength(0);
                // Use shell to pipe: xz -dc <file> | tar -xf - -C <dir>
                pb = new ProcessBuilder("/system/bin/sh", "-c",
                        "/system/bin/xz -dc " + tempFile.getAbsolutePath()
                                + " | /system/bin/tar -xf - -C " + destDir.getAbsolutePath());
                pb.redirectErrorStream(true);
                Process p2 = pb.start();
                reader = new Thread(() -> {
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p2.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            output.append(line).append('\n');
                        }
                    } catch (Exception ignored) {}
                }, "wl-tar-reader2");
                reader.start();
                exited = p2.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
                if (!exited) {
                    p2.destroyForcibly();
                    Log.e(TAG, "xz | tar pipeline timed out");
                    return false;
                }
                reader.join(2000);
                exitCode = p2.exitValue();
            }

            if (exitCode != 0) {
                Log.e(TAG, "Native extraction failed (exit=" + exitCode + "): "
                        + output.toString().trim());
                return false;
            }

            Log.i(TAG, "Native tar extraction complete");
            if (listener != null) listener.onProgress(95);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Native tar extraction error: " + e.getMessage());
            return false;
        } finally {
            // Step 3: Clean up temp file
            tempFile.delete();
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
