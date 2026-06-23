package io.waylandie.display.runtime.container;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages persistence of GameShortcut objects to a JSON file.
 * Stored at: /data/user/0/io.waylandie.display/files/shortcuts.json
 *
 * <p>Diagnostics: Every operation is logged. {@link #dumpDiagnostics()} provides
 * a full state dump.
 */
public class ShortcutManager {
    private static final String TAG = "WayLandIE/ShortcutMgr";
    private static final String FILENAME = "shortcuts.json";

    private final Context context;
    private List<GameShortcut> shortcuts;

    public ShortcutManager(Context context) {
        this.context = context;
        this.shortcuts = new ArrayList<>();
        load();
    }

    public List<GameShortcut> getShortcuts() {
        return new ArrayList<>(shortcuts);
    }

    public List<GameShortcut> getShortcutsForContainer(String containerId) {
        List<GameShortcut> result = new ArrayList<>();
        for (GameShortcut s : shortcuts) {
            if (containerId.equals(s.getContainerId())) {
                result.add(s);
            }
        }
        return result;
    }

    public GameShortcut createShortcut(String containerId, String name, String exePath) {
        GameShortcut s = new GameShortcut(containerId, name, exePath);
        shortcuts.add(s);
        save();
        Log.i(TAG, "Created shortcut: " + name + " → " + exePath + " (container=" + containerId + ")");
        return s;
    }

    public void deleteShortcut(String id) {
        GameShortcut toRemove = null;
        for (GameShortcut s : shortcuts) {
            if (s.getId().equals(id)) {
                toRemove = s;
                break;
            }
        }
        if (toRemove != null) {
            shortcuts.remove(toRemove);
            save();
            Log.i(TAG, "Deleted shortcut: " + toRemove.getName());
        }
    }

    private File getShortcutsFile() {
        return new File(context.getFilesDir(), FILENAME);
    }

    private void load() {
        File file = getShortcutsFile();
        if (!file.exists()) return;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            JSONObject json = new JSONObject(new String(data));
            JSONArray arr = json.optJSONArray("shortcuts");
            if (arr != null) {
                shortcuts = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    shortcuts.add(GameShortcut.fromJson(arr.getJSONObject(i)));
                }
            }
            Log.i(TAG, "Loaded " + shortcuts.size() + " shortcuts");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load shortcuts: " + e.getMessage());
            shortcuts = new ArrayList<>();
        }
    }

    private void save() {
        File file = getShortcutsFile();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            JSONObject json = new JSONObject();
            JSONArray arr = new JSONArray();
            for (GameShortcut s : shortcuts) arr.put(s.toJson());
            json.put("shortcuts", arr);
            fos.write(json.toString().getBytes());
            Log.i(TAG, "Saved " + shortcuts.size() + " shortcuts");
        } catch (Exception e) {
            Log.e(TAG, "Failed to save shortcuts: " + e.getMessage());
        }
    }

    public String dumpDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ShortcutManager Diagnostics ===\n");
        sb.append("  file: ").append(getShortcutsFile().getAbsolutePath()).append('\n');
        sb.append("  shortcut count: ").append(shortcuts.size()).append('\n');
        for (GameShortcut s : shortcuts) {
            sb.append("  ---\n");
            sb.append("  id: ").append(s.getId()).append('\n');
            sb.append("  name: ").append(s.getName()).append('\n');
            sb.append("  exe: ").append(s.getExePath()).append('\n');
            sb.append("  container: ").append(s.getContainerId()).append('\n');
            List<String> issues = s.validate();
            if (!issues.isEmpty()) {
                sb.append("  issues: ").append(issues).append('\n');
            }
        }
        sb.append("=== End ShortcutManager Diagnostics ===");
        String result = sb.toString();
        Log.i(TAG, result);
        return result;
    }
}
