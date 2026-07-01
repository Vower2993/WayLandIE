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
            copyIfExists(prefix, "lib/wine/arm64ec-windows/libarm64ecfex.dll",
                    new File(winePath, "lib/wine/arm64ec-windows"));
            copyIfExists(prefix, "lib/wine/arm64ec-windows/ntdll.dll",
                    new File(winePath, "lib/wine/arm64ec-windows"));

            // CRITICAL: Binary-patch winewayland.so to change the Vulkan surface
            // extension from VK_KHR_wayland_surface to VK_KHR_xlib_surface.
            //
            // WHY: Wine's winevulkan.dll asks the display driver for the host
            // surface extension. winewayland.drv returns "VK_KHR_wayland_surface".
            // But the Android Vulkan wrapper (loaded via VK_ICD_FILENAMES) only
            // supports VK_KHR_xlib_surface (which it translates to
            // VK_KHR_android_surface). So vkCreateInstance fails with
            // VK_ERROR_EXTENSION_NOT_PRESENT.
            //
            // FIX: Replace the string "VK_KHR_wayland_surface" (22 bytes) with
            // "VK_KHR_xlib_surface\0\0\0" (19 bytes + 3 null padding = 22 bytes)
            // in the .so binary. winevulkan.dll then requests
            // VK_KHR_xlib_surface, which the wrapper handles. Surface creation
            // uses vkCreateXlibSurfaceKHR — the wrapper ignores the X11
            // parameters and uses its internal ANativeWindow*.
            patchSurfaceExtension(new File(wineAarch64Unix, "winewayland.so"));
            // Also patch the copy in the prefix (in case Wine loads from there)
            patchSurfaceExtension(new File(prefix, "lib/wine/aarch64-unix/winewayland.so"));
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

        // Check if the .so was patched (look for VK_KHR_xlib_surface)
        boolean patched = false;
        String probeInfo = "";
        try {
            byte[] soData = java.nio.file.Files.readAllBytes(soInWinePath.toPath());
            byte[] xlibSearch = "VK_KHR_xlib_surface".getBytes("ASCII");
            for (int i = 0; i <= soData.length - xlibSearch.length; i++) {
                boolean match = true;
                for (int j = 0; j < xlibSearch.length; j++) {
                    if (soData[i + j] != xlibSearch[j]) { match = false; break; }
                }
                if (match) { patched = true; break; }
            }
            // Also probe for wayland_surface to see if it's still there
            byte[] waylandSearch = "wayland_surface".getBytes("ASCII");
            int waylandCount = 0;
            for (int i = 0; i <= soData.length - waylandSearch.length; i++) {
                boolean match = true;
                for (int j = 0; j < waylandSearch.length; j++) {
                    if (soData[i + j] != waylandSearch[j]) { match = false; break; }
                }
                if (match) waylandCount++;
            }
            probeInfo = " wayland_surface_count=" + waylandCount;
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
            byte[] search = "VK_KHR_wayland_surface".getBytes("ASCII");
            byte[] replace = "VK_KHR_xlib_surface\0\0\0".getBytes("ASCII");

            // First, search for various substrings to understand the .so's string table
            String[] probes = {"wayland_surface", "VK_KHR_wayland", "VK_KHR_xlib", "VK_KHR_surface", "winewayland"};
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

            int patched = 0;
            for (int i = 0; i <= data.length - search.length; i++) {
                boolean match = true;
                for (int j = 0; j < search.length; j++) {
                    if (data[i + j] != search[j]) { match = false; break; }
                }
                if (match) {
                    System.arraycopy(replace, 0, data, i, replace.length);
                    patched++;
                }
            }

            if (patched > 0) {
                java.nio.file.Files.write(soFile.toPath(), data);
                Log.i(TAG, "patchSurfaceExtension: patched " + patched + " occurrence(s) in " + soFile.getName());
            } else {
                Log.w(TAG, "patchSurfaceExtension: 'VK_KHR_wayland_surface' NOT FOUND in " + soFile.getName() + " — probe results: " + probeReport);
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
            // GraphicsDriver=winewayland.drv → winewinewayland.drv.drv (WRONG — doubled)
            String graphicsValue = "\"GraphicsDriver\"=\"wayland\"";

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
            Log.i(TAG, "Set system.reg: GraphicsDriver=wayland (all Video keys)");

            // Also set fallback in user.reg: [Software\\Wine\\Drivers] "Graphics"="wayland"
            // (Wine constructs the filename as wine + value + .drv, so "wayland" → winewayland.drv)
            File userReg = new File(prefix, "user.reg");
            if (userReg.exists()) {
                String userRegContent = new String(java.nio.file.Files.readAllBytes(userReg.toPath()));
                String driversKey = "[Software\\\\Wine\\\\Drivers]";
                String graphicsUserValue = "\"Graphics\"=\"wayland\"";

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
