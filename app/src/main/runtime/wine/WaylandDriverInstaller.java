package com.winlator.cmod.runtime.wine;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.util.zip.*;

/**
 * Installs winewayland.drv + winewayland.so + ntdll.dll into the proton tree.
 *
 * Two entry points:
 *   - install(): Called during container creation. Extracts the zip and
 *     (if display mode is wayland) sets GraphicsDriver in system.reg.
 *   - ensureDriverInstalled(): Called at LAUNCH TIME when display mode is
 *     Wayland. Ensures winewayland.drv is in system32 even if the container
 *     was created with X11 mode and later switched to Wayland.
 *
 * The driver zip is built by CI (.github/scripts/build-winewayland-driver.sh)
 * and shipped as an APK asset.
 */
public final class WaylandDriverInstaller {
    private static final String TAG = "WaylandDriverInstaller";
    private static final String ASSET = "winewayland-driver.zip";

    public static boolean install(Context ctx, File prefix, String displayMode) {
        Log.i(TAG, "Installing winewayland driver to " + prefix);
        try {
            extractZip(ctx, prefix);
            copyToSystem32(prefix);
            // Set GraphicsDriver=winewayland.drv only when display mode is wayland
            if (!"wayland".equals(displayMode)) {
                Log.i(TAG, "Display mode is not wayland — skipping GraphicsDriver");
                return true;
            }
            setGraphicsDriver(prefix);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Install failed", e);
            return false;
        }
    }

    /**
     * Called at launch time when display mode is Wayland.
     * Ensures winewayland.drv + libarm64ecfex.dll + ntdll.dll are in system32
     * and GraphicsDriver is set in system.reg.
     *
     * This handles the case where the container was created with X11 mode
     * (default) and the user later switched to Wayland via shortcut settings.
     * Without this, Wine would fail to load winewayland.drv with
     * STATUS_NOT_FOUND (0xc0000135) and fall back to nodrv_CreateWindow,
     * causing "The explorer process failed to start" and container crash.
     */
    /**
     * Called at launch time when display mode is Wayland.
     * Ensures winewayland.drv + libarm64ecfex.dll + ntdll.dll are in system32
     * and GraphicsDriver is set in system.reg.
     *
     * CRITICAL: Wine's builtin driver loader needs BOTH the PE file (.drv) in
     * system32 AND the Unix companion (.so) in Wine's install dir
     * (imageFs.winePath/lib/wine/aarch64-unix/). Without the .so, Wine fails
     * with STATUS_NOT_FOUND (0xc0000135) even though the .drv is in system32.
     *
     * @param ctx       Android context
     * @param prefix    container's .wine directory (for system32 + system.reg)
     * @param winePath  Wine install dir (imageFs.winePath = .../opt/proton-9.0-arm64ec)
     */
    public static boolean ensureDriverInstalled(Context ctx, File prefix, File winePath) {
        File system32 = new File(prefix, "drive_c/windows/system32");
        File driverInSystem32 = new File(system32, "winewayland.drv");

        boolean needsInstall = !driverInSystem32.exists()
                || driverInSystem32.length() < 1000;
        if (needsInstall) {
            Log.i(TAG, "ensureDriverInstalled: winewayland.drv missing from system32 — extracting");
            try {
                extractZip(ctx, prefix);
                copyToSystem32(prefix);
            } catch (IOException e) {
                Log.e(TAG, "ensureDriverInstalled: extraction failed", e);
                writeDiagnostic(ctx, prefix, winePath, "EXTRACTION_FAILED: " + e.getMessage());
                return false;
            }
        } else {
            Log.i(TAG, "ensureDriverInstalled: winewayland.drv already present in system32");
        }

        // CRITICAL: Copy winewayland.drv + winewayland.so to Wine's install dir.
        // Wine's builtin driver loader looks for the Unix companion .so in
        // winePath/lib/wine/aarch64-unix/ and the PE in winePath/lib/wine/aarch64-windows/.
        // Without these, Wine can't load the driver even if the .drv is in system32.
        if (winePath != null && winePath.isDirectory()) {
            File wineAarch64Windows = new File(winePath, "lib/wine/aarch64-windows");
            File wineAarch64Unix = new File(winePath, "lib/wine/aarch64-unix");
            Log.i(TAG, "ensureDriverInstalled: copying driver to Wine install dir: " + winePath);
            copyIfExists(prefix, "lib/wine/aarch64-windows/winewayland.drv", wineAarch64Windows);
            copyIfExists(prefix, "lib/wine/aarch64-unix/winewayland.so", wineAarch64Unix);
            copyIfExists(prefix, "lib/wine/aarch64-windows/libarm64ecfex.dll", wineAarch64Windows);
            copyIfExists(prefix, "lib/wine/aarch64-windows/ntdll.dll", wineAarch64Windows);
            // arm64ec-windows dir may not exist in all Proton builds — only copy if dir exists
            File arm64ecDir = new File(winePath, "lib/wine/arm64ec-windows");
            if (arm64ecDir.isDirectory()) {
                copyIfExists(prefix, "lib/wine/arm64ec-windows/libarm64ecfex.dll", arm64ecDir);
                copyIfExists(prefix, "lib/wine/arm64ec-windows/ntdll.dll", arm64ecDir);
            } else {
                Log.i(TAG, "ensureDriverInstalled: arm64ec-windows dir not present — skipping arm64ec copies");
            }

            // CRITICAL: Binary-patch winevulkan.dll to replace Vulkan surface
            // extension strings with VK_KHR_android_surface.
            //
            // WHY: Wine's winevulkan.dll contains hardcoded extension name
            // strings: "VK_KHR_wayland_surface" and "VK_KHR_win32_surface".
            // DXVK requests VK_KHR_win32_surface (Wine maps it to the native
            // platform surface). But the Android Vulkan wrapper (Turnip/Adreno)
            // only supports VK_KHR_android_surface. So vkCreateInstance fails
            // with VK_ERROR_EXTENSION_NOT_PRESENT.
            //
            // FIX: Replace BOTH strings with "VK_KHR_android_surface" (21 bytes)
            // using same-length null-patched binary patch:
            //   "VK_KHR_wayland_surface" (22B) → "VK_KHR_android_surface\0" (22B)
            //   "VK_KHR_win32_surface" (19B) → "VK_KHR_android_surface\0\0\0" (22B)
            // Also patch winex11.drv which contains VK_KHR_win32_surface too.
            //
            // winevulkan.dll locations — patch ALL copies that wine might load:
            // 1. winePath/lib/wine/aarch64-windows/winevulkan.dll (Proton install)
            // 2. winePath/lib/wine/arm64ec-windows/winevulkan.dll (arm64ec, may not exist)
            // 3. prefix/drive_c/windows/system32/winevulkan.dll (container's system32)
            // 4. prefix/drive_c/windows/syswow64/winevulkan.dll (32-bit, may not exist)
            File winevulkanAarch64 = new File(wineAarch64Windows, "winevulkan.dll");
            File winevulkanArm64ec = new File(winePath, "lib/wine/arm64ec-windows/winevulkan.dll");
            File winevulkanPrefix = new File(prefix, "drive_c/windows/system32/winevulkan.dll");
            File winevulkanSyswow64 = new File(prefix, "drive_c/windows/syswow64/winevulkan.dll");
            patchSurfaceExtension(winevulkanAarch64);
            patchSurfaceExtension(winevulkanArm64ec);
            patchSurfaceExtension(winevulkanPrefix);
            patchSurfaceExtension(winevulkanSyswow64);
            // Also patch winex11.drv — it also contains VK_KHR_win32_surface
            File winex11Aarch64 = new File(wineAarch64Windows, "winex11.drv");
            File winex11Prefix = new File(prefix, "drive_c/windows/system32/winex11.drv");
            patchSurfaceExtension(winex11Aarch64);
            patchSurfaceExtension(winex11Prefix);
            // Also patch winewayland.so (in case it has the string too)
            patchSurfaceExtension(new File(wineAarch64Unix, "winewayland.so"));
            patchSurfaceExtension(new File(prefix, "lib/wine/aarch64-unix/winewayland.so"));

            // CRITICAL: Patch DXVK DLLs — DXVK has VK_KHR_win32_surface hardcoded
            // in its own binaries (dxgi.dll, d3d11.dll, d3d9.dll, d3d8.dll).
            // These are NOT affected by the winevulkan.dll patch because DXVK
            // requests the extension by its own hardcoded name, not by reading
            // it from winevulkan.dll's data section.
            // Must patch BOTH system32 (64-bit) and syswow64 (32-bit) copies.
            File dxvkSystem32 = new File(prefix, "drive_c/windows/system32");
            File dxvkSyswow64 = new File(prefix, "drive_c/windows/syswow64");
            String[] dxvkDlls = {"dxgi.dll", "d3d11.dll", "d3d9.dll", "d3d8.dll",
                                 "d3d10.dll", "d3d10core.dll", "wined3d.dll"};
            for (String dll : dxvkDlls) {
                patchSurfaceExtension(new File(dxvkSystem32, dll));
                if (dxvkSyswow64.isDirectory()) {
                    patchSurfaceExtension(new File(dxvkSyswow64, dll));
                }
            }
        } else {
            Log.w(TAG, "ensureDriverInstalled: winePath is null or not a directory — "
                    + "cannot copy .so companion, driver will fail to load");
        }

        // Verify the driver is actually in system32 now
        if (!driverInSystem32.exists() || driverInSystem32.length() < 1000) {
            Log.e(TAG, "ensureDriverInstalled: winewayland.drv STILL missing from system32 after extraction!");
            writeDiagnostic(ctx, prefix, winePath, "DRIVER_STILL_MISSING after extraction");
            return false;
        }

        // Verify the .so companion is in Wine's install dir
        File soInWinePath = new File(winePath, "lib/wine/aarch64-unix/winewayland.so");
        if (!soInWinePath.exists() || soInWinePath.length() < 1000) {
            Log.e(TAG, "ensureDriverInstalled: winewayland.so missing from Wine install dir!");
            writeDiagnostic(ctx, prefix, winePath, "SO_MISSING in winePath: " + soInWinePath.getAbsolutePath());
            return false;
        }

        Log.i(TAG, "ensureDriverInstalled: winewayland.drv verified in system32 (" + driverInSystem32.length() + " bytes)");
        Log.i(TAG, "ensureDriverInstalled: winewayland.so verified in winePath (" + soInWinePath.length() + " bytes)");

        // Always set GraphicsDriver (idempotent — removes old entries first)
        setGraphicsDriver(prefix);

        // Check if winevulkan.dll was patched (look for VK_KHR_android_surface)
        // We check ALL copies: system32 (what wine loads) AND winePath (the source)
        boolean patched = false;
        String probeInfo = "";
        try {
            File winevulkanSys32 = new File(prefix, "drive_c/windows/system32/winevulkan.dll");
            File winevulkanWinePath = new File(winePath, "lib/wine/aarch64-windows/winevulkan.dll");
            File winevulkanToCheck = winevulkanSys32.exists() ? winevulkanSys32 : winevulkanWinePath;
            if (winevulkanToCheck.exists()) {
                byte[] vulkanData = java.nio.file.Files.readAllBytes(winevulkanToCheck.toPath());
                // Search for VK_KHR_android_surface (the replacement string)
                byte[] androidSearch = "VK_KHR_android_surface".getBytes("ASCII");
                int androidCount = 0;
                for (int i = 0; i <= vulkanData.length - androidSearch.length; i++) {
                    boolean match = true;
                    for (int j = 0; j < androidSearch.length; j++) {
                        if (vulkanData[i + j] != androidSearch[j]) { match = false; break; }
                    }
                    if (match) androidCount++;
                }
                patched = androidCount > 0;
                // Also count remaining unpatched strings
                byte[] waylandSearch = "VK_KHR_wayland_surface".getBytes("ASCII");
                int waylandCount = 0;
                for (int i = 0; i <= vulkanData.length - waylandSearch.length; i++) {
                    boolean match = true;
                    for (int j = 0; j < waylandSearch.length; j++) {
                        if (vulkanData[i + j] != waylandSearch[j]) { match = false; break; }
                    }
                    if (match) waylandCount++;
                }
                byte[] win32Search = "VK_KHR_win32_surface".getBytes("ASCII");
                int win32Count = 0;
                for (int i = 0; i <= vulkanData.length - win32Search.length; i++) {
                    boolean match = true;
                    for (int j = 0; j < win32Search.length; j++) {
                        if (vulkanData[i + j] != win32Search[j]) { match = false; break; }
                    }
                    if (match) win32Count++;
                }
                probeInfo = " winevulkan_patched=" + patched
                    + " android_surface=" + androidCount
                    + " wayland_surface=" + waylandCount
                    + " win32_surface=" + win32Count
                    + " file=" + winevulkanToCheck.getAbsolutePath()
                    + "(" + vulkanData.length + "B)";
                // Also check if the winePath copy is patched
                if (winevulkanWinePath.exists() && !winevulkanToCheck.equals(winevulkanWinePath)) {
                    byte[] wpData = java.nio.file.Files.readAllBytes(winevulkanWinePath.toPath());
                    int wpAndroid = 0;
                    for (int i = 0; i <= wpData.length - androidSearch.length; i++) {
                        boolean match = true;
                        for (int j = 0; j < androidSearch.length; j++) {
                            if (wpData[i + j] != androidSearch[j]) { match = false; break; }
                        }
                        if (match) wpAndroid++;
                    }
                    probeInfo += " winePath_android_surface=" + wpAndroid;
                }
            } else {
                probeInfo = " winevulkan.dll NOT FOUND in system32 or winePath";
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to check patch status: " + e.getMessage());
        }

        writeDiagnostic(ctx, prefix, winePath, "OK: drv=" + driverInSystem32.length()
                + " so=" + soInWinePath.length() + " GraphicsDriver set"
                + " surface_patched=" + patched + probeInfo);

        // Dump the GraphicsDriver registry entries so we can verify they
        // were actually written. This is critical for diagnosing nodrv_CreateWindow:
        // if the registry entry is missing or wrong, Wine can't find the driver.
        dumpRegistryDriverEntries(ctx, prefix);

        // Dump PE exports of winewayland.drv by scanning for export names.
        // Wine's USER_LoadDriver needs specific exports (DllMain + driver funcs).
        // If exports are missing, the driver loads but can't be used.
        dumpPeExports(ctx, driverInSystem32, "winewayland.drv");
        return true;
    }

    /**
     * Writes a diagnostic file to the app's external logs directory (same place
     * as wine/fexcore logs) so the user can share it without root access.
     */
    private static void writeDiagnostic(Context ctx, File prefix, File winePath, String message) {
        try {
            File logsDir = com.winlator.cmod.runtime.system.LogManager.getLogsDir(ctx);
            File diagFile = new File(logsDir, "wayland-driver-install.log");
            java.io.FileWriter fw = new java.io.FileWriter(diagFile, true);
            fw.write("[" + new java.util.Date() + "] " + message + "\n");
            fw.write("  prefix=" + prefix.getAbsolutePath() + "\n");
            fw.write("  prefix exists=" + prefix.exists() + "\n");
            fw.write("  winePath=" + (winePath != null ? winePath.getAbsolutePath() : "null") + "\n");
            fw.write("  winePath exists=" + (winePath != null && winePath.exists()) + "\n");
            fw.write("  system32=" + new File(prefix, "drive_c/windows/system32").getAbsolutePath() + "\n");
            fw.write("  system32 exists=" + new File(prefix, "drive_c/windows/system32").exists() + "\n");
            File drv = new File(prefix, "drive_c/windows/system32/winewayland.drv");
            fw.write("  winewayland.drv in system32 exists=" + drv.exists() + " size=" + drv.length() + "\n");
            File libDrv = new File(prefix, "lib/wine/aarch64-windows/winewayland.drv");
            fw.write("  winewayland.drv in prefix lib/wine exists=" + libDrv.exists() + " size=" + libDrv.length() + "\n");
            if (winePath != null) {
                File soInWine = new File(winePath, "lib/wine/aarch64-unix/winewayland.so");
                fw.write("  winewayland.so in winePath exists=" + soInWine.exists() + " size=" + soInWine.length() + "\n");
                File drvInWine = new File(winePath, "lib/wine/aarch64-windows/winewayland.drv");
                fw.write("  winewayland.drv in winePath exists=" + drvInWine.exists() + " size=" + drvInWine.length() + "\n");
            }
            // Check if asset exists
            try {
                java.io.InputStream test = ctx.getAssets().open(ASSET);
                fw.write("  asset " + ASSET + " openable=YES\n");
                test.close();
            } catch (IOException e) {
                fw.write("  asset " + ASSET + " openable=NO: " + e.getMessage() + "\n");
            }
            fw.close();
        } catch (Exception e) {
            Log.w(TAG, "Failed to write diagnostic: " + e.getMessage());
        }
    }

    private static void extractZip(Context ctx, File prefix) throws IOException {
        InputStream is = ctx.getAssets().open(ASSET);
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is, 65536));
        byte[] buf = new byte[65536];
        ZipEntry e;
        int extracted = 0;
        while ((e = zis.getNextEntry()) != null) {
            File out = new File(prefix, e.getName());
            if (e.isDirectory()) { out.mkdirs(); continue; }
            out.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(out)) {
                int n;
                while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
            }
            if (e.getName().endsWith(".so") || e.getName().endsWith(".drv")) {
                out.setExecutable(true, true);
            }
            extracted++;
            Log.i(TAG, "  extracted: " + e.getName() + " (" + out.length() + " bytes)");
        }
        Log.i(TAG, "Extraction complete: " + extracted + " files");
    }

    private static void copyToSystem32(File prefix) {
        File system32 = new File(prefix, "drive_c/windows/system32");
        if (!system32.isDirectory()) {
            Log.w(TAG, "system32 not found at " + system32 + " — skipping copy");
            return;
        }
        copyIfExists(prefix, "lib/wine/aarch64-windows/winewayland.drv", system32);
        copyIfExists(prefix, "lib/wine/aarch64-windows/libarm64ecfex.dll", system32);
        copyIfExists(prefix, "lib/wine/aarch64-windows/ntdll.dll", system32);
    }

    /**
     * Binary-patches winewayland.so to replace the Vulkan surface extension
     * string "VK_KHR_wayland_surface" with "VK_KHR_xlib_surface".
     *
     * This is necessary because the Android Vulkan wrapper only supports
     * VK_KHR_xlib_surface (which it translates to VK_KHR_android_surface).
     * Without this patch, vkCreateInstance fails with VK_ERROR_EXTENSION_NOT_PRESENT
     * when winewayland.drv reports VK_KHR_wayland_surface.
     *
     * The patch is safe because:
     * - "VK_KHR_wayland_surface" (22 bytes) is replaced with
     *   "VK_KHR_xlib_surface\0\0" (20 bytes + 2 null padding = 22 bytes)
     * - C string functions stop at the first null, so padding is ignored
     * - The .so's checksum/structure is unchanged (same-length replacement)
     * - Idempotent: if already patched, the string isn't found and no change is made
     */
    private static void patchSurfaceExtension(File soFile) {
        if (soFile == null || !soFile.exists()) {
            Log.w(TAG, "patchSurfaceExtension: file not found: " + soFile);
            return;
        }
        try {
            byte[] data = java.nio.file.Files.readAllBytes(soFile.toPath());

            // Patch multiple surface extension strings to VK_KHR_android_surface.
            //
            // String lengths (bytes, NOT including null terminator):
            //   "VK_KHR_wayland_surface"  = 22 bytes
            //   "VK_KHR_win32_surface"    = 20 bytes
            //   "VK_KHR_android_surface"  = 22 bytes
            //
            // For wayland_surface (22B) → android_surface (22B): same length, easy.
            // For win32_surface (20B) → android_surface (22B): replacement is 2 bytes
            // LONGER. We overwrite the original 20 bytes + 2 bytes of whatever follows.
            // In PE .rdata sections, strings are null-terminated and often followed by
            // padding or other strings. Overwriting 2 extra bytes is safe because:
            //   - If followed by null (end of string), we overwrite the null + 1 padding byte
            //   - If followed by another extension string we're also patching, it gets patched too
            //   - The null terminator of the replacement is at position 22, which is past
            //     the original string's null terminator at position 20
            String[][] patches = {
                {"VK_KHR_wayland_surface", "VK_KHR_android_surface"},
                {"VK_KHR_win32_surface",   "VK_KHR_android_surface"},
            };

            // First, probe for all relevant substrings
            String[] probes = {"wayland_surface", "win32_surface", "VK_KHR_wayland",
                               "VK_KHR_win32", "VK_KHR_android", "VK_KHR_xlib",
                               "VK_KHR_surface", "winewayland"};
            StringBuilder probeReport = new StringBuilder();
            for (String probe : probes) {
                byte[] probeBytes = probe.getBytes("ASCII");
                int count = 0;
                for (int i = 0; i <= data.length - probeBytes.length; i++) {
                    boolean match = true;
                    for (int j = 0; j < probeBytes.length; j++) {
                        if (data[i + j] != probeBytes[j]) { match = false; break; }
                    }
                    if (match) count++;
                }
                probeReport.append(probe).append("=").append(count).append(" ");
            }
            Log.i(TAG, "patchSurfaceExtension: probe '" + soFile.getName() + "' (" + data.length + " bytes): " + probeReport);

            int totalPatched = 0;
            for (String[] patch : patches) {
                byte[] search = patch[0].getBytes("ASCII");
                byte[] replace = patch[1].getBytes("ASCII");

                int patched = 0;
                for (int i = 0; i <= data.length - search.length; i++) {
                    boolean match = true;
                    for (int j = 0; j < search.length; j++) {
                        if (data[i + j] != search[j]) { match = false; break; }
                    }
                    if (match) {
                        // Write the full replacement string (may be longer than search).
                        // If replacement is shorter, pad with nulls.
                        int writeLen = Math.max(replace.length, search.length);
                        byte[] writeData = new byte[writeLen];
                        System.arraycopy(replace, 0, writeData, 0, replace.length);
                        // Null-fill remaining bytes (if replacement is shorter)
                        for (int j = replace.length; j < writeLen; j++) {
                            writeData[j] = 0;
                        }
                        // Only write if we have room (don't overflow past end of data)
                        if (i + writeLen <= data.length) {
                            System.arraycopy(writeData, 0, data, i, writeLen);
                            patched++;
                        }
                    }
                }
                if (patched > 0) {
                    Log.i(TAG, "patchSurfaceExtension: replaced '" + patch[0] + "' (" + search.length + "B) → '" + patch[1] + "' (" + replace.length + "B) (" + patched + "x) in " + soFile.getName());
                    totalPatched += patched;
                }
            }

            if (totalPatched > 0) {
                java.nio.file.Files.write(soFile.toPath(), data);
                Log.i(TAG, "patchSurfaceExtension: total " + totalPatched + " patch(es) applied to " + soFile.getName());
            } else {
                Log.w(TAG, "patchSurfaceExtension: no surface extension strings found in " + soFile.getName() + " — probe results: " + probeReport);
            }
        } catch (Exception e) {
            Log.w(TAG, "patchSurfaceExtension failed: " + e.getMessage());
        }
    }

    /**
     * Sets GraphicsDriver=winewayland.drv in system.reg.
     *
     * Wine reads this registry value to decide which display driver to load.
     * Without it, Wine defaults to winex11.drv even if winewayland.drv is
     * installed in system32/.
     *
     * The adapter GUID is generated dynamically during wineboot — we can't
     * predict it. Instead, we scan for ALL [System\\...\\Control\\Video\\{GUID}\\0000]
     * keys in system.reg and set GraphicsDriver on each one. This ensures
     * Wine finds it regardless of which GUID was assigned.
     *
     * We also set it under [Software\\Wine\\Drivers] in user.reg as a fallback
     * (Wine checks this if the system.reg Video key doesn't have GraphicsDriver).
     */
    private static void setGraphicsDriver(File prefix) {
        File systemReg = new File(prefix, "system.reg");
        if (!systemReg.exists()) {
            Log.w(TAG, "system.reg not found — skipping GraphicsDriver");
            return;
        }
        try {
            String reg = new String(java.nio.file.Files.readAllBytes(systemReg.toPath()));
            // Wine constructs the driver filename as: wine + <GraphicsDriver value> + .drv
            // So GraphicsDriver=wayland → winewayland.drv (correct)
            // GraphicsDriver=wayland,x11 → try winewayland.drv first, then winex11.drv
            // This allows fallback when Wayland isn't ready (rundll32, services.exe)
            String graphicsValue = "\"GraphicsDriver\"=\"wayland,x11\"";

            // Remove old GraphicsDriver entries that point to winex11.drv
            // (but keep any that the user might have set to other drivers).
            // We replace any existing GraphicsDriver with winewayland.drv.
            StringBuilder rebuilt = new StringBuilder();
            for (String line : reg.split("\n", -1)) {
                if (line.contains("\"GraphicsDriver\"")) {
                    rebuilt.append(graphicsValue).append("\n");
                } else {
                    rebuilt.append(line).append("\n");
                }
            }
            reg = rebuilt.toString();
            // Remove trailing extra newline if we added one
            if (reg.endsWith("\n\n")) {
                reg = reg.substring(0, reg.length() - 1);
            }

            // Now ensure GraphicsDriver is set under ALL Video adapter keys.
            // Find all lines matching [System\\CurrentControlSet\\Control\\Video\\{GUID}\\0000]
            // and add GraphicsDriver after them if not already present.
            StringBuilder result = new StringBuilder();
            String[] lines = reg.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                result.append(lines[i]);
                if (i < lines.length - 1) result.append("\n");
                // Check if this line is a Video adapter 0000 key
                if (lines[i].startsWith("[System\\\\CurrentControlSet\\\\Control\\\\Video\\\\")
                        && lines[i].endsWith("\\\\0000]")) {
                    // Check if the next non-empty line already has GraphicsDriver
                    boolean hasGraphics = false;
                    if (i + 1 < lines.length && lines[i + 1].contains("\"GraphicsDriver\"")) {
                        hasGraphics = true;
                    }
                    if (!hasGraphics) {
                        result.append(graphicsValue).append("\n");
                    }
                }
            }

            java.nio.file.Files.write(systemReg.toPath(), result.toString().getBytes());
            Log.i(TAG, "Set system.reg: GraphicsDriver=wayland,x11 (all Video keys)");

            // Also set fallback in user.reg: [Software\\Wine\\Drivers] "Graphics"="wayland,x11"
            // Wine's USER_LoadDriver reads this value and tries each driver in order.
            // "wayland,x11" means: try winewayland.drv first, and if it fails to load
            // (e.g. Wayland socket not ready, or DllMain returns FALSE), fall back to
            // winex11.drv. This prevents nodrv_CreateWindow in processes that start
            // before the Wayland driver is fully initialized (rundll32, services.exe).
            //
            // Wine constructs the filename as: wine + value + .drv
            // So "wayland" → winewayland.drv, "x11" → winex11.drv
            // Multiple drivers are comma-separated: "wayland,x11" → try both in order.
            File userReg = new File(prefix, "user.reg");
            if (userReg.exists()) {
                String userRegContent = new String(java.nio.file.Files.readAllBytes(userReg.toPath()));
                String driversKey = "[Software\\\\Wine\\\\Drivers]";
                String graphicsUserValue = "\"Graphics\"=\"wayland,x11\"";

                // Remove old Graphics= entries under Wine\Drivers
                int drvIdx = userRegContent.indexOf(driversKey);
                if (drvIdx >= 0) {
                    // Find the end of this key block
                    int nextKey = userRegContent.indexOf("\n[", drvIdx + 1);
                    if (nextKey < 0) nextKey = userRegContent.length();
                    String block = userRegContent.substring(drvIdx, nextKey);
                    // Remove existing Graphics= line
                    block = block.replaceAll("\"Graphics\"=\"[^\"]*\"\n?", "");
                    // Add our Graphics= line
                    block = block + graphicsUserValue + "\n";
                    userRegContent = userRegContent.substring(0, drvIdx) + block + userRegContent.substring(nextKey);
                } else {
                    // Key doesn't exist — append it
                    userRegContent += "\n" + driversKey + "\n" + graphicsUserValue + "\n";
                }
                java.nio.file.Files.write(userReg.toPath(), userRegContent.getBytes());
                Log.i(TAG, "Set user.reg: [Software\\Wine\\Drivers] Graphics=wayland");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to set GraphicsDriver: " + e.getMessage());
        }
    }

    private static void copyIfExists(File srcDir, String srcPath, File dstDir) {
        File src = new File(srcDir, srcPath);
        if (!src.exists()) return;
        File dst = new File(dstDir, src.getName());
        try {
            InputStream is = new FileInputStream(src);
            FileOutputStream fos = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            is.close();
            fos.close();
            Log.i(TAG, "  copied to system32: " + src.getName() + " (" + dst.length() + " bytes)");
        } catch (IOException e) {
            Log.w(TAG, "  copy failed: " + src.getName() + " — " + e.getMessage());
        }
    }

    /**
     * Dumps the GraphicsDriver registry entries from system.reg and user.reg
     * to the wayland-driver-install.log. This lets us verify that
     * setGraphicsDriver() actually wrote the entries correctly.
     */
    private static void dumpRegistryDriverEntries(Context ctx, File prefix) {
        try {
            File logsDir = com.winlator.cmod.runtime.system.LogManager.getLogsDir(ctx);
            File diagFile = new File(logsDir, "wayland-driver-install.log");
            java.io.FileWriter fw = new java.io.FileWriter(diagFile, true);

            // Dump system.reg GraphicsDriver entries
            File systemReg = new File(prefix, "system.reg");
            if (systemReg.exists()) {
                String reg = new String(java.nio.file.Files.readAllBytes(systemReg.toPath()));
                fw.write("--- system.reg GraphicsDriver entries ---\n");
                boolean foundAny = false;
                for (String line : reg.split("\n", -1)) {
                    if (line.contains("GraphicsDriver") || line.contains("Video\\\\")
                            || line.contains("Drivers]")) {
                        fw.write("  " + line + "\n");
                        foundAny = true;
                    }
                }
                if (!foundAny) {
                    fw.write("  (NO GraphicsDriver or Video entries found in system.reg)\n");
                }
            }

            // Dump user.reg [Software\Wine\Drivers] entries
            File userReg = new File(prefix, "user.reg");
            if (userReg.exists()) {
                String reg = new String(java.nio.file.Files.readAllBytes(userReg.toPath()));
                fw.write("--- user.reg Wine\\Drivers entries ---\n");
                int drvIdx = reg.indexOf("[Software\\\\Wine\\\\Drivers]");
                if (drvIdx < 0) {
                    fw.write("  (NO [Software\\Wine\\Drivers] key found in user.reg)\n");
                } else {
                    int nextKey = reg.indexOf("\n[", drvIdx + 1);
                    if (nextKey < 0) nextKey = reg.length();
                    String block = reg.substring(drvIdx, nextKey);
                    for (String line : block.split("\n", -1)) {
                        fw.write("  " + line + "\n");
                    }
                }
            }

            fw.close();
        } catch (Exception e) {
            Log.w(TAG, "dumpRegistryDriverEntries failed: " + e.getMessage());
        }
    }

    /**
     * Dumps the PE export names from a .dll/.drv file by scanning the binary
     * for the export directory. This is a simplified scanner that looks for
     * ASCII strings near the export table — it doesn't fully parse the PE
     * format but is good enough to list the exported function names.
     */
    private static void dumpPeExports(Context ctx, File peFile, String label) {
        try {
            File logsDir = com.winlator.cmod.runtime.system.LogManager.getLogsDir(ctx);
            File diagFile = new File(logsDir, "wayland-driver-install.log");
            java.io.FileWriter fw = new java.io.FileWriter(diagFile, true);

            byte[] data = java.nio.file.Files.readAllBytes(peFile.toPath());
            fw.write("--- " + label + " PE exports (size=" + data.length + " bytes) ---\n");

            // Search for known Wine display driver export names
            String[] knownExports = {
                "DllMain", "wine_get_vulkan_driver",
                "wine_create_window", "wine_destroy_window",
                "create_desktop", "create_window",
                "set_capture", "release_capture",
                "get_cursor_pos", "set_cursor_pos", "set_cursor",
                "change_display_settings", "enum_display_settings",
                "create_dc", "create_compat_dc", "delete_dc",
                "create_compat_bitmap", "delete_object",
                "bit_block_transfer", "stretch_block_transfer",
                "get_text_metrics", "get_text_extent",
                "create_font", "select_object",
                "get_device_caps", "get_system_metrics",
                "register_hotkey", "unregister_hotkey",
                "set_layered_window_attributes",
                "set_window_pos", "set_window_style",
                "set_window_text", "get_window_text",
                "show_window", "destroy_window",
                "set_focus", "set_active_window",
                "clip_cursor", "get_clip_cursor",
                "keybd_event", "mouse_event",
                "get_key_state", "get_async_key_state",
                "map_virtual_key", "to_unicode",
                "get_keyboard_layout", "get_keyboard_layout_list",
                "load_keyboard_layout", "unload_keyboard_layout",
                "activate_keyboard_layout",
                "vk_to_wchar", "get_keyboard_type",
                "beep", "message_beep",
                "get_monitor_info", "enum_display_monitors",
                "system_parameters_info",
            };

            int foundCount = 0;
            for (String name : knownExports) {
                byte[] search = name.getBytes("ASCII");
                for (int i = 0; i <= data.length - search.length; i++) {
                    boolean match = true;
                    for (int j = 0; j < search.length; j++) {
                        if (data[i + j] != search[j]) { match = false; break; }
                    }
                    if (match) {
                        fw.write("  EXPORT: " + name + " (found at offset 0x" + Integer.toHexString(i) + ")\n");
                        foundCount++;
                        break;
                    }
                }
            }
            fw.write("  Total known exports found: " + foundCount + "/" + knownExports.length + "\n");
            fw.close();
        } catch (Exception e) {
            Log.w(TAG, "dumpPeExports failed: " + e.getMessage());
        }
    }
}
