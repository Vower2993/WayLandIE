package io.waylandie.display.runtime.content;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * AdrenotoolsManager — manages Adreno GPU driver slots using libadrenotools.
 *
 * <p>Ported from WinNative's AdrenotoolsManager. libadrenotools lets the
 * app swap Adreno Vulkan drivers at runtime without root — it hooks the
 * Vulkan loader and redirects ICD calls to a user-supplied .so file.
 *
 * <p>Drivers are stored in {@code getFilesDir()/contents/adrenotools/<id>/}.
 * Each slot contains:
 * <ul>
 *   <li>{@code libvulkan_adreno.so} — the driver .so</li>
 *   <li>{@code meta.json} — driver metadata (name, version, libraryName)</li>
 * </ul>
 *
 * <p>Use {@link #activateDriver(String)} to set the active driver. Wine
 * processes launched via {@link io.waylandie.display.runtime.environment.ProotRunner}
 * will use the active driver via {@code VK_ICD_FILENAMES} env var.
 */
public final class AdrenotoolsManager {

    private static final String TAG = "WayLandIE/Adrenotools";

    private final Context context;
    private final File contentDir;

    public AdrenotoolsManager(Context context) {
        this.context = context;
        this.contentDir = new File(context.getFilesDir(), "contents/adrenotools");
        if (!contentDir.exists()) contentDir.mkdirs();
    }

    /**
     * Returns the directory where driver slots live.
     */
    public File getContentDir() { return contentDir; }

    /**
     * Returns the path to a specific driver slot.
     */
    public String getDriverPath(String driverId) {
        return new File(contentDir, driverId).getAbsolutePath() + "/";
    }

    /**
     * Returns the library name (e.g. "libvulkan_adreno.so") for a driver slot.
     */
    public String getLibraryName(String driverId) {
        try {
            JSONObject meta = readMeta(driverId);
            if (meta != null) return meta.optString("libraryName", "");
        } catch (JSONException ignored) {}
        return "";
    }

    /**
     * Returns the human-readable driver name.
     */
    public String getDriverName(String driverId) {
        try {
            JSONObject meta = readMeta(driverId);
            if (meta != null) return meta.optString("name", driverId);
        } catch (JSONException ignored) {}
        return driverId;
    }

    /**
     * Returns the driver version string.
     */
    public String getDriverVersion(String driverId) {
        try {
            JSONObject meta = readMeta(driverId);
            if (meta != null) return meta.optString("driverVersion", "unknown");
        } catch (JSONException ignored) {}
        return "unknown";
    }

    /**
     * Installs a driver from a .zip archive. The archive must contain
     * a {@code libvulkan_adreno.so} file (or similar) at any level.
     *
     * @param driverId    the slot name (e.g. "turnip-V29")
     * @param displayName human-readable name (e.g. "Turnip V29 KGSL")
     * @param version     version string (e.g. "V29")
     * @param archivePath path to the .zip file
     * @return true on success, false on failure
     */
    public boolean installDriver(String driverId, String displayName,
                                  String version, File archivePath) {
        File slotDir = new File(contentDir, driverId);
        if (slotDir.exists()) {
            deleteRecursive(slotDir);
        }
        if (!slotDir.mkdirs()) {
            Log.e(TAG, "Failed to mkdir " + slotDir);
            return false;
        }

        // Extract the .zip
        File driverSo = null;
        try (InputStream in = new java.io.FileInputStream(archivePath);
             ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                File outFile = new File(slotDir, new File(entry.getName()).getName());
                try (OutputStream out = new FileOutputStream(outFile)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) out.write(buf, 0, n);
                }
                // Track the .so file
                String name = entry.getName().toLowerCase();
                if (name.endsWith(".so") && name.contains("vulkan")) {
                    driverSo = outFile;
                }
                zin.closeEntry();
            }
        } catch (IOException e) {
            Log.e(TAG, "Extraction failed", e);
            return false;
        }

        if (driverSo == null) {
            Log.e(TAG, "No libvulkan*.so found in archive");
            return false;
        }

        // Write meta.json
        String libraryName = driverSo.getName();
        JSONObject meta = new JSONObject();
        try {
            meta.put("name", displayName);
            meta.put("driverVersion", version);
            meta.put("libraryName", libraryName);
            meta.put("sourceAsset", archivePath.getName());
            meta.put("installedAt", System.currentTimeMillis());
        } catch (JSONException e) {
            Log.e(TAG, "Failed to write meta", e);
        }
        try {
            java.io.FileWriter w = new java.io.FileWriter(new File(slotDir, "meta.json"));
            w.write(meta.toString());
            w.close();
        } catch (IOException e) {
            Log.e(TAG, "Failed to write meta.json", e);
        }

        Log.i(TAG, "Installed driver '" + driverId + "' (" + displayName
                + " v" + version + ") → " + slotDir);
        return true;
    }

    /**
     * Activates a driver slot. Future Wine launches will use this driver
     * via the VK_ICD_FILENAMES env var.
     */
    public boolean activateDriver(String driverId) {
        File slotDir = new File(contentDir, driverId);
        if (!slotDir.isDirectory()) {
            Log.e(TAG, "Driver slot not found: " + driverId);
            return false;
        }
        // Write active.txt with the driver id
        try {
            java.io.FileWriter w = new java.io.FileWriter(new File(contentDir, "active.txt"));
            w.write(driverId);
            w.close();
        } catch (IOException e) {
            Log.e(TAG, "Failed to write active.txt", e);
            return false;
        }
        Log.i(TAG, "Activated driver: " + driverId);
        return true;
    }

    /**
     * Returns the active driver id, or null if none.
     */
    public String getActiveDriver() {
        File activeFile = new File(contentDir, "active.txt");
        if (!activeFile.exists()) return null;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(activeFile));
            String id = r.readLine();
            r.close();
            return id;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Returns the absolute path to the active driver's .so file, or null.
     * Used by ProotRunner to set VK_ICD_FILENAMES.
     */
    public String getActiveDriverSoPath() {
        String id = getActiveDriver();
        if (id == null) return null;
        String libName = getLibraryName(id);
        if (libName.isEmpty()) libName = "libvulkan_adreno.so";
        File soFile = new File(getDriverPath(id), libName);
        return soFile.exists() ? soFile.getAbsolutePath() : null;
    }

    /**
     * Lists all installed driver slot ids.
     */
    public java.util.List<String> listInstalledDrivers() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        File[] kids = contentDir.listFiles();
        if (kids == null) return ids;
        for (File f : kids) {
            if (f.isDirectory() && new File(f, "meta.json").exists()) {
                ids.add(f.getName());
            }
        }
        return ids;
    }

    /**
     * Uninstalls a driver slot.
     */
    public boolean uninstallDriver(String driverId) {
        File slotDir = new File(contentDir, driverId);
        if (!slotDir.exists()) return false;
        // Don't allow uninstalling the active driver
        if (driverId.equals(getActiveDriver())) {
            Log.e(TAG, "Cannot uninstall active driver: " + driverId);
            return false;
        }
        deleteRecursive(slotDir);
        return true;
    }

    private JSONObject readMeta(String driverId) throws JSONException {
        File metaFile = new File(contentDir, driverId + "/meta.json");
        if (!metaFile.exists()) return null;
        try {
            byte[] data = java.nio.file.Files.readAllBytes(metaFile.toPath());
            return new JSONObject(new String(data));
        } catch (IOException e) {
            return null;
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursive(k);
            }
        }
        f.delete();
    }
}
