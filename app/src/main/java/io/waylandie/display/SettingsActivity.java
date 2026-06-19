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
     * Runs {@code waylandie-install-driver --list} inside proot to get
     * the archive's detected format + first 100 entries. Returns the
     * output as a string. If the command fails, returns an error message.
     */
    private String previewArchive(String archivePath) {
        io.waylandie.display.runtime.environment.ProotRunner runner =
                new io.waylandie.display.runtime.environment.ProotRunner(this);
        if (!runner.isReady()) {
            return "(Environment not ready — cannot preview. Tap Install anyway to try.)";
        }
        try {
            Process p = runner.exec("waylandie-install-driver --list --file " + shellQuote(archivePath));
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            // Cap at 500 lines so a huge archive doesn't OOM the preview.
            int lineCount = 0;
            while ((line = reader.readLine()) != null && lineCount < 500) {
                sb.append(line).append('\n');
                lineCount++;
            }
            // Wait with timeout — if proot hangs (rare but possible on
            // corrupted archives), don't leave this background thread
            // blocked forever. 15s is generous for listing contents.
            if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                sb.append("\n(preview timed out after 15s — archive may be very large or corrupted)");
            }
            return sb.length() > 0 ? sb.toString() : "(no output from preview command)";
        } catch (Exception e) {
            return "(preview failed: " + e.getMessage() + " — tap Install to try anyway)";
        }
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

    private void fireInstallCommand(String kind, String slot, String archivePath) {
        // Use ProotRunner to install driver inside the bundled rootfs
        io.waylandie.display.runtime.environment.ProotRunner runner =
                new io.waylandie.display.runtime.environment.ProotRunner(this);
        if (!runner.isReady()) {
            toast("Environment not ready. Initialize first.");
            return;
        }
        String inner = "waylandie-install-driver"
                + " --kind " + kind
                + " --slot " + shellQuote(slot)
                + " --file " + shellQuote(archivePath)
                + " --activate";

        toast("Installing " + kind + " slot '" + slot + "'…");
        log("Installing via ProotRunner: " + inner);
        io.waylandie.display.shared.util.LogRingBuffer.append(
                "[Settings] Installing " + kind + " slot '" + slot + "'…");
        try {
            Process p = runner.exec(inner);
            // Capture output line-by-line and append to LogRingBuffer so
            // it appears in crash logs + the diagnostic log. Every line
            // is logged — no silent discard. The install can take 10-30s
            // for large Proton packages, so this runs on a background
            // thread and posts the result back to the UI thread.
            new Thread(() -> {
                StringBuilder captured = new StringBuilder();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        captured.append(line).append('\n');
                        android.util.Log.i("WayLandIE/Install", line);
                        io.waylandie.display.shared.util.LogRingBuffer.append(
                                "[install] " + line);
                    }
                } catch (java.io.IOException e) {
                    String msg = "install output read failed: " + e.getMessage();
                    android.util.Log.w("WayLandIE/Install", msg);
                    io.waylandie.display.shared.util.LogRingBuffer.append(
                            "[install] " + msg);
                }
                int exitCode;
                try {
                    exitCode = p.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    exitCode = -1;
                }
                final int code = exitCode;
                final String output = captured.toString();
                runOnUiThread(() -> {
                    if (code == 0) {
                        toast(kind + " '" + slot + "' installed ✓");
                        log("Install succeeded: " + kind + " " + slot);
                        io.waylandie.display.shared.util.LogRingBuffer.append(
                                "[Settings] Install succeeded: " + kind + " " + slot);
                    } else {
                        toast(kind + " '" + slot + "' install FAILED (exit " + code + ")");
                        log("Install FAILED (exit " + code + "): " + kind + " " + slot
                                + "\n" + output);
                        io.waylandie.display.shared.util.LogRingBuffer.append(
                                "[Settings] Install FAILED (exit " + code + "): "
                                + kind + " " + slot);
                    }
                    refreshAllStatuses();
                });
            }, "wl-install-" + kind).start();
        } catch (java.io.IOException error) {
            String msg = "Install failed to start: " + error.getMessage();
            toast(msg);
            log(msg);
            io.waylandie.display.shared.util.LogRingBuffer.append("[install] " + msg);
            refreshAllStatuses();
        }
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
}
