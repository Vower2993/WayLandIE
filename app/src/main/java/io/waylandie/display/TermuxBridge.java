package io.waylandie.display;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

/**
 * TermuxBridge sends commands into Termux without root.
 *
 * <p>It uses the {@code com.termux.RUN_COMMAND} intent that the official Termux
 * app exposes when the user has the <i>Termux:API</i> companion installed. The
 * intent runs a single shell command inside Termux under Termux's own UID,
 * which has no special privileges — exactly what we want for the no-root path.
 *
 * <p>If Termux:API is not installed, callers should fall back to copying the
 * command to the clipboard and launching the Termux launcher activity so the
 * user can paste it manually.
 */
final class TermuxBridge {

    static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND";
    private static final String TERMUX_RUN_COMMAND_COMPONENT =
            "com.termux/com.termux.app.RunCommandService";

    private TermuxBridge() {
    }

    /**
     * Returns true if Termux is installed on the device.
     */
    static boolean isTermuxInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException notInstalled) {
            return false;
        }
    }

    /**
     * Opens the Termux launcher activity so the user can run a command
     * manually. Always works (no special permission required).
     */
    static void openTermuxLauncher(Context context) {
        Intent launchIntent = context.getPackageManager()
                .getLaunchIntentForPackage(TERMUX_PACKAGE);
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launchIntent);
        }
    }

    /**
     * Attempts to run a shell command inside Termux via the RUN_COMMAND
     * service. Requires Termux:API installed and the
     * {@code allow-external-apps} Termux preference set to {@code true}.
     *
     * <p>Returns true if the startService call succeeded. Returns false if
     * Termux or Termux:API is missing or the user has not opted in — caller
     * should fall back to {@link #openTermuxLauncher(Context)} and ask the
     * user to paste the command.
     */
    static boolean tryRunCommand(Context context, String command) {
        return tryRunCommand(context, "/data/data/com.termux/files/usr/bin/bash", "-lc", command);
    }

    /**
     * Same as {@link #tryRunCommand(Context, String)} but lets the caller pick
     * the executable + arguments. Useful for running scripts directly without
     * a shell wrapper.
     */
    static boolean tryRunCommand(Context context, String executable, String... args) {
        if (!isTermuxInstalled(context)) {
            return false;
        }
        Intent intent = new Intent();
        intent.setComponent(ComponentName.unflattenFromString(TERMUX_RUN_COMMAND_COMPONENT));
        intent.setAction(TERMUX_RUN_COMMAND_ACTION);
        intent.putExtra("com.termux.RUN_COMMAND_PATH", executable);
        if (args != null && args.length > 0) {
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args);
        }
        // Run in foreground so the user sees Termux pop up.
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            return true;
        } catch (IllegalStateException | SecurityException error) {
            // startForegroundService from background may fail on Android 12+,
            // or Termux:API may be missing.
            return false;
        }
    }

    /**
     * Opens F-Droid's Termux page in the browser. The Play Store version of
     * Termux is unmaintained — F-Droid is the only supported source.
     */
    static void openTermuxInstallPage(Context context) {
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://f-droid.org/packages/com.termux/"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (RuntimeException ignored) {
            // No browser — fall back to termux.dev
            try {
                context.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://termux.dev")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored2) {
                // Give up silently.
            }
        }
    }

    /**
     * Convenience: builds the canonical "run a command in the WayLandIE
     * Termux-native (bionic) environment" shell snippet used by the app.
     *
     * <p>No proot wrapping — Termux IS the bionic environment. Just runs
     * the inner command via bash -lc.
     */
    static String termuxCommand(String innerCommand) {
        return "bash -lc " + "'" + innerCommand.replace("'", "'\\''") + "'";
    }

    /**
     * @deprecated Use {@link #termuxCommand(String)} instead. The proot
     * Debian path is deprecated in favor of the bionic termux-native
     * architecture.
     */
    @Deprecated
    static String debianProotCommand(String innerCommand) {
        return "proot-distro login debian --shared-tmp -- bash -lc " +
                "'" + innerCommand.replace("'", "'\\''") + "'";
    }
}
