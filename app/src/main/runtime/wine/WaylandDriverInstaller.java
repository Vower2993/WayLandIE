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
        writeDiagnostic(ctx, prefix, winePath, "OK: drv=" + driverInSystem32.length()
                + " so=" + soInWinePath.length() + " GraphicsDriver set");
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
}
