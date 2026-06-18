package io.waylandie.display;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * SetupWizardActivity walks a brand-new user through the no-root setup path.
 *
 * <p>The wizard is intentionally linear: each step is verified before the
 * next one becomes runnable. Every step either runs a Termux RUN_COMMAND
 * intent (preferred) or copies the command to the clipboard + opens Termux
 * (fallback when Termux:API is missing).
 *
 * <p>Steps:
 * <ol>
 *   <li>Install Termux (F-Droid page)</li>
 *   <li>Grant Termux storage access (<code>termux-setup-storage</code>)</li>
 *   <li>Install proot-distro + Debian</li>
 *   <li>Push WayLandIE scripts to ~/storage/downloads/WayLandIE/</li>
 *   <li>Install WayLandIE inside Debian (<code>sh install.sh --backend proot</code>)</li>
 *   <li>Start the WayLandIE display activity</li>
 *   <li>Run <code>waylandie-doctor</code> inside Debian to verify</li>
 * </ol>
 *
 * <p>The wizard is re-runnable. If the user already completed setup, every
 * step shows "Done" and the user can just tap Finish.
 */
public final class SetupWizardActivity extends Activity {

    private LinearLayout stepsContainer;
    private Button btnFinish;
    private final List<StepHolder> holders = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        stepsContainer = findViewById(R.id.stepsContainer);
        btnFinish = findViewById(R.id.btnFinish);

        // Extract bundled scripts before showing the wizard so step 4 can
        // reference them.
        try {
            AssetInstaller.installAssets(this);
        } catch (IOException error) {
            toast("Asset install failed: " + error.getMessage());
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (SetupStep step : SetupStep.STEPS) {
            View row = inflater.inflate(R.layout.item_setup_step, stepsContainer, false);
            StepHolder holder = new StepHolder(step, row);
            holders.add(holder);
            stepsContainer.addView(row);
            holder.bind(this);
        }

        btnFinish.setOnClickListener(v -> finish());
    }

    void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("WayLandIE", text));
    }

    void runOrCopyCommand(String command, Runnable onSent) {
        if (TermuxBridge.isTermuxInstalled(this)) {
            boolean sent = TermuxBridge.tryRunCommand(this, command);
            if (sent) {
                onSent.run();
                return;
            }
        }
        // Fallback: copy + open Termux.
        copyToClipboard(command);
        toast("Command copied — paste into Termux.");
        if (TermuxBridge.isTermuxInstalled(this)) {
            TermuxBridge.openTermuxLauncher(this);
        } else {
            TermuxBridge.openTermuxInstallPage(this);
        }
        onSent.run();
    }

    void markStepDone(int index) {
        if (index < holders.size()) {
            holders.get(index).setState(StepState.DONE);
        }
        // Reveal finish button when all done.
        boolean allDone = true;
        for (StepHolder h : holders) {
            if (h.state != StepState.DONE && h.state != StepState.SKIPPED) {
                allDone = false;
                break;
            }
        }
        btnFinish.setVisibility(allDone ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------------

    enum StepState {
        PENDING("○", 0xFF9AA0A6),
        RUNNING("◌", 0xFFFDD663),
        DONE("✓", 0xFF81C995),
        FAILED("✗", 0xFFF28B82),
        SKIPPED("–", 0xFF9AA0A6);

        final String glyph;
        final int color;

        StepState(String glyph, int color) {
            this.glyph = glyph;
            this.color = color;
        }
    }

    static final class SetupStep {
        final String title;
        final String help;
        final String command;  // may be null for non-command steps

        SetupStep(String title, String help, String command) {
            this.title = title;
            this.help = help;
            this.command = command;
        }

        static final SetupStep[] STEPS = {
            new SetupStep(
                "1. Install Termux",
                "Open the F-Droid page for Termux. The Play Store version is unmaintained.",
                null),
            new SetupStep(
                "2. Grant Termux storage access",
                "Runs `termux-setup-storage` so Termux can read the shared Downloads folder.",
                "termux-setup-storage"),
            new SetupStep(
                "3. Install proot-distro + Debian",
                "Installs proot-distro (~5 MB) and then `proot-distro install debian` (~80 MB). No root.",
                "pkg install -y proot-distro && proot-distro install debian"),
            new SetupStep(
                "4. Push WayLandIE scripts into Downloads",
                "Scripts already extracted to /sdcard/Download/WayLandIE/linux-runtime/. Tap to verify.",
                "ls -la ~/storage/downloads/WayLandIE/linux-runtime/install.sh"),
            new SetupStep(
                "5. Install WayLandIE inside Debian",
                "Logs into Debian proot and runs install.sh --backend proot. Installs Wayland, mesa, Vulkan, gamescope.",
                "proot-distro login debian --shared-tmp -- bash -lc 'cd /sdcard/Download/WayLandIE/linux-runtime && sh install.sh --backend proot --prefix /usr/local --install-packages'"),
            new SetupStep(
                "6. Start the WayLandIE display",
                "Launches the Android display activity + keep-alive service. Tap and come back here when the display is showing.",
                null),
            new SetupStep(
                "7. Run waylandie-doctor inside Debian",
                "Verifies socket, Vulkan driver, and a test render of vkcube.",
                "proot-distro login debian --shared-tmp -- bash -lc 'waylandie-doctor'"),
        };
    }

    final class StepHolder {
        final SetupStep step;
        final View view;
        final TextView stateView;
        final TextView titleView;
        final TextView helpView;
        final TextView commandView;
        final View commandScroll;
        final Button btnRun;
        final Button btnSkip;
        final TextView resultView;
        StepState state = StepState.PENDING;

        StepHolder(SetupStep step, View view) {
            this.step = step;
            this.view = view;
            this.stateView = view.findViewById(R.id.stepState);
            this.titleView = view.findViewById(R.id.stepTitle);
            this.helpView = view.findViewById(R.id.stepHelp);
            this.commandView = view.findViewById(R.id.stepCommand);
            this.commandScroll = view.findViewById(R.id.stepCommandScroll);
            this.btnRun = view.findViewById(R.id.btnStepRun);
            this.btnSkip = view.findViewById(R.id.btnStepSkip);
            this.resultView = view.findViewById(R.id.stepResult);
        }

        void bind(Context context) {
            titleView.setText(step.title);
            helpView.setText(step.help);
            if (step.command != null) {
                commandView.setText(step.command);
                commandScroll.setVisibility(View.VISIBLE);
                btnRun.setText(R.string.setup_action_run);
            } else if (step.title.startsWith("1.")) {
                btnRun.setText(R.string.setup_action_install_termux);
            } else if (step.title.startsWith("6.")) {
                btnRun.setText(R.string.home_start_display);
            }
            btnRun.setOnClickListener(v -> onRun());
            btnSkip.setOnClickListener(v -> {
                setState(StepState.SKIPPED);
                checkAllDone();
            });
            setState(StepState.PENDING);
        }

        void setState(StepState newState) {
            state = newState;
            stateView.setText(newState.glyph);
            stateView.setTextColor(newState.color);
        }

        void onRun() {
            setState(StepState.RUNNING);
            if (step.title.startsWith("1.")) {
                // Install Termux — open F-Droid.
                TermuxBridge.openTermuxInstallPage(SetupWizardActivity.this);
                resultView.setVisibility(View.VISIBLE);
                resultView.setText("Opened F-Droid. Install Termux, then come back and tap Skip.");
                resultView.setTextColor(0xFFFDD663);
                setState(StepState.PENDING);
                return;
            }
            if (step.title.startsWith("6.")) {
                // Start display locally.
                BridgeKeepAliveService.start(SetupWizardActivity.this);
                Intent intent = new Intent(SetupWizardActivity.this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                setState(StepState.DONE);
                checkAllDone();
                return;
            }
            if (step.command == null) {
                setState(StepState.DONE);
                checkAllDone();
                return;
            }
            runOrCopyCommand(step.command, () -> {
                resultView.setVisibility(View.VISIBLE);
                resultView.setText("Sent to Termux. Verify it succeeded, then tap Next.");
                resultView.setTextColor(0xFF9AA0A6);
                setState(StepState.DONE);
                checkAllDone();
            });
        }
    }

    private void checkAllDone() {
        boolean allDone = true;
        for (StepHolder h : holders) {
            if (h.state != StepState.DONE && h.state != StepState.SKIPPED) {
                allDone = false;
                break;
            }
        }
        btnFinish.setVisibility(allDone ? View.VISIBLE : View.GONE);
    }
}
