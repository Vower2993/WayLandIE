package io.waylandie.display;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HomeActivity is the no-root entry point for WayLandIE.
 *
 * <p>It replaces the original MainActivity as the launcher activity. The
 * original MainActivity (which still exists, untouched) is the full-screen
 * Wayland presenter — it gets launched when the user taps "Start display",
 * and stays running as the visible surface the Linux side pushes dmabufs to.
 *
 * <p>HomeActivity provides:
 * <ul>
 *   <li>Live bridge status — checks whether the abstract socket
 *       {@code waylandie.display.bridge.v1} is currently listening.</li>
 *   <li>Start / Stop display — launches MainActivity and the
 *       BridgeKeepAliveService so the bridge stays up while the user is in
 *       Termux / proot / Steam.</li>
 *   <li>Launch Steam session — fires a Termux RUN_COMMAND intent that runs
 *       {@code waylandie-steam-session start} inside the Debian proot.</li>
 *   <li>Open Termux — convenience launcher for the Termux app.</li>
 *   <li>Setup wizard — first-run no-root setup.</li>
 *   <li>Driver slots — opens the driver slot manager UI.</li>
 *   <li>About — explains the zero-copy path.</li>
 * </ul>
 *
 * <p>The activity keeps a live bridge log (status changes + native callbacks)
 * so the user can see what's happening under the hood.
 */
public final class HomeActivity extends Activity {

    private static final String TAG = "WayLandIE/Home";
    private static final String BRIDGE_SOCKET = "waylandie.display.bridge.v1";

    private TextView bridgeStatusText;
    private TextView nativeStatusText;
    private TextView logText;
    private Button btnStartDisplay;
    private Button btnStopDisplay;
    private Button btnLaunchGame;
    private Button btnInstallDriver;
    private Button btnLaunchSteam;
    private Button btnOpenTerminal;
    private Button btnSetupWizard;
    private Button btnDriverSlots;
    private Button btnAbout;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private boolean displayStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        // Edge-to-edge dark UI.
        getWindow().setNavigationBarColor(0xFF0E0E10);
        getWindow().setStatusBarColor(0xFF0E0E10);

        bridgeStatusText = findViewById(R.id.bridgeStatusText);
        nativeStatusText = findViewById(R.id.nativeStatusText);
        logText = findViewById(R.id.logText);
        btnStartDisplay = findViewById(R.id.btnStartDisplay);
        btnStopDisplay = findViewById(R.id.btnStopDisplay);
        btnLaunchGame = findViewById(R.id.btnLaunchGame);
        btnInstallDriver = findViewById(R.id.btnInstallDriver);
        btnLaunchSteam = findViewById(R.id.btnLaunchSteam);
        btnOpenTerminal = findViewById(R.id.btnOpenTerminal);
        btnSetupWizard = findViewById(R.id.btnSetupWizard);
        btnDriverSlots = findViewById(R.id.btnDriverSlots);
        btnAbout = findViewById(R.id.btnAbout);

        btnStartDisplay.setOnClickListener(v -> startDisplay());
        btnStopDisplay.setOnClickListener(v -> stopDisplay());
        btnLaunchGame.setOnClickListener(v ->
                startActivity(new Intent(this, GameLauncherActivity.class)));
        btnInstallDriver.setOnClickListener(v ->
                startActivity(new Intent(this, DriverInstallerActivity.class)));
        btnLaunchSteam.setOnClickListener(v -> launchSteamSession());
        btnOpenTerminal.setOnClickListener(v -> openTerminal());
        btnSetupWizard.setOnClickListener(v ->
                startActivity(new Intent(this, SetupWizardActivity.class)));
        btnDriverSlots.setOnClickListener(v -> openDriverSlotsInTermux());
        btnAbout.setOnClickListener(v -> showAbout());

        // Extract bundled install scripts to app storage + public Downloads.
        // Safe to call on every launch — idempotent.
        try {
            File root = AssetInstaller.installAssets(this);
            log("Extracted linux-runtime to " + root);
        } catch (IOException error) {
            log("Asset install failed: " + error.getMessage());
        }

        // Try to read native status (calls into the JNI library if loaded).
        updateNativeStatus();

        // Request POST_NOTIFICATIONS so the keep-alive service can post its
        // foreground notification on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBridgeStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void startDisplay() {
        log("Starting display activity + keep-alive service…");
        BridgeKeepAliveService.start(this);
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        displayStarted = true;
        acquireWakeLock();
        refreshBridgeStatus();
    }

    private void stopDisplay() {
        log("Stopping display activity + keep-alive service…");
        BridgeKeepAliveService.stop(this);
        displayStarted = false;
        releaseWakeLock();
        refreshBridgeStatus();
    }

    private void launchSteamSession() {
        if (!TermuxBridge.isTermuxInstalled(this)) {
            showTermuxMissing();
            return;
        }
        String cmd = TermuxBridge.debianProotCommand(
                "waylandie-steam-session start");
        boolean sent = TermuxBridge.tryRunCommand(this, cmd);
        if (sent) {
            log("Sent Steam-session start to Termux.");
        } else {
            // Fallback: copy command + open Termux launcher.
            copyToClipboard(cmd);
            Toast.makeText(this,
                    "Termux:API not available. Command copied — paste into Termux.",
                    Toast.LENGTH_LONG).show();
            TermuxBridge.openTermuxLauncher(this);
            log("Termux:API missing. Command copied to clipboard.");
        }
    }

    private void openTerminal() {
        if (!TermuxBridge.isTermuxInstalled(this)) {
            showTermuxMissing();
            return;
        }
        TermuxBridge.openTermuxLauncher(this);
    }

    private void openDriverSlotsInTermux() {
        if (!TermuxBridge.isTermuxInstalled(this)) {
            showTermuxMissing();
            return;
        }
        String cmd = TermuxBridge.debianProotCommand(
                "waylandie-steam-profile list-profiles");
        boolean sent = TermuxBridge.tryRunCommand(this, cmd);
        if (!sent) {
            copyToClipboard(cmd);
            Toast.makeText(this, "Command copied — paste into Termux.",
                    Toast.LENGTH_LONG).show();
            TermuxBridge.openTermuxLauncher(this);
        }
    }

    private void showAbout() {
        TextView body = new TextView(this);
        body.setText(R.string.about_body);
        body.setPadding(48, 32, 48, 32);
        body.setTextColor(0xFFE8EAED);
        body.setTextSize(13);
        body.setLineSpacing(0, 1.3f);
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setView(body)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void refreshBridgeStatus() {
        new Thread(() -> {
            String status = probeBridge();
            main.post(() -> {
                bridgeStatusText.setText(status);
                log("bridge: " + status);
            });
        }).start();
    }

    /**
     * Probes whether the abstract socket is currently listening. Tries to
     * connect non-blocking; closes immediately. We don't actually speak the
     * protocol from here — this is just a liveness check.
     */
    private String probeBridge() {
        LocalSocket probe = null;
        try {
            probe = new LocalSocket();
            LocalSocketAddress addr = new LocalSocketAddress(
                    BRIDGE_SOCKET,
                    LocalSocketAddress.Namespace.ABSTRACT);
            probe.connect(addr);
            // Connected → something is listening.
            return getString(R.string.home_bridge_status_listening);
        } catch (IOException notListening) {
            return getString(R.string.home_bridge_status_off);
        } finally {
            if (probe != null) {
                try { probe.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void updateNativeStatus() {
        // MainActivity.nativeStatus is a static JNI entry point. It returns
        // "native-ok-arm64" if the lib loaded. If the lib is missing it
        // throws UnsatisfiedLinkError, which we catch.
        try {
            String s = MainActivity.nativeStatus();
            nativeStatusText.setText(String.format(Locale.US, "native: %s", s));
            log("native: " + s);
        } catch (UnsatisfiedLinkError error) {
            nativeStatusText.setText("native: libwaylandie_display_native.so not loaded");
            log("native lib not loaded: " + error.getMessage());
        }
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ON_AFTER_RELEASE,
                    "WayLandIE:display");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(8 * 60 * 60 * 1000L);  // 8 hours max
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void showTermuxMissing() {
        TextView body = new TextView(this);
        body.setText(R.string.termux_not_installed);
        body.setPadding(48, 32, 48, 32);
        body.setTextColor(0xFFE8EAED);
        body.setTextSize(13);
        body.setLineSpacing(0, 1.3f);
        new AlertDialog.Builder(this)
                .setTitle("Termux not installed")
                .setView(body)
                .setPositiveButton("Install",
                        (d, w) -> TermuxBridge.openTermuxInstallPage(this))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("WayLandIE command", text));
    }

    private final List<String> logBuffer = new ArrayList<>();

    private void log(String line) {
        long now = System.currentTimeMillis();
        String entry = String.format(Locale.US, "[%tT] %s", now, line);
        logBuffer.add(entry);
        if (logBuffer.size() > 200) {
            logBuffer.remove(0);
        }
        StringBuilder sb = new StringBuilder();
        for (String l : logBuffer) {
            sb.append(l).append('\n');
        }
        logText.setText(sb.toString());
    }
}
