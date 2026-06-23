package io.waylandie.display.runtime.container;

import android.util.Log;

import org.json.JSONObject;
import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a Wine container — a self-contained Wine prefix with its own
 * settings, shortcuts, and configuration. Modeled after Winlator's container
 * system.
 *
 * <p>Each container has:
 * <ul>
 *   <li>A unique ID (UUID)</li>
 *   <li>A name (user-visible)</li>
 *   <li>A Wine prefix path (inside the imagefs)</li>
 *   <li>Display settings (resolution, fullscreen)</li>
 *   <li>Windows version (win10, win7, winxp)</li>
 *   <li>Graphics driver (winewayland.drv)</li>
 *   <li>DXVK enabled/disabled</li>
 *   <li>FEX translation enabled/disabled (for x86 games on ARM)</li>
 *   <li>Audio driver selection</li>
 *   <li>Custom environment variables</li>
 * </ul>
 *
 * <p>Diagnostics: Every field change is logged via {@link #dumpDiagnostics()}.
 * Validation is available via {@link #validate()}.
 */
public class Container {
    private static final String TAG = "WayLandIE/Container";

    private String id;
    private String name;
    private String winePrefixPath;
    private int displayWidth;
    private int displayHeight;
    private boolean fullscreen;
    private String windowsVersion;  // "win10", "win7", "winxp", "win98"
    private String graphicsDriver;  // "winewayland.drv"
    private boolean dxvkEnabled;
    private boolean fexEnabled;
    private boolean audioEnabled;
    private List<String> envVars;   // "KEY=VALUE" pairs
    private long createdAt;
    private long modifiedAt;

    public Container() {
        this.id = UUID.randomUUID().toString();
        this.name = "New Container";
        this.displayWidth = 1280;
        this.displayHeight = 720;
        this.fullscreen = false;
        this.windowsVersion = "win10";
        this.graphicsDriver = "winewayland.drv";
        this.dxvkEnabled = true;
        this.fexEnabled = true;
        this.audioEnabled = true;
        this.envVars = new ArrayList<>();
        this.createdAt = System.currentTimeMillis();
        this.modifiedAt = this.createdAt;
    }

    // ===== Getters / Setters =====
    public String getId() { return id; }
    public void setId(String id) { this.id = id; touch(); }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; touch(); }

    public String getWinePrefixPath() { return winePrefixPath; }
    public void setWinePrefixPath(String path) { this.winePrefixPath = path; touch(); }

    public int getDisplayWidth() { return displayWidth; }
    public void setDisplayWidth(int w) { this.displayWidth = w; touch(); }

    public int getDisplayHeight() { return displayHeight; }
    public void setDisplayHeight(int h) { this.displayHeight = h; touch(); }

    public boolean isFullscreen() { return fullscreen; }
    public void setFullscreen(boolean f) { this.fullscreen = f; touch(); }

    public String getWindowsVersion() { return windowsVersion; }
    public void setWindowsVersion(String v) { this.windowsVersion = v; touch(); }

    public String getGraphicsDriver() { return graphicsDriver; }
    public void setGraphicsDriver(String d) { this.graphicsDriver = d; touch(); }

    public boolean isDxvkEnabled() { return dxvkEnabled; }
    public void setDxvkEnabled(boolean e) { this.dxvkEnabled = e; touch(); }

    public boolean isFexEnabled() { return fexEnabled; }
    public void setFexEnabled(boolean e) { this.fexEnabled = e; touch(); }

    public boolean isAudioEnabled() { return audioEnabled; }
    public void setAudioEnabled(boolean e) { this.audioEnabled = e; touch(); }

    public List<String> getEnvVars() { return envVars; }
    public void setEnvVars(List<String> vars) { this.envVars = vars; touch(); }

    public long getCreatedAt() { return createdAt; }
    public long getModifiedAt() { return modifiedAt; }

    private void touch() { this.modifiedAt = System.currentTimeMillis(); }

    // ===== Serialization =====
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("name", name);
            json.put("winePrefixPath", winePrefixPath != null ? winePrefixPath : "");
            json.put("displayWidth", displayWidth);
            json.put("displayHeight", displayHeight);
            json.put("fullscreen", fullscreen);
            json.put("windowsVersion", windowsVersion);
            json.put("graphicsDriver", graphicsDriver);
            json.put("dxvkEnabled", dxvkEnabled);
            json.put("fexEnabled", fexEnabled);
            json.put("audioEnabled", audioEnabled);
            json.put("createdAt", createdAt);
            json.put("modifiedAt", modifiedAt);
            JSONArray envArr = new JSONArray();
            for (String e : envVars) envArr.put(e);
            json.put("envVars", envArr);
        } catch (Exception e) {
            Log.e(TAG, "toJson failed: " + e.getMessage());
        }
        return json;
    }

    public static Container fromJson(JSONObject json) {
        Container c = new Container();
        try {
            c.id = json.optString("id", c.id);
            c.name = json.optString("name", c.name);
            String prefix = json.optString("winePrefixPath", "");
            c.winePrefixPath = prefix.isEmpty() ? null : prefix;
            c.displayWidth = json.optInt("displayWidth", 1280);
            c.displayHeight = json.optInt("displayHeight", 720);
            c.fullscreen = json.optBoolean("fullscreen", false);
            c.windowsVersion = json.optString("windowsVersion", "win10");
            c.graphicsDriver = json.optString("graphicsDriver", "winewayland.drv");
            c.dxvkEnabled = json.optBoolean("dxvkEnabled", true);
            c.fexEnabled = json.optBoolean("fexEnabled", true);
            c.audioEnabled = json.optBoolean("audioEnabled", true);
            c.createdAt = json.optLong("createdAt", System.currentTimeMillis());
            c.modifiedAt = json.optLong("modifiedAt", c.createdAt);
            JSONArray envArr = json.optJSONArray("envVars");
            if (envArr != null) {
                c.envVars = new ArrayList<>();
                for (int i = 0; i < envArr.length(); i++) {
                    c.envVars.add(envArr.getString(i));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "fromJson failed: " + e.getMessage());
        }
        return c;
    }

    // ===== Diagnostics =====
    public String dumpDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Container Diagnostics ===\n");
        sb.append("  id: ").append(id).append('\n');
        sb.append("  name: ").append(name).append('\n');
        sb.append("  winePrefixPath: ").append(winePrefixPath).append('\n');
        sb.append("  display: ").append(displayWidth).append('x').append(displayHeight)
          .append(fullscreen ? " (fullscreen)" : " (windowed)").append('\n');
        sb.append("  windowsVersion: ").append(windowsVersion).append('\n');
        sb.append("  graphicsDriver: ").append(graphicsDriver).append('\n');
        sb.append("  dxvk: ").append(dxvkEnabled ? "enabled" : "disabled").append('\n');
        sb.append("  fex: ").append(fexEnabled ? "enabled" : "disabled").append('\n');
        sb.append("  audio: ").append(audioEnabled ? "enabled" : "disabled").append('\n');
        sb.append("  envVars: ").append(envVars.size()).append(" entries\n");
        for (String e : envVars) {
            sb.append("    ").append(e).append('\n');
        }
        sb.append("  created: ").append(createdAt).append('\n');
        sb.append("  modified: ").append(modifiedAt).append('\n');
        // Validation
        List<String> issues = validate();
        if (issues.isEmpty()) {
            sb.append("  validation: PASS (no issues)\n");
        } else {
            sb.append("  validation: FAIL (").append(issues.size()).append(" issues)\n");
            for (String issue : issues) {
                sb.append("    - ").append(issue).append('\n');
            }
        }
        sb.append("=== End Container Diagnostics ===");
        String result = sb.toString();
        Log.i(TAG, result);
        return result;
    }

    /**
     * Validates the container configuration. Returns a list of issues
     * (empty = valid).
     */
    public List<String> validate() {
        List<String> issues = new ArrayList<>();
        if (name == null || name.trim().isEmpty()) {
            issues.add("Container name is empty");
        }
        if (displayWidth < 320 || displayWidth > 7680) {
            issues.add("Display width out of range: " + displayWidth);
        }
        if (displayHeight < 240 || displayHeight > 4320) {
            issues.add("Display height out of range: " + displayHeight);
        }
        if (windowsVersion == null || windowsVersion.isEmpty()) {
            issues.add("Windows version not set");
        }
        if (graphicsDriver == null || graphicsDriver.isEmpty()) {
            issues.add("Graphics driver not set");
        }
        if (!"winewayland.drv".equals(graphicsDriver)) {
            issues.add("Unsupported graphics driver: " + graphicsDriver +
                      " (only winewayland.drv is supported)");
        }
        return issues;
    }
}
