package com.winlator.cmod.runtime.wine;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.runtime.system.LogManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WaylandIE diagnostic harness.
 *
 * Checkpoints every stage of the Wayland display pipeline at launch time and,
 * after the guest exits, scans all captured logs to pinpoint exactly where the
 * pipeline broke and why. Output:
 *   - wayland-diagnostics.json  (machine readable, per-stage status + evidence)
 *   - wayland-diagnostics.txt   (human readable verdict + root cause + hint)
 *
 * Both files land in the app's logs dir and are included in the shared log zip,
 * so every future log captures the diagnosis automatically.
 *
 * The module is deliberately defensive: no diagnostics failure may crash or
 * block the app, so every public method wraps itself in try/catch.
 */
public final class WaylandDiagnostics {
    private static final String TAG = "WaylandDiagnostics";
    private static final String JSON_FILE = "wayland-diagnostics.json";
    private static final String TXT_FILE = "wayland-diagnostics.txt";

    /** Stage ids in dependency order; the first FAIL is the root cause. */
    private static final String[] STAGE_ORDER = {
        "compositor_socket",
        "driver_files",
        "driver_deps",
        "registry",
        "launch_env",
        "driver_load",
        "client_connect",
        "protocol_bind",
        "explorer_window",
        "game_window",
        "guest_exit",
    };

    private static final class LogLine {
        final String file;
        final int line;
        final String text;

        LogLine(String file, int line, String text) {
            this.file = file;
            this.line = line;
            this.text = text;
        }
    }

    private static final class Detection {
        final Pattern pattern;
        final String signature;
        final String explanation;
        final String hint;

        Detection(String regex, String signature, String explanation, String hint) {
            this.pattern = Pattern.compile(regex);
            this.signature = signature;
            this.explanation = explanation;
            this.hint = hint;
        }
    }

    private static final Detection[] DETECTIONS = {
        new Detection(
                "failed to load \\.so lib",
                "WINE_UNIX_SO_LOAD_FAILED",
                "Wine could not dlopen a unix .so (winewayland.so or one of its dependencies). The exact dlerror() text is on the matching line.",
                "Check the 'driver_deps' stage: libandroid-sysvshm.so / libfreetype.so.6 must exist in imagefs/usr/lib (which is on LD_LIBRARY_PATH)."),
        new Detection(
                "Make sure that your display server is running",
                "WINE_DRIVER_DLL_INIT_FAILED",
                "LoadLibraryW(\"winewayland.drv\") failed with ERROR_DLL_INIT_FAILED: the driver's DllMain returned FALSE (the unix init failed).",
                "The unix init fails at wl_display_connect or at a required protocol check. Inspect the 'client_connect' and 'protocol_bind' stages and the WAYLAND_SOCKET environment."),
        new Detection(
                "Failed to connect to Wayland display",
                "WAYLAND_CONNECT_FAILED",
                "winewayland.so's wl_display_connect() returned NULL - the guest process could not reach the compositor socket.",
                "Verify WAYLAND_SOCKET / XDG_RUNTIME_DIR / WAYLAND_DISPLAY in the launch env and that the socket file exists and is readable (see 'compositor_socket' stage)."),
        new Detection(
                "Wayland compositor doesn't support",
                "WAYLAND_PROTOCOL_GAP",
                "The compositor is missing a protocol global that winewayland.drv requires (wl_compositor, xdg_wm_base, wl_shm, wl_subcompositor, wp_viewporter).",
                "The in-process compositor must advertise all five required globals. The compositor's 'globals:' startup line lists what it exposes."),
        new Detection(
                "nodrv_CreateWindow",
                "WINE_NO_DISPLAY_DRIVER",
                "A process tried to create a window while Wine's display driver was still the null driver - the graphics driver did not load.",
                "Usually the downstream symptom of an earlier failing stage ('driver_load' / 'client_connect'). Inspect the stages listed above it."),
        new Detection(
                "Skipping: Device does not support required feature",
                "DXVK_FEATURE_GAP",
                "DXVK rejected the Vulkan device because a required feature/extension is missing.",
                "Update the Turnip driver to a Mesa 26.3+ build (exposes VK_KHR_maintenance5) or use DXVK 2.4.1 pre-reg ARM64EC, which does not require maintenance5."),
        new Detection(
                "DXVK: No adapters found",
                "DXVK_NO_ADAPTER",
                "DXVK found no usable Vulkan adapter - the Vulkan driver did not enumerate a device.",
                "Check the graphics driver selection (Settings -> Drivers) and confirm the wrapper driver actually loaded."),
        new Detection(
                "Unhandled (exception|page fault)",
                "GAME_CRASH",
                "The guest process crashed with an unhandled exception (page fault / NULL dereference etc.).",
                "If 'explorer_window' or 'game_window' failed first, the crash is usually a symptom of the missing window/display driver. Otherwise it is game-specific."),
        new Detection(
                "Fatal signal|SIGSEGV|SIGABRT",
                "NATIVE_CRASH",
                "A native process (compositor / bridge / wine loader) crashed with a fatal signal.",
                "Look at the application.log lines around the signal for the crashing module (libwaylandie_comp.so / libwinlator.so / wine)."),
        new Detection(
                "DXVK: v[0-9]",
                "DXVK_VERSION",
                "DXVK version active in this session.",
                null),
        new Detection(
                "Wrapper\\(Adreno",
                "VULKAN_DEVICE",
                "Vulkan device selected by DXVK.",
                null),
        new Detection(
                "GUEST_PROCESS_EXIT",
                "GUEST_EXIT",
                "Guest process exit record.",
                null),
    };

    private WaylandDiagnostics() {}

    private static File logsDir(Context ctx) {
        return LogManager.getLogsDir(ctx);
    }

    private static File jsonFile(Context ctx) {
        return new File(logsDir(ctx), JSON_FILE);
    }

    private static File txtFile(Context ctx) {
        return new File(logsDir(ctx), TXT_FILE);
    }

    private static JSONObject loadOrCreate(Context ctx) {
        File f = jsonFile(ctx);
        try {
            if (f.exists()) {
                String content = new String(
                        java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                if (content != null && !content.trim().isEmpty()) {
                    JSONObject json = new JSONObject(content);
                    if (!json.has("stages")) json.put("stages", new JSONArray());
                    return json;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "loadOrCreate: failed to read existing report", t);
        }
        JSONObject json = new JSONObject();
        try {
            json.put("session", new JSONObject());
            json.put("environment", new JSONObject());
            json.put("stages", new JSONArray());
        } catch (org.json.JSONException e) {
            Log.w(TAG, "loadOrCreate: init failed", e);
        }
        return json;
    }

    private static void save(Context ctx, JSONObject json) {
        try {
            File f = jsonFile(ctx);
            if (!f.getParentFile().isDirectory()) f.getParentFile().mkdirs();
            try (OutputStreamWriter w =
                    new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
                w.write(json.toString(2));
            }
        } catch (Throwable t) {
            Log.w(TAG, "save: failed to write report", t);
        }
    }

    /** Starts (or resumes) a diagnostic session. Idempotent: keeps existing stages. */
    public static synchronized void beginSession(
            Context ctx, String container, String winePath, String graphicsDriver) {
        try {
            JSONObject json = loadOrCreate(ctx);
            JSONObject session = new JSONObject();
            session.put("schemaVersion", 1);
            session.put("startedAt", System.currentTimeMillis());
            if (container != null) session.put("container", container);
            if (winePath != null) session.put("winePath", winePath);
            if (graphicsDriver != null) session.put("graphicsDriver", graphicsDriver);
            json.put("session", session);
            if (!json.has("environment")) json.put("environment", new JSONObject());
            if (!json.has("stages")) json.put("stages", new JSONArray());
            save(ctx, json);
        } catch (Throwable t) {
            Log.w(TAG, "beginSession failed", t);
        }
    }

    /** Records the exact environment the wine process was launched with. */
    public static synchronized void recordEnv(Context ctx, Map<String, String> env) {
        try {
            JSONObject json = loadOrCreate(ctx);
            JSONObject environment = new JSONObject();
            String[] keys = {
                "WAYLAND_DISPLAY",
                "XDG_RUNTIME_DIR",
                "WAYLAND_SOCKET",
                "LD_LIBRARY_PATH",
                "WINEDLLOVERRIDES",
                "WINEDEBUG",
                "WAYLANDIE_ANATIVE_WINDOW",
                "WAYLANDIE_ANATIVE_WINDOW_FILE",
                "WAYLANDIE_DMABUF_LAYER_ENABLE",
                "WAYLANDIE_BRIDGE_SOCKET",
                "DISPLAY",
                "WINEPREFIX",
            };
            for (String key : keys) {
                if (env != null && env.get(key) != null) environment.put(key, env.get(key));
            }
            json.put("environment", environment);
            save(ctx, json);
        } catch (Throwable t) {
            Log.w(TAG, "recordEnv failed", t);
        }
    }

    /**
     * Records a checkpoint. A stage with the same id is replaced (so launch-time
     * checks can be refreshed at exit with the definitive result).
     */
    public static synchronized void recordStage(
            Context ctx, String id, String name, String status, String detail, String hint) {
        try {
            JSONObject json = loadOrCreate(ctx);
            JSONArray stages = json.optJSONArray("stages");
            if (stages == null) {
                stages = new JSONArray();
                json.put("stages", stages);
            }
            JSONObject stage = new JSONObject();
            stage.put("id", id);
            stage.put("name", name);
            stage.put("status", status == null ? "UNKNOWN" : status);
            if (detail != null) stage.put("detail", detail);
            if (hint != null) stage.put("hint", hint);
            stage.put("at", System.currentTimeMillis());

            for (int i = 0; i < stages.length(); i++) {
                JSONObject existing = stages.optJSONObject(i);
                if (existing != null && id.equals(existing.optString("id"))) {
                    stages.put(i, stage);
                    save(ctx, json);
                    return;
                }
            }
            stages.put(stage);
            save(ctx, json);
        } catch (Throwable t) {
            Log.w(TAG, "recordStage failed for " + id, t);
        }
    }

    /**
     * Called after the guest exits. Scans every captured log, fills in the
     * exit-time stages, determines the root cause and writes the final report.
     */
    public static synchronized void finalize(
            Context ctx, Map<String, String> env, int exitStatus, boolean crashed) {
        try {
            List<LogLine> lines = collectLogLines(ctx);
            JSONObject json = loadOrCreate(ctx);

            List<Detection> findings = new ArrayList<>();
            List<LogLine> evidence = new ArrayList<>();
            for (Detection d : DETECTIONS) {
                List<LogLine> matches = grep(lines, d.pattern);
                if (matches.isEmpty()) continue;
                findings.add(d);
                for (int i = 0; i < matches.size() && evidence.size() < 40; i++) {
                    evidence.add(matches.get(i));
                }
            }

            boolean soLoadFailed = hasSignature(findings, "WINE_UNIX_SO_LOAD_FAILED");
            boolean dllInitFailed = hasSignature(findings, "WINE_DRIVER_DLL_INIT_FAILED");
            boolean connectFailed = hasSignature(findings, "WAYLAND_CONNECT_FAILED");
            boolean protocolGap = hasSignature(findings, "WAYLAND_PROTOCOL_GAP");
            boolean driverLoadFailed = soLoadFailed || dllInitFailed || connectFailed || protocolGap;

            boolean clientBound = grep(lines, Pattern.compile("\\[srv\\] client bound wl_compositor")).size() > 0;
            boolean anyBound = grep(lines, Pattern.compile("\\[srv\\] client bound")).size() > 0;
            boolean toplevelSeen = grep(lines, Pattern.compile("\\[srv\\] xdg_toplevel\\.set_title")).size() > 0;
            boolean frameSeen = grep(lines, Pattern.compile("\\[srv\\] surface\\.attach|DMABUF RECEIVED")).size() > 0;
            int nodrvCount = grep(lines, Pattern.compile("nodrv_CreateWindow")).size();

            boolean hasWineStderr = new File(ctx.getFilesDir(), "wine_stderr.log").isFile();
            boolean hasAppLog = new File(logsDir(ctx), "application.log").isFile();
            if (!hasWineStderr) {
                recordStage(ctx, "log_capture", "Log capture", "WARN",
                        "wine_stderr.log is missing - driver load errors may not be visible.",
                        "Enable wine/app debug logging so the diagnostic report is complete.");
            }
            if (!hasAppLog) {
                recordStage(ctx, "log_capture_app", "App log capture", "WARN",
                        "application.log is missing - compositor [srv] markers not visible.",
                        "Enable app debug logging (Settings -> Debug) for full stage detection.");
            }

            // --- exit-time stage resolution ---
            String driverLoadStatus = driverLoadFailed ? "FAIL" : (clientBound ? "PASS" : "UNKNOWN");
            String driverLoadDetail = driverLoadFailed ? "Driver failed to load; see evidence below." : null;
            if (soLoadFailed) driverLoadDetail = "winewayland.so (or a dependency) failed to dlopen - see wine_stderr.log line(s) in evidence.";
            if (dllInitFailed) driverLoadDetail = "winewayland.drv DllMain returned FALSE (ERROR_DLL_INIT_FAILED).";
            if (connectFailed) driverLoadDetail = "wl_display_connect() returned NULL in the guest.";
            if (protocolGap) driverLoadDetail = "A required Wayland protocol global is missing on the compositor.";
            recordStage(ctx, "driver_load", "winewayland.drv load", driverLoadStatus,
                    driverLoadDetail,
                    driverLoadFailed ? "See the matched evidence lines below for the exact reason." : null);

            String connectStatus = clientBound ? "PASS" : (driverLoadFailed ? "UNKNOWN" : "FAIL");
            recordStage(ctx, "client_connect", "Wayland client connection", connectStatus,
                    clientBound ? "Compositor logged '[srv] client bound wl_compositor'."
                                : (driverLoadFailed ? "Blocked by driver load failure." : "No compositor client connection observed."),
                    clientBound ? null : "Check WAYLAND_SOCKET/XDG_RUNTIME_DIR and the 'compositor_socket' stage.");

            String bindStatus = anyBound ? "PASS" : (clientBound ? "FAIL" : "UNKNOWN");
            recordStage(ctx, "protocol_bind", "Protocol globals bound", bindStatus,
                    anyBound ? "Compositor logged client binds." : (clientBound ? "Client connected but bound no globals." : "No binds observed."),
                    anyBound ? null : "If a client connected but bound nothing, the driver's registry roundtrip failed.");

            String explorerStatus = nodrvCount == 0 ? "PASS" : "FAIL";
            recordStage(ctx, "explorer_window", "Explorer desktop window", explorerStatus,
                    nodrvCount == 0 ? "No nodrv_CreateWindow errors." : nodrvCount + " nodrv_CreateWindow error(s).",
                    nodrvCount > 0 ? "The display driver did not load - see 'driver_load' / 'client_connect'." : null);

            String gameStatus = (toplevelSeen || frameSeen) ? "PASS" : (clientBound ? "UNKNOWN" : "FAIL");
            recordStage(ctx, "game_window", "Game window / frames", gameStatus,
                    toplevelSeen ? "Compositor saw xdg_toplevel(s)."
                                 : (frameSeen ? "Compositor saw surface attaches/dmabuf frames."
                                              : (clientBound ? "Client connected but no toplevel/frame observed."
                                                             : "No window activity observed.")),
                    gameStatus.equals("FAIL") ? "The game could not create/present a window." : null);

            boolean gameCrash = hasSignature(findings, "GAME_CRASH");
            boolean nativeCrash = hasSignature(findings, "NATIVE_CRASH");
            recordStage(ctx, "guest_exit", "Guest exit", crashed ? "FAIL" : (exitStatus == 0 ? "PASS" : "FAIL"),
                    "exitStatus=" + exitStatus
                            + (gameCrash ? " + unhandled guest exception" : "")
                            + (nativeCrash ? " + native fatal signal" : ""),
                    (crashed || gameCrash || nativeCrash)
                            ? "See evidence lines for the crash location (module + offset)."
                            : null);

            // --- root cause ---
            JSONObject failure = new JSONObject();
            boolean detected = false;
            String rootStage = null;
            for (String stageId : STAGE_ORDER) {
                JSONObject stage = findStage(json, stageId);
                if (stage != null && "FAIL".equals(stage.optString("status"))) {
                    rootStage = stageId;
                    detected = true;
                    break;
                }
            }
            if (rootStage == null && driverLoadFailed) {
                rootStage = "driver_load";
                detected = true;
            }
            failure.put("detected", detected);
            if (rootStage != null) failure.put("rootCauseStage", rootStage);
            if (!findings.isEmpty()) {
                String primary = null;
                for (String sig : new String[] {
                        "WINE_UNIX_SO_LOAD_FAILED",
                        "WINE_DRIVER_DLL_INIT_FAILED",
                        "WAYLAND_CONNECT_FAILED",
                        "WAYLAND_PROTOCOL_GAP",
                        "DXVK_FEATURE_GAP",
                        "DXVK_NO_ADAPTER",
                        "NATIVE_CRASH",
                        "GAME_CRASH",
                        "WINE_NO_DISPLAY_DRIVER",
                }) {
                    if (hasSignature(findings, sig)) {
                        primary = sig;
                        break;
                    }
                }
                for (Detection d : findings) {
                    if (d.signature.equals(primary)) {
                        failure.put("signature", d.signature);
                        if (d.explanation != null) failure.put("explanation", d.explanation);
                        if (d.hint != null) failure.put("hint", d.hint);
                        break;
                    }
                }
            }
            JSONArray evArray = new JSONArray();
            for (LogLine l : evidence) {
                JSONObject e = new JSONObject();
                e.put("file", l.file);
                e.put("line", l.line);
                e.put("text", l.text.length() > 500 ? l.text.substring(0, 500) : l.text);
                evArray.put(e);
            }
            json.put("evidence", evArray);
            json.put("failure", failure);
            JSONObject session = json.optJSONObject("session");
            if (session == null) {
                session = new JSONObject();
                json.put("session", session);
            }
            session.put("endedAt", System.currentTimeMillis());
            save(ctx, json);
            writeTxtReport(ctx, json);
            Log.i(TAG, "finalize: verdict=" + failure.optString("signature", "none")
                    + " rootStage=" + (rootStage == null ? "none" : rootStage));
        } catch (Throwable t) {
            Log.w(TAG, "finalize failed", t);
        }
    }

    // ---------- launch-time checks (called by the runtime before wine starts) ----------

    public static void checkCompositorSocket(Context ctx, File imageFsRoot) {
        if (imageFsRoot == null) {
            recordStage(ctx, "compositor_socket", "Compositor socket", "UNKNOWN",
                    "imagefs root unavailable.", null);
            return;
        }
        File socketFile = new File(imageFsRoot, "usr/tmp/runtime/wayland-0");
        String detail;
        String status;
        if (!socketFile.exists()) {
            status = "FAIL";
            detail = "socket missing: " + socketFile;
        } else if (!socketFile.canRead()) {
            status = "FAIL";
            detail = "socket exists but not readable: " + socketFile;
        } else {
            String probe = probeSocket(socketFile);
            status = probe.contains("OK") ? "PASS" : "WARN";
            detail = probe;
        }
        recordStage(ctx, "compositor_socket", "Compositor socket", status, detail,
                status.equals("FAIL")
                        ? "The compositor must bind usr/tmp/runtime/wayland-0 before wine starts."
                        : null);
    }

    public static void checkDriverFiles(Context ctx, File prefix, File winePath) {
        List<String> problems = new ArrayList<>();
        if (prefix == null || winePath == null) {
            recordStage(ctx, "driver_files", "Driver files", "UNKNOWN", "prefix/winePath unavailable.", null);
            return;
        }
        File drvSys32 = new File(prefix, "drive_c/windows/system32/winewayland.drv");
        File drvWin = new File(winePath, "lib/wine/aarch64-windows/winewayland.drv");
        File soUnix = new File(winePath, "lib/wine/aarch64-unix/winewayland.so");
        if (!drvSys32.isFile() || drvSys32.length() < 1000)
            problems.add("system32/winewayland.drv missing or tiny (" + drvSys32.length() + "B)");
        if (!drvWin.isFile() || drvWin.length() < 1000)
            problems.add("winePath lib/wine/aarch64-windows/winewayland.drv missing or tiny");
        if (!soUnix.isFile() || soUnix.length() < 1000)
            problems.add("winePath lib/wine/aarch64-unix/winewayland.so missing or tiny (" + soUnix.length() + "B)");
        else if (!isElf(soUnix))
            problems.add("winewayland.so is not a valid ELF binary");
        recordStage(ctx, "driver_files", "Driver files", problems.isEmpty() ? "PASS" : "FAIL",
                problems.isEmpty()
                        ? "winewayland.drv (system32+winPath) and winewayland.so present, ELF OK."
                        : String.join(" | ", problems),
                problems.isEmpty() ? null : "Reinstall the Proton/Wayland driver component from the current APK.");
    }

    public static void checkDriverDeps(Context ctx, File prefix, File imageFsRoot, String wineLibDir) {
        List<String> problems = new ArrayList<>();
        File bundledLibDir = new File(prefix, "lib");
        File[] bundled = bundledLibDir.isDirectory()
                ? bundledLibDir.listFiles((dir, name) -> name.endsWith(".so")) : null;
        if (bundled == null || bundled.length == 0) {
            recordStage(ctx, "driver_deps", "Driver shared libs", "UNKNOWN",
                    "No bundled .so found in " + bundledLibDir, null);
            return;
        }
        StringBuilder detail = new StringBuilder();
        for (File so : bundled) {
            File inUserLib = imageFsRoot != null ? new File(new File(imageFsRoot, "usr/lib"), so.getName()) : null;
            File inWineLib = wineLibDir != null ? new File(wineLibDir, so.getName()) : null;
            boolean ok = (inUserLib != null && inUserLib.isFile())
                    || (inWineLib != null && inWineLib.isFile());
            if (!ok) problems.add(so.getName() + " not on LD_LIBRARY_PATH");
            detail.append(so.getName()).append(ok ? "=OK" : "=MISSING").append(" ");
        }
        recordStage(ctx, "driver_deps", "Driver shared libs", problems.isEmpty() ? "PASS" : "FAIL",
                detail.toString().trim() + (problems.isEmpty() ? "" : " | " + String.join(" | ", problems)),
                problems.isEmpty() ? null
                        : "WaylandDriverInstaller must copy bundled .so files into imagefs/usr/lib (LD_LIBRARY_PATH).");
    }

    public static void checkRegistry(Context ctx, File prefix) {
        List<String> problems = new ArrayList<>();
        File userReg = new File(prefix, "user.reg");
        File systemReg = new File(prefix, "system.reg");
        boolean graphicsWayland = false;
        if (userReg.isFile()) {
            String content = readFile(userReg);
            if (content != null) {
                int drvIdx = content.indexOf("[Software\\\\Wine\\\\Drivers]");
                if (drvIdx >= 0) {
                    String tail = content.substring(drvIdx);
                    int nextKey = tail.indexOf("\n[", 1);
                    String block = nextKey >= 0 ? tail.substring(0, nextKey) : tail;
                    graphicsWayland = block.contains("\"Graphics\"=\"wayland\"");
                }
            }
        } else {
            problems.add("user.reg missing");
        }
        int videoKeys = 0;
        int graphicsEntries = 0;
        if (systemReg.isFile()) {
            String content = readFile(systemReg);
            if (content != null) {
                int idx = 0;
                String videoMarker = "[System\\\\CurrentControlSet\\\\Control\\\\Video\\\\{";
                while ((idx = content.indexOf(videoMarker, idx)) >= 0) {
                    videoKeys++;
                    idx += videoMarker.length();
                }
                idx = 0;
                String graphicsMarker = "\"GraphicsDriver\"=\"";
                while ((idx = content.indexOf(graphicsMarker, idx)) >= 0) {
                    graphicsEntries++;
                    idx += graphicsMarker.length();
                }
            }
        } else {
            problems.add("system.reg missing");
        }
        if (!graphicsWayland) problems.add("user.reg [Software\\Wine\\Drivers] Graphics != wayland");
        if (videoKeys == 0) problems.add("no Control\\Video\\{GUID}\\0000 keys found");
        if (graphicsEntries == 0) problems.add("no GraphicsDriver value in system.reg");
        recordStage(ctx, "registry", "GraphicsDriver registry", problems.isEmpty() ? "PASS" : "WARN",
                "user.reg Graphics=wayland: " + graphicsWayland
                        + " | system.reg Video keys: " + videoKeys
                        + " | GraphicsDriver entries: " + graphicsEntries
                        + (problems.isEmpty() ? "" : " | " + String.join(" | ", problems)),
                "Wine's explorer writes the volatile Video\\{GUID}\\0000 key itself at load time; missing keys here are expected before first success.");
    }

    public static void checkLaunchEnv(Context ctx, Map<String, String> env) {
        List<String> problems = new ArrayList<>();
        if (env == null) {
            recordStage(ctx, "launch_env", "Launch environment", "UNKNOWN", "env unavailable.", null);
            return;
        }
        String waylandDisplay = env.get("WAYLAND_DISPLAY");
        String xdg = env.get("XDG_RUNTIME_DIR");
        String socket = env.get("WAYLAND_SOCKET");
        String ld = env.get("LD_LIBRARY_PATH");
        if (waylandDisplay == null || waylandDisplay.isEmpty())
            problems.add("WAYLAND_DISPLAY unset");
        if (xdg == null || xdg.isEmpty())
            problems.add("XDG_RUNTIME_DIR unset");
        if (socket == null || socket.isEmpty())
            problems.add("WAYLAND_SOCKET unset");
        if (ld == null || !ld.contains("/usr/lib"))
            problems.add("LD_LIBRARY_PATH missing usr/lib");
        recordStage(ctx, "launch_env", "Launch environment", problems.isEmpty() ? "PASS" : "FAIL",
                "WAYLAND_DISPLAY=" + waylandDisplay
                        + " XDG_RUNTIME_DIR=" + xdg
                        + " WAYLAND_SOCKET=" + socket
                        + " LD_LIBRARY_PATH=" + ld
                        + (problems.isEmpty() ? "" : " | " + String.join(" | ", problems)),
                problems.isEmpty() ? null
                        : "GuestProgramLauncherComponent must set WAYLAND_DISPLAY/XDG_RUNTIME_DIR/WAYLAND_SOCKET and put usr/lib on LD_LIBRARY_PATH.");
    }

    // ---------- internals ----------

    private static boolean isElf(File f) {
        try {
            byte[] magic = new byte[4];
            java.io.InputStream in = new java.io.FileInputStream(f);
            try {
                int n = in.read(magic);
                return n == 4 && magic[0] == 0x7f && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static String probeSocket(File socketFile) {
        // A live connect probe needs Java 16+/API 33 UNIX sockets, which this
        // project's compileSdk doesn't expose. Existence + readability is the
        // reliable launch-time check; the real connection is proven at exit by
        // the compositor's "[srv] client bound" markers in application.log.
        if (!socketFile.canRead()) return "exists but NOT readable";
        return "exists + readable (size=" + socketFile.length() + "B)";
    }

    private static String readFile(File f) {
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<LogLine> collectLogLines(Context ctx) {
        List<LogLine> out = new ArrayList<>();
        File filesDir = ctx.getFilesDir();
        addFile(out, new File(filesDir, "wine_stderr.log"));
        File logs = logsDir(ctx);
        File[] entries = logs.isDirectory() ? logs.listFiles() : null;
        if (entries != null) {
            for (File f : entries) {
                if (!f.isFile()) continue;
                String name = f.getName();
                if (name.startsWith("wine_") && name.endsWith(".txt")) addFile(out, f);
                else if (name.equals("application.log")) addFile(out, f);
                else if (name.equals("guest-process-exit.log")) addFile(out, f);
                else if (name.equals("wayland-driver-install.log")) addFile(out, f);
            }
        }
        return out;
    }

    private static void addFile(List<LogLine> out, File f) {
        if (!f.isFile() || f.length() == 0) return;
        try {
            // Cap per-file memory: read only the tail for large logs. All
            // failure evidence (driver load errors, crash dumps) is at the end.
            long maxBytes = 8L * 1024 * 1024;
            long length = f.length();
            long offset = length > maxBytes ? length - maxBytes : 0;
            List<String> raw = new ArrayList<>();
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
                if (offset > 0) {
                    raf.seek(offset);
                    // Skip the possibly-split first line.
                    int c = raf.read();
                    while (c != -1 && c != '\n') c = raf.read();
                    offset = raf.getFilePointer();
                }
                raf.seek(offset);
                byte[] buf = new byte[(int) (length - offset)];
                int n = raf.read(buf);
                String tail = new String(buf, 0, n, StandardCharsets.UTF_8);
                for (String line : tail.split("\n", -1)) raw.add(line);
            }
            for (int i = 0; i < raw.size(); i++) {
                out.add(new LogLine(f.getName(), i + 1, raw.get(i)));
            }
        } catch (Throwable t) {
            Log.w(TAG, "addFile failed for " + f, t);
        }
    }

    private static List<LogLine> grep(List<LogLine> lines, Pattern pattern) {
        List<LogLine> matches = new ArrayList<>();
        for (LogLine l : lines) {
            if (pattern.matcher(l.text).find()) matches.add(l);
        }
        return matches;
    }

    private static boolean hasSignature(List<Detection> findings, String signature) {
        for (Detection d : findings) {
            if (d.signature.equals(signature)) return true;
        }
        return false;
    }

    private static JSONObject findStage(JSONObject json, String id) {
        JSONArray stages = json.optJSONArray("stages");
        if (stages == null) return null;
        for (int i = 0; i < stages.length(); i++) {
            JSONObject s = stages.optJSONObject(i);
            if (s != null && id.equals(s.optString("id"))) return s;
        }
        return null;
    }

    private static void writeTxtReport(Context ctx, JSONObject json) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("==========================================================\n");
            sb.append(" WayLandIE Diagnostic Report\n");
            sb.append("==========================================================\n");
            JSONObject session = json.optJSONObject("session");
            if (session != null && session.has("container"))
                sb.append("container      : ").append(session.optString("container")).append("\n");
            if (session != null && session.has("winePath"))
                sb.append("winePath       : ").append(session.optString("winePath")).append("\n");
            if (session != null && session.has("graphicsDriver"))
                sb.append("graphicsDriver : ").append(session.optString("graphicsDriver")).append("\n");

            JSONObject failure = json.optJSONObject("failure");
            sb.append("\n-- VERDICT --\n");
            if (failure != null && failure.optBoolean("detected", false)) {
                sb.append("DETECTED FAILURE at stage: ")
                        .append(failure.optString("rootCauseStage", "?")).append("\n");
                if (failure.has("signature"))
                    sb.append("Signature : ").append(failure.optString("signature")).append("\n");
                if (failure.has("explanation"))
                    sb.append("Why       : ").append(failure.optString("explanation")).append("\n");
                if (failure.has("hint"))
                    sb.append("Fix hint  : ").append(failure.optString("hint")).append("\n");
            } else {
                sb.append("No failure detected in the captured logs.\n");
                sb.append("If the game still does not start, enable app debug + wine debug logging\n")
                  .append("so the next report has complete evidence.\n");
            }

            sb.append("\n-- STAGES --\n");
            JSONArray stages = json.optJSONArray("stages");
            if (stages != null) {
                for (int i = 0; i < stages.length(); i++) {
                    JSONObject s = stages.optJSONObject(i);
                    if (s == null) continue;
                    sb.append(String.format("[%5s] %-22s %s%n",
                            s.optString("status", "?"),
                            s.optString("id", "?"),
                            s.optString("name", "")));
                    if (s.has("detail")) sb.append("        detail: ").append(s.optString("detail")).append("\n");
                    if (s.has("hint")) sb.append("        hint  : ").append(s.optString("hint")).append("\n");
                }
            }

            sb.append("\n-- ENVIRONMENT --\n");
            JSONObject environment = json.optJSONObject("environment");
            if (environment != null) {
                java.util.Iterator<String> keys = environment.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    sb.append("  ").append(k).append("=").append(environment.optString(k)).append("\n");
                }
            }

            JSONArray evidence = json.optJSONArray("evidence");
            if (evidence != null && evidence.length() > 0) {
                sb.append("\n-- EVIDENCE (matched log lines) --\n");
                for (int i = 0; i < evidence.length(); i++) {
                    JSONObject e = evidence.optJSONObject(i);
                    if (e == null) continue;
                    sb.append("  ").append(e.optString("file")).append(":")
                      .append(e.optInt("line")).append(": ")
                      .append(e.optString("text")).append("\n");
                }
            }
            sb.append("==========================================================\n");

            File txt = txtFile(ctx);
            if (!txt.getParentFile().isDirectory()) txt.getParentFile().mkdirs();
            try (OutputStreamWriter w =
                    new OutputStreamWriter(new FileOutputStream(txt), StandardCharsets.UTF_8)) {
                w.write(sb.toString());
            }
        } catch (Throwable t) {
            Log.w(TAG, "writeTxtReport failed", t);
        }
    }
}
