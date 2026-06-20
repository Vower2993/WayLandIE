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
    private Process runningWineProcess;
    private int winePid = -1;

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
            runningWineProcess = p;
            winePid = (int) getPid(p);
            log("Wine process started (pid=" + winePid + ")");
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
                    // runtime works (no SIGSYS from seccomp).
                    // NOTE: We can't use `--version` because glibc 2.31's linker
                    // treats it as a program name when invoked standalone.
                    // Instead, we run /bin/echo (which links libc) and read the
                    // glibc version from libc.so.6 strings.
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

                            // Read glibc version from libc.so.6 strings
                            File libcSo = new File(rootDir, "usr/lib/aarch64-linux-gnu/libc.so.6");
                            String glibcVersion = "";
                            if (libcSo.exists()) {
                                try {
                                    java.io.RandomAccessFile raf = new java.io.RandomAccessFile(libcSo, "r");
                                    byte[] data = new byte[(int) Math.min(raf.length(), 2 * 1024 * 1024)];
                                    raf.readFully(data);
                                    raf.close();
                                    String content = new String(data, java.nio.charset.StandardCharsets.US_ASCII);
                                    // Look for "GLIBC_2.XX" or "release version 2.XX"
                                    java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                                            "(?:GLIBC_|release version )(\\d+\\.\\d+)");
                                    java.util.regex.Matcher m = pat.matcher(content);
                                    double maxVer = 0;
                                    while (m.find()) {
                                        try {
                                            double v = Double.parseDouble(m.group(1));
                                            if (v > maxVer) maxVer = v;
                                        } catch (Exception ignored) {}
                                    }
                                    if (maxVer > 0) {
                                        glibcVersion = String.valueOf(maxVer);
                                        results.append("  glibc version: " + glibcVersion + "\n");
                                        // Check version safety
                                        if (maxVer >= 2.34) {
                                            results.append("  ✗ FATAL: glibc " + glibcVersion + " is too new!\n");
                                            results.append("    glibc 2.34+ calls clone3/rseq → SIGSYS (exit 159) on Android\n");
                                            results.append("    Need glibc < 2.34. Rebuild rootfs with Ubuntu 20.04 Focal.\n");
                                            failed++;
                                        } else {
                                            results.append("  ✓ glibc " + glibcVersion + " is safe (< 2.34 — no seccomp SIGSYS)\n");
                                            passed++;
                                        }
                                    }
                                } catch (Exception e) {
                                    results.append("  ⚠ Could not read glibc version: " + e.getMessage() + "\n");
                                    warned++;
                                }
                            }
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

            // === 3. BRIDGE TRANSLATOR — ACTUALLY LAUNCH it with 6 args ===
            results.append("\n--- BRIDGE TRANSLATOR (launch test) ---\n");
            File bridgeBin = new File(rootDir, "usr/local/bin/waylandie-wayland-bridge");
            if (!bridgeBin.exists()) {
                results.append("✗ Bridge binary NOT FOUND at " + bridgeBin + "\n");
                results.append("  Wine will have no Wayland display to render to.\n");
                results.append("  Rebuild rootfs with libc6-dev + wayland-scanner.\n");
                failed++;
            } else if (!linker.exists()) {
                results.append("⚠ Bridge binary present but libld_glibc.so missing — cannot launch\n");
                warned++;
            } else {
                results.append("✓ Bridge binary found: " + bridgeBin.length() + " bytes\n");
                bridgeBin.setExecutable(true, false);
                // Build the --library-path (same set WineRunner uses)
                File protonDirProbe = new File(filesDir, "contents/proton/active");
                String libPath = new File(rootDir, "usr/lib").getAbsolutePath() + ":"
                        + new File(rootDir, "usr/lib/aarch64-linux-gnu").getAbsolutePath() + ":"
                        + new File(rootDir, "usr/local/lib").getAbsolutePath() + ":"
                        + new File(protonDirProbe, "lib").getAbsolutePath() + ":"
                        + new File(protonDirProbe, "files/lib").getAbsolutePath();
                File socketFile = new File(rootDir, "tmp/diag-socket.txt");
                try { socketFile.getParentFile().mkdirs(); socketFile.delete(); } catch (Exception ignored) {}

                // --- ldd probe: use LD_TRACE_LOADED_OBJECTS=1 to list dependencies ---
                // This catches "error while loading shared libraries" BEFORE the
                // bridge launch, giving us a clear error message instead of a
                // silent crash.
                // NOTE: We can't use 'linker --list bridge' because glibc 2.31's
                // ld-linux treats --list as a program name (same bug as --version).
                // Instead, set LD_TRACE_LOADED_OBJECTS=1 env var which makes the
                // linker print library dependencies and exit (this is what ldd does).
                try {
                    List<String> lddCmd = new ArrayList<>();
                    lddCmd.add(linker.getAbsolutePath());
                    lddCmd.add("--library-path");
                    lddCmd.add(libPath);
                    lddCmd.add(bridgeBin.getAbsolutePath());
                    ProcessBuilder lddPb = new ProcessBuilder(lddCmd);
                    lddPb.redirectErrorStream(true);
                    lddPb.environment().clear();
                    lddPb.environment().put("LD_LIBRARY_PATH", libPath);
                    lddPb.environment().put("LD_TRACE_LOADED_OBJECTS", "1");
                    Process lddProc = lddPb.start();
                    StringBuilder lddOut = new StringBuilder();
                    java.io.BufferedReader lddReader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(lddProc.getInputStream()));
                    String lddLine;
                    while ((lddLine = lddReader.readLine()) != null) {
                        lddOut.append(lddLine).append('\n');
                    }
                    lddProc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                    lddProc.destroyForcibly();
                    String lddStr = lddOut.toString();
                    if (lddStr.contains("not found")) {
                        results.append("✗ ldd probe found MISSING libraries:\n");
                        for (String l : lddStr.split("\n")) {
                            if (l.contains("not found")) {
                                results.append("    ").append(l.trim()).append("\n");
                            }
                        }
                        failed++;
                    } else {
                        results.append("✓ ldd probe: all shared libraries resolved\n");
                        passed++;
                    }
                } catch (Exception e) {
                    results.append("⚠ ldd probe failed: " + e.getMessage() + "\n");
                    warned++;
                }

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
                cmd.add(linker.getAbsolutePath());
                cmd.add("--library-path");
                cmd.add(libPath);
                cmd.add(bridgeBin.getAbsolutePath());
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
                    // CRITICAL: Set env vars the bridge needs.
                    // Without XDG_RUNTIME_DIR, wl_display_add_socket_auto() fails
                    // and the bridge crashes with no output.
                    File runtimeDir = new File(rootDir, "usr/tmp/runtime");
                    if (!runtimeDir.exists()) runtimeDir.mkdirs();
                    File tmpDir = new File(rootDir, "usr/tmp");
                    pb.environment().clear();
                    pb.environment().put("HOME", new File(rootDir, "home/xuser").getAbsolutePath());
                    pb.environment().put("PATH", new File(rootDir, "usr/bin").getAbsolutePath() + ":"
                            + new File(rootDir, "usr/local/bin").getAbsolutePath());
                    pb.environment().put("LD_LIBRARY_PATH", libPath);
                    pb.environment().put("XDG_RUNTIME_DIR", runtimeDir.getAbsolutePath());
                    pb.environment().put("WAYLAND_DISPLAY", "waylandie");
                    pb.environment().put("WAYLANDIE_BRIDGE_SOCKET", "waylandie.display.bridge.v1");
                    pb.environment().put("WAYLANDIE_BRIDGE_PORT", "57391");
                    pb.environment().put("WAYLANDIE_FINAL_COPY", "forbidden");
                    pb.environment().put("TMPDIR", tmpDir.getAbsolutePath());
                    pb.environment().put("LANG", "en_US.UTF-8");
                    // CRITICAL: Disable glibc rseq to avoid SIGSYS from seccomp.
                    // glibc 2.35+ calls rseq() during __libc_start_main() (before
                    // main). Android's seccomp blocks rseq() → SIGSYS → exit 159.
                    pb.environment().put("GLIBC_TUNABLES", "glibc.pthread.rseq=0");
                    // LD_PRELOAD syscall shim — intercepts blocked syscalls from
                    // libwayland-server/libvulkan constructors (Test F finding)
                    File diagShim = new File(rootDir, "usr/local/lib/libwaylandie_shim.so");
                    if (diagShim.exists()) {
                        pb.environment().put("LD_PRELOAD", diagShim.getAbsolutePath());
                        results.append("  (with LD_PRELOAD shim)\n");
                    }
                    Process bridge = pb.start();
                    results.append("  Launched bridge via linker (8 args, output=" + dispW + "x" + dispH + ")\n");

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
                        results.append("  Check LD_LIBRARY_PATH / missing libs (see ldd probe above).\n");
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

            // === 3b. SIGSYS DIAGNOSIS ===
            // If the bridge crashed with exit 159 (SIGSYS from seccomp), run
            // additional tests to isolate the blocked syscall.
            results.append("\n--- SIGSYS DIAGNOSIS (exit 159 = signal 31 = blocked syscall) ---\n");
            File echoBin = new File(rootDir, "usr/bin/echo");
            File runtimeDirDiag = new File(rootDir, "usr/tmp/runtime");
            if (!runtimeDirDiag.exists()) runtimeDirDiag.mkdirs();
            File tmpDirDiag = new File(rootDir, "usr/tmp");
            // Rebuild libPath (same as bridge section — needed because libPath
            // was scoped to the bridge else-block above)
            File protonDirProbeSig = new File(filesDir, "contents/proton/active");
            String sigsysLibPath = new File(rootDir, "usr/lib").getAbsolutePath() + ":"
                    + new File(rootDir, "usr/lib/aarch64-linux-gnu").getAbsolutePath() + ":"
                    + new File(rootDir, "usr/local/lib").getAbsolutePath() + ":"
                    + new File(protonDirProbeSig, "lib").getAbsolutePath() + ":"
                    + new File(protonDirProbeSig, "files/lib").getAbsolutePath();

            // Test A: Minimal glibc binary WITHOUT rseq disable
            // /bin/echo only links libc — if this crashes, glibc's own
            // startup is making a blocked syscall (likely rseq).
            if (echoBin.exists() && linker.exists()) {
                results.append("Test A: /bin/echo via linker (NO rseq disable):\n");
                try {
                    List<String> echoCmd = new ArrayList<>();
                    echoCmd.add(linker.getAbsolutePath());
                    echoCmd.add("--library-path");
                    echoCmd.add(sigsysLibPath);
                    echoCmd.add(echoBin.getAbsolutePath());
                    echoCmd.add("hello-sigsys-test");
                    ProcessBuilder echoPb = new ProcessBuilder(echoCmd);
                    echoPb.directory(rootDir);
                    echoPb.redirectErrorStream(true);
                    echoPb.environment().clear();
                    echoPb.environment().put("LD_LIBRARY_PATH", sigsysLibPath);
                    echoPb.environment().put("HOME", new File(rootDir, "home/xuser").getAbsolutePath());
                    echoPb.environment().put("TMPDIR", tmpDirDiag.getAbsolutePath());
                    echoPb.environment().put("PATH", new File(rootDir, "usr/bin").getAbsolutePath() + ":"
                            + new File(rootDir, "usr/local/bin").getAbsolutePath());
                    // NOTE: GLIBC_TUNABLES intentionally NOT set — testing default behavior
                    Process echoProc = echoPb.start();
                    StringBuilder echoOut = new StringBuilder();
                    Thread echoReader = new Thread(() -> {
                        try (java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(echoProc.getInputStream()))) {
                            String l;
                            while ((l = r.readLine()) != null) echoOut.append(l).append('\n');
                        } catch (Exception ex) {
                            echoOut.append("[read error: ").append(ex.getMessage()).append("]\n");
                        }
                    }, "wl-diag-echo-a");
                    echoReader.start();
                    boolean echoExited = echoProc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                    if (!echoExited) echoProc.destroyForcibly();
                    echoReader.join(1000);
                    int echoExit = -1;
                    try { echoExit = echoProc.exitValue(); } catch (Exception ignored) {}
                    if (echoExit == 0 && echoOut.toString().contains("hello-sigsys-test")) {
                        results.append("  ✓ /bin/echo succeeded (exit=0) — glibc runtime OK without rseq disable\n");
                        results.append("  → SIGSYS is bridge-specific (library constructor or early syscall)\n");
                    } else if (echoExit == 159) {
                        results.append("  ✗ /bin/echo killed by SIGSYS (exit=159) — glibc startup itself is blocked\n");
                        results.append("  → Likely rseq() syscall (334) blocked by Android seccomp\n");
                        failed++;
                    } else {
                        results.append("  ⚠ /bin/echo exit=" + echoExit + " output='" + echoOut.toString().trim() + "'\n");
                        warned++;
                    }
                } catch (Exception e) {
                    results.append("  ⚠ /bin/echo test failed: " + e.getMessage() + "\n");
                    warned++;
                }
            } else {
                results.append("Test A: SKIP (/bin/echo or linker not found)\n");
            }

            // Test B: Minimal glibc binary WITH rseq disable
            // If Test A crashed but Test B works → rseq is confirmed as the culprit.
            if (echoBin.exists() && linker.exists()) {
                results.append("Test B: /bin/echo via linker (WITH rseq disable):\n");
                try {
                    List<String> echoCmd = new ArrayList<>();
                    echoCmd.add(linker.getAbsolutePath());
                    echoCmd.add("--library-path");
                    echoCmd.add(sigsysLibPath);
                    echoCmd.add(echoBin.getAbsolutePath());
                    echoCmd.add("hello-rseq-disabled");
                    ProcessBuilder echoPb = new ProcessBuilder(echoCmd);
                    echoPb.directory(rootDir);
                    echoPb.redirectErrorStream(true);
                    echoPb.environment().clear();
                    echoPb.environment().put("LD_LIBRARY_PATH", sigsysLibPath);
                    echoPb.environment().put("HOME", new File(rootDir, "home/xuser").getAbsolutePath());
                    echoPb.environment().put("TMPDIR", tmpDirDiag.getAbsolutePath());
                    echoPb.environment().put("PATH", new File(rootDir, "usr/bin").getAbsolutePath() + ":"
                            + new File(rootDir, "usr/local/bin").getAbsolutePath());
                    echoPb.environment().put("GLIBC_TUNABLES", "glibc.pthread.rseq=0");
                    Process echoProc = echoPb.start();
                    StringBuilder echoOut = new StringBuilder();
                    Thread echoReader = new Thread(() -> {
                        try (java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(echoProc.getInputStream()))) {
                            String l;
                            while ((l = r.readLine()) != null) echoOut.append(l).append('\n');
                        } catch (Exception ex) {
                            echoOut.append("[read error: ").append(ex.getMessage()).append("]\n");
                        }
                    }, "wl-diag-echo-b");
                    echoReader.start();
                    boolean echoExited = echoProc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                    if (!echoExited) echoProc.destroyForcibly();
                    echoReader.join(1000);
                    int echoExit = -1;
                    try { echoExit = echoProc.exitValue(); } catch (Exception ignored) {}
                    if (echoExit == 0 && echoOut.toString().contains("hello-rseq-disabled")) {
                        results.append("  ✓ /bin/echo succeeded (exit=0) with rseq disabled\n");
                        passed++;
                    } else if (echoExit == 159) {
                        results.append("  ✗ /bin/echo STILL killed by SIGSYS (exit=159) even with rseq disabled\n");
                        results.append("  → rseq is NOT the culprit — another glibc syscall is blocked\n");
                        failed++;
                    } else {
                        results.append("  ⚠ /bin/echo exit=" + echoExit + " output='" + echoOut.toString().trim() + "'\n");
                        warned++;
                    }
                } catch (Exception e) {
                    results.append("  ⚠ /bin/echo (rseq disabled) test failed: " + e.getMessage() + "\n");
                    warned++;
                }
            } else {
                results.append("Test B: SKIP (/bin/echo or linker not found)\n");
            }

            // Test C: Capture logcat for seccomp audit messages.
            // When Android's seccomp filter kills a process with SIGSYS, the
            // kernel/auditd logs the blocked syscall number. We capture logcat
            // output after the echo crash and search for "syscall=" to identify
            // the exact blocked syscall.
            results.append("Test C: logcat seccomp capture (identifies blocked syscall):\n");
            try {
                // Give logcat a moment to flush the seccomp message
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                // Capture last 200 lines from main + kernel buffers
                ProcessBuilder logcatPb = new ProcessBuilder(
                        "logcat", "-d", "-b", "main", "-b", "crash", "-b", "kernel", "-t", "200");
                logcatPb.redirectErrorStream(true);
                Process logcatProc = logcatPb.start();
                StringBuilder logcatOut = new StringBuilder();
                Thread logcatReader = new Thread(() -> {
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(logcatProc.getInputStream()))) {
                        String l;
                        while ((l = r.readLine()) != null) logcatOut.append(l).append('\n');
                    } catch (Exception ex) {
                        logcatOut.append("[read error: ").append(ex.getMessage()).append("]\n");
                    }
                }, "wl-diag-logcat");
                logcatReader.start();
                logcatProc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                logcatReader.join(1000);
                String logcatStr = logcatOut.toString();
                // Search for seccomp/SIGSYS/syscall messages
                boolean found = false;
                for (String l : logcatStr.split("\n")) {
                    String lower = l.toLowerCase();
                    if (lower.contains("seccomp") || lower.contains("sigsys")
                            || lower.contains("syscall=") || lower.contains("fatal signal 31")
                            || lower.contains("sys_seccomp") || lower.contains("auditd")) {
                        results.append("  ").append(l.trim()).append("\n");
                        found = true;
                    }
                }
                if (!found) {
                    results.append("  (no seccomp/SIGSYS messages found in logcat)\n");
                    results.append("  Note: kernel buffer may require root. Trying dmesg…\n");
                    // Try dmesg as fallback
                    try {
                        ProcessBuilder dmesgPb = new ProcessBuilder("dmesg");
                        dmesgPb.redirectErrorStream(true);
                        Process dmesgProc = dmesgPb.start();
                        StringBuilder dmesgOut = new StringBuilder();
                        Thread dmesgReader = new Thread(() -> {
                            try (java.io.BufferedReader r = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(dmesgProc.getInputStream()))) {
                                String l;
                                while ((l = r.readLine()) != null) dmesgOut.append(l).append('\n');
                            } catch (Exception ex) { /* ignore */ }
                        }, "wl-diag-dmesg");
                        dmesgReader.start();
                        dmesgProc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                        dmesgReader.join(500);
                        for (String l : dmesgOut.toString().split("\n")) {
                            String lower = l.toLowerCase();
                            if (lower.contains("seccomp") || lower.contains("sigsys")
                                    || lower.contains("syscall=")) {
                                results.append("  [dmesg] ").append(l.trim()).append("\n");
                                found = true;
                            }
                        }
                        if (!found) results.append("  (no seccomp messages in dmesg either)\n");
                    } catch (Exception de) {
                        results.append("  (dmesg not available: ").append(de.getMessage()).append(")\n");
                    }
                }
                if (found) {
                    results.append("  → The 'syscall=NNN' number above identifies the blocked syscall\n");
                    passed++;
                } else {
                    results.append("  ⚠ Could not capture seccomp audit log (may need root)\n");
                    warned++;
                }
            } catch (Exception e) {
                results.append("  ⚠ logcat capture failed: " + e.getMessage() + "\n");
                warned++;
            }

            // Test D: /bin/echo via PROOT (bypasses native seccomp).
            // Proot translates syscalls via ptrace, potentially avoiding the
            // blocked syscall. If this works, proot is a viable fallback.
            results.append("Test D: /bin/echo via proot (bypasses native seccomp):\n");
            File prootBinary = new File(nativeLibDir, "libproot.so");
            if (prootBinary.exists() && echoBin.exists()) {
                try {
                    List<String> prootCmd = new ArrayList<>();
                    prootCmd.add(prootBinary.getAbsolutePath());
                    prootCmd.add("-r");
                    prootCmd.add(rootDir.getAbsolutePath());
                    prootCmd.add("-w");
                    prootCmd.add("/home/xuser");
                    prootCmd.add("-b");
                    prootCmd.add("/dev:/dev");
                    prootCmd.add("-b");
                    prootCmd.add("/proc:/proc");
                    prootCmd.add("-b");
                    prootCmd.add("/sys:/sys");
                    prootCmd.add("/bin/echo");
                    prootCmd.add("hello-from-proot");
                    ProcessBuilder prootPb = new ProcessBuilder(prootCmd);
                    prootPb.redirectErrorStream(true);
                    Process prootProc = prootPb.start();
                    StringBuilder prootOut = new StringBuilder();
                    Thread prootReader = new Thread(() -> {
                        try (java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(prootProc.getInputStream()))) {
                            String l;
                            while ((l = r.readLine()) != null) prootOut.append(l).append('\n');
                        } catch (Exception ex) {
                            prootOut.append("[read error: ").append(ex.getMessage()).append("]\n");
                        }
                    }, "wl-diag-proot");
                    prootReader.start();
                    boolean prootExited = prootProc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                    if (!prootExited) prootProc.destroyForcibly();
                    prootReader.join(1000);
                    int prootExit = -1;
                    try { prootExit = prootProc.exitValue(); } catch (Exception ignored) {}
                    if (prootExit == 0 && prootOut.toString().contains("hello-from-proot")) {
                        results.append("  ✓ /bin/echo succeeded via proot (exit=0)\n");
                        results.append("  → PROOT WORKS — proot bypasses the seccomp-blocked syscall\n");
                        results.append("  → Recommend: use proot mode for bridge + Wine until native seccomp is resolved\n");
                        passed++;
                    } else if (prootExit == 159) {
                        results.append("  ✗ /bin/echo killed by SIGSYS via proot too (exit=159)\n");
                        results.append("  → seccomp blocks proot as well — deeper issue\n");
                        failed++;
                    } else {
                        String snippet = prootOut.toString();
                        if (snippet.length() > 200) snippet = snippet.substring(0, 200);
                        results.append("  ⚠ /bin/echo via proot exit=" + prootExit + " output='" + snippet.trim() + "'\n");
                        warned++;
                    }
                } catch (Exception e) {
                    results.append("  ⚠ proot echo test failed: " + e.getMessage() + "\n");
                    warned++;
                }
            } else {
                results.append("Test D: SKIP (libproot.so or /bin/echo not found)\n");
            }

            // Test E: Bionic test binary (compiled with NDK, linked against bionic).
            // This is the KEY test — if bionic works, it proves the seccomp issue
            // is glibc-specific and bionic is the correct native path.
            // The binary also tests individual syscalls (rseq, getcpu, clone3, etc.)
            // to identify which ones Android's seccomp blocks.
            results.append("Test E: bionic test binary (NDK-compiled, NO glibc):\n");
            File bionicTest = new File(nativeLibDir, "libwaylandie_bionic_test.so");
            if (bionicTest.exists()) {
                try {
                    bionicTest.setExecutable(true, false);
                    // Direct execve — bionic binary doesn't need a linker wrapper.
                    // Android's own linker (linker64) handles it natively.
                    ProcessBuilder bionicPb = new ProcessBuilder(bionicTest.getAbsolutePath());
                    bionicPb.redirectErrorStream(true);
                    Process bionicProc = bionicPb.start();
                    StringBuilder bionicOut = new StringBuilder();
                    Thread bionicReader = new Thread(() -> {
                        try (java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(bionicProc.getInputStream()))) {
                            String l;
                            while ((l = r.readLine()) != null) bionicOut.append(l).append('\n');
                        } catch (Exception ex) {
                            bionicOut.append("[read error: ").append(ex.getMessage()).append("]\n");
                        }
                    }, "wl-diag-bionic");
                    bionicReader.start();
                    boolean bionicExited = bionicProc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                    if (!bionicExited) bionicProc.destroyForcibly();
                    bionicReader.join(1000);
                    int bionicExit = -1;
                    try { bionicExit = bionicProc.exitValue(); } catch (Exception ignored) {}
                    String bionicStr = bionicOut.toString();
                    if (bionicExit == 0 && bionicStr.contains("hello-bionic")) {
                        results.append("  ✓ BIONIC TEST PASSED (exit=0)\n");
                        results.append("  → Bionic executes WITHOUT SIGSYS — seccomp issue is glibc-specific\n");
                        results.append("  → Bionic is the correct native path (no glibc, no proot needed)\n");
                        for (String l : bionicStr.split("\n")) {
                            if (!l.isEmpty() && !l.equals("hello-bionic")) {
                                results.append("    ").append(l).append("\n");
                            }
                        }
                        passed++;
                    } else if (bionicExit == 159) {
                        results.append("  ✗ Bionic test ALSO killed by SIGSYS (exit=159)\n");
                        results.append("  → seccomp blocks bionic too — deeper kernel-level issue\n");
                        failed++;
                    } else {
                        results.append("  ⚠ Bionic test exit=" + bionicExit + " output:\n");
                        for (String l : bionicStr.split("\n")) {
                            if (!l.isEmpty()) results.append("    ").append(l).append("\n");
                        }
                        warned++;
                    }
                } catch (Exception e) {
                    results.append("  ⚠ Bionic test failed: " + e.getMessage() + "\n");
                    warned++;
                }
            } else {
                results.append("Test E: SKIP (libwaylandie_bionic_test.so not found in nativeLibDir)\n");
                results.append("  → APK may not include the bionic test binary\n");
                warned++;
            }

            // Test F: LD_PRELOAD each bridge library to identify which one
            // triggers SIGSYS. /bin/echo works alone but the bridge crashes,
            // so a library CONSTRUCTOR is making a blocked syscall.
            // Test each library: libwayland-server, libX11, libXtst, libvulkan
            results.append("Test F: LD_PRELOAD library constructor SIGSYS test:\n");
            File echoBinF = new File(rootDir, "usr/bin/echo");
            File linkerF = new File(nativeLibDir, "libld_glibc.so");
            if (echoBinF.exists() && linkerF.exists()) {
                String baseLibPath = new File(rootDir, "usr/lib").getAbsolutePath() + ":"
                        + new File(rootDir, "usr/lib/aarch64-linux-gnu").getAbsolutePath() + ":"
                        + new File(rootDir, "usr/local/lib").getAbsolutePath();
                String[] libsToTest = {
                    "libwayland-server.so.0",
                    "libwayland-client.so.0",
                    "libX11.so.6",
                    "libXtst.so.6",
                    "libXext.so.6",
                    "libX11-xcb.so.1",
                    "libxcb.so.1",
                    "libvulkan.so.1",
                    "libfreetype.so.6"
                };
                for (String libName : libsToTest) {
                    File libFile = new File(rootDir, "usr/lib/aarch64-linux-gnu/" + libName);
                    if (!libFile.exists()) libFile = new File(rootDir, "usr/local/lib/" + libName);
                    if (!libFile.exists()) {
                        results.append("  " + libName + ": NOT FOUND — skip\n");
                        continue;
                    }
                    try {
                        List<String> cmd = new ArrayList<>();
                        cmd.add(linkerF.getAbsolutePath());
                        cmd.add("--library-path");
                        cmd.add(baseLibPath);
                        cmd.add(echoBinF.getAbsolutePath());
                        cmd.add("preload-test-" + libName);
                        ProcessBuilder pb = new ProcessBuilder(cmd);
                        pb.redirectErrorStream(true);
                        pb.environment().clear();
                        pb.environment().put("LD_LIBRARY_PATH", baseLibPath);
                        pb.environment().put("LD_PRELOAD", libFile.getAbsolutePath());
                        Process p = pb.start();
                        StringBuilder out = new StringBuilder();
                        Thread reader = new Thread(() -> {
                            try (java.io.BufferedReader r = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(p.getInputStream()))) {
                                String l;
                                while ((l = r.readLine()) != null) out.append(l).append('\n');
                            } catch (Exception ignored) {}
                        }, "wl-diag-preload-" + libName);
                        reader.start();
                        boolean exited = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                        if (!exited) p.destroyForcibly();
                        reader.join(500);
                        int exit = -1;
                        try { exit = p.exitValue(); } catch (Exception ignored) {}
                        if (exit == 0) {
                            results.append("  ✓ " + libName + ": OK (exit=0)\n");
                        } else if (exit == 159) {
                            results.append("  ✗ " + libName + ": SIGSYS (exit=159) ← THIS LIBRARY TRIGGERS SIGSYS\n");
                        } else {
                            String snippet = out.toString().trim();
                            if (snippet.length() > 100) snippet = snippet.substring(0, 100);
                            results.append("  ⚠ " + libName + ": exit=" + exit + " " + snippet + "\n");
                        }
                    } catch (Exception e) {
                        results.append("  ⚠ " + libName + ": test failed: " + e.getMessage() + "\n");
                    }
                }
            } else {
                results.append("Test F: SKIP (/bin/echo or linker not found)\n");
            }

            // Test G: Verify the LD_PRELOAD syscall shim fixes libwayland-server SIGSYS.
            // Test F showed libwayland-server triggers SIGSYS. The shim intercepts
            // blocked syscalls. This test verifies: /bin/echo + shim + libwayland-server
            // = exit 0 (shim catches the blocked syscall).
            results.append("Test G: LD_PRELOAD shim + libwayland-server test:\n");
            File shimFile = new File(rootDir, "usr/local/lib/libwaylandie_shim.so");
            File wlServerLib = new File(rootDir, "usr/lib/aarch64-linux-gnu/libwayland-server.so.0");
            if (shimFile.exists() && echoBinF.exists() && linkerF.exists() && wlServerLib.exists()) {
                try {
                    List<String> cmd = new ArrayList<>();
                    cmd.add(linkerF.getAbsolutePath());
                    cmd.add("--library-path");
                    cmd.add(sigsysLibPath);
                    cmd.add(echoBinF.getAbsolutePath());
                    cmd.add("shim-test-ok");
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.redirectErrorStream(true);
                    pb.environment().clear();
                    pb.environment().put("LD_LIBRARY_PATH", sigsysLibPath);
                    pb.environment().put("LD_PRELOAD", shimFile.getAbsolutePath() + ":"
                            + wlServerLib.getAbsolutePath());
                    pb.environment().put("WAYLANDIE_SHIM_DEBUG", "1");
                    Process p = pb.start();
                    StringBuilder out = new StringBuilder();
                    Thread reader = new Thread(() -> {
                        try (java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(p.getInputStream()))) {
                            String l;
                            while ((l = r.readLine()) != null) out.append(l).append('\n');
                        } catch (Exception ignored) {}
                    }, "wl-diag-shim");
                    reader.start();
                    boolean exited = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                    if (!exited) p.destroyForcibly();
                    reader.join(500);
                    int exit = -1;
                    try { exit = p.exitValue(); } catch (Exception ignored) {}
                    if (exit == 0 && out.toString().contains("shim-test-ok")) {
                        results.append("  ✓ SHIM WORKS! /bin/echo + shim + libwayland-server = exit 0\n");
                        results.append("  → The shim successfully intercepted the blocked syscall\n");
                        // Show which syscalls were intercepted
                        for (String l : out.toString().split("\n")) {
                            if (l.contains("WAYLANDIE_SHIM:")) {
                                results.append("    ").append(l).append("\n");
                            }
                        }
                        passed++;
                    } else if (exit == 159) {
                        results.append("  ✗ Shim did NOT fix libwayland-server SIGSYS (exit=159)\n");
                        results.append("  → The blocked syscall is NOT in our intercept list\n");
                        results.append("  → Need to identify the exact syscall (expand shim blocklist)\n");
                        failed++;
                    } else {
                        String snippet = out.toString().trim();
                        if (snippet.length() > 200) snippet = snippet.substring(0, 200);
                        results.append("  ⚠ exit=").append(exit).append(" output: ").append(snippet).append("\n");
                        warned++;
                    }
                } catch (Exception e) {
                    results.append("  ⚠ Shim test failed: " + e.getMessage() + "\n");
                    warned++;
                }
            } else {
                results.append("Test G: SKIP (shim or libwayland-server not found)\n");
                results.append("  shim: " + shimFile.exists() + " wl-server: " + wlServerLib.exists() + "\n");
                warned++;
            }

            // Test H: Syscall scanner — identifies EXACT blocked syscalls.
            // Runs a bionic binary that forks for each syscall number (0-450).
            // If a child exits with SIGSYS (signal 31), that syscall is blocked.
            // This gives us the device's EXACT seccomp blocklist so we can
            // expand the shim to intercept the right syscalls.
            results.append("Test H: Syscall scanner (identifies ALL blocked syscalls):\n");
            File scanBin = new File(nativeLibDir, "libwaylandie_syscall_scan.so");
            if (scanBin.exists()) {
                try {
                    scanBin.setExecutable(true, false);
                    ProcessBuilder scanPb = new ProcessBuilder(scanBin.getAbsolutePath());
                    scanPb.redirectErrorStream(true);
                    Process scanProc = scanPb.start();
                    StringBuilder scanOut = new StringBuilder();
                    Thread scanReader = new Thread(() -> {
                        try (java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(scanProc.getInputStream()))) {
                            String l;
                            while ((l = r.readLine()) != null) scanOut.append(l).append('\n');
                        } catch (Exception ignored) {}
                    }, "wl-diag-syscall-scan");
                    scanReader.start();
                    // Scanning 451 syscalls with fork() takes ~30-60s
                    boolean scanExited = scanProc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
                    if (!scanExited) scanProc.destroyForcibly();
                    scanReader.join(2000);
                    int scanExit = -1;
                    try { scanExit = scanProc.exitValue(); } catch (Exception ignored) {}

                    if (scanExit == 0) {
                        results.append("  ✓ Syscall scan complete:\n");
                        int blockedCount = 0;
                        for (String l : scanOut.toString().split("\n")) {
                            if (l.startsWith("BLOCKED:")) {
                                results.append("    ").append(l).append("\n");
                                blockedCount++;
                            }
                        }
                        // Check for scan complete line
                        if (scanOut.toString().contains("SCAN_COMPLETE")) {
                            results.append("  Total blocked syscalls: " + blockedCount + "\n");
                        }
                        if (blockedCount > 0) {
                            results.append("  → Compare with shim blocklist to find missing syscalls\n");
                            passed++;
                        } else {
                            results.append("  ⚠ No blocked syscalls found (unexpected — seccomp should block something)\n");
                            warned++;
                        }
                    } else {
                        results.append("  ✗ Syscall scanner failed (exit=" + scanExit + ")\n");
                        String snippet = scanOut.toString().trim();
                        if (snippet.length() > 300) snippet = snippet.substring(0, 300);
                        results.append("  Output: " + snippet + "\n");
                        failed++;
                    }
                } catch (Exception e) {
                    results.append("  ⚠ Syscall scanner failed: " + e.getMessage() + "\n");
                    warned++;
                }
            } else {
                results.append("Test H: SKIP (libwaylandie_syscall_scan.so not found)\n");
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
