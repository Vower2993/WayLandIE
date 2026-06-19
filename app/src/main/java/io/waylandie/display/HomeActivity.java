package io.waylandie.display;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
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

import io.waylandie.display.runtime.environment.ProotRunner;
import io.waylandie.display.shared.util.LogCollector;

/**
 * HomeActivity — the main hub. Fully self-contained, no external dependencies.
 *
 * <p>Winlator/GameHub-inspired layout. Primary action is picking a
 * Windows .exe, which auto-launches through Proton via ProotRunner.
 */
public final class HomeActivity extends Activity {

    private static final String TAG = "WayLandIE/Home";
    private static final String BRIDGE_SOCKET = "waylandie.display.bridge.v1";
    private static final int PICK_EXE = 1001;

    private TextView selectedExePath;
    private CheckBox chkGamescope;
    private CheckBox chkUseProton;
    private Button btnPickExe;
    private Button btnLaunchGame;

    private View bridgeStatusDot;
    private TextView bridgeStatusText;
    private TextView nativeStatusText;
    private View controllerStatusDot;
    private TextView controllerStatusText;
    private View audioStatusDot;
    private TextView audioStatusText;
    private TextView envStatusText;

    private TextView logText;
    private Button btnLaunchSteam;
    private Button btnStartDisplay;
    private Button btnStopDisplay;
    private Button btnSettings;
    private Button btnSaveLogs;
    private Button btnAbout;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;

    private Uri pickedUri;
    private String pickedRealPath;
    private final List<String> logBuffer = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If WayLandIEApplication detected a fresh crash file, route to
        // CrashReportActivity BEFORE setContentView. This breaks the
        // relaunch-into-same-crash loop the user reported.
        if (WayLandIEApplication.freshCrashFilePresent()) {
            startActivity(new Intent(this, CrashReportActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_home);

        getWindow().setNavigationBarColor(0xFF0F0F12);
        getWindow().setStatusBarColor(0xFF0F0F12);

        bindViews();
        wireListeners();

        // Check MANAGE_EXTERNAL_STORAGE
        checkStoragePermission();
        updateNativeStatus();
        updateEnvStatus();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
        }
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
        envStatusText = findViewById(R.id.envStatusText);
        btnLaunchSteam = findViewById(R.id.btnLaunchSteam);
        btnStartDisplay = findViewById(R.id.btnStartDisplay);
        btnStopDisplay = findViewById(R.id.btnStopDisplay);
        btnSettings = findViewById(R.id.btnSettings);
        btnSaveLogs = findViewById(R.id.btnSaveLogs);
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
        btnAbout.setOnClickListener(v -> showAbout());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBridgeStatus();
        refreshControllerStatus();
        refreshAudioStatus();
        updateEnvStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // --- File picker ---
    private void openExePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/x-msdownload", "application/x-msdos-program",
                "application/x-msi", "application/vnd.ms-exe",
                "application/x-exe", "application/octet-stream",
                "application/zip", "application/x-7z-compressed"
        });
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
        try {
            getContentResolver().takePersistableUriPermission(
                    pickedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {}

        String displayName = queryDisplayName(pickedUri);
        pickedRealPath = resolveRealPath(displayName);
        String displayPath = pickedRealPath != null ? pickedRealPath
                : (displayName != null ? displayName : pickedUri.toString());
        selectedExePath.setText(displayPath);
        btnLaunchGame.setVisibility(View.VISIBLE);
        btnPickExe.setText("Pick a different .exe");
        log("Picked: " + displayPath);
        launchPickedGame();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return cursor.getString(idx);
            }
        } catch (RuntimeException ignored) {}
        return null;
    }

    private String resolveRealPath(String displayName) {
        if (pickedUri.getScheme() != null && pickedUri.getScheme().equals("file")) {
            return pickedUri.getPath();
        }
        if (displayName != null) {
            File guess = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), displayName);
            if (guess.exists()) return guess.getAbsolutePath();
            File guess2 = new File(Environment.getExternalStorageDirectory(),
                    "Download/WayLandIE/games/" + displayName);
            if (guess2.exists()) return guess2.getAbsolutePath();
        }
        return null;
    }

    // --- Game launch ---
    private void launchPickedGame() {
        if (pickedUri == null) { toast("Pick an .exe first."); return; }
        final String exePath = pickedRealPath != null ? pickedRealPath : copyToDownloadsThenReturn();
        if (exePath == null) {
            toast("Could not access the .exe. Try copying it into /sdcard/Download/ manually.");
            return;
        }

        final boolean gamescope = chkGamescope.isChecked();
        final boolean useProton = chkUseProton.isChecked();

        startDisplay();

        // Use WineRunner (glibc-native, no proot) for game launch.
        // Falls back to ProotRunner if WineRunner fails to initialize.
        io.waylandie.display.runtime.environment.WineRunner wineRunner =
                new io.waylandie.display.runtime.environment.WineRunner(this);
        if (!wineRunner.isReady()) {
            new AlertDialog.Builder(this)
                    .setTitle("Environment not ready")
                    .setMessage("The bundled Linux environment hasn't been extracted yet. "
                            + "Please reopen the app to initialize it.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        log("Launching " + exePath + " via WineRunner (glibc-native, gamescope="
                + gamescope + ", proton=" + useProton + ")…");
        try {
            String[] extraArgs = gamescope ? new String[]{"--gamescope"} : new String[0];
            Process p = wineRunner.execWine(exePath, extraArgs, useProton);
            log("Wine process started (pid=" + getPid(p) + ")");
        } catch (IOException error) {
            log("Launch failed: " + error.getMessage());
            toast("Launch failed: " + error.getMessage());
        }
    }

    private String copyToDownloadsThenReturn() {
        String displayName = queryDisplayName(pickedUri);
        if (displayName == null) displayName = "game.exe";
        File outDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "WayLandIE/games");
        if (!outDir.exists() && !outDir.mkdirs()) return null;
        File outFile = new File(outDir, displayName);
        try (InputStream in = getContentResolver().openInputStream(pickedUri);
             OutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException error) { return null; }
        pickedRealPath = outFile.getAbsolutePath();
        return pickedRealPath;
    }

    // --- Bridge display ---
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

    // --- Steam ---
    private void launchSteamSession() {
        ProotRunner runner = new ProotRunner(this);
        if (!runner.isReady()) { toast("Environment not ready. Initialize first."); return; }
        startDisplay();
        log("Launching Steam session via ProotRunner…");
        try {
            Process p = runner.exec("waylandie-steam-session start");
            log("Steam session started (pid=" + getPid(p) + ")");
        } catch (IOException error) {
            log("Steam launch failed: " + error.getMessage());
            toast("Steam launch failed: " + error.getMessage());
        }
    }

    // --- About ---
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

    // --- Save Logs ---
    private void saveLogs() {
        toast("Collecting logs…");
        new Thread(() -> {
            StringBuilder logBuf = new StringBuilder();
            for (String l : logBuffer) logBuf.append(l).append('\n');
            final File logFile = LogCollector.collect(HomeActivity.this, logBuf.toString());
            runOnUiThread(() -> {
                if (logFile != null && logFile.exists()) {
                    log("Logs saved to: " + logFile.getAbsolutePath());
                    new AlertDialog.Builder(HomeActivity.this)
                            .setTitle("Logs saved")
                            .setMessage("Log file saved to:\n"
                                    + logFile.getAbsolutePath()
                                    + "\n\nSize: " + logFile.length() + " bytes\n\n"
                                    + "Share it or find it in:\n"
                                    + "Files → Downloads → WayLandIE → logs")
                            .setPositiveButton("Share", (d, w) -> {
                                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                                shareIntent.setType("text/plain");
                                shareIntent.putExtra(Intent.EXTRA_SUBJECT, "WayLandIE log");
                                shareIntent.putExtra(Intent.EXTRA_STREAM,
                                        androidx.core.content.FileProvider.getUriForFile(
                                                HomeActivity.this,
                                                getPackageName() + ".fileprovider", logFile));
                                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(Intent.createChooser(shareIntent, "Share log"));
                            })
                            .setNegativeButton("OK", null)
                            .show();
                } else {
                    toast("Failed to save logs. Check storage permission.");
                }
            });
        }).start();
    }

    // --- Status probes ---
    private void refreshBridgeStatus() {
        new Thread(() -> {
            String status = probeBridge();
            boolean listening = status.equals(getString(R.string.home_bridge_status_listening));
            main.post(() -> {
                // Defensive null check — see updateEnvStatus() comment for rationale.
                if (bridgeStatusText == null || bridgeStatusDot == null) return;
                bridgeStatusText.setText(status);
                bridgeStatusDot.setBackgroundColor(listening ? 0xFF4ADE80 : 0xFF666674);
                log("bridge: " + status);
            });
        }).start();
    }

    private String probeBridge() {
        LocalSocket probe = null;
        try {
            probe = new LocalSocket();
            probe.connect(new LocalSocketAddress(BRIDGE_SOCKET,
                    LocalSocketAddress.Namespace.ABSTRACT));
            return getString(R.string.home_bridge_status_listening);
        } catch (IOException notListening) {
        } finally {
            if (probe != null) try { probe.close(); } catch (IOException ignored) {}
        }
        Socket tcpProbe = null;
        try {
            tcpProbe = new Socket();
            tcpProbe.connect(new InetSocketAddress("127.0.0.1", 57391), 200);
            return getString(R.string.home_bridge_status_listening);
        } catch (IOException tcpNotListening) {
            return getString(R.string.home_bridge_status_off);
        } finally {
            if (tcpProbe != null) try { tcpProbe.close(); } catch (IOException ignored) {}
        }
    }

    private void refreshControllerStatus() {
        // Defensive null check — see updateEnvStatus() comment for rationale.
        if (controllerStatusDot == null || controllerStatusText == null) return;
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
                    + (gamepads.size() > 1 ? " +" + (gamepads.size() - 1) : ""));
        }
    }

    private void refreshAudioStatus() {
        new Thread(() -> {
            boolean listening = false;
            Socket s = null;
            try {
                s = new Socket();
                s.connect(new InetSocketAddress("127.0.0.1", 57392), 200);
                listening = true;
            } catch (IOException ignored) {} finally {
                if (s != null) try { s.close(); } catch (IOException ignored) {}
            }
            final boolean finalListening = listening;
            main.post(() -> {
                // Defensive null check — see updateEnvStatus() comment for rationale.
                if (audioStatusDot == null || audioStatusText == null) return;
                if (finalListening) {
                    audioStatusDot.setBackgroundColor(0xFF4ADE80);
                    audioStatusText.setText("PulseAudio :57392");
                } else {
                    audioStatusDot.setBackgroundColor(0xFF666674);
                    audioStatusText.setText("off — starts with game");
                }
            });
        }).start();
    }

    private void updateEnvStatus() {
        // Defensive null check — envStatusText was missing from the layout
        // in a prior version, causing an NPE that crashed HomeActivity
        // immediately after the probe succeeded. Now bound in bindViews()
        // from R.id.envStatusText (added to activity_home.xml), but we keep
        // the null check so a future layout regression can't take down the
        // whole activity.
        if (envStatusText == null) return;
        io.waylandie.display.runtime.environment.ImageFsManager imageFs =
                new io.waylandie.display.runtime.environment.ImageFsManager(this);
        ProotRunner runner = new ProotRunner(this);
        if (imageFs.isValid()) {
            envStatusText.setText("Environment: ready (v" + imageFs.getFormattedVersion() + ")");
            envStatusText.setTextColor(0xFF4ADE80);
        } else if (imageFs.getRootDir().exists()) {
            envStatusText.setText("Environment: extracting… (root dir exists, "
                    + getDirSize(imageFs.getRootDir()) + " bytes)");
            envStatusText.setTextColor(0xFFFBBF24);
        } else {
            envStatusText.setText("Environment: not initialized");
            envStatusText.setTextColor(0xFFF87171);
        }
    }

    private void updateNativeStatus() {
        // Defensive null check — see updateEnvStatus() comment for rationale.
        if (nativeStatusText == null) return;
        try {
            String s = MainActivity.nativeStatus();
            nativeStatusText.setText(String.format(Locale.US, "native: %s", s));
            log("native: " + s);
        } catch (Throwable error) {
            // Previously this caught only UnsatisfiedLinkError. That missed
            // ExceptionInInitializerError (thrown when MainActivity's class
            // init fails — which is exactly the bug that caused the
            // Continue → force-close), NoClassDefFoundError,
            // OutOfMemoryError, StackOverflowError, etc.
            //
            // Catching Throwable here means: even if MainActivity's class
            // init blows up, HomeActivity stays alive long enough for the
            // global CrashHandler (installed by WayLandIEApplication) to
            // write a tombstone. The user sees "native: unavailable
            // <ExceptionClass>" instead of a force-close.
            String msg = "native: unavailable " + error.getClass().getSimpleName()
                    + ": " + error.getMessage();
            nativeStatusText.setText(msg);
            log(msg);
            try {
                io.waylandie.display.shared.util.LogRingBuffer.append(
                        "[HomeActivity.updateNativeStatus] " + error);
            } catch (Throwable ignored) {}
        }
    }

    // --- Permissions ---
    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !Environment.isExternalStorageManager()) {
            TextView body = new TextView(this);
            body.setText("WayLandIE needs \"All files access\" so it can read game .exe files "
                    + "from your Downloads folder.\n\n"
                    + "Tap Grant, then toggle \"Allow access to manage all files\" in Settings.");
            body.setPadding(48, 32, 48, 32);
            body.setTextColor(0xFFF5F5F7);
            body.setTextSize(13);
            body.setLineSpacing(0, 1.3f);
            new AlertDialog.Builder(this)
                    .setTitle("Storage permission required")
                    .setView(body)
                    .setPositiveButton("Grant", (d, w) -> {
                        try {
                            startActivity(new Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                    .setData(Uri.parse("package:" + getPackageName())));
                        } catch (RuntimeException e) {
                            try {
                                startActivity(new Intent(
                                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            } catch (RuntimeException ignored) {
                                toast("Open Settings → Apps → WayLandIE → Storage manually.");
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
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                    "WayLandIE:display");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire(8 * 60 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    private void copyToClipboard(String text) {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("WayLandIE", text));
    }

    private static String shellQuote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    private static long getPid(Process p) {
        try {
            java.lang.reflect.Method m = Process.class.getMethod("pid");
            Object result = m.invoke(p);
            if (result instanceof Long) return (Long) result;
            if (result instanceof Integer) return (Integer) result;
        } catch (Exception ignored) {}
        return -1;
    }

    private static long getDirSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long size = 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        for (File f : kids) {
            if (f.isDirectory()) size += getDirSize(f);
            else size += f.length();
        }
        return size;
    }

    private void log(String line) {
        String entry = String.format(Locale.US, "[%tT] %s", System.currentTimeMillis(), line);
        logBuffer.add(entry);
        if (logBuffer.size() > 200) logBuffer.remove(0);
        StringBuilder sb = new StringBuilder();
        for (String l : logBuffer) sb.append(l).append('\n');
        logText.setText(sb.toString());
    }
}
