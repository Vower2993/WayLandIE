package io.waylandie.display;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import io.waylandie.display.runtime.container.Container;
import io.waylandie.display.runtime.container.ContainerManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Settings editor for a Container. Allows the user to configure:
 * - Name
 * - Display resolution (width × height)
 * - Fullscreen mode
 * - Windows version (win10, win7, winxp, win98)
 * - DXVK enabled/disabled
 * - FEX enabled/disabled
 * - Audio enabled/disabled
 *
 * <p>Diagnostics: All changes are logged. The container's full diagnostics
 * are dumped on save.
 */
public class ContainerSettingsActivity extends Activity {
    private static final String TAG = "WayLandIE/ContainerSettings";

    public static final String EXTRA_CONTAINER_ID = "containerId";

    private ContainerManager manager;
    private Container container;

    private EditText nameField;
    private EditText widthField;
    private EditText heightField;
    private CheckBox fullscreenCheck;
    private Spinner windowsVersionSpinner;
    private CheckBox dxvkCheck;
    private CheckBox fexCheck;
    private CheckBox audioCheck;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "ContainerSettingsActivity created");

        manager = new ContainerManager(this);
        String containerId = getIntent().getStringExtra(EXTRA_CONTAINER_ID);
        container = manager.getContainer(containerId);

        if (container == null) {
            Log.e(TAG, "Container not found: " + containerId);
            Toast.makeText(this, "Container not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Log.i(TAG, "Editing container: " + container.getName() + " (id=" + containerId + ")");

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        // Title
        TextView title = new TextView(this);
        title.setText("Container Settings");
        title.setTextSize(20);
        title.setPadding(0, 0, 0, 24);
        root.addView(title);

        // Name
        root.addView(makeLabel("Container Name"));
        nameField = new EditText(this);
        nameField.setText(container.getName());
        root.addView(nameField);

        // Display resolution
        root.addView(makeLabel("Display Resolution"));
        LinearLayout resRow = new LinearLayout(this);
        resRow.setOrientation(LinearLayout.HORIZONTAL);
        widthField = new EditText(this);
        widthField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        widthField.setText(String.valueOf(container.getDisplayWidth()));
        widthField.setHint("Width");
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        resRow.addView(widthField, wlp);
        heightField = new EditText(this);
        heightField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        heightField.setText(String.valueOf(container.getDisplayHeight()));
        heightField.setHint("Height");
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        resRow.addView(heightField, hlp);
        root.addView(resRow);

        // Fullscreen
        fullscreenCheck = new CheckBox(this);
        fullscreenCheck.setText("Fullscreen");
        fullscreenCheck.setChecked(container.isFullscreen());
        root.addView(fullscreenCheck);

        // Windows version
        root.addView(makeLabel("Windows Version"));
        windowsVersionSpinner = new Spinner(this);
        List<String> versions = Arrays.asList("win10", "win7", "winxp", "win98");
        ArrayAdapter<String> verAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, versions);
        windowsVersionSpinner.setAdapter(verAdapter);
        int verIdx = versions.indexOf(container.getWindowsVersion());
        if (verIdx >= 0) windowsVersionSpinner.setSelection(verIdx);
        root.addView(windowsVersionSpinner);

        // DXVK
        dxvkCheck = new CheckBox(this);
        dxvkCheck.setText("DXVK (DirectX → Vulkan)");
        dxvkCheck.setChecked(container.isDxvkEnabled());
        root.addView(dxvkCheck);

        // FEX
        fexCheck = new CheckBox(this);
        fexCheck.setText("FEX (x86 → ARM translation)");
        fexCheck.setChecked(container.isFexEnabled());
        root.addView(fexCheck);

        // Audio
        audioCheck = new CheckBox(this);
        audioCheck.setText("Audio");
        audioCheck.setChecked(container.isAudioEnabled());
        root.addView(audioCheck);

        // Diagnostics dump
        Button diagBtn = new Button(this);
        diagBtn.setText("Dump Diagnostics");
        diagBtn.setOnClickListener(v -> {
            String diag = container.dumpDiagnostics();
            Log.i(TAG, diag);
            Toast.makeText(this, "Diagnostics logged to logcat", Toast.LENGTH_SHORT).show();
        });
        root.addView(diagBtn);

        // Save button
        Button saveBtn = new Button(this);
        saveBtn.setText("Save");
        saveBtn.setOnClickListener(v -> save());
        root.addView(saveBtn);

        // Cancel button
        Button cancelBtn = new Button(this);
        cancelBtn.setText("Cancel");
        cancelBtn.setOnClickListener(v -> finish());
        root.addView(cancelBtn);

        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setPadding(0, 16, 0, 4);
        return tv;
    }

    private void save() {
        Log.i(TAG, "Saving container settings");

        container.setName(nameField.getText().toString().trim());
        try {
            container.setDisplayWidth(Integer.parseInt(widthField.getText().toString().trim()));
            container.setDisplayHeight(Integer.parseInt(heightField.getText().toString().trim()));
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid resolution", Toast.LENGTH_SHORT).show();
            return;
        }
        container.setFullscreen(fullscreenCheck.isChecked());
        container.setWindowsVersion((String) windowsVersionSpinner.getSelectedItem());
        container.setDxvkEnabled(dxvkCheck.isChecked());
        container.setFexEnabled(fexCheck.isChecked());
        container.setAudioEnabled(audioCheck.isChecked());

        // Validate
        List<String> issues = container.validate();
        if (!issues.isEmpty()) {
            Log.w(TAG, "Validation issues: " + issues);
            Toast.makeText(this, "Validation: " + issues.get(0), Toast.LENGTH_LONG).show();
        }

        manager.updateContainer(container);
        Log.i(TAG, "Container saved: " + container.getName());
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        finish();
    }
}
