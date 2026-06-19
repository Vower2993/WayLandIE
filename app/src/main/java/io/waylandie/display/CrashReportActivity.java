package io.waylandie.display;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import io.waylandie.display.runtime.environment.ImageFsManager;
import io.waylandie.display.runtime.environment.SetupStateStore;

/**
 * CrashReportActivity — shown when WayLandIEApplication detects a fresh
 * crash file on launch.
 *
 * <p>Shows the most recent tombstone in a monospace TextView inside a
 * ScrollView. Three buttons:
 * <ul>
 *   <li><b>Copy logs</b> — copies the tombstone text to the clipboard.</li>
 *   <li><b>Share</b> — ACTION_SEND with the tombstone file via FileProvider.</li>
 *   <li><b>Reset environment</b> — deletes the imagefs/, clears the
 *       setup state, finishes to launcher. Use this if the crash is
 *       caused by a corrupt rootfs extraction.</li>
 * </ul>
 *
 * <p>On any user action, the fresh-crash flag is cleared so the next
 * launch proceeds normally.
 */
public final class CrashReportActivity extends Activity {

    private static final String TAG = "WayLandIE/CrashReport";

    private TextView tombstoneText;
    private Button btnCopy;
    private Button btnShare;
    private Button btnReset;
    private Button btnContinue;
    private ScrollView scroll;

    private File tombstoneFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crash_report);

        scroll = findViewById(R.id.crashScroll);
        tombstoneText = findViewById(R.id.tombstoneText);
        btnCopy = findViewById(R.id.btnCopyLogs);
        btnShare = findViewById(R.id.btnShareLogs);
        btnReset = findViewById(R.id.btnResetEnv);
        btnContinue = findViewById(R.id.btnContinueAnyway);

        tombstoneFile = WayLandIEApplication.getMostRecentCrashFile();
        if (tombstoneFile == null || !tombstoneFile.exists()) {
            tombstoneText.setText("(no crash file found — WayLandIEApplication.freshCrashFilePresent()\n"
                    + "was true but the file is gone. This should not happen.)");
            btnShare.setEnabled(false);
        } else {
            tombstoneText.setText(readTombstone(tombstoneFile));
        }

        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("WayLandIE crash",
                    tombstoneText.getText()));
            toast("Copied to clipboard");
        });

        btnShare.setOnClickListener(v -> {
            if (tombstoneFile == null || !tombstoneFile.exists()) {
                toast("No file to share");
                return;
            }
            try {
                Uri uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", tombstoneFile);
                Intent share = new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "WayLandIE crash report")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(share, "Share crash report"));
            } catch (Exception e) {
                toast("Share failed: " + e.getMessage());
            }
        });

        btnReset.setOnClickListener(v -> {
            Log.w(TAG, "Reset environment requested — wiping imagefs + state");
            try {
                ImageFsManager imgFs = new ImageFsManager(this);
                deleteRecursive(imgFs.getRootDir());
            } catch (Exception e) {
                Log.e(TAG, "Failed to wipe imagefs", e);
            }
            try {
                new SetupStateStore(this).reset();
            } catch (Exception e) {
                Log.e(TAG, "Failed to reset state", e);
            }
            WayLandIEApplication.clearFreshCrashFlag();
            toast("Environment reset. Relaunch the app.");
            finishAffinity();
        });

        btnContinue.setOnClickListener(v -> {
            Log.i(TAG, "User acknowledged crash — proceeding to HomeActivity");
            WayLandIEApplication.clearFreshCrashFlag();
            startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
        });

        // Auto-scroll to bottom so the user sees the most recent log lines
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private String readTombstone(File f) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            sb.append("(failed to read tombstone: ").append(e.getMessage()).append(")\n");
        }
        return sb.toString();
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursive(k);
            }
        }
        if (!f.delete()) Log.w(TAG, "Failed to delete " + f);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
