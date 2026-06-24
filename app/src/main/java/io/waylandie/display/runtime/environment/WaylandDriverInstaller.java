package io.waylandie.display.runtime.environment;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.util.zip.*;

/**
 * Extracts winewayland-driver.zip from APK assets into the proton tree.
 * Logs to THREE places:
 *   1. logcat (Log.i/Log.w)
 *   2. WineRunner.preLaunchDiagnostics (captured by GameLaunchTracer)
 *   3. System.err (captured if running under a stderr-redirecting shell)
 */
public final class WaylandDriverInstaller {
    private static final String TAG = "WaylandDriverInstaller";
    private static final String ASSET = "winewayland-driver.zip";

    public static boolean install(Context ctx, File prefix) {
        log("=== WaylandDriverInstaller starting ===");
        log("  prefix=" + prefix);
        log("  prefix exists=" + prefix.exists());

        // Check if asset exists in APK
        try {
            String[] assets = ctx.getAssets().list("");
            boolean found = false;
            if (assets != null) {
                for (String a : assets) {
                    if (ASSET.equals(a)) { found = true; break; }
                }
            }
            log("  APK assets listed: " + (assets != null ? assets.length : 0) + " items");
            if (assets != null && assets.length < 20) {
                StringBuilder sb = new StringBuilder("  assets: ");
                for (int i = 0; i < assets.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(assets[i]);
                }
                log(sb.toString());
            }
            log("  " + ASSET + " in assets: " + found);
            if (!found) {
                log("  SKIP: asset not found in APK");
                return false;
            }
        } catch (IOException ioe) {
            log("  FAILED to list assets: " + ioe.getMessage());
            return false;
        }

        // Always re-extract the driver — the old cached version may be from
        // a previous build (e.g. without FreeType) and won't be overwritten
        // if we skip. Delete old files first to ensure clean install.
        File drvCheck = new File(prefix, "lib/wine/aarch64-windows/winewayland.drv");
        File soCheck = new File(prefix, "lib/wine/aarch64-unix/winewayland.so");
        File ntdllAarch64Check = new File(prefix, "lib/wine/aarch64-windows/ntdll.dll");
        File ntdllArm64ecCheck = new File(prefix, "lib/wine/arm64ec-windows/ntdll.dll");
        if (drvCheck.exists()) {
            log("  deleting old winewayland.drv (" + drvCheck.length() + " bytes)");
            drvCheck.delete();
        }
        if (soCheck.exists()) {
            log("  deleting old winewayland.so (" + soCheck.length() + " bytes)");
            soCheck.delete();
        }
        // Delete old ntdll.dll in BOTH arch dirs so our fresh build (with
        // RtlIsEcCode + ProcessPendingCrossProcessEmulatorWork exports
        // required by FEX's libarm64ecfex.dll) replaces the user's pre-
        // installed Proton armec ntdll.dll.
        if (ntdllAarch64Check.exists()) {
            log("  deleting old aarch64-windows/ntdll.dll (" + ntdllAarch64Check.length() + " bytes)");
            ntdllAarch64Check.delete();
        }
        if (ntdllArm64ecCheck.exists()) {
            log("  deleting old arm64ec-windows/ntdll.dll (" + ntdllArm64ecCheck.length() + " bytes)");
            ntdllArm64ecCheck.delete();
        }
        // CRITICAL: Do NOT delete or replace ntdll.so. The user's Proton armec
        // ntdll.so has wineserver protocol version 933, but our build from
        // proton_11.0 has version 932. Replacing ntdll.so causes:
        //   wine client error:0: version mismatch 933/932.
        // Instead, the 8MB stack fix is done by patching PE headers of exe
        // files (see patchExeStackReserve below).

        // Extract
        long extracted = 0;
        try (InputStream is = ctx.getAssets().open(ASSET);
             ZipInputStream z = new ZipInputStream(new BufferedInputStream(is, 65536))) {
            byte[] buf = new byte[65536];
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                File out = safePath(prefix, e.getName());
                if (out == null) { log("  skip " + e.getName()); continue; }
                if (e.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = z.read(buf)) > 0) fos.write(buf, 0, n);
                }
                if (e.getName().endsWith(".so") || e.getName().endsWith(".drv")) {
                    out.setExecutable(true, true);
                }
                extracted++;
                log("  extracted: " + e.getName() + " (" + out.length() + " bytes)");
            }
            log("=== Install complete: " + extracted + " files extracted ===");
            log("  winewayland.drv at: " + drvCheck);
            log("  exists=" + drvCheck.exists() + " size=" + (drvCheck.exists() ? drvCheck.length() : 0));
            log("  ntdll.dll (aarch64) at: " + ntdllAarch64Check);
            log("  exists=" + ntdllAarch64Check.exists() + " size=" + (ntdllAarch64Check.exists() ? ntdllAarch64Check.length() : 0));
            log("  ntdll.dll (arm64ec) at: " + ntdllArm64ecCheck);
            log("  exists=" + ntdllArm64ecCheck.exists() + " size=" + (ntdllArm64ecCheck.exists() ? ntdllArm64ecCheck.length() : 0));
            if (ntdllArm64ecCheck.exists() && ntdllArm64ecCheck.length() < 100000) {
                log("  WARNING: arm64ec ntdll.dll is suspiciously small — FEX may still crash");
            }

            // === Patch PE headers of exe files to set SizeOfStackReserve = 8MB ===
            // FEX's libarm64ecfex.dll DllMain consumes ~1MB of stack. Wine's default
            // thread stack is 1MB (from the exe's PE header). By patching the
            // SizeOfStackReserve field in the PE Optional Header to 8MB, all threads
            // spawned by Wine get 8MB of stack — enough for FEX + Wine's loader.
            //
            // We patch exes in TWO locations:
            //   1. proton/lib/wine/{arch}-windows/ — builtin exes (fallback)
            //   2. wineprefix/drive_c/windows/{system32,syswow64}/ — prefix exes
            //      (these are the ones Wine ACTUALLY loads first; the builtin
            //      versions are only used if the prefix copy doesn't exist)
            //
            // This is safe because:
            //   - PE files don't have a protocol version (only Unix ELF .so files do)
            //   - We're only changing one number in the header, no code changes
            //   - 8MB is virtual-reserved, physical memory is committed on demand
            log("=== Patching PE headers for 8MB stack ===");
            patchExeStackReserve(prefix);

            // Also patch the Wine prefix exes — these are the ones Wine actually loads
            File rootDir = new File(ctx.getFilesDir(), "imagefs");
            File winePrefix = new File(rootDir, "home/xuser/.wine");
            File prefixSystem32 = new File(winePrefix, "drive_c/windows/system32");
            File prefixSyswow64 = new File(winePrefix, "drive_c/windows/syswow64");
            log("  Wine prefix: " + winePrefix.getAbsolutePath());
            log("  system32 exists: " + prefixSystem32.isDirectory());
            log("  syswow64 exists: " + prefixSyswow64.isDirectory());
            patchExeStackReserveInDir(prefixSystem32);
            patchExeStackReserveInDir(prefixSyswow64);

            return true;
        } catch (IOException ioe) {
            log("  INSTALL FAILED: " + ioe.getClass().getSimpleName() + ": " + ioe.getMessage());
            Log.e(TAG, "install failed", ioe);
            return false;
        }
    }

    /**
     * Log to three places:
     *   1. logcat (Log.i)
     *   2. WineRunner.installerDiagnostics (separate from preLaunchDiagnostics,
     *      which gets cleared by the diagnostics section)
     *   3. System.err (best-effort)
     */
    private static void log(String msg) {
        Log.i(TAG, msg);
        try {
            WineRunner.installerDiagnostics.append("[wayland-installer] ")
                .append(msg).append('\n');
        } catch (Throwable t) {
            // Ignore if static init order issues
        }
        System.err.println("[WaylandDriverInstaller] " + msg);
    }

    /**
     * Patch SizeOfStackReserve in PE headers of all .exe files in the proton
     * prefix's lib/wine/{arch}-windows/ directories. Sets the value to 8MB
     * (0x800000) so Wine allocates 8MB thread stacks instead of the default 1MB.
     *
     * This replaces the ntdll.so stack-size patch (which caused a wineserver
     * protocol version mismatch). PE files don't have a protocol version, so
     * patching their headers is safe regardless of Wine version.
     *
     * PE header layout:
     *   offset 0x3C: e_lfanew (4 bytes, LE) — pointer to PE signature
     *   e_lfanew + 0: PE signature ("PE\0\0")
     *   e_lfanew + 24: Optional Header
     *   e_lfanew + 24 + 0: Magic (0x20b = PE32+, 0x10b = PE32)
     *   For PE32+: SizeOfStackReserve at e_lfanew + 24 + 72 (8 bytes, LE)
     *   For PE32:  SizeOfStackReserve at e_lfanew + 24 + 72 (4 bytes, LE)
     */
    private static void patchExeStackReserve(File prefix) {
        final long TARGET_STACK_RESERVE = 0x800000L; // 8MB
        // Collect ALL proton directories that might contain exes.
        // Wine may load exes from proton/active/ OR proton/proton-armec/
        // (they can be different directories). Scan both + any proton-*/ dirs.
        java.util.List<File> protonDirs = new java.util.ArrayList<>();
        protonDirs.add(prefix);
        File protonParent = prefix.getParentFile(); // contents/proton/
        if (protonParent != null && protonParent.isDirectory()) {
            File[] siblings = protonParent.listFiles();
            if (siblings != null) {
                for (File s : siblings) {
                    if (s.isDirectory() && !s.equals(prefix)) {
                        protonDirs.add(s);
                    }
                }
            }
        }
        int patched = 0;
        int skipped = 0;
        for (File protonDir : protonDirs) {
            File[] archDirs = {
                new File(protonDir, "lib/wine/aarch64-windows"),
                new File(protonDir, "lib/wine/arm64ec-windows"),
                new File(protonDir, "lib/wine/x86_64-windows"),
                new File(protonDir, "lib/wine/i386-windows"),
            };
            for (File archDir : archDirs) {
                if (!archDir.isDirectory()) continue;
                File[] exes = archDir.listFiles((d, name) -> name.endsWith(".exe"));
                if (exes == null) continue;
                for (File exe : exes) {
                    try {
                        if (patchOneExe(exe, TARGET_STACK_RESERVE)) {
                            patched++;
                        } else {
                            skipped++;
                        }
                    } catch (Exception e) {
                        log("  PATCH FAIL: " + exe.getName() + " — " + e.getMessage());
                        skipped++;
                    }
                }
            }
        }
        log("  Builtin exes: patched " + patched + ", skipped " + skipped);
    }

    /**
     * Patch all .exe files in a single directory (e.g. wineprefix's system32/
     * or syswow64/). Unlike patchExeStackReserve which scans multiple arch
     * subdirs, this scans a FLAT directory.
     */
    private static void patchExeStackReserveInDir(File dir) {
        final long TARGET_STACK_RESERVE = 0x800000L; // 8MB
        if (!dir.isDirectory()) return;
        File[] exes = dir.listFiles((d, name) -> name.endsWith(".exe"));
        if (exes == null || exes.length == 0) {
            log("  " + dir.getName() + "/: no .exe files found");
            return;
        }
        int patched = 0;
        int skipped = 0;
        for (File exe : exes) {
            try {
                if (patchOneExe(exe, TARGET_STACK_RESERVE)) {
                    patched++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log("  PATCH FAIL: " + exe.getName() + " — " + e.getMessage());
                skipped++;
            }
        }
        log("  " + dir.getName() + "/: patched " + patched + ", skipped " + skipped);
    }

    /**
     * Patch a single PE file's SizeOfStackReserve. Returns true if the file
     * was modified, false if it was already >= target or couldn't be parsed.
     */
    private static boolean patchOneExe(File exe, long targetReserve) throws IOException {
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(exe, "rw");
        try {
            // Read e_lfanew at offset 0x3C
            raf.seek(0x3C);
            int eLfanew = Integer.reverseBytes(raf.readInt()) & 0x7FFFFFFF;
            if (eLfanew <= 0 || eLfanew > raf.length() - 26) return false;

            // Verify PE signature ("PE\0\0" in the file = bytes 50 45 00 00)
            // readInt() reads big-endian, so the raw value is 0x50450000
            raf.seek(eLfanew);
            int peSig = raf.readInt();
            if (peSig != 0x50450000) return false;

            // Read Optional Header magic
            raf.seek(eLfanew + 24);
            short magic = Short.reverseBytes(raf.readShort());

            long stackReserveOffset;
            int fieldSize;
            if (magic == 0x020B) { // PE32+ (64-bit)
                stackReserveOffset = eLfanew + 24 + 72;
                fieldSize = 8;
            } else if (magic == 0x010B) { // PE32 (32-bit)
                stackReserveOffset = eLfanew + 24 + 72;
                fieldSize = 4;
            } else {
                return false; // Unknown PE format
            }

            // Read current SizeOfStackReserve
            raf.seek(stackReserveOffset);
            long currentReserve;
            if (fieldSize == 8) {
                currentReserve = Long.reverseBytes(raf.readLong());
            } else {
                currentReserve = Integer.reverseBytes(raf.readInt()) & 0xFFFFFFFFL;
            }

            if (currentReserve >= targetReserve) {
                log("  skip " + exe.getParentFile().getName() + "/" + exe.getName()
                        + " — already " + (currentReserve / 1024 / 1024) + "MB");
                return false;
            }

            // Write new SizeOfStackReserve
            raf.seek(stackReserveOffset);
            if (fieldSize == 8) {
                raf.writeLong(Long.reverseBytes(targetReserve));
            } else {
                raf.writeInt(Integer.reverseBytes((int) targetReserve));
            }

            log("  patched " + exe.getParentFile().getName() + "/" + exe.getName()
                    + " — " + (currentReserve / 1024 / 1024) + "MB → " + (targetReserve / 1024 / 1024) + "MB");
            return true;
        } finally {
            raf.close();
        }
    }

    private static File safePath(File prefix, String name) {
        if (name == null || name.isEmpty() || name.startsWith("/")) return null;
        // Reject entries containing .. (path traversal)
        if (name.contains("..")) return null;
        File f = new File(prefix, name);
        // Use getCanonicalPath() to resolve any remaining .. or symlinks
        try {
            String p = f.getCanonicalPath();
            String pp = prefix.getCanonicalPath();
            if (!p.equals(pp) && !p.startsWith(pp + File.separator)) return null;
        } catch (IOException e) {
            return null;
        }
        return f;
    }
}
