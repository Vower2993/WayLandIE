package io.waylandie.display;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GameLauncherActivity lets the user pick any Windows .exe and run it
 * through Wine inside the Debian proot.
 *
 * <p>The user flow is:
 * <ol>
 *   <li>Pick an .exe from the system file picker (Downloads folder, SD
 *       card, or any other SAF location).</li>
 *   <li>Optionally pick a profile (driver slot) that was previously
 *       installed via {@link DriverInstallerActivity}.</li>
 *   <li>Optionally toggle gamescope, DXVK, FEX.</li>
 *   <li>Tap Launch — the activity fires a Termux RUN_COMMAND intent that
 *       runs {@code waylandie-run-game} inside the Debian proot with the
 *       .exe path and the chosen options.</li>
 * </ol>
 *
 * <p>The .exe is <b>not</b> copied — Termux (after
 * {@code termux-setup-storage}) can read directly from
 * {@code /sdcard/Download/...} and any SAF-persisted URI. We pass the
 * real filesystem path to wine.
 *
 * <p>For SAF URIs that don't have a real path (e.g. Google Drive), the
 * activity copies the file into
 * {@code /sdcard/Download/WayLandIE/games/} first and passes that path.
 */
public final class GameLauncherActivity extends Activity {

    private static final String TAG = "WayLandIE/GameLauncher";
    private static final int PICK_EXE = 1001;

    private TextView exePathText;
    private EditText profileEdit;
    private CheckBox chkGamescope;
    private CheckBox chkDxvk;
    private CheckBox chkFex;
    private CheckBox chkBox86;
    private Button btnPickExe;
    private Button btnLaunch;
    private Button btnCancel;
    private TextView logText;

    private Uri pickedUri;
    private String pickedRealPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_launcher);

        exePathText = findViewById(R.id.exePathText);
        profileEdit = findViewById(R.id.profileEdit);
        chkGamescope = findViewById(R.id.chkGamescope);
        chkDxvk = findViewById(R.id.chkDxvk);
        chkFex = findViewById(R.id.chkFex);
        chkBox86 = findViewById(R.id.chkBox86);
        btnPickExe = findViewById(R.id.btnPickExe);
        btnLaunch = findViewById(R.id.btnLaunch);
        btnCancel = findViewById(R.id.btnCancel);
        logText = findViewById(R.id.logText);

        btnPickExe.setOnClickListener(v -> openExePicker());
        btnLaunch.setOnClickListener(v -> launchGame());
        btnCancel.setOnClickListener(v -> finish());

        log("Ready. Tap 'Pick .exe' to choose a game.");
    }

    private void openExePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        // Hint the picker at .exe + .msi + common archive types.
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/x-msdownload",
                "application/x-msi",
                "application/octet-stream",
                "application/zip",
                "application/x-7z-compressed"
        });
        try {
            startActivityForResult(intent, PICK_EXE);
        } catch (RuntimeException error) {
            toast("No file picker available: " + error.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_EXE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            log("File picker cancelled.");
            return;
        }
        pickedUri = data.getData();
        // Try to persist read permission so we can copy the file later.
        try {
            getContentResolver().takePersistableUriPermission(
                    pickedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Not all providers support persistable permissions — copy will
            // still work for the lifetime of this activity.
        }

        String displayName = queryDisplayName(pickedUri);
        pickedRealPath = resolveRealPath(displayName);
        exePathText.setText(pickedRealPath != null
                ? pickedRealPath
                : (displayName != null ? displayName : pickedUri.toString()));
        log("Picked: " + (pickedRealPath != null ? pickedRealPath : displayName));
    }

    /**
     * Tries to find a real filesystem path the Debian proot can read. Falls
     * back to null — caller should copy the file into the public Downloads
     * folder in that case.
     */
    private String resolveRealPath(String displayName) {
        // If the URI is a file:// URI pointing at /sdcard/..., Termux can
        // read it directly.
        if (pickedUri.getScheme() != null && pickedUri.getScheme().equals("file")) {
            return pickedUri.getPath();
        }
        // If we can guess it's in /sdcard/Download/, use that.
        if (displayName != null) {
            File guess = new File(
                    Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS),
                    displayName);
            if (guess.exists()) {
                return guess.getAbsolutePath();
            }
            // Also try /sdcard/WayLandIE/games/
            File guess2 = new File(
                    Environment.getExternalStorageDirectory(),
                    "Download/WayLandIE/games/" + displayName);
            if (guess2.exists()) {
                return guess2.getAbsolutePath();
            }
        }
        return null;
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

    private void launchGame() {
        if (pickedUri == null) {
            toast("Pick an .exe first.");
            return;
        }
        final String exePath = pickedRealPath != null
                ? pickedRealPath
                : copyToDownloadsThenReturn();
        if (exePath == null) {
            toast("Could not access the .exe. Try copying it into /sdcard/Download/ manually.");
            return;
        }

        final String profile = profileEdit.getText().toString().trim();
        final boolean gamescope = chkGamescope.isChecked();
        final boolean dxvk = chkDxvk.isChecked();
        final boolean fex = chkFex.isChecked();
        final boolean box86 = chkBox86.isChecked();

        // Build the command that runs inside Termux:
        //   proot-distro login debian --shared-tmp -- bash -lc \
        //     'waylandie-run-game --exe PATH [--profile NAME] [--gamescope] [--dxvk] [--fex] [--box86]'
        StringBuilder inner = new StringBuilder("waylandie-run-game");
        inner.append(" --exe ").append(shellQuote(exePath));
        if (!profile.isEmpty()) {
            inner.append(" --profile ").append(shellQuote(profile));
        }
        if (gamescope) inner.append(" --gamescope");
        if (dxvk)      inner.append(" --dxvk");
        if (fex)       inner.append(" --fex");
        if (box86)     inner.append(" --box86");

        String cmd = TermuxBridge.termuxCommand(inner.toString());

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
            copyToClipboard(cmd);
            toast("Termux:API not available. Command copied — paste into Termux.");
            TermuxBridge.openTermuxLauncher(this);
            log("Fallback: command copied to clipboard.");
        } else {
            log("Command sent to Termux.");
        }
    }

    /**
     * Copies the picked file (likely a SAF URI) into the public Downloads
     * folder so Termux can read it via /sdcard/Download/WayLandIE/games/.
     * On Android 13+ without MANAGE_EXTERNAL_STORAGE, falls back to
     * app-private external storage at /sdcard/Android/data/<pkg>/files/Download/.
     * Returns the absolute path, or null on failure.
     */
    private String copyToDownloadsThenReturn() {
        String displayName = queryDisplayName(pickedUri);
        if (displayName == null) {
            displayName = "game.exe";
        }
        // Try public Downloads first.
        File outDir = new File(
                Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS),
                "WayLandIE/games");
        if (!outDir.exists() && !outDir.mkdirs()) {
            // Fall back to app-private external storage.
            outDir = new File(
                    getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "WayLandIE/games");
            if (!outDir.exists() && !outDir.mkdirs()) {
                log("Failed to mkdir " + outDir
                        + " (grant All files access in Settings)");
                return null;
            }
            log("Falling back to app-private external: " + outDir);
        }
        File outFile = new File(outDir, displayName);
        try (java.io.InputStream in = getContentResolver().openInputStream(pickedUri);
             java.io.OutputStream out = new java.io.FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (IOException error) {
            log("Copy failed: " + error.getMessage());
            return null;
        }
        log("Copied to " + outFile.getAbsolutePath());
        pickedRealPath = outFile.getAbsolutePath();
        return pickedRealPath;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("WayLandIE", text));
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
