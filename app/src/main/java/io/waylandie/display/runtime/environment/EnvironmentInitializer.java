package io.waylandie.display.runtime.environment;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.waylandie.display.CrashReportActivity;
import io.waylandie.display.HomeActivity;
import io.waylandie.display.MainActivity;
import io.waylandie.display.R;
import io.waylandie.display.WayLandIEApplication;
import io.waylandie.display.shared.util.LogRingBuffer;
import io.waylandie.display.shared.util.LogCollector;

/**
 * EnvironmentInitializer — first-run activity that extracts the bundled
 * rootfs (ImageFs) from APK assets into app-private storage.
 *
 * <p><b>Fix history (2026-06-19):</b>
 * <ul>
 *   <li>The previous version had NO log TextView in its layout — only
 *       a progress bar + status text + Continue button. The user
 *       reported "first page no longer shows log" — confirmed by
 *       reading {@code waylandie_activity_initializer.xml}: no
 *       {@code logText} view existed. This version restores the log
 *       surface as a release-build feature.</li>
 *   <li>The Continue button previously jumped straight to
 *       {@link HomeActivity}, which crashed because
 *       {@link HomeActivity#updateNativeStatus()} triggered
 *       {@code MainActivity}'s class init, which could throw
 *       {@code ExceptionInInitializerError} from the eager
 *       {@code loadNativeVulkanStatusText()} call. This version
 *       runs {@link MainActivity#nativeProbeCompositor()} FIRST
 *       and refuses to advance if the probe fails.</li>
 *   <li>The previous version used {@link ImageFsManager#isUpToDate()}
 *       as the skip-setup check, which returned true as soon as the
 *       version file was written — BEFORE the prefix was verified
 *       bootable. This version uses {@link SetupStateStore} with an
 *       explicit state machine: BEGIN → EXTRACTING → VERIFYING →
 *       READY. Only READY skips setup.</li>
 *   <li>If {@link WayLandIEApplication#freshCrashFilePresent()}
 *       returns true on entry, we route to {@link CrashReportActivity}
 *       instead of running setup or entering HomeActivity.</li>
 * </ul>
 *
 * <p>Reference patterns:
 * <ul>
 *   <li>winlator {@code RootFSInstaller.java:70-72} — marker file
 *       written only after extraction success (we extend with a
 *       separate VERIFYING state).</li>
 *   <li>WinNative {@code SetupWizardActivity.kt:577} — refuse-to-boot
 *       with reason if state check fails.</li>
 *   <li>WinNative {@code ImageFsInstaller.clearRootDir:338-353} —
 *       wipe partial state before re-extraction.</li>
 * </ul>
 */
public final class EnvironmentInitializer extends Activity {

    private static final String TAG = "WayLandIE/Setup";

    private ProgressBar progressBar;
    private TextView statusText;
    private TextView percentText;
    private TextView logText;
    private ScrollView logScroll;
    private Button btnContinue;
    private Button btnRetry;
    private Button btnSaveLogs;
    private Button btnSkip;

    private SetupStateStore stateStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If we crashed last time, route to CrashReportActivity BEFORE
        // anything else. This is the loop-breaker.
        if (WayLandIEApplication.freshCrashFilePresent()) {
            logToRing("Fresh crash file detected — routing to CrashReportActivity.");
            startActivity(new Intent(this, CrashReportActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.waylandie_activity_initializer);

        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);
        percentText = findViewById(R.id.percentText);
        logText = findViewById(R.id.logText);
        logScroll = findViewById(R.id.logScroll);
        btnContinue = findViewById(R.id.btnContinue);
        btnRetry = findViewById(R.id.btnRetry);
        btnSaveLogs = findViewById(R.id.btnSaveLogs);
        btnSkip = findViewById(R.id.btnSkip);

        // Make the log TextView scrollable and auto-scroll to bottom
        // when new lines are appended. (Mirrors HomeActivity.log()
        // behavior — see HomeActivity.java:552-559.)
        logText.setMovementMethod(new ScrollingMovementMethod());

        btnContinue.setOnClickListener(v -> onContinueClicked());
        btnRetry.setOnClickListener(v -> startExtraction());
        btnSaveLogs.setOnClickListener(v -> saveLogs());
        btnSkip.setOnClickListener(v -> goToHome());

        stateStore = new SetupStateStore(this);

        logToRing("EnvironmentInitializer.onCreate — state=" + stateStore.read());

        // If state is READY AND consistent with reality, skip straight
        // to Home. Otherwise run setup.
        if (stateStore.read() == SetupStateStore.State.READY
                && stateStore.isConsistentWithReality()) {
            logToRing("State READY + reality check passed — skipping setup, going to Home.");
            // Still probe before advancing — the user might have a
            // broken Vulkan driver even if the rootfs is fine.
            startExtraction();
        } else {
            startExtraction();
        }
    }

    /** Appends a line to the visible log AND the LogRingBuffer. */
    private void logToRing(String line) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(new Date());
        String entry = "[" + ts + "] " + line;
        LogRingBuffer.append(entry);
        Log.i(TAG, line);
        runOnUiThread(() -> {
            if (logText != null) {
                logText.append(entry + "\n");
                if (logScroll != null) {
                    logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
                }
            }
        });
    }

    private void onContinueClicked() {
        logToRing("Continue tapped — running nativeProbeCompositor() before advancing…");
        stateStore.markVerifying();
        btnContinue.setEnabled(false);
        btnContinue.setText("Verifying…");

        // Probe on a background thread so we don't ANR if vkCreateInstance
        // takes a while on a cold GPU driver.
        new Thread(() -> {
            String probeResult;
            try {
                // Force the native library to load BEFORE calling
                // nativeProbeCompositor(). Without this, the first
                // native method call would throw UnsatisfiedLinkError
                // because System.loadLibrary is only called from
                // MainActivity.loadNativeStatusText() (lazy). Calling
                // getNativeStatusText() here triggers the lazy load +
                // installs the native crash handler via JNI_OnLoad.
                MainActivity.getNativeStatusText();
                probeResult = MainActivity.nativeProbeCompositor();
            } catch (Throwable t) {
                probeResult = "fail: probe-threw " + t.getClass().getSimpleName()
                        + ": " + t.getMessage();
                LogRingBuffer.append("[EnvironmentInitializer.probe] " + t);
            }
            final String result = probeResult;
            runOnUiThread(() -> {
                logToRing("nativeProbeCompositor() -> " + result);
                if ("ok".equals(result)) {
                    stateStore.markReady();
                    logToRing("State -> READY. Advancing to HomeActivity.");
                    goToHome();
                } else {
                    stateStore.markFailed(result);
                    btnContinue.setEnabled(true);
                    btnContinue.setText("Retry probe");
                    statusText.setText("Compositor probe failed: " + result);
                    statusText.setTextColor(0xFFF87171);
                    logToRing("Probe failed — Continue refused. State -> FAILED.");
                }
            });
        }).start();
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

        // If already extracted AND up to date, jump straight to probe.
        if (imageFs.isUpToDate() && stateStore.read() == SetupStateStore.State.READY) {
            statusText.setText("Environment ready (v" + imageFs.getFormattedVersion() + ")");
            progressBar.setProgress(100);
            percentText.setText("100%");
            logToRing("ImageFs already up-to-date (v" + imageFs.getFormattedVersion()
                    + "). Ready for probe.");
            showContinueButton();
            return;
        }

        // If state is READY but ImageFs is NOT actually valid (interrupted
        // extraction, partial files), reset and re-extract.
        if (stateStore.read() == SetupStateStore.State.READY && !imageFs.isValid()) {
            logToRing("State says READY but ImageFs is invalid — wiping and re-extracting.");
            deleteRecursive(imageFs.getRootDir());
        }

        stateStore.markExtracting();
        logToRing("State -> EXTRACTING. Starting imagefs install…");

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
                            logToRing("Extraction finished successfully. Ready for probe.");
                            showContinueButton();
                        } else {
                            String reason = imageFs.getLastError() != null
                                    ? imageFs.getLastError()
                                    : "Extraction failed (unknown reason).";
                            stateStore.markFailed(reason);
                            showFailureButtons("Extraction failed. Check storage space "
                                    + "(need ~500 MB free) and retry.\n\nReason: " + reason);
                        }
                    });
                }
            });
            if (!ok) {
                runOnUiThread(() -> {
                    String reason = imageFs.getLastError() != null
                            ? imageFs.getLastError()
                            : "Extraction failed (unknown reason).";
                    stateStore.markFailed(reason);
                    showFailureButtons("Extraction failed. Tap Retry, Save Logs, or Skip.\n\n"
                            + "Reason: " + reason);
                });
            }
        }).start();
    }

    private void showContinueButton() {
        btnContinue.setText("Continue");
        btnContinue.setEnabled(true);
        btnContinue.setVisibility(View.VISIBLE);
        btnRetry.setVisibility(View.GONE);
        btnSaveLogs.setVisibility(View.GONE);
        btnSkip.setVisibility(View.GONE);
    }

    /**
     * Shows the Retry + Save Logs + Skip buttons when extraction fails.
     */
    private void showFailureButtons(String message) {
        statusText.setText(message);
        statusText.setTextColor(0xFFF87171);
        btnRetry.setVisibility(View.VISIBLE);
        btnSaveLogs.setVisibility(View.VISIBLE);
        btnSkip.setVisibility(View.VISIBLE);
        logToRing(message);
    }

    /**
     * Collects all diagnostic logs and saves to
     * {@code getExternalFilesDir(null)/logs/} (mirrors CrashHandler path).
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
}
