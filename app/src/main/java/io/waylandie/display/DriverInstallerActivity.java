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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * DriverInstallerActivity lets the user pick a driver archive (.tar.gz,
 * .zip, .deb) from the file explorer and install it into a named slot
 * inside the Debian proot.
 *
 * <p>Supported driver kinds:
 * <ul>
 *   <li><b>DXVK</b> — DirectX 9/10/11 → Vulkan translation layer</li>
 *   <li><b>Turnip</b> — Mesa Vulkan ICD for Qualcomm Adreno</li>
 *   <li><b>FEX</b> — x86/ARM emulator (FEX-Emu) for running x86 Windows
 *       games on arm64 hosts</li>
 *   <li><b>Qualcomm Adreno</b> — proprietary Vulkan driver from
 *       Qualcomm Linux release (.deb)</li>
 *   <li><b>Box86 / Box64</b> — alternative x86/x64 emulators</li>
 * </ul>
 *
 * <p>Flow:
 * <ol>
 *   <li>Pick a kind from the spinner.</li>
 *   <li>Optionally pick an existing slot name to overwrite, or type a
 *       new slot name.</li>
 *   <li>Pick the archive file from the file explorer.</li>
 *   <li>Tap Install — the file is copied into
 *       /sdcard/Download/WayLandIE/drivers/ and a Termux RUN_COMMAND
 *       intent fires that runs waylandie-install-driver inside the
 *       Debian proot.</li>
 * </ol>
 */
public final class DriverInstallerActivity extends Activity {

    private static final int PICK_ARCHIVE = 2001;

    private Spinner kindSpinner;
    private EditText slotEdit;
    private TextView archivePathText;
    private CheckBox chkActivate;
    private Button btnPickArchive;
    private Button btnInstall;
    private Button btnCancel;
    private TextView logText;
    private TextView existingSlotsText;

    private Uri pickedUri;
    private String pickedDisplayName;
    private String copiedArchivePath;

    private static final String[] KINDS = {
            "dxvk", "turnip", "fex", "qcom-adreno", "box86", "box64"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_driver_installer);

        kindSpinner = findViewById(R.id.kindSpinner);
        slotEdit = findViewById(R.id.slotEdit);
        archivePathText = findViewById(R.id.archivePathText);
        chkActivate = findViewById(R.id.chkActivate);
        btnPickArchive = findViewById(R.id.btnPickArchive);
        btnInstall = findViewById(R.id.btnInstall);
        btnCancel = findViewById(R.id.btnCancel);
        logText = findViewById(R.id.logText);
        existingSlotsText = findViewById(R.id.existingSlotsText);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, KINDS);
        kindSpinner.setAdapter(adapter);

        btnPickArchive.setOnClickListener(v -> openArchivePicker());
        btnInstall.setOnClickListener(v -> installDriver());
        btnCancel.setOnClickListener(v -> finish());

        refreshExistingSlots();
        log("Ready. Pick a driver kind + archive to install.");
    }

    private void openArchivePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip",
                "application/x-tar",
                "application/gzip",
                "application/x-gzip",
                "application/x-compressed-tar",
                "application/vnd.debian.binary-package",
                "application/octet-stream"
        });
        try {
            startActivityForResult(intent, PICK_ARCHIVE);
        } catch (RuntimeException error) {
            toast("No file picker available: " + error.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ARCHIVE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            log("File picker cancelled.");
            return;
        }
        pickedUri = data.getData();
        pickedDisplayName = queryDisplayName(pickedUri);
        archivePathText.setText(pickedDisplayName != null
                ? pickedDisplayName : pickedUri.toString());
        log("Picked: " + (pickedDisplayName != null
                ? pickedDisplayName : pickedUri.toString()));
    }

    private void installDriver() {
        if (pickedUri == null) {
            toast("Pick an archive first.");
            return;
        }
        String kind = (String) kindSpinner.getSelectedItem();
        String slot = slotEdit.getText().toString().trim();
        if (slot.isEmpty()) {
            toast("Enter a slot name (e.g. 'turnip-current').");
            return;
        }

        // 1. Copy archive into /sdcard/Download/WayLandIE/drivers/ so
        //    Termux can read it via ~/storage/downloads/WayLandIE/drivers/.
        //    On Android 13+ without MANAGE_EXTERNAL_STORAGE, fall back to
        //    app-private external storage at /sdcard/Android/data/<pkg>/files/Download/
        File driversDir = new File(
                Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS),
                "WayLandIE/drivers");
        if (!driversDir.exists() && !driversDir.mkdirs()) {
            // Fall back to app-private external storage.
            driversDir = new File(
                    getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "WayLandIE/drivers");
            if (!driversDir.exists() && !driversDir.mkdirs()) {
                toast("Failed to mkdir " + driversDir
                        + ". Grant All files access in Settings.");
                return;
            }
            log("Falling back to app-private external: " + driversDir);
        }
        if (pickedDisplayName == null) {
            pickedDisplayName = kind + "-" + slot + ".archive";
        }
        File outFile = new File(driversDir, pickedDisplayName);
        try (InputStream in = getContentResolver().openInputStream(pickedUri);
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
        copiedArchivePath = outFile.getAbsolutePath();
        log("Copied archive to " + copiedArchivePath);

        // 2. Build the waylandie-install-driver command and fire it
        //    into Termux inside the Debian proot. The script tries multiple
        //    candidate paths so it finds the file regardless of which
        //    storage location we copied to.
        String inner = "waylandie-install-driver"
                + " --kind " + kind
                + " --slot " + shellQuote(slot)
                + " --file " + shellQuote(copiedArchivePath);
        if (chkActivate.isChecked()) {
            inner += " --activate";
        }
        String cmd = TermuxBridge.termuxCommand(inner);

        log("Sending to Termux:\n  " + cmd);
        if (!TermuxBridge.isTermuxInstalled(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Termux not installed")
                    .setMessage(getString(R.string.termux_not_installed))
                    .setPositiveButton("Install",
                            (d, w) -> TermuxBridge.openTermuxInstallPage(this))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        boolean sent = TermuxBridge.tryRunCommand(this, cmd);
        if (!sent) {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("WayLandIE", cmd));
            toast("Termux:API not available. Command copied — paste into Termux.");
            TermuxBridge.openTermuxLauncher(this);
            log("Fallback: command copied to clipboard.");
        } else {
            log("Command sent to Termux.");
            refreshExistingSlots();
        }
    }

    /**
     * Refreshes the "existing slots" text by listing the driver slot
     * directory on the Android side. (Authoritative state lives inside
     * Debian, so this is just a hint.)
     */
    private void refreshExistingSlots() {
        File root = new File(
                Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS),
                "WayLandIE/drivers");
        StringBuilder sb = new StringBuilder();
        if (root.exists()) {
            File[] kids = root.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    sb.append(kid.getName()).append('\n');
                }
            }
        }
        if (sb.length() == 0) {
            sb.append("(no driver archives staged yet)");
        }
        existingSlotsText.setText(sb.toString());
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

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void log(String line) {
        String entry = String.format(Locale.US, "[%tT] %s",
                System.currentTimeMillis(), line);
        logText.setText(logText.getText() + "\n" + entry);
    }
}
