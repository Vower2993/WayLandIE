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

            // === PE header patching: SizeOfStackReserve → 8MB ===
            //
            // ROOT CAUSE (found by source-level analysis of proton-wine's
            // init_thread_stack in dlls/ntdll/unix/thread.c):
            //
            // Wine allocates MULTIPLE stacks per thread:
            //   1. Kernel stack: uses kernel_stack_size (8MB via WINE_KERNEL_STACK_SIZE) ✓
            //   2. WoW64 64-bit stack: HARDCODED 0x40000 (256KB) — not affected by env var
            //   3. WoW64 32-bit stack: uses PE header SizeOfStackReserve (1MB default)
            //   4. ARM64EC emulator stack: HARDCODED 0x40000 (256KB) — not affected
            //   5. Native stack: uses PE header SizeOfStackReserve (1MB default)
            //
            // FEX's libarm64ecfex.dll DllMain runs on stack #3 or #5 (the
            // native/WoW64 stack), NOT on the kernel stack. WINE_KERNEL_STACK_SIZE
            // only affects the kernel stack (#1), so it does NOT help FEX.
            //
            // The previous diagnosis (commit 74193cd) was WRONG: it claimed
            // WINE_KERNEL_STACK_SIZE=8192 alone was sufficient, but that was
            // never tested with an x86 game that loads FEX. The new trace
            // (launch-trace-2026-06-25_02-47-59.txt) proves the stack overflow
            // persists with WINE_KERNEL_STACK_SIZE=8192 set:
            //   00fc:err:module:hacks_init HACK: setting kernel_stack_size to 8192KB.
            //   00fc:err:virtual:virtual_setup_exception stack overflow 432 bytes
            //     stack 0x100800000-0x100801000-0x1008ffd20  ← only 1MB
            //
            // The previous claim that "ntdll.so ignores SizeOfStackReserve" was
            // also wrong — the source clearly shows virtual_alloc_thread_stack
            // uses main_image_info.MaximumStackSize (from PE header) when
            // reserve_size=0:
            //   if (!reserve_size) reserve_size = main_image_info.MaximumStackSize;
            //
            // FIX: Patch SizeOfStackReserve to 8MB in ALL exe files:
            //   - Builtin exes in proton lib/wine/{arch}-windows/
            //   - Prefix exes in .wine/drive_c/windows/system32/ and syswow64/
            //
            // The "regression" observed in 303a802 was from mscoree=d (added
            // in the same commit), NOT from PE patching. mscoree=d crashes
            // explorer.exe. PE patching is safe and necessary.
            log("=== PE header patching: SizeOfStackReserve → 8MB ===");
            patchExeStackReserve(prefix);

            // Also patch exes in the Wine prefix's system32/ and syswow64/.
            // The prefix exes are real files (copied from prefixPack.txz),
            // NOT symlinks, so they need independent patching.
            File imagefsRoot = new File(ctx.getFilesDir(), "imagefs");
            File winePrefix = new File(new File(imagefsRoot, "home"), "xuser/.wine");
            File system32 = new File(winePrefix, "drive_c/windows/system32");
            File syswow64 = new File(winePrefix, "drive_c/windows/syswow64");
            log("  patching prefix system32/: " + system32.getAbsolutePath());
            patchExeStackReserveInDir(system32);
            log("  patching prefix syswow64/: " + syswow64.getAbsolutePath());
            patchExeStackReserveInDir(syswow64);
            log("=== PE header patching complete ===");

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
                stackReserveOffset = eLfanew + 24 + 56;
                fieldSize = 8;
            } else if (magic == 0x010B) { // PE32 (32-bit)
                stackReserveOffset = eLfanew + 24 + 48;
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

    /**
     * Patch a single game exe's SizeOfStackReserve to 8MB. Called by
     * WineRunner.execWine() before launching Wine, so the game's main
     * thread gets 8MB stack (enough for FEX DllMain + game init).
     *
     * This is needed because:
     *   - Builtin exes (rundll32.exe, explorer.exe, etc.) are patched once
     *     during install via patchExeStackReserve().
     *   - Game exes (ROTTR.exe, sekiro.exe, etc.) are NOT in the proton tree
     *     and are NOT patched during install. They need per-launch patching.
     *
     * The patch is idempotent: if the exe already has SizeOfStackReserve >= 8MB,
     * it returns false (no modification). This means re-running is safe.
     *
     * @param exe the game exe file (must be a PE file)
     * @return true if the file was modified, false if already patched or not a valid PE
     */
    public static boolean patchGameExe(File exe) {
        if (exe == null || !exe.exists() || !exe.isFile()) return false;
        final long TARGET = 0x800000L; // 8MB
        try {
            boolean modified = patchOneExe(exe, TARGET);
            if (modified) {
                log("  [game-exe] patched " + exe.getName() + " → 8MB stack reserve");
            }
            return modified;
        } catch (IOException e) {
            log("  [game-exe] PATCH FAIL: " + exe.getName() + " — " + e.getMessage());
            return false;
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
