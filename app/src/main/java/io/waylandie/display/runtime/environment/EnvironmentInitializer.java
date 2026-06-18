package io.waylandie.display.runtime.environment;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import io.waylandie.display.HomeActivity;
import io.waylandie.display.R;

/**
 * EnvironmentInitializer — first-run activity that extracts the bundled
 * rootfs (ImageFs) from APK assets into app-private storage.
 *
 * <p>This replaces the old Termux-based setup wizard. The user just sees:
 * <ol>
 *   <li>"Initializing environment…" with a progress bar (1-3 min)</li>
 *   <li>"Environment ready" → tap Continue → goes to HomeActivity</li>
 * </ol>
 *
 * <p>No Termux, no package installs, no missing repos. Everything the
 * app needs ships inside the APK.
 */
public final class EnvironmentInitializer extends Activity {

    private ProgressBar progressBar;
    private TextView statusText;
    private TextView percentText;
    private Button btnContinue;
    private Button btnRetry;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.waylandie_activity_initializer);

        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);
        percentText = findViewById(R.id.percentText);
        btnContinue = findViewById(R.id.btnContinue);
        btnRetry = findViewById(R.id.btnRetry);

        btnContinue.setOnClickListener(v -> {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
        });
        btnRetry.setOnClickListener(v -> startExtraction());

        startExtraction();
    }

    private void startExtraction() {
        btnRetry.setVisibility(View.GONE);
        btnContinue.setVisibility(View.GONE);
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
                            statusText.setText("Extraction failed. Check storage space "
                                    + "(need ~500 MB free) and retry.");
                            btnRetry.setVisibility(View.VISIBLE);
                        }
                    });
                }
            });
            if (!ok) {
                runOnUiThread(() -> {
                    statusText.setText("Extraction failed. Tap Retry.");
                    btnRetry.setVisibility(View.VISIBLE);
                });
            }
        }).start();
    }
}
