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
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

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
 * Termux RUN_COMMAND. The picked archive is first copied to
 * /sdcard/Download/WayLandIE/drivers/ (or app-private external storage
 * fallback) so Termux can read it.
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
                openProfileManagerInTermux());

        refreshAllStatuses();
    }

    private void openArchivePicker(int requestCode, String title) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip",
                "application/x-tar",
                "application/gzip",
                "application/x-gzip",
                "application/x-compressed-tar",
                "application/x-xz-compressed-tar",
                "application/x-bzip2",
                "application/vnd.debian.binary-package",
                "application/x-rar-compressed",
                "application/x-7z-compressed",
                "application/octet-stream"
        });
        try {
            startActivityForResult(intent, requestCode);
            toast(title + " — pick the archive file");
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

        // 2. Prompt the user for a slot name.
        final EditTextEx slotInput = new EditTextEx(this);
        slotInput.setHint("e.g. " + suggestSlotName(kind));
        new AlertDialog.Builder(this)
                .setTitle("Install " + kind)
                .setMessage("Copied archive to:\n" + outFile.getAbsolutePath()
                        + "\n\nEnter a slot name:")
                .setView(slotInput)
                .setPositiveButton("Install", (d, w) -> {
                    String slot = slotInput.getText().toString().trim();
                    if (slot.isEmpty()) slot = suggestSlotName(kind);
                    fireInstallCommand(kind, slot, outFile.getAbsolutePath());
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void fireInstallCommand(String kind, String slot, String archivePath) {
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
        String inner = "waylandie-install-driver"
                + " --kind " + kind
                + " --slot " + shellQuote(slot)
                + " --file " + shellQuote(archivePath)
                + " --activate";
        String cmd = TermuxBridge.termuxCommand(inner);

        boolean sent = TermuxBridge.tryRunCommand(this, cmd);
        if (sent) {
            toast("Installing " + kind + " slot '" + slot + "' in Termux…");
        } else {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("WayLandIE", cmd));
            toast("Termux:API not available. Command copied — paste into Termux.");
            TermuxBridge.openTermuxLauncher(this);
        }
        refreshAllStatuses();
    }

    private void refreshAllStatuses() {
        refreshStatus(dxvkStatus, "dxvk");
        refreshStatus(turnipStatus, "turnip");
        refreshStatus(qcomStatus, "qcom-adreno");
        refreshStatus(protonStatus, "proton");
        refreshFexStatus();
        refreshActiveProfile();
    }

    private void refreshStatus(TextView view, String kind) {
        File root = new File(
                Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS),
                "WayLandIE/drivers");
        StringBuilder sb = new StringBuilder("Installed slots: ");
        int count = 0;
        if (root.exists()) {
            File[] kids = root.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    if (kid.getName().toLowerCase(Locale.US).contains(kind)
                            || kind.equals("dxvk") && kid.getName().toLowerCase(Locale.US).contains("dxvk")
                            || kind.equals("turnip") && kid.getName().toLowerCase(Locale.US).contains("turnip")) {
                        if (count > 0) sb.append(", ");
                        sb.append(kid.getName());
                        count++;
                    }
                }
            }
        }
        // Also list staged archives (just the .tar.gz/.zip/.deb files staged for install)
        view.setText(sb.toString() + (count == 0 ? "(none — install one to begin)" : ""));
    }

    private void refreshFexStatus() {
        // FEX is installed via apt, so we can't easily check it from here.
        // Just show a hint to run the setup wizard.
        fexStatus.setText("Status: managed by setup wizard (apt: fex-emu-app)");
    }

    private void refreshActiveProfile() {
        activeProfileText.setText("default (auto-activated slots apply to all games)");
    }

    private void openProfileManagerInTermux() {
        if (!TermuxBridge.isTermuxInstalled(this)) {
            toast("Termux not installed.");
            return;
        }
        String cmd = TermuxBridge.termuxCommand(
                "waylandie-steam-profile list-games");
        boolean sent = TermuxBridge.tryRunCommand(this, cmd);
        if (!sent) {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("WayLandIE", cmd));
            toast("Command copied — paste into Termux.");
            TermuxBridge.openTermuxLauncher(this);
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
