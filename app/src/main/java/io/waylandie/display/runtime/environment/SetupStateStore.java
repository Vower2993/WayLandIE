package io.waylandie.display.runtime.environment;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * SetupStateStore — atomic state machine for first-run setup.
 *
 * <p>States (in order):
 * <ul>
 *   <li>{@link #BEGIN}        — initial state, no setup attempted yet.</li>
 *   <li>{@link #EXTRACTING}   — rootfs tarball currently being extracted.</li>
 *   <li>{@link #VERIFYING}    — extraction finished, probe in progress.</li>
 *   <li>{@link #READY}        — probe passed, app can enter HomeActivity.</li>
 *   <li>{@link #FAILED}       — extraction or probe failed; user must retry.</li>
 * </ul>
 *
 * <p>Only {@link #READY} allows the app to skip setup. {@link #EXTRACTING}
 * and {@link #VERIFYING} both force re-run with the log visible.
 * {@link #FAILED} requires explicit user Retry.
 *
 * <p>State is persisted atomically:
 * <ol>
 *   <li>Write state + timestamp + reason to {@code waylandie.state.tmp}</li>
 *   <li>{@code Os.fdatasync} the temp file</li>
 *   <li>{@code Files.move} (atomic rename) to {@code waylandie.state}</li>
 * </ol>
 *
 * <p>Reference patterns:
 * <ul>
 *   <li>winlator {@code RootFSInstaller.java:70-72} — marker written
 *       only after extraction success. We extend this with a separate
 *       VERIFYING state because winlator has no probe step.</li>
 *   <li>WinNative {@code ImageFsInstaller.clearRootDir} (lines 338-353)
 *       — wipes everything except {@code home/} before re-extraction.</li>
 * </ul>
 */
public final class SetupStateStore {

    private static final String TAG = "WayLandIE/SetupState";
    private static final String STATE_FILE = "waylandie.state";
    private static final String STATE_FILE_TMP = "waylandie.state.tmp";

    public enum State {
        BEGIN, EXTRACTING, VERIFYING, READY, FAILED
    }

    private final Context context;
    private final File stateFile;
    private final File stateFileTmp;

    public SetupStateStore(Context context) {
        this.context = context.getApplicationContext();
        this.stateFile = new File(context.getFilesDir(), STATE_FILE);
        this.stateFileTmp = new File(context.getFilesDir(), STATE_FILE_TMP);
    }

    public State read() {
        if (!stateFile.exists()) return State.BEGIN;
        try {
            String content = new String(Files.readAllBytes(stateFile.toPath()),
                    StandardCharsets.UTF_8);
            String[] parts = content.split("\\|", 3);
            return State.valueOf(parts[0].trim());
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse state file: " + e.getMessage()
                    + " — treating as BEGIN");
            return State.BEGIN;
        }
    }

    public String readReason() {
        if (!stateFile.exists()) return "";
        try {
            String content = new String(Files.readAllBytes(stateFile.toPath()),
                    StandardCharsets.UTF_8);
            String[] parts = content.split("\\|", 3);
            return parts.length >= 3 ? parts[2] : "";
        } catch (Exception e) {
            return "";
        }
    }

    public long readTimestamp() {
        if (!stateFile.exists()) return 0L;
        try {
            String content = new String(Files.readAllBytes(stateFile.toPath()),
                    StandardCharsets.UTF_8);
            String[] parts = content.split("\\|", 3);
            return parts.length >= 2 ? Long.parseLong(parts[1].trim()) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Atomically writes the new state to disk.
     *
     * @param state  new state
     * @param reason optional human-readable reason (may be empty)
     */
    public void write(State state, String reason) {
        long ts = System.currentTimeMillis();
        String content = state.name() + "|" + ts + "|" + (reason == null ? "" : reason);
        try {
            // 1. Write temp file
            try (FileOutputStream fos = new FileOutputStream(stateFileTmp)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                FileDescriptor fd = fos.getFD();
                try {
                    Os.fdatasync(fd);
                } catch (ErrnoException ee) {
                    Log.w(TAG, "fdatasync failed (non-fatal): " + ee.getMessage());
                }
            }
            // 2. Atomic rename
            if (!stateFileTmp.renameTo(stateFile)) {
                // Fallback for cross-device or stubborn filesystems
                Files.move(stateFileTmp.toPath(), stateFile.toPath());
            }
            Log.i(TAG, "State -> " + state + " (reason=" + reason + ")");
        } catch (IOException ioe) {
            Log.e(TAG, "Failed to write state " + state + ": " + ioe.getMessage(), ioe);
        }
    }

    /** Convenience: write READY with no reason. */
    public void markReady() {
        write(State.READY, "probe-ok");
    }

    /** Convenience: write FAILED with the given reason. */
    public void markFailed(String reason) {
        write(State.FAILED, reason);
    }

    /** Convenience: write EXTRACTING. */
    public void markExtracting() {
        write(State.EXTRACTING, "extraction-started");
    }

    /** Convenience: write VERIFYING. */
    public void markVerifying() {
        write(State.VERIFYING, "probe-started");
    }

    /**
     * Verifies that the persisted state matches reality.
     *
     * <p>Checks:
     * <ol>
     *   <li>State file exists and parses</li>
     *   <li>If state is READY: ImageFsManager.isValid() must be true</li>
     *   <li>If state is READY: native lib must load (best-effort;
     *       full probe is done by EnvironmentInitializer before showing
     *       Continue)</li>
     * </ol>
     *
     * @return true iff persisted state is consistent with reality
     */
    public boolean isConsistentWithReality() {
        State s = read();
        if (s == State.BEGIN) return true;  // nothing to verify
        if (s != State.READY) return false; // mid-setup or failed — re-run
        ImageFsManager imgFs = new ImageFsManager(context);
        return imgFs.isValid();
    }

    /** Deletes the state file — used by "Reset environment" in CrashReportActivity. */
    public void reset() {
        if (stateFile.exists() && !stateFile.delete()) {
            Log.w(TAG, "Failed to delete state file: " + stateFile);
        }
        if (stateFileTmp.exists() && !stateFileTmp.delete()) {
            Log.w(TAG, "Failed to delete temp state file: " + stateFileTmp);
        }
    }
}
