package io.waylandie.display;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * SettingsActivity — central hub for driver and translation layer
 * management. Replaces the old DriverInstallerActivity as the single
 * entry point for installing DXVK / Turnip / FEX / Qualcomm / box86 /
 * box64 packages.
 *
 * <p>Each driver kind has its own card with:
 * <ul>
 *   <li>A status line showing installed slots</li>
 *   <li>An "Install from file" button that opens the file picker</li>
 * </ul>
 *
 * <p>Installs use the bundled waylandie-install-driver script via
 * ProotRunner. The picked archive is first copied to
 * /sdcard/Download/WayLandIE/drivers/ (or app-private external storage
 * fallback) so the rootfs can read it.
 */
public final class SettingsActivity extends Activity {

    private static final int PICK_DXVK = 1;
    private static final int PICK_TURNIP = 2;
    private static final int PICK_FEX = 3;
    private static final int PICK_QCOM = 4;
    private static final int PICK_BOX86 = 5;
    private static final int PICK_BOX64 = 6;
    private static final int PICK_PROTON = 7;

    private TextView dxvkStatus;
    private TextView turnipStatus;
    private TextView fexStatus;
    private TextView qcomStatus;
    private TextView protonStatus;
    private TextView activeProfileText;

    // Track the currently-running install process so we can destroy it
    // cleanly if the activity is destroyed (user navigates away, system
    // kills the app, OOM, task-swipe). Without this, proot child processes
    // are orphaned and bind mounts left stale, which can cause lockfile
    // conflicts on the next install attempt.
    private Process runningInstallProcess;
    private Thread runningInstallThread;

    /**
     * Modal progress dialog shown during driver/component installs.
     * Updated step-by-step from the install background thread to give
     * the user visible feedback (detect → extract → validate → install).
     *
     * <p>ProgressDialog is deprecated as of API 26 but still functions
     * on Android 16. We use it because it's a single class that gives
     * us both a message and a horizontal progress bar; an equivalent
     * AlertDialog + ProgressBar view would require significantly more
     * boilerplate for the same UX.
     */
    private android.app.ProgressDialog installProgressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        dxvkStatus = findViewById(R.id.dxvkStatus);
        turnipStatus = findViewById(R.id.turnipStatus);
        fexStatus = findViewById(R.id.fexStatus);
        qcomStatus = findViewById(R.id.qcomStatus);
        protonStatus = findViewById(R.id.protonStatus);
        activeProfileText = findViewById(R.id.activeProfileText);

        findViewById(R.id.btnInstallDxvk).setOnClickListener(v ->
                openArchivePicker(PICK_DXVK, "Install DXVK"));
        findViewById(R.id.btnInstallTurnip).setOnClickListener(v ->
                openArchivePicker(PICK_TURNIP, "Install Turnip (KGSL bionic)"));
        findViewById(R.id.btnInstallFex).setOnClickListener(v ->
                openArchivePicker(PICK_FEX, "Install FEX-Emu"));
        findViewById(R.id.btnInstallProton).setOnClickListener(v ->
                openArchivePicker(PICK_PROTON, "Install Armec Proton"));
        findViewById(R.id.btnInstallQcom).setOnClickListener(v ->
                openArchivePicker(PICK_QCOM, "Install Qualcomm Adreno driver"));
        findViewById(R.id.btnInstallBox86).setOnClickListener(v ->
                openArchivePicker(PICK_BOX86, "Install box86"));
        findViewById(R.id.btnInstallBox64).setOnClickListener(v ->
                openArchivePicker(PICK_BOX64, "Install box64"));
        findViewById(R.id.btnManageProfiles).setOnClickListener(v ->
                openProfileManager());

        refreshAllStatuses();
    }

    private void openArchivePicker(int requestCode, String title) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Accept ANY file — the user may have .wcp (Winlator Container
        // Package), .tar.gz, .zip, .tar.xz, .deb, or other formats. The
        // install script auto-detects format by magic bytes, not extension.
        // Using setType("*/*") without EXTRA_MIME_TYPES makes the picker
        // show all files, which is what we want.
        intent.setType("*/*");
        try {
            startActivityForResult(intent, requestCode);
            toast(title + " — pick the archive file (.tar.gz / .zip / .wcp / .tar.xz / .deb)");
        } catch (RuntimeException error) {
            toast("No file picker available: " + error.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        String kind;
        switch (requestCode) {
            case PICK_DXVK:   kind = "dxvk"; break;
            case PICK_TURNIP: kind = "turnip"; break;
            case PICK_FEX:    kind = "fex"; break;
            case PICK_PROTON: kind = "proton"; break;
            case PICK_QCOM:   kind = "qcom-adreno"; break;
            case PICK_BOX86:  kind = "box86"; break;
            case PICK_BOX64:  kind = "box64"; break;
            default: return;
        }
        installDriver(kind, uri);
    }

    private void installDriver(String kind, Uri archiveUri) {
        String displayName = queryDisplayName(archiveUri);
        if (displayName == null) {
            displayName = kind + "-slot.archive";
        }

        // 1. Copy archive into /sdcard/Download/WayLandIE/drivers/
        //    (or app-private external storage fallback).
        File driversDir = new File(
                Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS),
                "WayLandIE/drivers");
        if (!driversDir.exists() && !driversDir.mkdirs()) {
            driversDir = new File(
                    getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "WayLandIE/drivers");
            if (!driversDir.exists() && !driversDir.mkdirs()) {
                toast("Failed to create drivers directory. "
                        + "Grant All files access in Settings.");
                return;
            }
        }
        File outFile = new File(driversDir, displayName);
        try (InputStream in = getContentResolver().openInputStream(archiveUri);
             OutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (IOException error) {
            toast("Copy failed: " + error.getMessage());
            return;
        }

        // 2. Preview the archive contents in a background thread, then
        //    prompt for slot name with the preview shown. This lets the
        //    user verify the archive has the expected layout (e.g. wine
        //    binary present for proton) before committing to install.
        toast("Copied. Analyzing archive…");
        new Thread(() -> {
            final String preview = previewArchive(outFile.getAbsolutePath());
            runOnUiThread(() -> showInstallPromptWithPreview(kind, outFile, preview));
        }).start();
    }

    /**
     * Previews the archive contents in pure Java — no proot, no bash.
     * Detects the archive type by magic bytes, then lists entries.
     */
    private String previewArchive(String archivePath) {
        File archiveFile = new File(archivePath);
        if (!archiveFile.isFile()) {
            return "(archive file not found: " + archivePath + ")";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== Archive: ").append(archiveFile.getName()).append(" ===\n");
        sb.append("Size: ").append(archiveFile.length()).append(" bytes\n");
        io.waylandie.display.shared.io.TarCompressorUtils.Type type =
                io.waylandie.display.shared.io.TarCompressorUtils.detectArchiveType(archiveFile);
        sb.append("Detected type: ").append(type != null ? type : "UNKNOWN").append("\n\n");
        sb.append("=== Contents (first 100 entries) ===\n");
        try {
            java.util.List<String> entries = listArchiveEntries(archiveFile, type, 100);
            if (entries.isEmpty()) {
                sb.append("(no entries found or unsupported format)\n");
            } else {
                for (String entry : entries) {
                    sb.append(entry).append('\n');
                }
                if (entries.size() == 100) {
                    sb.append("... (truncated at 100 entries)\n");
                }
            }
        } catch (Exception e) {
            sb.append("(preview failed: ").append(e.getMessage()).append(")\n");
        }
        return sb.toString();
    }

    /**
     * Lists archive entries in pure Java. Supports gzip-tar, xz-tar, plain tar, and zip.
     */
    private java.util.List<String> listArchiveEntries(File archiveFile,
            io.waylandie.display.shared.io.TarCompressorUtils.Type type, int maxEntries)
            throws IOException {
        java.util.List<String> entries = new java.util.ArrayList<>();
        if (type == null) return entries;
        if (type == io.waylandie.display.shared.io.TarCompressorUtils.Type.ZIP) {
            try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                    new java.io.BufferedInputStream(new java.io.FileInputStream(archiveFile), 65536))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null && entries.size() < maxEntries) {
                    entries.add(entry.isDirectory()
                            ? entry.getName() + "/"
                            : entry.getName() + " (" + entry.getSize() + " bytes)");
                }
            }
        } else {
            java.io.InputStream fileIn = new java.io.BufferedInputStream(new java.io.FileInputStream(archiveFile), 65536);
            java.io.InputStream decompressed;
            if (type == io.waylandie.display.shared.io.TarCompressorUtils.Type.XZ) {
                decompressed = new org.tukaani.xz.XZInputStream(fileIn);
            } else if (type == io.waylandie.display.shared.io.TarCompressorUtils.Type.GZIP) {
                decompressed = new java.util.zip.GZIPInputStream(fileIn);
            } else {
                decompressed = fileIn;
            }
            // Read tar entries — we only need names + sizes, so use a
            // simplified parser that reads headers and skips data.
            byte[] header = new byte[512];
            while (entries.size() < maxEntries) {
                int read = readFully(decompressed, header, 0, 512);
                if (read == 0) break;
                if (read < 512) break;
                boolean allZero = true;
                for (int i = 0; i < 512; i++) {
                    if (header[i] != 0) { allZero = false; break; }
                }
                if (allZero) break;
                String name = readTarString(header, 0, 100);
                if (name.isEmpty()) break;
                long size = parseTarOctal(header, 124, 12);
                entries.add(name + " (" + size + " bytes)");
                // Skip data blocks + padding
                long dataBlocks = (size + 511) / 512;
                long toSkip = dataBlocks * 512;
                while (toSkip > 0) {
                    long skipped = decompressed.skip(toSkip);
                    if (skipped <= 0) break;
                    toSkip -= skipped;
                }
            }
            decompressed.close();
        }
        return entries;
    }

    private static int readFully(java.io.InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, off + total, len - total);
            if (n < 0) return total == 0 ? 0 : total;
            total += n;
        }
        return total;
    }

    private static String readTarString(byte[] buf, int offset, int length) {
        int end = offset;
        while (end < offset + length && buf[end] != 0) end++;
        return new String(buf, offset, end - offset).trim();
    }

    private static long parseTarOctal(byte[] buf, int offset, int length) {
        String s = readTarString(buf, offset, length);
        if (s.isEmpty()) return 0;
        try { return Long.parseLong(s, 8); }
        catch (NumberFormatException e) { return 0; }
    }

    private void showInstallPromptWithPreview(String kind, File archiveFile, String preview) {
        final EditTextEx slotInput = new EditTextEx(this);
        slotInput.setHint("e.g. " + suggestSlotName(kind));

        // Build a scrollable preview view
        ScrollView previewScroll = new ScrollView(this);
        TextView previewText = new TextView(this);
        previewText.setTypeface(android.graphics.Typeface.MONOSPACE);
        previewText.setTextSize(10);
        previewText.setTextColor(0xFFA0A0B0);
        previewText.setText("Archive preview:\n\n" + preview);
        previewScroll.addView(previewText);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        container.setPadding(pad, pad, pad, pad);

        TextView label = new TextView(this);
        label.setText("Copied to:\n" + archiveFile.getAbsolutePath()
                + "\n\nEnter a slot name:");
        label.setTextColor(0xFFF5F5F7);
        label.setTextSize(13);
        container.addView(label);

        LinearLayout.LayoutParams slotParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        slotParams.topMargin = pad / 2;
        slotInput.setLayoutParams(slotParams);
        container.addView(slotInput);

        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0);
        previewParams.weight = 1f;
        previewParams.topMargin = pad;
        previewScroll.setLayoutParams(previewParams);
        container.addView(previewScroll);

        new AlertDialog.Builder(this)
                .setTitle("Install " + kind)
                .setView(container)
                .setPositiveButton("Install", (d, w) -> {
                    String slot = slotInput.getText().toString().trim();
                    if (slot.isEmpty()) slot = suggestSlotName(kind);
                    fireInstallCommand(kind, slot, archiveFile.getAbsolutePath());
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Installs a driver archive in PURE JAVA — no proot, no bash, no shell.
     *
     * <p>This bypasses the proot+script architecture entirely. The archive
     * is extracted using {@link io.waylandie.display.shared.io.TarCompressorUtils}
     * directly to {@code getFilesDir()/contents/<kind>/<slot>/}, validated
     * for the expected layout, and an {@code active} symlink is created.
     *
     * <p>This mirrors winlator's architecture: all driver/component
     * extraction happens in Java, never via shell scripts inside proot.
     * Proot is only used for the actual Wine game launch (see
     * {@code ProotRunner.execWine()}).
     */
    private void fireInstallCommand(String kind, String slot, String archivePath) {
        final File archiveFile = new File(archivePath);
        if (!archiveFile.isFile()) {
            toast("Archive file not found: " + archivePath);
            return;
        }

        toast("Installing " + kind + " slot '" + slot + "'…");
        log("Installing (pure Java): " + kind + " " + slot + " from " + archivePath);
        io.waylandie.display.shared.util.LogRingBuffer.append(
                "[Settings] Installing " + kind + " slot '" + slot + "'…");

        // Show a modal progress dialog so the user sees visible feedback
        // during the long-running detect → extract → validate → install
        // pipeline. Without this, the only feedback was a single toast
        // at the start and the screen appeared frozen until the install
        // completed (or failed and popped up the error dialog).
        installProgressDialog = new android.app.ProgressDialog(this);
        installProgressDialog.setMessage("Installing " + kind + " slot '" + slot + "'…");
        installProgressDialog.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        installProgressDialog.setMax(100);
        installProgressDialog.setProgress(0);
        installProgressDialog.setCancelable(false);
        installProgressDialog.show();

        Thread t = new Thread(() -> {
            StringBuilder output = new StringBuilder();
            int exitCode = 0;
            try {
                // 1. Detect archive type by magic bytes
                updateInstallProgress(5, "Detecting archive type…");
                io.waylandie.display.shared.io.TarCompressorUtils.Type type =
                        io.waylandie.display.shared.io.TarCompressorUtils.detectArchiveType(archiveFile);
                if (type == null) {
                    exitCode = 1;
                    output.append("ERROR: unrecognized archive format.\n");
                    output.append("  Magic bytes don't match gzip/xz/zip/tar.\n");
                    output.append("  File: ").append(archiveFile).append('\n');
                    throw new IOException("unrecognized archive format");
                }
                output.append("Detected archive type: ").append(type).append('\n');
                updateInstallProgress(25, "Extracting…");

                // 2. Create slot directory in app-private contents/
                File kindDir = new File(getFilesDir(), "contents/" + kind);
                File slotDir = new File(kindDir, slot);
                if (slotDir.exists()) {
                    deleteRecursive(slotDir);
                }
                slotDir.mkdirs();
                output.append("Slot dir: ").append(slotDir).append('\n');

                // 3. Extract archive in pure Java
                output.append("Extracting…\n");
                boolean ok = io.waylandie.display.shared.io.TarCompressorUtils
                        .extractFileWithType(archiveFile, slotDir, type, null);
                if (!ok) {
                    exitCode = 1;
                    output.append("ERROR: extraction failed.\n");
                    throw new IOException("extraction failed");
                }
                output.append("Extraction complete.\n");
                updateInstallProgress(60, "Validating…");
                output.append("Top-level contents:\n");
                File[] kids = slotDir.listFiles();
                if (kids != null) {
                    for (File kid : kids) {
                        output.append("  ").append(kid.getName())
                                .append(kid.isDirectory() ? "/" : "")
                                .append('\n');
                    }
                }

                // 4. Kind-specific validation + hoisting
                String validationError = validateAndHoist(kind, slotDir, output);
                if (validationError != null) {
                    exitCode = 1;
                    output.append(validationError).append('\n');
                    deleteRecursive(slotDir);
                    throw new IOException("validation failed");
                }
                updateInstallProgress(80, "Installing to Wine prefix…");

                // 5. Activate — create 'active' symlink
                File activeLink = new File(kindDir, "active");
                if (activeLink.exists()) {
                    activeLink.delete();
                }
                try {
                    java.nio.file.Files.createSymbolicLink(
                            activeLink.toPath(),
                            new File(kindDir, slot).toPath());
                } catch (Exception e) {
                    // Fallback: if symlink fails (rare on Android), try rename
                    output.append("WARNING: symlink failed, trying rename: ")
                            .append(e.getMessage()).append('\n');
                    activeLink.createNewFile();
                }
                output.append("Activated: ").append(activeLink).append(" → ").append(slot).append('\n');

                // 6. Write meta.json
                File metaFile = new File(slotDir, "meta.json");
                try (java.io.PrintWriter pw = new java.io.PrintWriter(metaFile)) {
                    pw.println("{");
                    pw.println("  \"kind\": \"" + kind + "\",");
                    pw.println("  \"slot\": \"" + slot + "\",");
                    pw.println("  \"installed_from\": \"" + archiveFile + "\",");
                    pw.println("  \"installed_at\": \"" + new java.text.SimpleDateFormat(
                            "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                            .format(new java.util.Date()) + "\",");
                    pw.println("  \"activated\": true");
                    pw.println("}");
                }
                output.append("Done.\n");
                updateInstallProgress(100, "Done");

            } catch (Exception e) {
                if (exitCode == 0) exitCode = 1;
                output.append("INSTALL FAILED: ").append(e.getMessage()).append('\n');
                updateInstallProgress(-1, "Install failed — see error dialog");
            }

            final int code = exitCode;
            final String result = output.toString();
            // Log every line to LogRingBuffer + LogCat
            for (String line : result.split("\n")) {
                android.util.Log.i("WayLandIE/Install", line);
                io.waylandie.display.shared.util.LogRingBuffer.append("[install] " + line);
            }
            runOnUiThread(() -> {
                // Auto-dismiss the progress dialog on success OR failure.
                // Guarded so we don't throw if the activity was destroyed
                // mid-install (onDestroy already dismissed it).
                if (installProgressDialog != null && installProgressDialog.isShowing()) {
                    try { installProgressDialog.dismiss(); } catch (IllegalArgumentException ignored) {}
                }
                if (code == 0) {
                    toast(kind + " '" + slot + "' installed ✓");
                    log("Install succeeded: " + kind + " " + slot);
                    io.waylandie.display.shared.util.LogRingBuffer.append(
                            "[Settings] Install succeeded: " + kind + " " + slot);
                } else {
                    log("Install FAILED: " + kind + " " + slot + "\n" + result);
                    io.waylandie.display.shared.util.LogRingBuffer.append(
                            "[Settings] Install FAILED: " + kind + " " + slot);
                    showInstallFailureDialog(kind, slot, code, result);
                }
                refreshAllStatuses();
            });
        }, "wl-install-" + kind);
        runningInstallThread = t;
        t.start();
    }

    /**
     * Updates the install progress dialog from a background thread.
     * Safe to call from any thread; posts the actual UI mutation to the
     * main looper via {@link #runOnUiThread(Runnable)}. No-op if the
     * dialog has been dismissed (e.g., activity destroyed mid-install).
     *
     * @param progress 0-100 for the horizontal bar, or -1 to leave it unchanged
     * @param message  new message text, or null to leave it unchanged
     */
    private void updateInstallProgress(final int progress, final String message) {
        runOnUiThread(() -> {
            if (installProgressDialog == null || !installProgressDialog.isShowing()) return;
            if (progress >= 0) installProgressDialog.setProgress(progress);
            if (message != null) installProgressDialog.setMessage(message);
        });
    }

    /**
     * Validates the extracted slot directory and hoists nested layouts.
     * Returns null on success, or an error message string on failure.
     */
    private String validateAndHoist(String kind, File slotDir, StringBuilder output) {
        switch (kind) {
            case "proton": {
                // Look for wine binary at known paths
                File wineBin = null;
                String[] winePaths = {"files/bin/wine", "dist/bin/wine", "bin/wine"};
                for (String p : winePaths) {
                    if (new File(slotDir, p).isFile()) {
                        wineBin = new File(slotDir, p);
                        break;
                    }
                }
                // Try hoisting if wine not found
                if (wineBin == null) {
                    File[] topDirs = slotDir.listFiles(File::isDirectory);
                    if (topDirs != null && topDirs.length == 1) {
                        File top = topDirs[0];
                        output.append("Hoisting nested layout: ").append(top.getName()).append('\n');
                        for (String p : winePaths) {
                            if (new File(top, p).isFile()) {
                                // Move all contents up
                                File[] kids = top.listFiles();
                                if (kids != null) {
                                    for (File kid : kids) {
                                        kid.renameTo(new File(slotDir, kid.getName()));
                                    }
                                }
                                top.delete();
                                wineBin = new File(slotDir, p);
                                break;
                            }
                        }
                    }
                }
                if (wineBin == null) {
                    StringBuilder err = new StringBuilder();
                    err.append("ERROR: wine binary not found.\n");
                    err.append("  Checked (relative to slot dir):\n");
                    for (String p : winePaths) err.append("    ").append(p).append('\n');
                    err.append("  Top-level contents:\n");
                    File[] kids = slotDir.listFiles();
                    if (kids != null) {
                        for (File kid : kids) {
                            err.append("    ").append(kid.getName()).append('\n');
                        }
                    }
                    err.append("  Recursive wine search:\n");
                    findRecursive(slotDir, "wine", err, 10);
                    return err.toString();
                }
                output.append("Found wine: ").append(wineBin).append('\n');
                wineBin.setExecutable(true, false);

                // Unpack prefixPack.txz to Wine prefix (pre-built Windows C: drive)
                File prefixPack = new File(slotDir, "prefixPack.txz");
                if (prefixPack.exists()) {
                    io.waylandie.display.runtime.environment.ImageFsManager imgFs =
                            new io.waylandie.display.runtime.environment.ImageFsManager(this);
                    // CRITICAL: Extract prefixPack to home/xuser/ (NOT .wine/) because
                    // the prefixPack contains paths like .wine/drive_c/... internally.
                    // Extracting to .wine/ creates .wine/.wine/drive_c/... (double nested).
                    // Extracting to home/xuser/ creates home/xuser/.wine/drive_c/... (correct).
                    File homeDir = new File(imgFs.getRootDir(), "home/xuser");
                    if (!homeDir.exists()) homeDir.mkdirs();
                    File winePrefix = new File(homeDir, ".wine");
                    winePrefix.mkdirs();
                    output.append("Unpacking prefixPack.txz to home dir (prefixPack contains .wine/ internally)…\n");
                    boolean unpacked = io.waylandie.display.shared.io.TarCompressorUtils.extractFileWithType(
                            prefixPack, homeDir,
                            io.waylandie.display.shared.io.TarCompressorUtils.Type.XZ, null);
                    if (unpacked) {
                        output.append("  ✓ Prefix pack unpacked to: ").append(homeDir.getAbsolutePath()).append("/.wine\n");
                        // Create dosdevices symlinks ONLY if they don't exist
                        // (prefixPack may already contain them)
                        File dosdevices = new File(winePrefix, "dosdevices");
                        if (!new File(dosdevices, "c:").exists()) {
                            dosdevices.mkdirs();
                            try {
                                java.nio.file.Files.createSymbolicLink(
                                        new File(dosdevices, "c:").toPath(),
                                        new File("../drive_c").toPath());
                                java.nio.file.Files.createSymbolicLink(
                                        new File(dosdevices, "z:").toPath(),
                                        imgFs.getRootDir().toPath());
                                output.append("  ✓ dosdevices symlinks created (c:, z:)\n");
                            } catch (Exception e) {
                                output.append("  ⚠ dosdevices symlink failed: ").append(e.getMessage()).append('\n');
                            }
                        } else {
                            output.append("  ✓ dosdevices already present (from prefixPack)\n");
                        }
                        // Verify Windows system DLLs are present
                        // Use recursive search — arm64ec Wine prefixes may have
                        // system32 as a symlink (→ sysarm64_x64) so fixed path
                        // check fails on broken symlinks.
                        File kernel32 = findFileRecursiveFile(winePrefix, "kernel32.dll");
                        if (kernel32 != null) {
                            output.append("  ✓ kernel32.dll found: ").append(kernel32.getAbsolutePath()).append("\n");
                        } else {
                            output.append("  ⚠ kernel32.dll NOT found — listing prefix contents:\n");
                            listDirContents(winePrefix, output, "    ", 3);
                        }
                    } else {
                        output.append("  ✗ Prefix pack extraction FAILED\n");
                    }
                }
                break;
            }
            case "dxvk": {
                // Look for d3d11.dll or dxgi.dll
                if (!findFileRecursive(slotDir, "d3d11.dll") && !findFileRecursive(slotDir, "dxgi.dll")) {
                    return "ERROR: no DXVK dlls (d3d11.dll / dxgi.dll) found in archive.";
                }
                output.append("DXVK dlls found ✓\n");
                // Install DXVK dlls into the Wine prefix's system32 + syswow64.
                // Wine needs the dlls IN the prefix for WINEDLLOVERRIDES=native
                // to work — the override tells Wine to use the Windows DLL
                // instead of its built-in one, but it must be in system32/.
                // This mirrors winlator's approach: extract DXVK directly to
                // rootfs/home/xuser/.wine/drive_c/windows/system32/.
                installDxvkToWinePrefix(slotDir, output);
                break;
            }
            case "turnip": {
                File turnipSo = findFileRecursiveFile(slotDir, "libvulkan_freedreno.so");
                if (turnipSo == null) {
                    return "ERROR: no libvulkan_freedreno.so in archive.";
                }
                output.append("Turnip .so found: ").append(turnipSo).append(" ✓\n");
                // Create the Vulkan ICD JSON at the GUEST path that
                // VK_ICD_FILENAMES points to (/usr/local/etc/vulkan/icd.d/).
                // The ICD JSON tells the Vulkan loader where to find the
                // driver .so. Without this, Vulkan can't find Turnip.
                // The .so path in the JSON is the GUEST path (/opt/turnip/...)
                // because the Vulkan loader runs inside proot.
                installTurnipIcd(turnipSo, output);
                break;
            }
            case "fex": {
                // FEX packages come in two layouts:
                // 1. Winlator-style: contains profile.json + system32/ with DLLs
                //    (FEX is loaded as WoW64 hook DLLs, not as a standalone bin/)
                // 2. Standalone: contains bin/ + lib/ (raw FEX-Emu binary)
                // Accept either layout.
                File profileJson = new File(slotDir, "profile.json");
                File system32Dir = new File(slotDir, "system32");
                File binDir = new File(slotDir, "bin");

                if (profileJson.isFile() && system32Dir.isDirectory()) {
                    // Winlator-style FEX — copy DLLs to Wine prefix system32
                    output.append("FEX Winlator-style (profile.json + system32/) ✓\n");
                    installFexDllsToWinePrefix(system32Dir, output);
                } else if (binDir.isDirectory()) {
                    // Standalone FEX-Emu binary
                    output.append("FEX bin/ found ✓\n");
                } else {
                    // Try hoisting nested layout
                    File[] topDirs = slotDir.listFiles(File::isDirectory);
                    if (topDirs != null && topDirs.length == 1) {
                        File top = topDirs[0];
                        if (new File(top, "bin").isDirectory()
                                || (new File(top, "profile.json").isFile()
                                    && new File(top, "system32").isDirectory())) {
                            output.append("Hoisting nested FEX layout: " + top.getName() + "\n");
                            File[] kids = top.listFiles();
                            if (kids != null) {
                                for (File kid : kids) kid.renameTo(new File(slotDir, kid.getName()));
                            }
                            top.delete();
                            // Re-check after hoisting
                            if (new File(slotDir, "profile.json").isFile()
                                    && new File(slotDir, "system32").isDirectory()) {
                                output.append("FEX Winlator-style (after hoist) ✓\n");
                                installFexDllsToWinePrefix(new File(slotDir, "system32"), output);
                            } else if (new File(slotDir, "bin").isDirectory()) {
                                output.append("FEX bin/ found (after hoist) ✓\n");
                            } else {
                                return "ERROR: FEX archive has unrecognized layout. "
                                        + "Expected profile.json+system32/ or bin/. Found: "
                                        + listContents(slotDir);
                            }
                        } else {
                            return "ERROR: FEX archive has unrecognized layout. "
                                    + "Expected profile.json+system32/ or bin/. Found: "
                                    + listContents(slotDir);
                        }
                    } else {
                        return "ERROR: FEX archive has unrecognized layout. "
                                + "Expected profile.json+system32/ or bin/. Found: "
                                + listContents(slotDir);
                    }
                }
                break;
            }
        }
        return null;
    }

    private boolean findFileRecursive(File dir, String name) {
        return findFileRecursiveFile(dir, name) != null;
    }

    private File findFileRecursiveFile(File dir, String name) {
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File kid : kids) {
            if (kid.isDirectory()) {
                File found = findFileRecursiveFile(kid, name);
                if (found != null) return found;
            } else if (kid.getName().equalsIgnoreCase(name)) {
                return kid;
            }
        }
        return null;
    }

    /**
     * Copies DXVK dlls from the extracted slot into the Wine prefix's
     * system32/ and syswow64/ directories. Creates the prefix structure
     * if it doesn't exist. This is necessary because WINEDLLOVERRIDES=native
     * tells Wine to use the external DLL, but it must be IN the prefix.
     */
    private void installDxvkToWinePrefix(File slotDir, StringBuilder output) {
        try {
            io.waylandie.display.runtime.environment.ImageFsManager imageFs =
                    new io.waylandie.display.runtime.environment.ImageFsManager(this);
            File winePrefix = new File(imageFs.getRootDir(), "home/xuser/.wine");
            File dstSystem32 = new File(winePrefix, "drive_c/windows/system32");
            File dstSyswow64 = new File(winePrefix, "drive_c/windows/syswow64");
            dstSystem32.mkdirs();
            dstSyswow64.mkdirs();

            int copied = 0;

            // Layout 1: Winlator-style — system32/ and syswow64/ at top level
            File srcSystem32 = new File(slotDir, "system32");
            File srcSyswow64 = new File(slotDir, "syswow64");
            if (srcSystem32.isDirectory()) {
                File[] dlls = srcSystem32.listFiles((d, name) -> name.endsWith(".dll"));
                if (dlls != null) {
                    for (File dll : dlls) {
                        copyFile(dll, new File(dstSystem32, dll.getName()));
                        copied++;
                    }
                }
            }
            if (srcSyswow64.isDirectory()) {
                File[] dlls = srcSyswow64.listFiles((d, name) -> name.endsWith(".dll"));
                if (dlls != null) {
                    for (File dll : dlls) {
                        copyFile(dll, new File(dstSyswow64, dll.getName()));
                        copied++;
                    }
                }
            }

            // Layout 2: Standard DXVK — x64/ and x32/ subdirs
            if (copied == 0) {
                String[] dxvkDlls = {"d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll",
                        "d3d10core.dll", "d3d11.dll", "dxgi.dll"};
                for (String dll : dxvkDlls) {
                    File x64Dll = new File(slotDir, "x64/" + dll);
                    if (!x64Dll.isFile()) x64Dll = findFileRecursiveFile(slotDir, dll);
                    if (x64Dll != null) {
                        copyFile(x64Dll, new File(dstSystem32, dll));
                        copied++;
                    }
                    File x32Dir = new File(slotDir, "x32");
                    if (x32Dir.isDirectory()) {
                        File x32Dll = new File(x32Dir, dll);
                        if (x32Dll.isFile()) {
                            copyFile(x32Dll, new File(dstSyswow64, dll));
                            copied++;
                        }
                    }
                }
            }

            output.append("DXVK: copied ").append(copied).append(" dlls to Wine prefix\n");
            output.append("  system32: ").append(dstSystem32).append('\n');
            output.append("  syswow64: ").append(dstSyswow64).append('\n');
        } catch (Exception e) {
            output.append("WARNING: DXVK prefix install failed: ").append(e.getMessage()).append('\n');
        }
    }

    /**
     * Creates the Vulkan ICD JSON pointing to the Turnip driver .so.
     * The .so is copied to rootfs/usr/local/lib/ so the ICD JSON uses
     * /usr/local/lib/libvulkan_freedreno.so — this path works in BOTH
     * native mode (rootfs is the working directory) and proot mode
     * (rootfs is the proot root).
     */
    private void installTurnipIcd(File turnipSo, StringBuilder output) {
        try {
            io.waylandie.display.runtime.environment.ImageFsManager imageFs =
                    new io.waylandie.display.runtime.environment.ImageFsManager(this);
            File icdDir = new File(imageFs.getRootDir(), "usr/local/etc/vulkan/icd.d");
            icdDir.mkdirs();
            File icdFile = new File(icdDir, "freedreno_icd.json");

            // Copy the .so to rootfs/usr/local/lib/ — this path works in
            // both native and proot modes.
            File libDir = new File(imageFs.getRootDir(), "usr/local/lib");
            libDir.mkdirs();
            File destSo = new File(libDir, "libvulkan_freedreno.so");
            copyFile(turnipSo, destSo);

            // Write ICD JSON pointing to /usr/local/lib/ (works in both modes)
            try (java.io.PrintWriter pw = new java.io.PrintWriter(icdFile)) {
                pw.println("{");
                pw.println("    \"file_format_version\": \"1.0.0\",");
                pw.println("    \"ICD\": {");
                pw.println("        \"library_path\": \"/usr/local/lib/libvulkan_freedreno.so\",");
                pw.println("        \"api_version\": \"1.3.0\"");
                pw.println("    }");
                pw.println("}");
            }
            output.append("Turnip ICD JSON: ").append(icdFile).append('\n');
            output.append("  → library_path: /usr/local/lib/libvulkan_freedreno.so\n");
            output.append("  Also copied .so to: ").append(destSo).append('\n');

            // Disable llvmpipe ICD if present (so Turnip wins)
            File lvpIcd = new File(icdDir, "lvp_icd.json");
            if (lvpIcd.exists()) {
                lvpIcd.renameTo(new File(icdDir, "lvp_icd.json.disabled"));
                output.append("  Disabled llvmpipe ICD\n");
            }
        } catch (Exception e) {
            output.append("WARNING: Turnip ICD install failed: ").append(e.getMessage()).append('\n');
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    /**
     * Copies FEX DLLs from the extracted system32/ into the Wine prefix's
     * system32/. FEX (Winlator-style) is loaded as WoW64 hook DLLs, so the
     * DLLs must be in the Wine prefix's system32/ directory.
     */
    private void installFexDllsToWinePrefix(File srcSystem32, StringBuilder output) {
        try {
            io.waylandie.display.runtime.environment.ImageFsManager imageFs =
                    new io.waylandie.display.runtime.environment.ImageFsManager(this);
            File winePrefix = new File(imageFs.getRootDir(), "home/xuser/.wine");
            File dstSystem32 = new File(winePrefix, "drive_c/windows/system32");
            dstSystem32.mkdirs();
            File[] dlls = srcSystem32.listFiles((d, name) -> name.endsWith(".dll"));
            if (dlls == null || dlls.length == 0) {
                output.append("WARNING: no .dll files in FEX system32/\n");
                return;
            }
            int copied = 0;
            for (File dll : dlls) {
                copyFile(dll, new File(dstSystem32, dll.getName()));
                copied++;
            }
            output.append("FEX: copied ").append(copied).append(" DLLs to Wine prefix system32/\n");
            output.append("  → ").append(dstSystem32).append('\n');
        } catch (Exception e) {
            output.append("WARNING: FEX DLL install failed: ").append(e.getMessage()).append('\n');
        }
    }

    private String listContents(File dir) {
        StringBuilder sb = new StringBuilder();
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File kid : kids) {
                sb.append(kid.getName());
                if (kid.isDirectory()) {
                    sb.append("/ (").append(kid.list() != null ? kid.list().length : 0).append(" items)");
                } else {
                    sb.append(" (").append(kid.length()).append(" bytes)");
                }
                sb.append(", ");
            }
        }
        return sb.toString();
    }

    private void findRecursive(File dir, String pattern, StringBuilder out, int maxResults) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        int found = 0;
        for (File kid : kids) {
            if (found >= maxResults) {
                out.append("    ... (truncated)\n");
                return;
            }
            if (kid.isDirectory()) {
                findRecursive(kid, pattern, out, maxResults - found);
            } else if (kid.getName().toLowerCase().contains(pattern.toLowerCase())) {
                out.append("    ").append(kid.getAbsolutePath()).append('\n');
                found++;
            }
        }
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File kid : kids) deleteRecursive(kid);
            }
        }
        f.delete();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Dismiss the install progress dialog to avoid leaking its
        // window if the user navigates away mid-install. The install
        // thread keeps running in the background; subsequent
        // updateInstallProgress() calls become no-ops because the
        // dialog is no longer showing (guard inside the helper).
        if (installProgressDialog != null && installProgressDialog.isShowing()) {
            try { installProgressDialog.dismiss(); } catch (IllegalArgumentException ignored) {}
        }
        // Kill any running install process to prevent orphaned proot children
        // and stale bind mounts. On hard crashes (OOM, task-swipe), Android
        // may not call onDestroy(), but for normal navigation (user backs out
        // of Settings while install is running), this ensures the proot
        // process and its children are terminated cleanly.
        if (runningInstallProcess != null && runningInstallProcess.isAlive()) {
            log("onDestroy: killing running install process");
            io.waylandie.display.shared.util.LogRingBuffer.append(
                    "[Settings] onDestroy: killing running install process");
            runningInstallProcess.destroyForcibly();
        }
        runningInstallProcess = null;
        runningInstallThread = null;
    }

    /**
     * Shows a detailed, scrollable alert dialog when an install fails with a
     * non-zero exit code. Displays the full stdout/stderr output captured from
     * the install script so the user can see exactly what went wrong — missing
     * tools, extraction errors, validation failures, etc. — without having to
     * dig through logcat or diagnostic logs.
     */
    private void showInstallFailureDialog(String kind, String slot, int exitCode, String output) {
        TextView consoleView = new TextView(this);
        consoleView.setTypeface(android.graphics.Typeface.MONOSPACE);
        consoleView.setTextSize(10);
        consoleView.setTextColor(0xFFA0A0B0);
        consoleView.setText(output.isEmpty()
                ? "(no output captured — the script may have crashed before printing anything)"
                : output);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        consoleView.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(consoleView);

        new AlertDialog.Builder(this)
                .setTitle("Install failed: " + kind + " '" + slot + "' (exit " + exitCode + ")")
                .setView(scroll)
                .setPositiveButton("OK", null)
                .setNegativeButton("Copy log", (d, w) -> {
                    try {
                        String fullLog = "WayLandIE install failure\n"
                                + "Kind: " + kind + "\nSlot: " + slot + "\nExit: " + exitCode + "\n\n"
                                + output;
                        android.content.ClipboardManager clip =
                                (android.content.ClipboardManager)
                                        getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        clip.setPrimaryClip(android.content.ClipData.newPlainText(
                                "WayLandIE install log", fullLog));
                        toast("Log copied to clipboard");
                    } catch (Exception e) {
                        toast("Copy failed: " + e.getMessage());
                    }
                })
                .show();
    }

    private void refreshAllStatuses() {
        refreshStatus(dxvkStatus, "dxvk");
        refreshStatus(turnipStatus, "turnip");
        refreshStatus(qcomStatus, "qcom-adreno");
        refreshStatus(protonStatus, "proton");
        refreshStatus(fexStatus, "fex");
        refreshActiveProfile();
    }

    /**
     * Scans the app-private contents directory for installed driver
     * slots of the given kind. Shows the slot names + which one is
     * active (via the 'active' symlink created by
     * waylandie-install-driver).
     *
     * <p>Previously this scanned /sdcard/Download/WayLandIE/drivers/
     * (the staged archive files), which showed the user's downloaded
     * .wcp/.zip files instead of actual installed slots. That was
     * misleading — it looked like drivers were "installed" when they
     * weren't. Now scans the real install path:
     * getFilesDir()/contents/<kind>/.
     */
    private void refreshStatus(TextView view, String kind) {
        File kindDir = new File(getFilesDir(), "contents/" + kind);
        StringBuilder sb = new StringBuilder("Installed slots: ");
        int count = 0;
        String activeSlot = null;
        if (kindDir.isDirectory()) {
            // Resolve the 'active' symlink to find which slot is active
            File activeLink = new File(kindDir, "active");
            if (activeLink.exists()) {
                try {
                    activeSlot = activeLink.getCanonicalFile().getName();
                } catch (java.io.IOException e) {
                    // Symlink exists but can't resolve — show it anyway
                    activeSlot = "(active — unresolved)";
                }
            }
            File[] kids = kindDir.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    if (kid.getName().equals("active")) continue;
                    if (!kid.isDirectory()) continue;
                    if (count > 0) sb.append(", ");
                    sb.append(kid.getName());
                    if (kid.getName().equals(activeSlot)) {
                        sb.append(" (active)");
                    }
                    count++;
                }
            }
        }
        view.setText(sb.toString() + (count == 0 ? "(none — install one to begin)" : ""));
    }

    private void refreshActiveProfile() {
        activeProfileText.setText("default (auto-activated slots apply to all games)");
    }

    private void openProfileManager() {
        io.waylandie.display.runtime.environment.ProotRunner runner =
                new io.waylandie.display.runtime.environment.ProotRunner(this);
        if (!runner.isReady()) {
            toast("Environment not ready.");
            return;
        }
        try {
            runner.exec("waylandie-steam-profile list-games");
            toast("Profile list running in background.");
        } catch (java.io.IOException error) {
            toast("Failed: " + error.getMessage());
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return cursor.getString(idx);
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private String suggestSlotName(String kind) {
        switch (kind) {
            case "dxvk":        return "dxvk-2.4";
            case "turnip":      return "turnip-current";
            case "fex":         return "fex-2412";
            case "proton":      return "proton-armec";
            case "qcom-adreno": return "qcom-251009";
            case "box86":       return "box86-latest";
            case "box64":       return "box64-latest";
            default:            return "default";
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void log(String msg) {
        android.util.Log.i("WayLandIE/Settings", msg);
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Tiny helper — a single-line EditText in a card-friendly padding.
     * Avoids the need for an XML layout just for a dialog prompt.
     */
    private static final class EditTextEx extends android.widget.EditText {
        EditTextEx(android.content.Context context) {
            super(context);
            setPadding(32, 24, 32, 24);
            setTextColor(0xFFF5F5F7);
            setHintTextColor(0xFF666674);
            setBackground(null);
            setSingleLine(true);
            setTextSize(14);
        }
    }

    private void listDirContents(File dir, StringBuilder output, String indent, int maxDepth) {
        if (dir == null || !dir.isDirectory() || maxDepth <= 0) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File kid : kids) {
            if (kid.getName().startsWith(".")) continue;
            if (kid.isDirectory()) {
                int count = kid.list() != null ? kid.list().length : 0;
                output.append(indent).append(kid.getName()).append("/ (").append(count).append(" items)\n");
                if (maxDepth > 1) listDirContents(kid, output, indent + "  ", maxDepth - 1);
            } else {
                output.append(indent).append(kid.getName()).append(" (").append(kid.length()).append(" bytes)\n");
            }
            if (output.length() > 5000) break;
        }
    }
}
