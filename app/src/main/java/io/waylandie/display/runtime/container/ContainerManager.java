package io.waylandie.display.runtime.container;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages persistence of Container objects to a JSON file.
 * Stored at: /data/user/0/io.waylandie.display/files/containers.json
 *
 * <p>Diagnostics: Every CRUD operation is logged. {@link #dumpDiagnostics()}
 * provides a full state dump for debugging.
 */
public class ContainerManager {
    private static final String TAG = "WayLandIE/ContainerMgr";
    private static final String FILENAME = "containers.json";

    private final Context context;
    private List<Container> containers;

    public ContainerManager(Context context) {
        this.context = context;
        this.containers = new ArrayList<>();
        load();
    }

    // ===== CRUD =====

    public List<Container> getContainers() {
        return new ArrayList<>(containers);
    }

    public Container getContainer(String id) {
        for (Container c : containers) {
            if (c.getId().equals(id)) return c;
        }
        Log.w(TAG, "Container not found: " + id);
        return null;
    }

    public Container createContainer(String name) {
        Container c = new Container();
        c.setName(name);
        // Set default prefix path inside imagefs
        File prefixDir = new File(context.getFilesDir(),
                "imagefs/home/xuser/.wine-" + c.getId().substring(0, 8));
        c.setWinePrefixPath(prefixDir.getAbsolutePath());
        containers.add(c);
        save();
        Log.i(TAG, "Created container: " + name + " (id=" + c.getId() + ")");
        return c;
    }

    public void updateContainer(Container container) {
        for (int i = 0; i < containers.size(); i++) {
            if (containers.get(i).getId().equals(container.getId())) {
                containers.set(i, container);
                save();
                Log.i(TAG, "Updated container: " + container.getName());
                return;
            }
        }
        Log.w(TAG, "Update failed — container not found: " + container.getId());
    }

    public void deleteContainer(String id) {
        Container toRemove = null;
        for (Container c : containers) {
            if (c.getId().equals(id)) {
                toRemove = c;
                break;
            }
        }
        if (toRemove != null) {
            containers.remove(toRemove);
            save();
            Log.i(TAG, "Deleted container: " + toRemove.getName() + " (id=" + id + ")");
        } else {
            Log.w(TAG, "Delete failed — container not found: " + id);
        }
    }

    // ===== Persistence =====

    private File getContainersFile() {
        return new File(context.getFilesDir(), FILENAME);
    }

    private void load() {
        File file = getContainersFile();
        if (!file.exists()) {
            Log.i(TAG, "No containers file found — starting fresh");
            return;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            String jsonStr = new String(data);
            JSONObject json = new JSONObject(jsonStr);
            JSONArray arr = json.optJSONArray("containers");
            if (arr != null) {
                containers = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    Container c = Container.fromJson(arr.getJSONObject(i));
                    containers.add(c);
                }
            }
            Log.i(TAG, "Loaded " + containers.size() + " containers from " + file);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load containers: " + e.getMessage(), e);
            containers = new ArrayList<>();
        }
    }

    private void save() {
        File file = getContainersFile();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            JSONObject json = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Container c : containers) {
                arr.put(c.toJson());
            }
            json.put("containers", arr);
            fos.write(json.toString().getBytes());
            Log.i(TAG, "Saved " + containers.size() + " containers to " + file);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save containers: " + e.getMessage(), e);
        }
    }

    // ===== Diagnostics =====

    public String dumpDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ContainerManager Diagnostics ===\n");
        sb.append("  file: ").append(getContainersFile().getAbsolutePath()).append('\n');
        sb.append("  file exists: ").append(getContainersFile().exists()).append('\n');
        sb.append("  file size: ").append(getContainersFile().length()).append(" bytes\n");
        sb.append("  container count: ").append(containers.size()).append('\n');
        for (Container c : containers) {
            sb.append("  ---\n");
            sb.append("  id: ").append(c.getId()).append('\n');
            sb.append("  name: ").append(c.getName()).append('\n');
            sb.append("  display: ").append(c.getDisplayWidth()).append('x')
              .append(c.getDisplayHeight()).append('\n');
            sb.append("  dxvk: ").append(c.isDxvkEnabled()).append('\n');
            sb.append("  fex: ").append(c.isFexEnabled()).append('\n');
            List<String> issues = c.validate();
            if (!issues.isEmpty()) {
                sb.append("  validation issues: ").append(issues.size()).append('\n');
                for (String issue : issues) {
                    sb.append("    - ").append(issue).append('\n');
                }
            }
        }
        sb.append("=== End ContainerManager Diagnostics ===");
        String result = sb.toString();
        Log.i(TAG, result);
        return result;
    }
}
