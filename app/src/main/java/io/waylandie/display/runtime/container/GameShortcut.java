package io.waylandie.display.runtime.container;

import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a game shortcut — a saved .exe path with a name and optional
 * arguments, belonging to a specific container.
 *
 * <p>Shortcuts are displayed on the home screen as a grid, similar to
 * Winlator's game shortcut list. Tapping a shortcut launches the game
 * within the container it belongs to.
 */
public class GameShortcut {
    private static final String TAG = "WayLandIE/Shortcut";

    private String id;
    private String containerId;
    private String name;
    private String exePath;
    private String arguments;
    private String workingDir;
    private long createdAt;

    public GameShortcut() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
    }

    public GameShortcut(String containerId, String name, String exePath) {
        this();
        this.containerId = containerId;
        this.name = name;
        this.exePath = exePath;
        this.arguments = "";
        this.workingDir = "";
    }

    // ===== Getters / Setters =====
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getContainerId() { return containerId; }
    public void setContainerId(String cid) { this.containerId = cid; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getExePath() { return exePath; }
    public void setExePath(String path) { this.exePath = path; }
    public String getArguments() { return arguments; }
    public void setArguments(String args) { this.arguments = args; }
    public String getWorkingDir() { return workingDir; }
    public void setWorkingDir(String dir) { this.workingDir = dir; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long t) { this.createdAt = t; }

    // ===== Serialization =====
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("containerId", containerId != null ? containerId : "");
            json.put("name", name != null ? name : "");
            json.put("exePath", exePath != null ? exePath : "");
            json.put("arguments", arguments != null ? arguments : "");
            json.put("workingDir", workingDir != null ? workingDir : "");
            json.put("createdAt", createdAt);
        } catch (Exception e) {
            Log.e(TAG, "toJson failed: " + e.getMessage());
        }
        return json;
    }

    public static GameShortcut fromJson(JSONObject json) {
        GameShortcut s = new GameShortcut();
        try {
            s.id = json.optString("id", s.id);
            s.containerId = json.optString("containerId", null);
            if (s.containerId != null && s.containerId.isEmpty()) s.containerId = null;
            s.name = json.optString("name", "");
            s.exePath = json.optString("exePath", "");
            s.arguments = json.optString("arguments", "");
            s.workingDir = json.optString("workingDir", "");
            s.createdAt = json.optLong("createdAt", System.currentTimeMillis());
        } catch (Exception e) {
            Log.e(TAG, "fromJson failed: " + e.getMessage());
        }
        return s;
    }

    // ===== Diagnostics =====
    public String dumpDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== GameShortcut Diagnostics ===\n");
        sb.append("  id: ").append(id).append('\n');
        sb.append("  containerId: ").append(containerId).append('\n');
        sb.append("  name: ").append(name).append('\n');
        sb.append("  exePath: ").append(exePath).append('\n');
        sb.append("  arguments: ").append(arguments).append('\n');
        sb.append("  workingDir: ").append(workingDir).append('\n');
        sb.append("  created: ").append(createdAt).append('\n');
        // Validation
        List<String> issues = validate();
        if (issues.isEmpty()) {
            sb.append("  validation: PASS\n");
        } else {
            sb.append("  validation: FAIL\n");
            for (String issue : issues) {
                sb.append("    - ").append(issue).append('\n');
            }
        }
        sb.append("=== End GameShortcut Diagnostics ===");
        return sb.toString();
    }

    public List<String> validate() {
        List<String> issues = new ArrayList<>();
        if (name == null || name.trim().isEmpty()) {
            issues.add("Shortcut name is empty");
        }
        if (exePath == null || exePath.trim().isEmpty()) {
            issues.add("Exe path is empty");
        }
        if (containerId == null || containerId.trim().isEmpty()) {
            issues.add("Container ID is empty");
        }
        return issues;
    }

    // Need import for List
    // (added at top via implicit java.util.List)
}
