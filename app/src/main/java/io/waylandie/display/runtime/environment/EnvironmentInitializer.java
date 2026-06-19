package io.waylandie.display.runtime.environment;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

import io.waylandie.display.HomeActivity;
import io.waylandie.display.R;
import io.waylandie.display.shared.util.LogCollector;

/**
 * EnvironmentInitializer — first-run activity that extracts the bundled
 * rootfs (ImageFs) from APK assets into app-private storage.
 *
 * <p>If extraction fails, the user sees three buttons:
 * <ul>
 *   <li><b>Retry</b> — try extraction again</li>
 *   <li><b>Save Logs</b> — collect diagnostic info for debugging</li>
 *   <li><b>Skip</b> — go to Home anyway (limited functionality without rootfs)</li>
 * </ul>
 */
public final class EnvironmentInitializer extends Activity {

    private ProgressBar progressBar;
    private TextView statusText;
    private TextView percentText;
    private Button btnContinue;
    private Button btnRetry;
    private Button btnSaveLogs;
    private Button btnSkip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.waylandie_activity_initializer);

        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);
        percentText = findViewById(R.id.percentText);
        btnContinue = findViewById(R.id.btnContinue);
        btnRetry = findViewById(R.id.btnRetry);
        btnSaveLogs = findViewById(R.id.btnSaveLogs);
        btnSkip = findViewById(R.id.btnSkip);

        btnContinue.setOnClickListener(v -> goToHome());
        btnRetry.setOnClickListener(v -> startExtraction());
        btnSaveLogs.setOnClickListener(v -> saveLogs());
        btnSkip.setOnClickListener(v -> goToHome());

        startExtraction();
    }

    private void goToHome() {
        startActivity(new Intent(this, HomeActivity.class));
        finish();
    }

    private void startExtraction() {
        btnRetry.setVisibility(View.GONE);
        btnContinue.setVisibility(View.GONE);
        btnSaveLogs.setVisibility(View.GONE);
        btnSkip.setVisibility(View.GONE);
        progressBar.setProgress(0);
        statusText.setText("Extracting bundled environment…");
        percentText.setText("0%");

        final ImageFsManager imageFs = new ImageFsManager(this);

        // If already extracted and up to date, skip
        if (imageFs.isUpToDate()) {
            statusText.setText("Environment ready (v" + imageFs.getFormattedVersion() + ")");
            progressBar.setProgress(100);
            percentText.setText("100%");
            btnContinue.setVisibility(View.VISIBLE);
            return;
        }

        new Thread(() -> {
            boolean ok = imageFs.install(new ImageFsManager.ProgressListener() {
                @Override
                public void onProgress(int percent) {
                    runOnUiThread(() -> {
                        progressBar.setProgress(percent);
                        percentText.setText(percent + "%");
                    });
                }
                @Override
                public void onFinished(boolean success) {
                    runOnUiThread(() -> {
                        if (success) {
                            statusText.setText("Environment ready (v"
                                    + imageFs.getFormattedVersion() + ")");
                            btnContinue.setVisibility(View.VISIBLE);
                        } else {
                            showFailureButtons("Extraction failed. Check storage space "
                                    + "(need ~500 MB free) and retry.");
                        }
                    });
                }
            });
            if (!ok) {
                runOnUiThread(() -> {
                    showFailureButtons("Extraction failed. Tap Retry, Save Logs, or Skip.");
                });
            }
        }).start();
    }

    /**
     * Shows the Retry + Save Logs + Skip buttons when extraction fails.
     */
    private void showFailureButtons(String message) {
        statusText.setText(message);
        btnRetry.setVisibility(View.VISIBLE);
        btnSaveLogs.setVisibility(View.VISIBLE);
        btnSkip.setVisibility(View.VISIBLE);
    }

    /**
     * Collects all diagnostic logs and saves to /sdcard/Download/WayLandIE/logs/.
     */
    private void saveLogs() {
        Toast.makeText(this, "Collecting logs…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final File logFile = LogCollector.collect(this, null);
            runOnUiThread(() -> {
                if (logFile != null && logFile.exists()) {
                    new AlertDialog.Builder(this)
                            .setTitle("Logs saved")
                            .setMessage("Log file saved to:\n"
                                    + logFile.getAbsolutePath()
                                    + "\n\nSize: " + logFile.length() + " bytes\n\n"
                                    + "Share it so I can diagnose the extraction failure.")
                            .setPositiveButton("Share", (d, w) -> {
                                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                                shareIntent.setType("text/plain");
                                shareIntent.putExtra(Intent.EXTRA_SUBJECT,
                                        "WayLandIE diagnostic log");
                                shareIntent.putExtra(Intent.EXTRA_STREAM,
                                        androidx.core.content.FileProvider.getUriForFile(
                                                this,
                                                getPackageName() + ".fileprovider",
                                                logFile));
                                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(Intent.createChooser(shareIntent, "Share log file"));
                            })
                            .setNegativeButton("OK", null)
                            .show();
                } else {
                    Toast.makeText(this, "Failed to save logs.",
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }
}
