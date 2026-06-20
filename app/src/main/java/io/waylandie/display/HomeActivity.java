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

    // --- Diagnostics (pre-flight check) ---
    private void runDiagnostics() {
        log("Running pre-flight diagnostics…");
        io.waylandie.display.shared.util.LogRingBuffer.append("[Diag] Running pre-flight diagnostics…");

        new Thread(() -> {
            try {
            StringBuilder results = new StringBuilder();
            int passed = 0, failed = 0, warned = 0;

            io.waylandie.display.runtime.environment.ImageFsManager imageFs =
                    new io.waylandie.display.runtime.environment.ImageFsManager(this);
            File rootDir = imageFs.getRootDir();
            String nativeLibDir = getApplicationInfo().nativeLibraryDir;
            File filesDir = getFilesDir();

            results.append("=== WayLandIE Pre-Flight Diagnostics ===\n\n");

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

            // === 2. GLIBC LINKER (libld_glibc.so) ===
            results.append("\n--- GLIBC LINKER (native launch) ---\n");
            File linker = new File(nativeLibDir, "libld_glibc.so");
            if (linker.exists()) {
                results.append("✓ libld_glibc.so found: " + linker + "\n");
                results.append("  Size: " + linker.length() + " bytes\n");
                if (linker.canExecute()) {
                    results.append("✓ Executable: YES\n");
                    passed++;
                } else {
                    results.append("✗ Executable: NO\n");
                    failed++;
                }
            } else {
                results.append("⚠ libld_glibc.so NOT FOUND — will fall back to proot\n");
                warned++;
            }

            // === 3. BRIDGE TRANSLATOR ===
            results.append("\n--- BRIDGE TRANSLATOR ---\n");
            File bridgeBin = new File(rootDir, "usr/local/bin/waylandie-wayland-bridge");
            if (bridgeBin.exists()) {
                results.append("✓ Bridge binary found\n");
                results.append("  Size: " + bridgeBin.length() + " bytes\n");
                passed++;
            } else {
                results.append("✗ Bridge binary NOT FOUND at " + bridgeBin + "\n");
                results.append("  Wine will have no Wayland display to render to.\n");
                results.append("  Rebuild rootfs with libc6-dev + wayland-scanner.\n");
                failed++;
            }

            // === 4. PROTON ===
            results.append("\n--- PROTON ---\n");
            File protonDir = new File(filesDir, "contents/proton/active");
            if (protonDir.exists()) {
                File wineBin = new File(protonDir, "files/bin/wine");
                if (!wineBin.exists()) wineBin = new File(protonDir, "dist/bin/wine");
                if (!wineBin.exists()) wineBin = new File(protonDir, "bin/wine");
                if (wineBin.exists()) {
                    results.append("✓ Wine binary found: " + wineBin.getName() + "\n");
                    results.append("  Size: " + wineBin.length() + " bytes\n");
                    // Check ELF architecture
                    try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(wineBin, "r")) {
                        byte[] magic = new byte[20];
                        raf.readFully(magic);
                        if (magic[0] == 0x7f && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F') {
                            int eMachine = (magic[19] & 0xFF) << 8 | (magic[18] & 0xFF);
                            if (eMachine == 183) {
                                results.append("  Architecture: ARM64 (arm64ec) — native launch\n");
                            } else if (eMachine == 62) {
                                results.append("  Architecture: x86_64 — needs box64\n");
                            } else {
                                results.append("  Architecture: unknown (e_machine=" + eMachine + ")\n");
                            }
                        }
                    }
                    passed++;
                } else {
                    results.append("✗ Wine binary NOT FOUND in proton/active\n");
                    failed++;
                }
            } else {
                results.append("✗ Proton NOT INSTALLED\n");
                failed++;
            }

            // === 5. DXVK ===
            results.append("\n--- DXVK ---\n");
            File dxvkActive = new File(filesDir, "contents/dxvk/active");
            if (dxvkActive.exists()) {
                File dxvkSystem32 = new File(dxvkActive, "system32");
                File dxvkSyswow64 = new File(dxvkActive, "syswow64");
                int dllCount = 0;
                if (dxvkSystem32.isDirectory()) {
                    File[] dlls = dxvkSystem32.listFiles((d, n) -> n.endsWith(".dll"));
                    if (dlls != null) dllCount += dlls.length;
                }
                if (dxvkSyswow64.isDirectory()) {
                    File[] dlls = dxvkSyswow64.listFiles((d, n) -> n.endsWith(".dll"));
                    if (dlls != null) dllCount += dlls.length;
                }
                results.append("✓ DXVK installed (" + dllCount + " DLLs in slot)\n");

                // Check if DLLs were copied to Wine prefix
                File wineSystem32 = new File(rootDir, "home/xuser/.wine/drive_c/windows/system32");
                File d3d11 = new File(wineSystem32, "d3d11.dll");
                if (d3d11.exists()) {
                    results.append("✓ d3d11.dll in Wine prefix system32/\n");
                    passed++;
                } else {
                    results.append("✗ d3d11.dll NOT in Wine prefix — DXVK won't work\n");
                    results.append("  Reinstall DXVK to copy DLLs to prefix.\n");
                    failed++;
                }
            } else {
                results.append("⚠ DXVK not installed\n");
                warned++;
            }

            // === 6. TURNIP (Vulkan driver) ===
            results.append("\n--- TURNIP (Vulkan driver) ---\n");
            File turnipActive = new File(filesDir, "contents/turnip/active");
            if (turnipActive.exists()) {
                results.append("✓ Turnip installed\n");
                // Check ICD JSON
                File icdJson = new File(rootDir, "usr/local/etc/vulkan/icd.d/freedreno_icd.json");
                if (icdJson.exists()) {
                    String json = new String(java.nio.file.Files.readAllBytes(icdJson.toPath()));
                    if (json.contains("/usr/local/lib/libvulkan_freedreno.so")) {
                        results.append("✓ ICD JSON points to /usr/local/lib/ (correct for native)\n");
                        passed++;
                    } else if (json.contains("/opt/turnip/")) {
                        results.append("✗ ICD JSON points to /opt/turnip/ (proot path — WRONG for native)\n");
                        results.append("  Reinstall Turnip to fix ICD JSON path.\n");
                        failed++;
                    } else {
                        results.append("⚠ ICD JSON has unexpected path: " + json + "\n");
                        warned++;
                    }
                } else {
                    results.append("✗ ICD JSON NOT FOUND at " + icdJson + "\n");
                    failed++;
                }
                // Check .so was copied to rootfs
                File soInRootfs = new File(rootDir, "usr/local/lib/libvulkan_freedreno.so");
                if (soInRootfs.exists()) {
                    results.append("✓ libvulkan_freedreno.so in rootfs/usr/local/lib/\n");
                    passed++;
                } else {
                    results.append("✗ libvulkan_freedreno.so NOT in rootfs/usr/local/lib/\n");
                    failed++;
                }
            } else {
                results.append("⚠ Turnip not installed\n");
                warned++;
            }

            // === 7. FEX ===
            results.append("\n--- FEX ---\n");
            File fexActive = new File(filesDir, "contents/fex/active");
            if (fexActive.exists()) {
                results.append("✓ FEX installed\n");
                // Check FEXCore DLLs in Wine prefix
                File fexSystem32 = new File(fexActive, "system32");
                File libArm64ecFex = new File(fexSystem32, "libarm64ecfex.dll");
                if (libArm64ecFex.exists()) {
                    results.append("✓ libarm64ecfex.dll found in FEX slot\n");
                    // Check if copied to Wine prefix
                    File wineSystem32 = new File(rootDir, "home/xuser/.wine/drive_c/windows/system32");
                    File dllInPrefix = new File(wineSystem32, "libarm64ecfex.dll");
                    if (dllInPrefix.exists()) {
                        results.append("✓ libarm64ecfex.dll in Wine prefix\n");
                        passed++;
                    } else {
                        results.append("✗ libarm64ecfex.dll NOT in Wine prefix\n");
                        failed++;
                    }
                } else {
                    results.append("⚠ libarm64ecfex.dll not found in FEX slot\n");
                    warned++;
                }
            } else {
                results.append("⚠ FEX not installed\n");
                warned++;
            }

            // === 8. KEY LIBRARIES PATHS ===
            results.append("\n--- KEY LIBRARIES (Debian multiarch) ---\n");
            String[] keyLibs = {
                "usr/lib/aarch64-linux-gnu/libwayland-server.so.0",
                "usr/lib/aarch64-linux-gnu/libX11.so.6",
                "usr/lib/aarch64-linux-gnu/libfreetype.so.6",
                "usr/lib/aarch64-linux-gnu/libvulkan.so.1",
                "usr/lib/aarch64-linux-gnu/libGL.so.1",
                "usr/lib/ld-linux-aarch64.so.1"
            };
            for (String lib : keyLibs) {
                File libFile = new File(rootDir, lib);
                if (libFile.exists()) {
                    results.append("✓ " + lib + " (" + libFile.length() + " bytes)\n");
                    passed++;
                } else {
                    results.append("✗ " + lib + " MISSING\n");
                    failed++;
                }
            }

            // === 9. SELINUX CHECK ===
            results.append("\n--- SELINUX CHECK ---\n");
            // Try to execve the linker — if SELinux blocks it, we'll get permission denied
            if (linker.exists()) {
                try {
                    Process testP = new ProcessBuilder(linker.getAbsolutePath(), "--version").start();
                    int exitCode = testP.waitFor();
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(testP.getInputStream()));
                    String firstLine = r.readLine();
                    if (exitCode == 0 || firstLine != null) {
                        results.append("✓ SELinux allows execve of libld_glibc.so\n");
                        if (firstLine != null) results.append("  Version: " + firstLine + "\n");
                        passed++;
                    } else {
                        results.append("✗ libld_glibc.so failed to execute (exit=" + exitCode + ")\n");
                        failed++;
                    }
                } catch (Exception e) {
                    results.append("✗ SELinux BLOCKED execve: " + e.getMessage() + "\n");
                    results.append("  Native launch will fail — proot fallback required.\n");
                    failed++;
                }
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
                        .setTitle("Pre-Flight Diagnostics")
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
