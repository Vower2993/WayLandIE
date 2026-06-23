package io.waylandie.display.runtime.container;

import android.content.Context;
import android.util.Log;

import io.waylandie.display.runtime.environment.WineRunner;
import io.waylandie.display.shared.util.LogRingBuffer;

import java.io.File;
import java.util.List;

/**
 * Orchestrates launching a Wine desktop or game within a Container.
 *
 * <p>Container launch modes:
 * <ul>
 *   <li>{@code DESKTOP} — launches `wine explorer /desktop=shell,WxH` to
 *       show the Wine desktop with file manager, taskbar, etc.</li>
 *   <li>{@code GAME} — launches a specific .exe within the container's
 *       Wine prefix.</li>
 * </ul>
 *
 * <p>Diagnostics: Every launch step is logged to logcat, LogRingBuffer,
 * and the installerDiagnostics buffer (which GameLaunchTracer dumps to
 * the trace file). The full launch chain is traced:
 * <ol>
 *   <li>Container validation</li>
 *   <li>Wine prefix existence check</li>
 *   <li>Registry setup (GraphicsDriver, Windows version)</li>
 *   <li>Environment variable configuration</li>
 *   <li>Wine process launch</li>
 *   <li>Post-launch verification</li>
 * </ol>
 */
public class ContainerLauncher {
    private static final String TAG = "WayLandIE/ContainerLauncher";

    public enum LaunchMode { DESKTOP, GAME }

    private final Context context;
    private final WineRunner wineRunner;
    private final StringBuilder diagnostics;

    public ContainerLauncher(Context context, WineRunner wineRunner) {
        this.context = context;
        this.wineRunner = wineRunner;
        this.diagnostics = new StringBuilder();
    }

    /**
     * Launches the Wine desktop (explorer.exe) within the given container.
     * This shows the Wine desktop with taskbar, file manager, etc.
     */
    public Process launchDesktop(Container container) {
        log("=== ContainerLauncher: launchDesktop ===");
        log("container: " + container.getName() + " (id=" + container.getId() + ")");

        // Validate container
        List<String> issues = container.validate();
        if (!issues.isEmpty()) {
            log("VALIDATION FAILED:");
            for (String issue : issues) log("  - " + issue);
            return null;
        }
        log("validation: PASS");

        // Check wine prefix
        if (container.getWinePrefixPath() != null) {
            File prefix = new File(container.getWinePrefixPath());
            log("winePrefix: " + prefix.getAbsolutePath() + " exists=" + prefix.exists());
        }

        // For desktop mode, we launch wine explorer.exe with /desktop flag
        // This creates a virtual desktop window that contains the Wine desktop.
        // The game .exe is NOT passed here — desktop mode just shows the desktop.
        String desktopSize = container.getDisplayWidth() + "x" + container.getDisplayHeight();
        log("desktop size: " + desktopSize);
        log("launch args: explorer /desktop=shell," + desktopSize);

        try {
            Process p = wineRunner.execWine("explorer",
                    new String[]{"/desktop=shell," + desktopSize}, true);
            log("Wine process started: pid=" + getPid(p));
            log("=== ContainerLauncher: launchDesktop COMPLETE ===");
            return p;
        } catch (Exception e) {
            log("FAILED to launch desktop: " + e.getMessage());
            Log.e(TAG, "launchDesktop failed", e);
            return null;
        }
    }

    /**
     * Launches a specific game .exe within the given container.
     *
     * <p>Uses the WinNative pattern: {@code wine explorer /desktop=shell,WxH game.exe}
     * This is CRITICAL — without the explorer wrapper:
     * <ul>
     *   <li>Wine runs wineboot separately, which starts explorer.exe</li>
     *   <li>Explorer.exe and game.exe fight for the desktop window</li>
     *   <li>Explorer renders 1 frame (the blank desktop), game never gets foregrounded</li>
     * </ul>
     * With the explorer wrapper, the game .exe is the shell's initial process
     * on the virtual desktop — it starts immediately, no wineboot race.
     */
    public Process launchGame(Container container, String exePath, String extraArgs) {
        log("=== ContainerLauncher: launchGame ===");
        log("container: " + container.getName() + " (id=" + container.getId() + ")");
        log("exe: " + exePath);
        log("args: " + (extraArgs != null ? extraArgs : "(none)"));

        // Validate container
        List<String> issues = container.validate();
        if (!issues.isEmpty()) {
            log("VALIDATION FAILED:");
            for (String issue : issues) log("  - " + issue);
            return null;
        }
        log("validation: PASS");

        // Check exe exists
        File exeFile = new File(exePath);
        log("exe exists: " + exeFile.exists() + " readable=" + exeFile.canRead());
        if (!exeFile.exists()) {
            log("FAILED: exe not found at " + exePath);
            return null;
        }

        // Launch with explorer desktop wrapper — needed for file management,
        // wine config, game launchers, and installers.
        String desktopSize = container.getDisplayWidth() + "x" + container.getDisplayHeight();
        log("desktop wrapper: explorer /desktop=shell," + desktopSize);

        try {
            java.util.List<String> argList = new java.util.ArrayList<>();
            argList.add("/desktop=shell," + desktopSize);
            argList.add(exePath);
            if (extraArgs != null && !extraArgs.isEmpty()) {
                for (String a : extraArgs.split("\\s+")) {
                    if (!a.isEmpty()) argList.add(a);
                }
            }
            String[] args = argList.toArray(new String[0]);
            Process p = wineRunner.execWine("explorer", args, true);
            log("Wine process started: pid=" + getPid(p));
            log("=== ContainerLauncher: launchGame COMPLETE ===");
            return p;
        } catch (Exception e) {
            log("FAILED to launch game: " + e.getMessage());
            Log.e(TAG, "launchGame failed", e);
            return null;
        }
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        LogRingBuffer.append("[container] " + msg);
        diagnostics.append("[container] ").append(msg).append('\n');
    }

    public String getDiagnostics() {
        return diagnostics.toString();
    }

    private long getPid(Process p) {
        try {
            java.lang.reflect.Method m = p.getClass().getMethod("pid");
            m.setAccessible(true);
            return (long) m.invoke(p);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Runs a full diagnostic check on the container system.
     * Checks all containers, shortcuts, and launch prerequisites.
     */
    public static String runFullDiagnostics(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Container System Full Diagnostics ===\n");

        ContainerManager cm = new ContainerManager(context);
        sb.append(cm.dumpDiagnostics()).append('\n');

        ShortcutManager sm = new ShortcutManager(context);
        sb.append(sm.dumpDiagnostics()).append('\n');

        // Check imagefs
        File imagefs = new File(context.getFilesDir(), "imagefs");
        sb.append("imagefs exists: ").append(imagefs.exists()).append('\n');
        sb.append("imagefs path: ").append(imagefs.getAbsolutePath()).append('\n');

        // Check proton
        File proton = new File(context.getFilesDir(), "contents/proton/active");
        sb.append("proton exists: ").append(proton.exists()).append('\n');
        File wineBin = new File(proton, "bin/wine");
        sb.append("wine binary exists: ").append(wineBin.exists()).append('\n');

        // Check turnip driver
        File turnip = new File(context.getFilesDir(), "contents/turnip/active/libvulkan_freedreno.so");
        sb.append("turnip driver exists: ").append(turnip.exists());
        if (turnip.exists()) sb.append(" (").append(turnip.length()).append(" bytes)");
        sb.append('\n');

        // Check adrenotools
        File adrenotools = new File(context.getApplicationInfo().nativeLibraryDir, "libadrenotools.so");
        sb.append("libadrenotools.so exists: ").append(adrenotools.exists());
        if (adrenotools.exists()) sb.append(" (").append(adrenotools.length()).append(" bytes)");
        sb.append('\n');

        // Check libc++_shared
        File libcxx = new File(context.getApplicationInfo().nativeLibraryDir, "libc++_shared.so");
        sb.append("libc++_shared.so exists: ").append(libcxx.exists());
        if (libcxx.exists()) sb.append(" (").append(libcxx.length()).append(" bytes)");
        sb.append('\n');

        sb.append("=== End Container System Full Diagnostics ===");
        String result = sb.toString();
        Log.i(TAG, result);
        return result;
    }
}
