package io.waylandie.display;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import io.waylandie.display.runtime.container.Container;
import io.waylandie.display.runtime.container.ContainerManager;
import io.waylandie.display.runtime.container.GameShortcut;
import io.waylandie.display.runtime.container.ShortcutManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * File explorer for browsing the filesystem and selecting .exe files.
 *
 * <p>Two modes:
 * <ul>
 *   <li>BROWSE — navigate directories, tap an .exe to return its path</li>
 *   <li>CREATE_SHORTCORT — select an .exe and create a game shortcut for
 *       the specified container</li>
 * </ul>
 *
 * <p>Diagnostics: All file operations are logged to logcat with tag
 * "WayLandIE/FileExplorer".
 */
public class FileExplorerActivity extends Activity {
    private static final String TAG = "WayLandIE/FileExplorer";

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_CONTAINER_ID = "containerId";
    public static final String EXTRA_START_PATH = "startPath";
    public static final String EXTRA_RESULT_PATH = "resultPath";

    public static final int MODE_BROWSE = 0;
    public static final int MODE_CREATE_SHORTCUT = 1;

    private File currentDir;
    private FileListAdapter adapter;
    private int mode = MODE_BROWSE;
    private String containerId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_BROWSE);
        containerId = getIntent().getStringExtra(EXTRA_CONTAINER_ID);
        String startPath = getIntent().getStringExtra(EXTRA_START_PATH);
        if (startPath == null) {
            startPath = Environment.getExternalStorageDirectory().getAbsolutePath();
        }

        Log.i(TAG, "FileExplorer started: mode=" + mode + " containerId=" + containerId
                + " startPath=" + startPath);

        currentDir = new File(startPath);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // Path bar
        TextView pathLabel = new TextView(this);
        pathLabel.setPadding(16, 16, 16, 16);
        pathLabel.setTextSize(14);
        root.addView(pathLabel);

        // File list
        adapter = new FileListAdapter();
        ListView listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            File item = adapter.getItem(position);
            if (item == null) return;
            if (item.isDirectory()) {
                navigateTo(item);
            } else if (item.getName().toLowerCase().endsWith(".exe")) {
                onExeSelected(item);
            } else {
                Toast.makeText(this, "Not an .exe file", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(listView, lp);

        // Up button
        Button upBtn = new Button(this);
        upBtn.setText("↑ Up");
        upBtn.setOnClickListener(v -> {
            File parent = currentDir.getParentFile();
            if (parent != null && parent.canRead()) {
                navigateTo(parent);
            }
        });
        root.addView(upBtn);

        setContentView(root);
        navigateTo(currentDir);
    }

    private void navigateTo(File dir) {
        Log.i(TAG, "Navigate to: " + dir.getAbsolutePath());
        currentDir = dir;
        setTitle(dir.getName());

        File[] files = dir.listFiles();
        List<File> fileList = new ArrayList<>();
        if (files != null) {
            // Sort: directories first, then by name
            List<File> dirs = new ArrayList<>();
            List<File> exes = new ArrayList<>();
            List<File> others = new ArrayList<>();
            for (File f : files) {
                if (f.isHidden()) continue;
                if (f.isDirectory()) {
                    dirs.add(f);
                } else if (f.getName().toLowerCase().endsWith(".exe")) {
                    exes.add(f);
                } else {
                    others.add(f);
                }
            }
            Comparator<File> cmp = Comparator.comparing(File::getName,
                    String.CASE_INSENSITIVE_ORDER);
            Collections.sort(dirs, cmp);
            Collections.sort(exes, cmp);
            Collections.sort(others, cmp);
            fileList.addAll(dirs);
            fileList.addAll(exes);
            fileList.addAll(others);
        }

        adapter.setFiles(fileList);
        ((TextView) ((LinearLayout) findViewById(android.R.id.content))
                .getChildAt(0)).setText(dir.getAbsolutePath());
    }

    private void onExeSelected(File exe) {
        Log.i(TAG, "EXE selected: " + exe.getAbsolutePath());

        if (mode == MODE_CREATE_SHORTCUT && containerId != null) {
            // Prompt for shortcut name
            EditText input = new EditText(this);
            input.setText(exe.getName().replace(".exe", "").replace(".EXE", ""));
            new AlertDialog.Builder(this)
                    .setTitle("Create Shortcut")
                    .setMessage("Shortcut name:")
                    .setView(input)
                    .setPositiveButton("Create", (d, w) -> {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) name = exe.getName();
                        ShortcutManager sm = new ShortcutManager(this);
                        sm.createShortcut(containerId, name, exe.getAbsolutePath());
                        Log.i(TAG, "Shortcut created: " + name + " → " + exe.getAbsolutePath());
                        Toast.makeText(this, "Shortcut created: " + name, Toast.LENGTH_SHORT).show();
                        finish();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            // Browse mode — return the path
            Intent result = new Intent();
            result.putExtra(EXTRA_RESULT_PATH, exe.getAbsolutePath());
            setResult(RESULT_OK, result);
            finish();
        }
    }

    private class FileListAdapter extends BaseAdapter {
        private List<File> files = new ArrayList<>();

        void setFiles(List<File> files) {
            this.files = files;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() { return files.size(); }
        @Override
        public File getItem(int position) { return files.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = (TextView) convertView;
            if (tv == null) {
                tv = new TextView(parent.getContext());
                tv.setPadding(24, 16, 16, 16);
                tv.setTextSize(14);
                tv.setGravity(Gravity.CENTER_VERTICAL);
                tv.setLayoutParams(new ListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            File f = getItem(position);
            String prefix = f.isDirectory() ? "📁 " : "📄 ";
            if (f.getName().toLowerCase().endsWith(".exe")) prefix = "🎮 ";
            tv.setText(prefix + f.getName());
            return tv;
        }
    }
}
