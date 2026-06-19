package io.waylandie.display;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.PorterDuff;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HomeActivity — the main hub.
 *
 * <p>Winlator/GameHub-inspired layout. Primary action is picking a
 * Windows .exe from the file manager, which then auto-launches through
 * WaylandIE + Wine. A gamescope toggle on the homepage controls whether
 * the launch wraps in gamescope. A prominent Steam button boots
 * directly into Steam Big Picture.
 *
 * <p>Driver and wrapper installation moved to SettingsActivity
 * (accessible from the home screen).
 */
public final class HomeActivity extends Activity {

    private static final String TAG = "WayLandIE/Home";
    private static final String BRIDGE_SOCKET = "waylandie.display.bridge.v1";
    private static final int PICK_EXE = 1001;

    // UI: hero card
    private TextView selectedExePath;
    private CheckBox chkGamescope;
    private CheckBox chkUseProton;
    private Button btnPickExe;
    private Button btnLaunchGame;

    // UI: bridge status
    private View bridgeStatusDot;
    private TextView bridgeStatusText;
    private TextView nativeStatusText;

    // UI: controller status
    private View controllerStatusDot;
    private TextView controllerStatusText;

    // UI: audio status
    private View audioStatusDot;
    private TextView audioStatusText;

    // UI: log
    private TextView logText;

    // UI: secondary actions
    private Button btnLaunchSteam;
    private Button btnStartDisplay;
    private Button btnStopDisplay;
    private Button btnSettings;
    private Button btnSaveLogs;
    private Button btnSetupWizard;
    private Button btnOpenTerminal;
    private Button btnAbout;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;

    // The currently picked exe
    private Uri pickedUri;
    private String pickedRealPath;

    private final List<String> logBuffer = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        // Edge-to-edge dark UI.
        getWindow().setNavigationBarColor(0xFF0F0F12);
        getWindow().setStatusBarColor(0xFF0F0F12);

        bindViews();
        wireListeners();

        // Extract bundled install scripts to app storage + public Downloads.
        try {
            File root = AssetInstaller.installAssets(this);
            log("Extracted linux-runtime to " + root);
        } catch (IOException error) {
            log("Asset install failed: " + error.getMessage());
        }

        updateNativeStatus();

        // Request POST_NOTIFICATIONS so the keep-alive service can post its
        // foreground notification on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
        }

        // Check MANAGE_EXTERNAL_STORAGE — needed on Android 13+ to write
        // bundled scripts + staged driver archives into /sdcard/Download/.
        checkStoragePermission();
    }

    private void bindViews() {
        selectedExePath = findViewById(R.id.selectedExePath);
        chkGamescope = findViewById(R.id.chkGamescope);
        chkUseProton = findViewById(R.id.chkUseProton);
        btnPickExe = findViewById(R.id.btnPickExe);
        btnLaunchGame = findViewById(R.id.btnLaunchGame);
        bridgeStatusDot = findViewById(R.id.bridgeStatusDot);
        bridgeStatusText = findViewById(R.id.bridgeStatusText);
        nativeStatusText = findViewById(R.id.nativeStatusText);
        controllerStatusDot = findViewById(R.id.controllerStatusDot);
        controllerStatusText = findViewById(R.id.controllerStatusText);
        audioStatusDot = findViewById(R.id.audioStatusDot);
        audioStatusText = findViewById(R.id.audioStatusText);
        logText = findViewById(R.id.logText);
        btnLaunchSteam = findViewById(R.id.btnLaunchSteam);
        btnStartDisplay = findViewById(R.id.btnStartDisplay);
        btnStopDisplay = findViewById(R.id.btnStopDisplay);
        btnSettings = findViewById(R.id.btnSettings);
        btnSaveLogs = findViewById(R.id.btnSaveLogs);
        btnSetupWizard = findViewById(R.id.btnSetupWizard);
        btnOpenTerminal = findViewById(R.id.btnOpenTerminal);
        btnAbout = findViewById(R.id.btnAbout);
    }

    private void wireListeners() {
        btnPickExe.setOnClickListener(v -> openExePicker());
        btnLaunchGame.setOnClickListener(v -> launchPickedGame());
        btnLaunchSteam.setOnClickListener(v -> launchSteamSession());
        btnStartDisplay.setOnClickListener(v -> startDisplay());
        btnStopDisplay.setOnClickListener(v -> stopDisplay());
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        btnSaveLogs.setOnClickListener(v -> saveLogs());
        btnSetupWizard.setOnClickListener(v ->
                startActivity(new Intent(this, SetupWizardActivity.class)));
        btnOpenTerminal.setOnClickListener(v -> openTerminal());
        btnAbout.setOnClickListener(v -> showAbout());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBridgeStatus();
        refreshControllerStatus();
        refreshAudioStatus();
    }

    /**
     * Refreshes the controller status indicator. Detects any connected
     * gamepad/joystick via InputDevice. Sets the dot green and shows the
     * device name when one is connected.
     */
    private void refreshControllerStatus() {
        int[] deviceIds = InputDevice.getDeviceIds();
        List<String> gamepads = new ArrayList<>();
        for (int id : deviceIds) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev == null) continue;
            int sources = dev.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                gamepads.add(dev.getName());
            }
        }
        if (gamepads.isEmpty()) {
            controllerStatusDot.setBackgroundColor(0xFF666674);
            controllerStatusText.setText("none");
        } else {
            controllerStatusDot.setBackgroundColor(0xFF4ADE80);
            controllerStatusText.setText(gamepads.get(0)
                    + (gamepads.size() > 1 ? " +" + (gamepads.size() - 1) + " more" : ""));
        }
    }

    /**
     * Refreshes the audio status indicator. Checks whether the Termux-side
     * PulseAudio is listening on TCP 57392.
     */
    private void refreshAudioStatus() {
        new Thread(() -> {
            boolean listening = false;
            Socket s = null;
            try {
                s = new Socket();
                s.connect(new InetSocketAddress("127.0.0.1", 57392), 200);
                listening = true;
            } catch (IOException ignored) {
            } finally {
                if (s != null) {
                    try { s.close(); } catch (IOException ignored) {}
                }
            }
            final boolean finalListening = listening;
            main.post(() -> {
                if (finalListening) {
                    audioStatusDot.setBackgroundColor(0xFF4ADE80);
                    audioStatusText.setText("PulseAudio :57392");
                } else {
                    audioStatusDot.setBackgroundColor(0xFF666674);
                    audioStatusText.setText("off — run waylandie-audio start in Termux");
                }
            });
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    // ---------------------------------------------------------------
    // File picker — explicitly allows .exe + .msi + common archive types.
    // ---------------------------------------------------------------

    private void openExePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Use specific MIME types so the picker shows .exe files. Some
        // Android file managers hide .exe by default when the MIME filter
        // is too broad. We include application/x-ms-dos-program and
        // application/x-msi which are the canonical MIME types Windows
        // installers map to.
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/x-msdownload",
                "application/x-msdos-program",
                "application/x-msi",
                "application/vnd.ms-exe",
                "application/x-exe",
                "application/octet-stream",
                "application/zip",
                "application/x-7z-compressed",
                "application/x-rar-compressed"
        });
        // Allow picking multiple files in case the user wants a setup.exe
        // plus data files.
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);

        try {
            startActivityForResult(intent, PICK_EXE);
            log("Opening file picker…");
        } catch (RuntimeException error) {
            toast("No file picker available: " + error.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_EXE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            log("File picker cancelled.");
            return;
        }
        pickedUri = data.getData();
        // Take persistable read permission so we can read the file later
        // even if the activity is destroyed.
        try {
            getContentResolver().takePersistableUriPermission(
                    pickedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Not all providers support persistable permissions — copy
            // still works for the lifetime of this activity.
        }

        String displayName = queryDisplayName(pickedUri);
        pickedRealPath = resolveRealPath(displayName);
        String displayPath = pickedRealPath != null
                ? pickedRealPath
                : (displayName != null ? displayName : pickedUri.toString());
        selectedExePath.setText(displayPath);
        btnLaunchGame.setVisibility(View.VISIBLE);
        btnPickExe.setText("Pick a different .exe");
        log("Picked: " + displayPath);

        // Auto-launch immediately after pick — Winlator-style flow where
        // selecting a game launches it without an extra tap.
        launchPickedGame();
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

    /**
     * Tries to find a real filesystem path the Debian proot can read.
     * Falls back to null — caller should copy the file into the public
     * Downloads folder in that case.
     */
    private String resolveRealPath(String displayName) {
        if (pickedUri.getScheme() != null && pickedUri.getScheme().equals("file")) {
            return pickedUri.getPath();
        }
        if (displayName != null) {
            File guess = new File(
                    Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS),
                    displayName);
            if (guess.exists()) {
                return guess.getAbsolutePath();
            }
            File guess2 = new File(
                    Environment.getExternalStorageDirectory(),
                    "Download/WayLandIE/games/" + displayName);
            if (guess2.exists()) {
                return guess2.getAbsolutePath();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Game launch — fires waylandie-run-game inside Debian proot.
    // ---------------------------------------------------------------

    private void launchPickedGame() {
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

        final boolean gamescope = chkGamescope.isChecked();
        final boolean useProton = chkUseProton.isChecked();

        // Auto-start the display activity if not already running, so the
        // game has a surface to render into immediately.
        startDisplay();

        // Verify the self-contained environment is ready.
        io.waylandie.display.runtime.environment.ProotRunner runner =
                new io.waylandie.display.runtime.environment.ProotRunner(this);
        if (!runner.isReady()) {
            new AlertDialog.Builder(this)
                    .setTitle("Environment not ready")
                    .setMessage("The bundled Linux environment hasn't been extracted yet. "
                            + "Open WayLandIE from the launcher to initialize it first.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        // Build the wine command. We exec directly inside our bundled
        // rootfs via proot — no Termux needed.
        log("Launching " + exePath + " via ProotRunner (gamescope=" + gamescope
                + ", proton=" + useProton + ")…");

        try {
            String[] extraArgs = gamescope
                    ? new String[]{"--gamescope"} : new String[0];
            Process p = runner.execWine(exePath, extraArgs, useProton);
            log("Wine process started (pid=" + getPid(p) + ")");
            // Don't wait — let it run in the background.
        } catch (java.io.IOException error) {
            log("Launch failed: " + error.getMessage());
            toast("Launch failed: " + error.getMessage());
        }
    }

    private static long getPid(Process p) {
        // Process.pid() requires API 26+. Use reflection for older devices.
        try {
            java.lang.reflect.Method m = Process.class.getMethod("pid");
            Object result = m.invoke(p);
            if (result instanceof Long) return (Long) result;
            if (result instanceof Integer) return (Integer) result;
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * Copies the picked file (likely a SAF URI) into the public Downloads
     * folder so the bundled rootfs (proot) can read it via /sdcard bind.
     */
    private String copyToDownloadsThenReturn() {
        String displayName = queryDisplayName(pickedUri);
        if (displayName == null) {
            displayName = "game.exe";
        }
        File outDir = new File(
                Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS),
                "WayLandIE/games");
        if (!outDir.exists() && !outDir.mkdirs()) {
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
        try (InputStream in = getContentResolver().openInputStream(pickedUri);
             OutputStream out = new FileOutputStream(outFile)) {
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

    // ---------------------------------------------------------------
    // Bridge display start/stop
    // ---------------------------------------------------------------

    private void startDisplay() {
        log("Starting display activity + keep-alive service…");
        BridgeKeepAliveService.start(this);
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        acquireWakeLock();
        refreshBridgeStatus();
    }

    private void stopDisplay() {
        log("Stopping display activity + keep-alive service…");
        BridgeKeepAliveService.stop(this);
        releaseWakeLock();
        refreshBridgeStatus();
    }

    // ---------------------------------------------------------------
    // Steam session launch
    // ---------------------------------------------------------------

    private void launchSteamSession() {
        // Verify environment ready
        io.waylandie.display.runtime.environment.ProotRunner runner =
                new io.waylandie.display.runtime.environment.ProotRunner(this);
        if (!runner.isReady()) {
            toast("Environment not ready. Initialize first.");
            return;
        }
        // Start the display first so Steam has a surface to render into.
        startDisplay();
        log("Launching Steam session via ProotRunner…");
        try {
            Process p = runner.exec("waylandie-steam-session start");
            log("Steam session started (pid=" + getPid(p) + ")");
        } catch (java.io.IOException error) {
            log("Steam launch failed: " + error.getMessage());
            toast("Steam launch failed: " + error.getMessage());
        }
    }

    private void openTerminal() {
        // No external terminal — open the bundled rootfs shell via ProotRunner.
        // For now, just toast. A full in-app terminal is future work.
        toast("In-app terminal coming soon. Use the display activity to see game output.");
    }

    /**
     * Collects all diagnostic logs + system state and saves to
     * /sdcard/Download/WayLandIE/logs/waylandie-log-<timestamp>.txt.
     * Also offers to share via Android share intent.
     */
    private void saveLogs() {
        toast("Collecting logs…");
        new Thread(() -> {
            // Build the in-app log buffer string
            StringBuilder logBuf = new StringBuilder();
            for (String l : logBuffer) {
                logBuf.append(l).append('\n');
            }
            final File logFile = io.waylandie.display.shared.util.LogCollector
                    .collect(this, logBuf.toString());
            runOnUiThread(() -> {
                if (logFile != null && logFile.exists()) {
                    log("Logs saved to: " + logFile.getAbsolutePath());
                    // Offer to share
                    new AlertDialog.Builder(this)
                            .setTitle("Logs saved")
                            .setMessage("Log file saved to:\n"
                                    + logFile.getAbsolutePath()
                                    + "\n\nSize: " + logFile.length() + " bytes\n\n"
                                    + "Share it via email, message, or upload?")
                            .setPositiveButton("Share", (d, w) -> {
                                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                                shareIntent.setType("text/plain");
                                shareIntent.putExtra(Intent.EXTRA_SUBJECT,
                                        "WayLandIE diagnostic log");
                                shareIntent.putExtra(Intent.EXTRA_TEXT,
                                        "WayLandIE log file attached.");
                                // Use FileProvider for secure sharing
                                androidx.core.content.FileProvider.getUriForFile(
                                        this,
                                        getPackageName() + ".fileprovider",
                                        logFile);
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
                    toast("Failed to save logs. Check storage permission.");
                    log("Log save FAILED — check MANAGE_EXTERNAL_STORAGE permission");
                }
            });
        }).start();
    }

    private void showAbout() {
        TextView body = new TextView(this);
        body.setText(R.string.about_body);
        body.setPadding(48, 32, 48, 32);
        body.setTextColor(0xFFF5F5F7);
        body.setTextSize(13);
        body.setLineSpacing(0, 1.3f);
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setView(body)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------
    // Bridge status probe
    // ---------------------------------------------------------------

    private void refreshBridgeStatus() {
        new Thread(() -> {
            String status = probeBridge();
            boolean listening = status.equals(getString(R.string.home_bridge_status_listening));
            main.post(() -> {
                bridgeStatusText.setText(status);
                bridgeStatusDot.setBackgroundColor(listening
                        ? 0xFF4ADE80  // success green
                        : 0xFF666674); // text_tertiary grey
                log("bridge: " + status);
            });
        }).start();
    }

    private String probeBridge() {
        // First try the abstract socket — fastest check.
        LocalSocket probe = null;
        try {
            probe = new LocalSocket();
            LocalSocketAddress addr = new LocalSocketAddress(
                    BRIDGE_SOCKET,
                    LocalSocketAddress.Namespace.ABSTRACT);
            probe.connect(addr);
            return getString(R.string.home_bridge_status_listening);
        } catch (IOException notListening) {
            // Abstract socket not listening — fall through to TCP check.
        } finally {
            if (probe != null) {
                try { probe.close(); } catch (IOException ignored) {}
            }
        }
        // Fallback: check the TCP bridge port. MainActivity also listens
        // on 127.0.0.1:57391 in case abstract sockets fail to bind.
        Socket tcpProbe = null;
        try {
            tcpProbe = new Socket();
            tcpProbe.connect(new InetSocketAddress("127.0.0.1", 57391), 200);
            return getString(R.string.home_bridge_status_listening);
        } catch (IOException tcpNotListening) {
            return getString(R.string.home_bridge_status_off);
        } finally {
            if (tcpProbe != null) {
                try { tcpProbe.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void updateNativeStatus() {
        try {
            String s = MainActivity.nativeStatus();
            nativeStatusText.setText(String.format(Locale.US, "native: %s", s));
            log("native: " + s);
        } catch (UnsatisfiedLinkError error) {
            nativeStatusText.setText("native: libwaylandie_display_native.so not loaded");
            log("native lib not loaded: " + error.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // Permissions + wakelock
    // ---------------------------------------------------------------

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !Environment.isExternalStorageManager()) {
            TextView body = new TextView(this);
            body.setText("WayLandIE needs \"All files access\" so it can copy its "
                    + "Linux runtime scripts and your staged driver archives into "
                    + "/sdcard/Download/WayLandIE/ where Termux can read them.\n\n"
                    + "Tap Grant, then in Settings choose "
                    + "Apps > WayLandIE > Storage > All files and toggle it on. "
                    + "Then come back to this app.");
            body.setPadding(48, 32, 48, 32);
            body.setTextColor(0xFFF5F5F7);
            body.setTextSize(13);
            body.setLineSpacing(0, 1.3f);
            new AlertDialog.Builder(this)
                    .setTitle("Storage permission required")
                    .setView(body)
                    .setPositiveButton("Grant",
                            (d, w) -> {
                                try {
                                    Intent intent = new Intent(
                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                                    intent.setData(Uri.parse(
                                            "package:" + getPackageName()));
                                    startActivity(intent);
                                } catch (RuntimeException noSuchActivity) {
                                    try {
                                        startActivity(new Intent(
                                                Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                                    } catch (RuntimeException ignored) {
                                        toast("Open Settings > Apps > WayLandIE > Storage manually.");
                                    }
                                }
                            })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
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

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("WayLandIE command", text));
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

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
