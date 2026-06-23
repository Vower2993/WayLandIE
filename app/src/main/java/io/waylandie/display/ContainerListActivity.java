package io.waylandie.display;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import io.waylandie.display.runtime.container.Container;
import io.waylandie.display.runtime.container.ContainerLauncher;
import io.waylandie.display.runtime.container.ContainerManager;
import io.waylandie.display.runtime.container.GameShortcut;
import io.waylandie.display.runtime.container.ShortcutManager;
import io.waylandie.display.runtime.environment.WineRunner;
import io.waylandie.display.shared.util.LogRingBuffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Winlator-style container list + game shortcuts home screen.
 *
 * <p>Shows:
 * <ul>
 *   <li>A list of containers — tap to select, long-press for options</li>
 *   <li>"Start Desktop" button — launches Wine desktop (explorer.exe)</li>
 *   <li>"Browse for .exe" button — opens FileExplorerActivity</li>
 *   <li>Game shortcuts grid — tap to launch, long-press to delete</li>
 *   <li>"Create Container" button — creates a new container</li>
 *   <li>"Diagnostics" button — dumps full system diagnostics</li>
 * </ul>
 *
 * <p>Diagnostics: Every action is logged to logcat + LogRingBuffer.
 */
public class ContainerListActivity extends Activity {
    private static final String TAG = "WayLandIE/ContainerList";

    private ContainerManager containerManager;
    private ShortcutManager shortcutManager;
    private WineRunner wineRunner;
    private Container selectedContainer;
    private ContainerAdapter containerAdapter;
    private ShortcutAdapter shortcutAdapter;
    private TextView shortcutsLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "ContainerListActivity created");

        containerManager = new ContainerManager(this);
        shortcutManager = new ShortcutManager(this);

        // Create default container if none exist
        if (containerManager.getContainers().isEmpty()) {
            Log.i(TAG, "No containers found — creating default");
            containerManager.createContainer("Default");
        }

        try {
            wineRunner = new WineRunner(this);
        } catch (Exception e) {
            Log.e(TAG, "Failed to init WineRunner: " + e.getMessage());
        }

        buildUI();
    }

    private void buildUI() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        // Title
        TextView title = new TextView(this);
        title.setText("Containers");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 16);
        root.addView(title);

        // Container list
        containerAdapter = new ContainerAdapter();
        ListView containerList = new ListView(this);
        containerList.setAdapter(containerAdapter);
        // Fixed height for ListView inside ScrollView
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 600);
        containerList.setLayoutParams(clp);
        containerList.setOnItemClickListener((parent, view, position, id) -> {
            selectedContainer = containerAdapter.getItem(position);
            Log.i(TAG, "Selected container: " + selectedContainer.getName());
            refreshShortcuts();
            Toast.makeText(this, "Selected: " + selectedContainer.getName(),
                    Toast.LENGTH_SHORT).show();
        });
        containerList.setOnItemLongClickListener((parent, view, position, id) -> {
            Container c = containerAdapter.getItem(position);
            showContainerOptions(c);
            return true;
        });
        root.addView(containerList);

        // Create container button
        Button createBtn = new Button(this);
        createBtn.setText("+ New Container");
        createBtn.setOnClickListener(v -> {
            EditText input = new EditText(this);
            input.setText("Container " + (containerManager.getContainers().size() + 1));
            new AlertDialog.Builder(this)
                    .setTitle("New Container")
                    .setMessage("Container name:")
                    .setView(input)
                    .setPositiveButton("Create", (d, w) -> {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) name = "New Container";
                        containerManager.createContainer(name);
                        containerAdapter.notifyDataSetChanged();
                        Log.i(TAG, "Created container: " + name);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        root.addView(createBtn);

        // Start desktop button
        Button startDesktopBtn = new Button(this);
        startDesktopBtn.setText("▶ Start Wine Desktop");
        startDesktopBtn.setOnClickListener(v -> {
            if (selectedContainer == null) {
                Toast.makeText(this, "Select a container first", Toast.LENGTH_SHORT).show();
                return;
            }
            startWineDesktop(selectedContainer);
        });
        root.addView(startDesktopBtn);

        // Browse button
        Button browseBtn = new Button(this);
        browseBtn.setText("📁 Browse for .exe");
        browseBtn.setOnClickListener(v -> {
            if (selectedContainer == null) {
                Toast.makeText(this, "Select a container first", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, FileExplorerActivity.class);
            intent.putExtra(FileExplorerActivity.EXTRA_MODE,
                    FileExplorerActivity.MODE_CREATE_SHORTCUT);
            intent.putExtra(FileExplorerActivity.EXTRA_CONTAINER_ID,
                    selectedContainer.getId());
            startActivity(intent);
        });
        root.addView(browseBtn);

        // Settings button
        Button settingsBtn = new Button(this);
        settingsBtn.setText("⚙ Container Settings");
        settingsBtn.setOnClickListener(v -> {
            if (selectedContainer == null) {
                Toast.makeText(this, "Select a container first", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, ContainerSettingsActivity.class);
            intent.putExtra(ContainerSettingsActivity.EXTRA_CONTAINER_ID,
                    selectedContainer.getId());
            startActivity(intent);
        });
        root.addView(settingsBtn);

        // Diagnostics button
        Button diagBtn = new Button(this);
        diagBtn.setText("🔍 System Diagnostics");
        diagBtn.setOnClickListener(v -> {
            String diag = ContainerLauncher.runFullDiagnostics(this);
            Log.i(TAG, diag);
            LogRingBuffer.append(diag);
            Toast.makeText(this, "Diagnostics logged to logcat", Toast.LENGTH_SHORT).show();
        });
        root.addView(diagBtn);

        // Shortcuts section
        shortcutsLabel = new TextView(this);
        shortcutsLabel.setText("Game Shortcuts (select a container)");
        shortcutsLabel.setTextSize(18);
        shortcutsLabel.setPadding(0, 32, 0, 8);
        root.addView(shortcutsLabel);

        shortcutAdapter = new ShortcutAdapter();
        ListView shortcutList = new ListView(this);
        shortcutList.setAdapter(shortcutAdapter);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 500);
        shortcutList.setLayoutParams(slp);
        shortcutList.setOnItemClickListener((parent, view, position, id) -> {
            GameShortcut s = shortcutAdapter.getItem(position);
            if (selectedContainer != null) {
                launchGame(selectedContainer, s);
            }
        });
        shortcutList.setOnItemLongClickListener((parent, view, position, id) -> {
            GameShortcut s = shortcutAdapter.getItem(position);
            new AlertDialog.Builder(this)
                    .setTitle("Delete Shortcut")
                    .setMessage("Delete '" + s.getName() + "'?")
                    .setPositiveButton("Delete", (d, w) -> {
                        shortcutManager.deleteShortcut(s.getId());
                        refreshShortcuts();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });
        root.addView(shortcutList);

        scroll.addView(root);
        setContentView(scroll);
    }

    private void refreshShortcuts() {
        if (selectedContainer != null) {
            List<GameShortcut> shortcuts =
                    shortcutManager.getShortcutsForContainer(selectedContainer.getId());
            shortcutAdapter.setShortcuts(shortcuts);
            shortcutsLabel.setText("Game Shortcuts (" + shortcuts.size() + ")");
            Log.i(TAG, "Refreshed shortcuts: " + shortcuts.size() + " for container "
                    + selectedContainer.getName());
        }
    }

    private void startWineDesktop(Container container) {
        Log.i(TAG, "Starting Wine desktop for container: " + container.getName());
        container.dumpDiagnostics();

        if (wineRunner == null) {
            try {
                wineRunner = new WineRunner(this);
            } catch (Exception e) {
                Log.e(TAG, "WineRunner init failed: " + e.getMessage());
                Toast.makeText(this, "WineRunner error: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                return;
            }
        }

        ContainerLauncher launcher = new ContainerLauncher(this, wineRunner);
        Process p = launcher.launchDesktop(container);
        if (p != null) {
            Toast.makeText(this, "Wine desktop starting...", Toast.LENGTH_SHORT).show();
            Log.i(TAG, "Wine desktop launched: pid=" + p.toString());
        } else {
            Toast.makeText(this, "Failed to start Wine desktop", Toast.LENGTH_LONG).show();
        }
    }

    private void launchGame(Container container, GameShortcut shortcut) {
        Log.i(TAG, "Launching game: " + shortcut.getName() + " → " + shortcut.getExePath());
        shortcut.dumpDiagnostics();

        if (wineRunner == null) {
            try {
                wineRunner = new WineRunner(this);
            } catch (Exception e) {
                Log.e(TAG, "WineRunner init failed: " + e.getMessage());
                return;
            }
        }

        ContainerLauncher launcher = new ContainerLauncher(this, wineRunner);
        Process p = launcher.launchGame(container, shortcut.getExePath(),
                shortcut.getArguments());
        if (p != null) {
            Toast.makeText(this, "Launching: " + shortcut.getName(),
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Failed to launch game", Toast.LENGTH_LONG).show();
        }
    }

    private void showContainerOptions(Container c) {
        new AlertDialog.Builder(this)
                .setTitle(c.getName())
                .setItems(new String[]{"Start Desktop", "Settings", "Diagnostics",
                        "Delete"}, (d, which) -> {
                    switch (which) {
                        case 0:
                            selectedContainer = c;
                            startWineDesktop(c);
                            break;
                        case 1:
                            Intent intent = new Intent(this, ContainerSettingsActivity.class);
                            intent.putExtra(ContainerSettingsActivity.EXTRA_CONTAINER_ID,
                                    c.getId());
                            startActivity(intent);
                            break;
                        case 2:
                            c.dumpDiagnostics();
                            Toast.makeText(this, "Diagnostics logged",
                                    Toast.LENGTH_SHORT).show();
                            break;
                        case 3:
                            new AlertDialog.Builder(this)
                                    .setTitle("Delete Container")
                                    .setMessage("Delete '" + c.getName() + "'?")
                                    .setPositiveButton("Delete", (d2, w) -> {
                                        containerManager.deleteContainer(c.getId());
                                        containerAdapter.notifyDataSetChanged();
                                    })
                                    .setNegativeButton("Cancel", null)
                                    .show();
                            break;
                    }
                })
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        containerAdapter.notifyDataSetChanged();
        refreshShortcuts();
    }

    // ===== Adapters =====

    private class ContainerAdapter extends BaseAdapter {
        private List<Container> containers = new ArrayList<>();

        ContainerAdapter() {
            containers = containerManager.getContainers();
        }

        @Override
        public int getCount() { return containers.size(); }
        @Override
        public Container getItem(int position) { return containers.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(24, 16, 16, 16);

            Container c = getItem(position);
            boolean isSelected = selectedContainer != null
                    && selectedContainer.getId().equals(c.getId());

            TextView name = new TextView(parent.getContext());
            name.setText((isSelected ? "▶ " : "📦 ") + c.getName());
            name.setTextSize(16);
            row.addView(name);

            TextView desc = new TextView(parent.getContext());
            desc.setText(c.getDisplayWidth() + "x" + c.getDisplayHeight()
                    + " | " + c.getWindowsVersion()
                    + (c.isDxvkEnabled() ? " | DXVK" : "")
                    + (c.isFexEnabled() ? " | FEX" : ""));
            desc.setTextSize(11);
            desc.setTextColor(0xFF888888);
            row.addView(desc);

            if (isSelected) {
                row.setBackgroundColor(0x330000FF);
            }

            return row;
        }
    }

    private class ShortcutAdapter extends BaseAdapter {
        private List<GameShortcut> shortcuts = new ArrayList<>();

        void setShortcuts(List<GameShortcut> s) {
            this.shortcuts = s;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() { return shortcuts.size(); }
        @Override
        public GameShortcut getItem(int position) { return shortcuts.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = new TextView(parent.getContext());
            GameShortcut s = getItem(position);
            tv.setText("🎮 " + s.getName());
            tv.setTextSize(15);
            tv.setPadding(24, 16, 16, 16);
            return tv;
        }
    }
}
