package io.waylandie.display;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.LocalServerSocket;
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
import android.widget.ScrollView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
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
    private Button btnContainers;
    private Button btnSaveLogs;
    private Button btnAbout;
    private Button btnTaskManager;
    private Button btnDiagnostics;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;

    private Uri pickedUri;
    private String pickedRealPath;
    private final List<String> logBuffer = new ArrayList<>();

    // Task Manager — tracks the currently-running Wine/exe process so
    // the user can inspect its status and forcibly kill it from the
    // home screen. Both fields are reset (null / -1) when the process
    // is detected to have exited (via Refresh).
    private volatile Process runningWineProcess;
    private volatile int winePid = -1;

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
        btnContainers = findViewById(R.id.btnContainers);
        btnSaveLogs = findViewById(R.id.btnSaveLogs);
        btnAbout = findViewById(R.id.btnAbout);
        btnTaskManager = findViewById(R.id.btnTaskManager);
        btnDiagnostics = findViewById(R.id.btnDiagnostics);
    }

    private void wireListeners() {
        btnPickExe.setOnClickListener(v -> openExePicker());
        btnLaunchGame.setOnClickListener(v -> launchPickedGame());
        btnLaunchSteam.setOnClickListener(v -> launchSteamSession());
        btnStartDisplay.setOnClickListener(v -> startDisplay());
        btnStopDisplay.setOnClickListener(v -> stopDisplay());
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        btnContainers.setOnClickListener(v ->
                startActivity(new Intent(this, ContainerListActivity.class)));
        btnSaveLogs.setOnClickListener(v -> saveLogs());
        btnAbout.setOnClickListener(v -> showAbout());
        btnTaskManager.setOnClickListener(v -> showTaskManager());
        btnDiagnostics.setOnClickListener(v -> runDiagnostics());
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
        // Kill Wine process when the activity is destroyed. This prevents
        // socket "Address already in use" errors on the next launch — the
        // bridge holds the abstract Unix socket (waylandie.display.bridge.v1)
        // and TCP port 57391 until it exits.
        try {
            if (runningWineProcess != null && runningWineProcess.isAlive()) {
                runningWineProcess.destroyForcibly();
                android.util.Log.i("HomeActivity", "Killed Wine process in onDestroy");
            }
        } catch (Throwable t) {
            android.util.Log.w("HomeActivity", "Wine process cleanup threw: " + t.getMessage());
        }
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

        // CRITICAL: Run the launch on a BACKGROUND THREAD.
        //
        // We just called startDisplay() which fires startForegroundService().
        // Android 14+ gives the service 5 SECONDS from that call to call
        // startForeground(). The service's onCreate() runs on the MAIN thread,
        // so if we do heavy work on the main thread right after startDisplay()
        // (WineRunner does: file checks, adrenotools sync, bridge spawn +
        // 2s socket wait, Wine spawn), the service's onCreate() is blocked
        // from running → 5s window expires → ForegroundServiceDidNotStartInTimeException.
        //
        // Moving launch to a background thread lets the service's onCreate()
        // run immediately and call startForeground() within milliseconds.
        final String exePathFinal = exePath;
        final boolean gamescopeFinal = gamescope;
        final boolean useProtonFinal = useProton;
        new Thread(() -> {
            try {
                // CRITICAL: Wait for the Android bridge socket to be listening
                // BEFORE launching Wine. The bionic bridge connects to the
                // Android display bridge via abstract socket
                // "waylandie.display.bridge.v1". If the Android side isn't
                // listening yet, the bridge gets ECONNREFUSED (errno 111)
                // and the game never displays.
                //
                // The Android bridge starts in MainActivity.onCreate() via
                // BridgeLocalServer. On a cold start or activity recreation,
                // this can take 10-20 seconds. We poll every 500ms for up to
                // 30 seconds.
                final String BRIDGE_SOCKET = "waylandie.display.bridge.v1";
                boolean bridgeReady = false;
                runOnUiThread(() -> log("Waiting for Android bridge socket to be ready…"));
                for (int i = 0; i < 60; i++) {  // 60 × 500ms = 30s max
                    try {
                        android.net.LocalSocket probe = new android.net.LocalSocket();
                        probe.connect(new android.net.LocalSocketAddress(
                                BRIDGE_SOCKET,
                                android.net.LocalSocketAddress.Namespace.ABSTRACT));
                        probe.close();
                        bridgeReady = true;
                        final int ms = i * 500;
                        runOnUiThread(() -> log("Android bridge socket ready (after " + ms + "ms)"));
                        break;
                    } catch (IOException notReady) {
                        // Not ready yet — wait and retry
                    }
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {
                        break;
                    }
                }
                if (!bridgeReady) {
                    runOnUiThread(() -> log("WARNING: Android bridge socket NOT ready after 30s — "
                            + "launching anyway (game may not display)"));
                }

                String[] extraArgs = gamescopeFinal ? new String[]{"--gamescope"} : new String[0];
                io.waylandie.display.runtime.environment.GameLaunchTracer tracer =
                        new io.waylandie.display.runtime.environment.GameLaunchTracer(HomeActivity.this);
                Process p = tracer.launchAndTrace(exePathFinal, extraArgs, useProtonFinal);
                runningWineProcess = p;
                winePid = (int) getPid(p);
                runOnUiThread(() -> log("Wine process started (pid=" + winePid + ")"));
            } catch (IOException error) {
                runOnUiThread(() -> {
                    log("Launch failed: " + error.getMessage());
                    toast("Launch failed: " + error.getMessage());
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    log("Launch failed (unexpected): " + error.getClass().getSimpleName()
                            + ": " + error.getMessage());
                    toast("Launch failed: " + error.getMessage());
                });
            }
        }, "wl-game-launch").start();
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
        // CRITICAL: Enable the Wayland bridge server. Without this extra,
        // MainActivity.startBridgeControlServerIfNeeded() early-returns at
        // line 1054, the TCP 57391 listener and abstract socket
        // "waylandie.display.bridge.v1" are never bound, and Wine has no
        // Wayland display to connect to. This was the root cause of
        // "bridge: off" in every diagnostic log.
        intent.putExtra("waylandie_bridge_server", true);
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

    // --- Task Manager (running Wine/exe process status) ---
    /**
     * Shows the Task Manager dialog. Lists the currently-running Wine
     * process (if any), its PID, and whether it is still alive. Offers
     * Kill Process + Refresh actions inline; Close is the dialog's
     * positive button. If no process has been launched since activity
     * creation (or the last reset), shows a "not running" state with
     * only the Close button.
     */
    private void showTaskManager() {
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Task Manager")
                .setPositiveButton("Close", null)
                .create();
        // Body is a LinearLayout whose children we swap on Refresh / Kill
        // — we can't call setView() after show(), but we CAN clear and
        // repopulate the existing container, which is what
        // refreshTaskManagerBody() does.
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(48, 32, 48, 32);
        dialog.setView(body);
        dialog.show();
        refreshTaskManagerBody(body, dialog);
    }

    /**
     * Populates (or re-populates) the Task Manager dialog body with the
     * current process state. Called once on dialog show, then again on
     * every Kill / Refresh tap so the user sees up-to-date status.
     */
    private void refreshTaskManagerBody(final LinearLayout body, final AlertDialog dialog) {
        body.removeAllViews();

        final boolean hasProcess = runningWineProcess != null;
        final boolean alive = hasProcess && runningWineProcess.isAlive();

        TextView status = new TextView(this);
        status.setTextColor(0xFFF5F5F7);
        status.setTextSize(13);
        status.setLineSpacing(0, 1.3f);
        if (hasProcess) {
            status.setText("Wine process: Running (PID: " + winePid + ")\n"
                    + "Status: " + (alive ? "Alive" : "Exited"));
        } else {
            status.setText("Wine process: Not running\n"
                    + "No game is currently running.");
        }
        body.addView(status);

        if (hasProcess) {
            // Inline button row: [Kill Process] [Refresh]
            // Close is the dialog's positive button (rendered separately
            // by AlertDialog at the bottom-right).
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = 24;
            row.setLayoutParams(rowLp);

            Button kill = new Button(this);
            kill.setText("Kill Process");
            LinearLayout.LayoutParams killLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            killLp.rightMargin = 8;
            kill.setLayoutParams(killLp);
            kill.setEnabled(alive);
            kill.setOnClickListener(v -> {
                if (runningWineProcess != null && runningWineProcess.isAlive()) {
                    runningWineProcess.destroyForcibly();
                    log("Killed Wine process (pid=" + winePid + ") via Task Manager");
                    toast("Kill signal sent to pid " + winePid);
                } else {
                    toast("Process already exited.");
                }
                refreshTaskManagerBody(body, dialog);
            });
            row.addView(kill);

            Button refresh = new Button(this);
            refresh.setText("Refresh");
            LinearLayout.LayoutParams refLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            refLp.leftMargin = 8;
            refresh.setLayoutParams(refLp);
            refresh.setOnClickListener(v -> refreshTaskManagerBody(body, dialog));
            row.addView(refresh);

            body.addView(row);
        }
    }

    // --- Diagnostics (deep pre-flight check) ---
    private void runDiagnostics() {
        log("Running deep pre-flight diagnostics…");
        io.waylandie.display.shared.util.LogRingBuffer.append("[Diag] Running deep pre-flight diagnostics…");

        new Thread(() -> {
            try {
            StringBuilder results = new StringBuilder();
            int passed = 0, failed = 0, warned = 0;

            io.waylandie.display.runtime.environment.ImageFsManager imageFs =
                    new io.waylandie.display.runtime.environment.ImageFsManager(this);
            File rootDir = imageFs.getRootDir();
            String nativeLibDir = getApplicationInfo().nativeLibraryDir;
            File filesDir = getFilesDir();

            results.append("=== WayLandIE Deep Pre-Flight Diagnostics ===\n\n");

            // === 1. ROOTFS ===
            results.append("--- ROOTFS ---\n");
            if (imageFs.isValid()) {
                results.append("✓ Rootfs valid (v" + imageFs.getVersion() + ")\n");
                results.append("  Path: " + rootDir.getAbsolutePath() + "\n");
                results.append("  Size: " + (rootDir.length() / 1024 / 1024) + " MB\n");
                passed++;
            } else {
                results.append("✗ Rootfs INVALID\n");
                results.append(imageFs.describeValidity() + "\n");
                failed++;
            }

            // === 2. GLIBC LINKER — ACTUALLY EXECUTE it to test SELinux ===
            results.append("\n--- GLIBC LINKER (execve test) ---\n");
            File linker = new File(nativeLibDir, "libld_glibc.so");
            if (!linker.exists()) {
                results.append("⚠ libld_glibc.so NOT FOUND — will fall back to proot\n");
                warned++;
            } else {
                results.append("✓ libld_glibc.so found: " + linker + "\n");
                results.append("  Size: " + linker.length() + " bytes\n");
                if (!linker.canExecute()) {
                    results.append("✗ NOT executable (x bit missing on extracted .so)\n");
                    failed++;
                } else {
                    // ACTUALLY execute `libld_glibc.so /bin/echo test` to confirm
                    // SELinux allows execve on the extracted .so AND the glibc
                    // runtime works. With the bionic bridge as the primary path,
                    // this is a fallback check — the glibc linker is only used
                    // for the glibc bridge variant and for Wine itself.
                    try {
                        File echoBin = new File(rootDir, "usr/bin/echo");
                        String libPath = new File(rootDir, "usr/lib").getAbsolutePath() + ":"
                                + new File(rootDir, "usr/lib/aarch64-linux-gnu").getAbsolutePath();
                        ProcessBuilder pb = new ProcessBuilder(
                                linker.getAbsolutePath(),
                                "--library-path", libPath,
                                echoBin.getAbsolutePath(),
                                "waylandie-linker-ok");
                        pb.redirectErrorStream(true);
                        pb.environment().clear();
                        pb.environment().put("LD_LIBRARY_PATH", libPath);
                        Process p = pb.start();
                        java.io.BufferedReader br = new java.io.BufferedReader(
                                new java.io.InputStreamReader(p.getInputStream()));
                        StringBuilder out = new StringBuilder();
                        String l;
                        while ((l = br.readLine()) != null) out.append(l).append('\n');
                        boolean done = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                        int exit = done ? p.exitValue() : -1;
                        if (!done) p.destroyForcibly();
                        if (done && exit == 0 && out.toString().contains("waylandie-linker-ok")) {
                            results.append("✓ SELinux ALLOWED execve of libld_glibc.so\n");
                            results.append("✓ glibc linker executes /bin/echo successfully (exit=0)\n");
                            passed++;
                        } else {
                            results.append("✗ libld_glibc.so executed but FAILED (exit=" + exit + ")\n");
                            results.append("  Output: " + out.toString().trim() + "\n");
                            results.append("  Native launch will FAIL — check glibc version + seccomp.\n");
                            failed++;
                        }
                    } catch (Exception e) {
                        results.append("✗ SELinux BLOCKED execve: " + e.getMessage() + "\n");
                        results.append("  Native launch will fail — proot fallback required.\n");
                        failed++;
                    }
                }
            }

            // === 3. BRIDGE TRANSLATOR — launch test (bionic preferred, glibc fallback) ===
            results.append("\n--- BRIDGE TRANSLATOR (launch test) ---\n");
            // Determine which bridge to test: bionic (preferred) or glibc (fallback).
            // The bionic bridge (libwaylandie_bridge.so) is compiled with NDK
            // against bionic libc — no glibc SIGSYS issue. The glibc bridge
            // (usr/local/bin/waylandie-wayland-bridge) is the legacy fallback.
            File bionicBridgeForLaunch = new File(nativeLibDir, "libwaylandie_bridge.so");
            File glibcBridgeBin = new File(rootDir, "usr/local/bin/waylandie-wayland-bridge");
            File socketFile = new File(rootDir, "tmp/diag-socket.txt");
            try { socketFile.getParentFile().mkdirs(); socketFile.delete(); } catch (Exception ignored) {}

            // Build the --library-path (same set WineRunner uses) for glibc fallback
            File protonDirProbe = new File(filesDir, "contents/proton/active");
            String glibcLibPath = new File(rootDir, "usr/lib").getAbsolutePath() + ":"
                    + new File(rootDir, "usr/lib/aarch64-linux-gnu").getAbsolutePath() + ":"
                    + new File(rootDir, "usr/local/lib").getAbsolutePath() + ":"
                    + new File(protonDirProbe, "lib").getAbsolutePath() + ":"
                    + new File(protonDirProbe, "files/lib").getAbsolutePath();

            // Bridge requires 8 args (argc=9):
            //   argv[1]=bridge_socket  argv[2]=target_commits  argv[3]=socket_file
            //   argv[4]=timeout_ms     argv[5]=clear_ahb       argv[6]=accept_client
            //   argv[7]=output_width   argv[8]=output_height
            // Without all 8, the bridge prints a usage message to stderr and exits(2).
            int dispW = 2688, dispH = 1216;
            try {
                android.graphics.Point sz = new android.graphics.Point();
                getWindowManager().getDefaultDisplay().getRealSize(sz);
                dispW = Math.max(sz.x, sz.y);
                dispH = Math.min(sz.x, sz.y);
            } catch (Exception ignored) {}

            List<String> cmd = new ArrayList<>();
            String bridgeType;
            if (bionicBridgeForLaunch.exists()) {
                // Bionic bridge (PREFERRED): launch directly — no linker, no
                // LD_LIBRARY_PATH, no LD_PRELOAD shim, no GLIBC_TUNABLES.
                // Compiled with NDK against bionic libc — no glibc SIGSYS issue.
                bionicBridgeForLaunch.setExecutable(true, false);
                cmd.add(bionicBridgeForLaunch.getAbsolutePath());
                bridgeType = "bionic";
            } else if (glibcBridgeBin.exists() && linker.exists()) {
                // Glibc bridge (FALLBACK): launch via libld_glibc.so.
                // Used when the bionic bridge isn't in the APK.
                glibcBridgeBin.setExecutable(true, false);
                cmd.add(linker.getAbsolutePath());
                cmd.add("--library-path");
                cmd.add(glibcLibPath);
                cmd.add(glibcBridgeBin.getAbsolutePath());
                bridgeType = "glibc";
            } else {
                results.append("✗ No bridge binary available (bionic bridge not built, glibc bridge missing)\n");
                results.append("  Wine will have no Wayland display to render to.\n");
                failed++;
                bridgeType = null;
            }

            if (bridgeType != null) {
                cmd.add("waylandie.display.bridge.v1");   // argv[1]
                cmd.add("1");                              // argv[2] target_commits
                cmd.add(socketFile.getAbsolutePath());     // argv[3] socket file
                cmd.add("5000");                           // argv[4] timeout_ms
                cmd.add("0");                              // argv[5] clear_ahb_outside
                cmd.add("0");                              // argv[6] accept_client_complete
                cmd.add(String.valueOf(dispW));            // argv[7] output_width
                cmd.add(String.valueOf(dispH));            // argv[8] output_height

                try {
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.directory(rootDir);
                    pb.redirectErrorStream(true);
                    // Env vars the bridge needs (both bionic and glibc).
                    // Without XDG_RUNTIME_DIR, wl_display_add_socket_auto() fails
                    // and the bridge crashes with no output.
                    File runtimeDir = new File(rootDir, "usr/tmp/runtime");
                    if (!runtimeDir.exists()) runtimeDir.mkdirs();
                    File tmpDir = new File(rootDir, "usr/tmp");
                    pb.environment().clear();
                    pb.environment().put("HOME", new File(rootDir, "home/xuser").getAbsolutePath());
                    pb.environment().put("PATH", new File(rootDir, "usr/bin").getAbsolutePath() + ":"
                            + new File(rootDir, "usr/local/bin").getAbsolutePath());
                    pb.environment().put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());
                    pb.environment().put("WAYLAND_DISPLAY", "waylandie");
                    pb.environment().put("WAYLANDIE_BRIDGE_SOCKET", "waylandie.display.bridge.v1");
                    pb.environment().put("WAYLANDIE_BRIDGE_PORT", "57391");
                    pb.environment().put("WAYLANDIE_FINAL_COPY", "forbidden");
                    pb.environment().put("TMPDIR", tmpDir.getAbsolutePath());
                    pb.environment().put("LANG", "en_US.UTF-8");
                    // Glibc bridge needs LD_LIBRARY_PATH; bionic bridge doesn't.
                    if (bridgeType.equals("glibc")) {
                        pb.environment().put("LD_LIBRARY_PATH", glibcLibPath);
                    }
                    Process bridge = pb.start();
                    results.append("  Launched " + bridgeType + " bridge (8 args, output=" + dispW + "x" + dispH + ")\n");

                    // Drain ALL output in a reader thread (avoids race where
                    // process exits before we read buffered data).
                    StringBuilder bridgeOut = new StringBuilder();
                    Thread reader = new Thread(() -> {
                        try (java.io.BufferedReader br = new java.io.BufferedReader(
                                new java.io.InputStreamReader(bridge.getInputStream()))) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                bridgeOut.append(line).append('\n');
                            }
                        } catch (java.io.IOException e) {
                            bridgeOut.append("[read error: ").append(e.getMessage()).append("]\n");
                        }
                    }, "wl-diag-bridge-reader");
                    reader.start();

                    // Wait up to 6s for the bridge to exit (bridge has 5s timeout)
                    boolean exited = bridge.waitFor(6, java.util.concurrent.TimeUnit.SECONDS);
                    if (!exited) {
                        bridge.destroyForcibly();
                        bridge.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
                    }
                    // Wait for reader to finish draining
                    reader.join(1000);

                    int exitCode = -1;
                    try { exitCode = bridge.exitValue(); } catch (Exception ignored) {}

                    boolean ready = bridgeOut.toString().contains("server=ready");

                    if (bridgeOut.length() == 0) {
                        results.append("✗ Bridge produced NO output — likely crashed on launch\n");
                        results.append("  Exit code: " + exitCode + "\n");
                        results.append("  Check LD_LIBRARY_PATH / missing libs.\n");
                        failed++;
                    } else {
                        String snippet = bridgeOut.toString();
                        if (snippet.length() > 800) snippet = snippet.substring(0, 800);
                        results.append("  Bridge output (exit=" + exitCode + ", first 800 chars):\n");
                        for (String ol : snippet.split("\n")) {
                            if (!ol.isEmpty()) results.append("    " + ol + "\n");
                        }
                        if (ready) {
                            results.append("✓ Bridge reported 'server=ready'\n");
                            passed++;
                        } else if (exitCode == 2) {
                            results.append("✗ Bridge exited with code 2 (usage error — wrong arg count)\n");
                            failed++;
                        } else {
                            results.append("✗ Bridge did NOT report 'server=ready' (exit=" + exitCode + ")\n");
                            failed++;
                        }
                    }
                    // Check if the socket file was written by the bridge
                    if (socketFile.exists() && socketFile.length() > 0) {
                        String sock = new String(
                                java.nio.file.Files.readAllBytes(socketFile.toPath())).trim();
                        results.append("✓ Socket file written: '" + sock + "'\n");
                        passed++;
                    } else {
                        results.append("✗ Socket file NOT written (" + socketFile + ")\n");
                        failed++;
                    }
                    try { socketFile.delete(); } catch (Exception ignored) {}
                } catch (Exception e) {
                    results.append("✗ Bridge launch failed: " + e.getMessage() + "\n");
                    failed++;
                }
            }

            // Test A: Bionic bridge launch test — verifies the bionic-compiled
            // bridge (libwaylandie_bridge.so) can execute without SIGSYS.
            // The bionic bridge is compiled with NDK against bionic libc,
            // eliminating the glibc seccomp issues. If it exists and runs
            // (even with usage error exit=2), the seccomp problem is solved.
            results.append("Test A: Bionic bridge launch test:\n");
            File bionicBridgeBin = new File(nativeLibDir, "libwaylandie_bridge.so");
            if (bionicBridgeBin.exists()) {
                try {
                    bionicBridgeBin.setExecutable(true, false);
                    // Launch with no args — bridge should print usage and exit(2)
                    ProcessBuilder bpb = new ProcessBuilder(bionicBridgeBin.getAbsolutePath());
                    bpb.redirectErrorStream(true);
                    Process bproc = bpb.start();
                    StringBuilder bOut = new StringBuilder();
                    Thread bReader = new Thread(() -> {
                        try (java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(bproc.getInputStream()))) {
                            String l;
                            while ((l = r.readLine()) != null) bOut.append(l).append('\n');
                        } catch (Exception ignored) {}
                    }, "wl-diag-bionic-bridge");
                    bReader.start();
                    boolean bExited = bproc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                    if (!bExited) bproc.destroyForcibly();
                    bReader.join(1000);
                    int bExit = -1;
                    try { bExit = bproc.exitValue(); } catch (Exception ignored) {}

                    if (bExit == 2) {
                        // Usage error = bridge reached main() and checked argc.
                        // This means bionic startup + all library constructors succeeded.
                        results.append("  ✓ BIONIC BRIDGE WORKS! (exit=2 = usage error = reached main)\n");
                        results.append("  → No SIGSYS — bionic eliminates the seccomp issue\n");
                        results.append("  → Bridge can now create Wayland socket + accept Wine connections\n");
                        passed++;
                    } else if (bExit == 159) {
                        results.append("  ✗ Bionic bridge killed by SIGSYS (exit=159)\n");
                        results.append("  → Bionic bridge also triggers seccomp — deeper issue\n");
                        failed++;
                    } else if (bExit == 0) {
                        // Bridge ran and exited cleanly (maybe timeout with 0 commits)
                        results.append("  ✓ Bionic bridge ran (exit=0)\n");
                        results.append("  → No SIGSYS — bionic works!\n");
                        passed++;
                    } else {
                        String snippet = bOut.toString().trim();
                        if (snippet.length() > 300) snippet = snippet.substring(0, 300);
                        results.append("  ⚠ Bionic bridge exit=").append(bExit).append("\n");
                        results.append("  Output: ").append(snippet).append("\n");
                        warned++;
                    }
                } catch (Exception e) {
                    results.append("  ⚠ Bionic bridge test failed: " + e.getMessage() + "\n");
                    warned++;
                }
            } else {
                results.append("Test A: SKIP (libwaylandie_bridge.so not found in nativeLibDir)\n");
                results.append("  → Bionic bridge not built — using glibc bridge (SIGSYS expected)\n");
                warned++;
            }

            // === 4. PROTON + WINE ===
            results.append("\n--- PROTON + WINE ---\n");
            File protonDir = new File(filesDir, "contents/proton/active");
            File wineBin = null;
            if (protonDir.exists()) {
                for (String p : new String[]{"files/bin/wine", "dist/bin/wine", "bin/wine"}) {
                    File f = new File(protonDir, p);
                    if (f.exists()) { wineBin = f; break; }
                }
            }
            if (!protonDir.exists() || wineBin == null) {
                results.append("✗ Proton NOT INSTALLED (wine binary missing)\n");
                results.append("  Install Proton FIRST — DXVK install alone won't work.\n");
                failed++;
            } else {
                results.append("✓ Wine binary: " + wineBin.getAbsolutePath() + "\n");
                results.append("  Size: " + wineBin.length() + " bytes\n");
                passed++;
                // Read ELF architecture (LITTLE-ENDIAN: lo=byte[18], hi=byte[19])
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(wineBin, "r")) {
                    byte[] magic = new byte[20];
                    raf.readFully(magic);
                    if (magic[0] == 0x7f && magic[1] == 'E'
                            && magic[2] == 'L' && magic[3] == 'F') {
                        int lo = magic[18] & 0xFF;
                        int hi = magic[19] & 0xFF;
                        int eMachine = (hi << 8) | lo;   // little-endian
                        if (eMachine == 183) {
                            results.append("✓ Architecture: ARM64 (e_machine=183) — native arm64ec launch\n");
                            passed++;
                        } else if (eMachine == 62) {
                            results.append("⚠ Architecture: x86_64 (e_machine=62) — needs box64/emulation\n");
                            warned++;
                        } else {
                            results.append("⚠ Architecture: unknown (e_machine=" + eMachine + ")\n");
                            warned++;
                        }
                    } else {
                        results.append("✗ Not an ELF file (magic mismatch)\n");
                        failed++;
                    }
                } catch (Exception e) {
                    results.append("✗ Could not read ELF header: " + e.getMessage() + "\n");
                    failed++;
                }
                // Check Proton lib/ directory
                File protonLib = new File(protonDir, "lib");
                if (!protonLib.isDirectory()) protonLib = new File(protonDir, "files/lib");
                if (protonLib.isDirectory()) {
                    File[] sos = protonLib.listFiles(
                            (d, n) -> n.contains(".so"));
                    int soCount = sos != null ? sos.length : 0;
                    results.append("✓ Proton lib/ directory present (" + soCount + " .so files)\n");
                    passed++;
                } else {
                    results.append("✗ Proton lib/ directory MISSING at " + protonLib + "\n");
                    failed++;
                }
                // Check prefixPack.txz exists (proves the Proton package ships a prefix)
                File prefixPack = new File(protonDir, "prefixPack.txz");
                if (prefixPack.exists()) {
                    results.append("✓ prefixPack.txz present ("
                            + (prefixPack.length() / 1024 / 1024) + " MB)\n");
                    passed++;
                } else {
                    results.append("⚠ prefixPack.txz not found in Proton slot (consumed during install?)\n");
                    warned++;
                }
            }

            // === 5. DXVK (DLLs in Wine prefix) ===
            results.append("\n--- DXVK (Wine prefix check) ---\n");
            File wineSystem32 = new File(rootDir, "home/xuser/.wine/drive_c/windows/system32");
            File d3d11 = new File(wineSystem32, "d3d11.dll");
            if (d3d11.exists()) {
                results.append("✓ d3d11.dll in Wine prefix system32/ ("
                        + d3d11.length() + " bytes)\n");
                passed++;
            } else {
                results.append("✗ d3d11.dll NOT in Wine prefix — DXVK won't work\n");
                results.append("  Reinstall DXVK to copy DLLs to prefix.\n");
                failed++;
            }

            // === 6. TURNIP ===
            results.append("\n--- TURNIP (Vulkan ICD) ---\n");
            File icdJson = new File(rootDir, "usr/local/etc/vulkan/icd.d/freedreno_icd.json");
            if (!icdJson.exists()) {
                results.append("✗ ICD JSON NOT FOUND at " + icdJson + "\n");
                failed++;
            } else {
                String json = new String(
                        java.nio.file.Files.readAllBytes(icdJson.toPath()));
                if (json.contains("/usr/local/lib/libvulkan_freedreno.so")) {
                    results.append("✓ ICD JSON points to /usr/local/lib/ (correct for native)\n");
                    passed++;
                } else if (json.contains("/opt/turnip/")) {
                    results.append("✗ ICD JSON points to /opt/turnip/ (proot path — WRONG for native)\n");
                    results.append("  Reinstall Turnip to fix ICD JSON path.\n");
                    failed++;
                } else {
                    results.append("⚠ ICD JSON has unexpected path:\n  " + json.trim() + "\n");
                    warned++;
                }
            }
            File soInRootfs = new File(rootDir, "usr/local/lib/libvulkan_freedreno.so");
            if (soInRootfs.exists()) {
                results.append("✓ libvulkan_freedreno.so in rootfs/usr/local/lib/ ("
                        + soInRootfs.length() + " bytes)\n");
                passed++;
            } else {
                results.append("✗ libvulkan_freedreno.so NOT in rootfs/usr/local/lib/\n");
                failed++;
            }

            // === 7. FEX ===
            results.append("\n--- FEX (arm64ec host override) ---\n");
            File fexDll = new File(wineSystem32, "libarm64ecfex.dll");
            if (fexDll.exists()) {
                results.append("✓ libarm64ecfex.dll in Wine prefix system32/ ("
                        + fexDll.length() + " bytes)\n");
                passed++;
            } else {
                results.append("⚠ libarm64ecfex.dll NOT in Wine prefix (FEX not installed?)\n");
                warned++;
            }

            // === 8. KEY LIBRARIES PATHS ===
            results.append("\n--- KEY LIBRARIES (Debian multiarch) ---\n");
            String[] keyLibs = {
                "usr/lib/aarch64-linux-gnu/libwayland-server.so.0",
                "usr/lib/aarch64-linux-gnu/libwayland-client.so.0",
                "usr/lib/aarch64-linux-gnu/libX11.so.6",
                "usr/lib/aarch64-linux-gnu/libfreetype.so.6",
                "usr/lib/aarch64-linux-gnu/libvulkan.so.1",
                "usr/lib/aarch64-linux-gnu/libdl.so.2",
                "usr/lib/aarch64-linux-gnu/libdl.so",
                "usr/lib/ld-linux-aarch64.so.1"
            };
            for (String lib : keyLibs) {
                File libFile = new File(rootDir, lib);
                if (libFile.exists()) {
                    String note = "";
                    if (lib.endsWith("/libdl.so")) {
                        // unversioned symlink — exists() follows symlinks, so this
                        // confirms the symlink resolves to a real target.
                        note = " (unversioned symlink ok)";
                    }
                    results.append("✓ " + lib + " (" + libFile.length() + " bytes)" + note + "\n");
                    passed++;
                } else {
                    results.append("✗ " + lib + " MISSING\n");
                    if (lib.endsWith("/libdl.so")) {
                        results.append("    Unversioned libdl.so symlink missing — glibc not fully installed.\n");
                    }
                    failed++;
                }
            }

            // === 9. ANDROID BRIDGE TCP PROBE ===
            results.append("\n--- ANDROID BRIDGE (TCP 57391) ---\n");
            Socket tcpProbe = null;
            try {
                tcpProbe = new Socket();
                tcpProbe.connect(new InetSocketAddress("127.0.0.1", 57391), 300);
                results.append("✓ Android bridge listening on TCP 57391\n");
                passed++;
            } catch (IOException notListening) {
                results.append("✗ Android bridge NOT listening on TCP 57391\n");
                results.append("  Start the display/bridge from the home screen first.\n");
                failed++;
            } finally {
                if (tcpProbe != null) try { tcpProbe.close(); } catch (IOException ignored) {}
            }

            // === 9b. ANDROID BRIDGE ABSTRACT SOCKET DEEP DIAGNOSTIC ===
            // The Android-side LocalServerSocket "waylandie.display.bridge.v1"
            // is what the Linux bridge connects to. If it can't bind, NOTHING
            // will display even if Wine + Linux bridge are working perfectly.
            // Previous logs showed "BindException: Address already in use" —
            // this section diagnoses exactly why.
            results.append("\n--- ANDROID BRIDGE ABSTRACT SOCKET (deep) ---\n");
            final String ABSTRACT_SOCKET = "waylandie.display.bridge.v1";
            try {
                // 1. Try to CONNECT as a client — if it succeeds, a server IS
                //    listening (good or stale — we'll find out next).
                LocalSocket probeClient = null;
                boolean serverIsListening = false;
                try {
                    probeClient = new LocalSocket();
                    probeClient.connect(new LocalSocketAddress(ABSTRACT_SOCKET,
                            LocalSocketAddress.Namespace.ABSTRACT));
                    serverIsListening = true;
                } catch (IOException notListening) {
                    results.append("  Connect probe: ✗ no server listening ("
                            + notListening.getMessage() + ")\n");
                } finally {
                    if (probeClient != null) try { probeClient.close(); } catch (IOException ignored) {}
                }
                if (serverIsListening) {
                    results.append("  Connect probe: ✓ a server IS listening on \""
                            + ABSTRACT_SOCKET + "\"\n");
                    // 2. Try to BIND a NEW server socket — if this fails with
                    //    BindException, the existing server is holding it.
                    //    If the existing server is OURS (from this app), this
                    //    is normal. If it's STALE (from a crashed/killed
                    //    previous instance), this is the bug.
                    LocalServerSocket bindTest = null;
                    try {
                        bindTest = new LocalServerSocket(ABSTRACT_SOCKET);
                        // If we get here, no one was holding it — but our
                        // connect probe said someone WAS listening. That's a
                        // race; treat as success.
                        results.append("  Bind probe: ✓ new bind succeeded (no holder)\n");
                        results.append("  → Socket state: HEALTHY (was temporarily free)\n");
                        passed++;
                    } catch (IOException bindErr) {
                        results.append("  Bind probe: ✗ new bind FAILED ("
                                + bindErr.getClass().getSimpleName() + ": "
                                + bindErr.getMessage() + ")\n");
                        results.append("  → Socket state: HELD by an existing server\n");
                        // 3. Read /proc/net/unix to find what process holds it.
                        //    Android usually blocks this (the existing diagnostic
                        //    shows "grep: /proc/net/unix: Permission denied"),
                        //    but try anyway — some Android versions allow it.
                        try (java.io.BufferedReader br = new java.io.BufferedReader(
                                new java.io.InputStreamReader(
                                    new java.io.FileInputStream("/proc/net/unix")))) {
                            String line;
                            int matches = 0;
                            while ((line = br.readLine()) != null && matches < 5) {
                                if (line.contains(ABSTRACT_SOCKET)) {
                                    results.append("  /proc/net/unix match: " + line.trim() + "\n");
                                    matches++;
                                }
                            }
                            if (matches == 0) {
                                results.append("  /proc/net/unix: no matches (file readable but socket not listed)\n");
                            }
                        } catch (IOException | SecurityException procErr) {
                            results.append("  /proc/net/unix: ✗ not readable ("
                                    + procErr.getClass().getSimpleName() + ") — needs root\n");
                        }
                        // 4. Check if our own app process is the holder
                        int myPid = android.os.Process.myPid();
                        results.append("  This app PID: " + myPid + "\n");
                        results.append("  → If a previous app instance was force-killed, the socket\n");
                        results.append("    may be held by a zombie thread. Force-stop the app from\n");
                        results.append("    Android Settings → Apps → WayLandIE → Force stop, then\n");
                        results.append("    relaunch. If that fixes it, the bug is in our lifecycle\n");
                        results.append("    cleanup (BridgeLocalServer.stop() may not be killing the\n");
                        results.append("    accept thread before the new instance tries to bind).\n");
                        failed++;
                    } finally {
                        if (bindTest != null) try { bindTest.close(); } catch (IOException ignored) {}
                    }
                } else {
                    // No server listening — that's OK for pre-launch, but
                    // it means MainActivity hasn't started its bridge yet.
                    results.append("  → Socket state: NOT BOUND (start display first)\n");
                    // Verify we CAN bind (i.e., no stale holder)
                    LocalServerSocket bindTest = null;
                    try {
                        bindTest = new LocalServerSocket(ABSTRACT_SOCKET);
                        results.append("  Bind probe: ✓ new bind succeeded (no stale holder)\n");
                        passed++;
                    } catch (IOException bindErr) {
                        results.append("  Bind probe: ✗ new bind FAILED ("
                                + bindErr.getMessage() + ")\n");
                        results.append("  → STALE HOLDER: socket is held but no server is accepting.\n");
                        results.append("    This is the worst case — a zombie process holds the socket\n");
                        results.append("    but won't accept connections. Force-stop the app from\n");
                        results.append("    Android Settings, then relaunch.\n");
                        failed++;
                    } finally {
                        if (bindTest != null) try { bindTest.close(); } catch (IOException ignored) {}
                    }
                }
            } catch (Exception socketDiagErr) {
                results.append("  Socket diagnostic failed: "
                        + socketDiagErr.getClass().getSimpleName() + ": "
                        + socketDiagErr.getMessage() + "\n");
            }

            // === 10. PROOT FALLBACK ===
            results.append("\n--- PROOT FALLBACK ---\n");
            File prootBin = new File(nativeLibDir, "libproot.so");
            if (prootBin.exists()) {
                results.append("✓ libproot.so available as fallback\n");
                passed++;
            } else {
                results.append("✗ libproot.so NOT FOUND — no fallback if native fails\n");
                failed++;
            }

            // === 11. INSTALL ORDER CHECK ===
            // NOTE: kernel32.dll / ntdll.dll are NOT in the Wine prefix's system32/.
            // Wine provides built-in DLLs from its lib/wine/ directory at runtime.
            // The prefix only needs: system.reg (Wine registry) + drive_c/windows/
            // structure. Checking for kernel32.dll in the prefix was a false negative.
            results.append("\n--- INSTALL ORDER (prefixPack vs DXVK) ---\n");
            File winePrefixDir = new File(rootDir, "home/xuser/.wine");
            File systemReg = new File(winePrefixDir, "system.reg");
            File prefixSystem32 = new File(winePrefixDir, "drive_c/windows/system32");
            boolean hasPrefix = systemReg.exists() && prefixSystem32.isDirectory();
            boolean hasDxvk = d3d11.exists();

            // Also verify Wine's built-in DLLs exist in lib/wine/
            File protonLibWine = new File(protonDir, "lib/wine");
            if (!protonLibWine.isDirectory()) protonLibWine = new File(protonDir, "files/lib/wine");
            boolean hasWineBuiltinDlls = false;
            if (protonLibWine.isDirectory()) {
                // Wine stores built-in DLLs in subdirs like x86_64-windows/, aarch64-windows/, arm64ec/
                File[] wineSubdirs = protonLibWine.listFiles(File::isDirectory);
                if (wineSubdirs != null) {
                    for (File sd : wineSubdirs) {
                        File k32 = new File(sd, "kernel32.dll");
                        if (k32.exists()) { hasWineBuiltinDlls = true; break; }
                    }
                }
            }

            if (hasPrefix && hasDxvk) {
                results.append("✓ Wine prefix valid (system.reg + system32/) AND DXVK (d3d11) installed\n");
                results.append("  Install order correct: Proton (prefixPack) first → DXVK.\n");
                passed++;
            } else if (hasDxvk && !hasPrefix) {
                results.append("✗ DXVK installed but Wine prefix INVALID (system.reg or system32/ missing)\n");
                results.append("  prefixPack.txz was NOT properly unpacked into the Wine prefix.\n");
                results.append("  system.reg exists: " + systemReg.exists() + "\n");
                results.append("  system32/ exists: " + prefixSystem32.isDirectory() + "\n");
                results.append("  → REINSTALL PROTON FIRST (unpacks prefixPack), then reinstall DXVK.\n");
                failed++;
            } else if (!hasPrefix && !hasDxvk) {
                results.append("✗ Wine prefix is EMPTY — no system.reg, no DXVK\n");
                results.append("  Install Proton (which unpacks prefixPack.txz) before anything else.\n");
                failed++;
            } else {
                // Prefix present, DXVK pending — warning, not error
                results.append("⚠ Wine prefix valid but no d3d11.dll (DXVK not yet installed)\n");
                warned++;
            }
            // Report Wine built-in DLL status
            if (hasWineBuiltinDlls) {
                results.append("✓ Wine built-in DLLs (kernel32.dll) found in " + protonLibWine + "\n");
                passed++;
            } else {
                results.append("⚠ Wine built-in DLLs (kernel32.dll) NOT found in " + protonLibWine + "\n");
                results.append("  Wine may still provide them at runtime — this is informational.\n");
                warned++;
            }

            // === SUMMARY ===
            results.append("\n=== SUMMARY ===\n");
            results.append("Passed: " + passed + "\n");
            results.append("Failed: " + failed + "\n");
            results.append("Warnings: " + warned + "\n");
            if (failed == 0) {
                results.append("\n✓ ALL CRITICAL CHECKS PASSED — ready to launch\n");
            } else {
                results.append("\n✗ " + failed + " CRITICAL ISSUES — fix before launching\n");
            }

            final String report = results.toString();
            for (String line : report.split("\n")) {
                android.util.Log.i("WayLandIE/Diag", line);
                io.waylandie.display.shared.util.LogRingBuffer.append("[Diag] " + line);
            }

            runOnUiThread(() -> {
                // Show in a scrollable dialog
                TextView tv = new TextView(this);
                tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                tv.setTextSize(10);
                tv.setText(report);
                int pad = (int) (getResources().getDisplayMetrics().density * 16);
                tv.setPadding(pad, pad, pad, pad);
                ScrollView scroll = new ScrollView(this);
                scroll.addView(tv);
                new AlertDialog.Builder(this)
                        .setTitle("Deep Pre-Flight Diagnostics")
                        .setView(scroll)
                        .setPositiveButton("OK", null)
                        .setNegativeButton("Copy", (d, w) -> {
                            android.content.ClipboardManager clip =
                                    (android.content.ClipboardManager)
                                            getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("diag", report));
                            toast("Diagnostics copied to clipboard");
                        })
                        .show();
                log("Diagnostics complete: " + report.substring(report.indexOf("=== SUMMARY ===")));
            });
            } catch (Exception e) {
                android.util.Log.e("WayLandIE/Diag", "Diagnostics failed", e);
                io.waylandie.display.shared.util.LogRingBuffer.append("[Diag] ERROR: " + e.getMessage());
                runOnUiThread(() -> toast("Diagnostics error: " + e.getMessage()));
            }
        }, "wl-diagnostics").start();
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
