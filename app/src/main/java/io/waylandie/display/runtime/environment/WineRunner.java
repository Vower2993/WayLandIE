package io.waylandie.display.runtime.environment;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * WineRunner — launches Wine + bridge translator via PROOT (Option C).
 *
 * <p>Both the Wayland bridge translator and Wine are glibc binaries.
 * Android 16 SELinux blocks the glibc dynamic linker from app-private
 * storage, so they CANNOT run directly. Proot provides the glibc
 * execution environment via syscall translation — no SELinux issues.
 *
 * <p>Flow:
 * <ol>
 *   <li>Start bridge translator via ProotRunner (background)</li>
 *   <li>Wait 2s for Wayland socket creation</li>
 *   <li>Start Wine via ProotRunner (foreground)</li>
 * </ol>
 *
 * <p>ProotRunner handles: rootfs, bind mounts, PATH, LD_LIBRARY_PATH,
 * WAYLAND_DISPLAY, WAYLANDIE_BRIDGE_*, VK_ICD_FILENAMES, etc.
 * The '--' option terminator is already removed from ProotRunner.
 */
public final class WineRunner {

    private static final String TAG = "WayLandIE/WineRunner";

    private final Context context;
    private final ImageFsManager imageFs;

    public WineRunner(Context context) {
        this.context = context;
        this.imageFs = new ImageFsManager(context);
    }

    public boolean isReady() {
        return imageFs.isValid();
    }

    /**
     * Launches Wine with the given .exe path using PROOT.
     *
     * @param exePath  absolute path to the .exe (accessible inside proot
     *                 via /storage bind mount)
     * @param extraArgs extra command-line args
     * @param useProton ignored — Proton is always used
     * @return the started Wine Process
     * @throws IOException if Wine binary not found or launch fails
     */
    public Process execWine(String exePath, String[] extraArgs, boolean useProton) throws IOException {
        if (!isReady()) {
            throw new IOException("WineRunner not ready: imagefs invalid. "
                    + imageFs.describeValidity());
        }

        File rootDir = imageFs.getRootDir();

        // 1. Find Proton's Wine binary (validate on HOST, launch via GUEST path)
        File protonDir = new File(context.getFilesDir(), "contents/proton/active");
        if (!protonDir.exists()) {
            throw new IOException("Proton is not installed. Please go to the "
                    + "Settings tab and install Proton first.");
        }

        File wineFromProton = new File(protonDir, "files/bin/wine");
        File wineFromProtonAlt = new File(protonDir, "dist/bin/wine");
        File wineFromProtonFlat = new File(protonDir, "bin/wine");

        File wineBin = null;
        String wineGuestPath = null;
        if (wineFromProton.exists()) {
            wineBin = wineFromProton;
            wineGuestPath = "/opt/proton/files/bin/wine";
        } else if (wineFromProtonAlt.exists()) {
            wineBin = wineFromProtonAlt;
            wineGuestPath = "/opt/proton/dist/bin/wine";
        } else if (wineFromProtonFlat.exists()) {
            wineBin = wineFromProtonFlat;
            wineGuestPath = "/opt/proton/bin/wine";
        }

        if (wineBin == null) {
            throw new IOException("Proton found at '" + protonDir
                    + "' but no wine binary. Checked:\n"
                    + "  " + wineFromProton + "\n"
                    + "  " + wineFromProtonAlt + "\n"
                    + "  " + wineFromProtonFlat);
        }
        wineBin.setExecutable(true, false);

        // 2. Create ProotRunner — handles bind mounts, PATH, env, glibc exec
        ProotRunner proot = new ProotRunner(context);
        if (!proot.isReady()) {
            throw new IOException("ProotRunner not ready.");
        }

        // 3. Start bridge translator via proot (background)
        File bridgeBin = new File(rootDir, "usr/local/bin/waylandie-wayland-bridge");
        if (bridgeBin.exists()) {
            bridgeBin.setExecutable(true, false);
            Log.i(TAG, "Starting bridge translator via proot (background)…");
            try {
                Process bp = proot.exec(
                        "XDG_RUNTIME_DIR=/tmp WAYLAND_DISPLAY=waylandie "
                        + "WAYLANDIE_BRIDGE_SOCKET=waylandie.display.bridge.v1 "
                        + "WAYLANDIE_BRIDGE_PORT=57391 "
                        + "WAYLANDIE_BRIDGE_PREFER=abstract "
                        + "WAYLANDIE_FINAL_COPY=forbidden "
                        + "/usr/local/bin/waylandie-wayland-bridge &");
                Log.i(TAG, "Bridge translator started (pid=" + getPid(bp) + ")");
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            } catch (IOException e) {
                Log.w(TAG, "Bridge failed to start: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "Bridge translator not found at " + bridgeBin);
        }

        // 4. Build Wine command (GUEST path, runs inside proot)
        boolean isArm64ec = isArm64ecWine(wineBin);
        File fexDir = new File(context.getFilesDir(), "contents/fex/active");
        boolean fexCoreInstalled = fexDir.isDirectory();

        StringBuilder wineCmd = new StringBuilder();
        if (fexCoreInstalled && isArm64ec) {
            wineCmd.append("HODLL=libarm64ecfex.dll ");
            Log.i(TAG, "FEXCore arm64ec: HODLL=libarm64ecfex.dll");
        }
        wineCmd.append(wineGuestPath).append(" ").append(exePath);
        if (extraArgs != null) {
            for (String arg : extraArgs) wineCmd.append(" ").append(arg);
        }

        Log.i(TAG, "Launching Wine via proot: " + wineCmd);
        Log.i(TAG, "Wine (host): " + wineBin + " | (guest): " + wineGuestPath);
        Log.i(TAG, "Architecture: " + (isArm64ec ? "arm64ec" : "x86_64"));

        // 5. Sync adrenotools driver if active
        io.waylandie.display.runtime.content.AdrenotoolsManager atm =
                new io.waylandie.display.runtime.content.AdrenotoolsManager(context);
        String activeDriverSo = atm.getActiveDriverSoPath();
        if (activeDriverSo != null) {
            syncAdrenotoolsDriverToRootfs(activeDriverSo);
            Log.i(TAG, "Adrenotools driver synced: " + activeDriverSo);
        }

        // 6. Launch Wine via ProotRunner
        return proot.exec(wineCmd.toString());
    }

    private void syncAdrenotoolsDriverToRootfs(String driverSoHostPath) {
        try {
            File srcSo = new File(driverSoHostPath);
            if (!srcSo.isFile()) return;
            File libDir = new File(imageFs.getRootDir(), "usr/local/lib");
            libDir.mkdirs();
            File destSo = new File(libDir, "libvulkan_freedreno.so");
            copyFile(srcSo, destSo);
            File icdDir = new File(imageFs.getRootDir(), "usr/local/etc/vulkan/icd.d");
            icdDir.mkdirs();
            File icdFile = new File(icdDir, "freedreno_icd.json");
            try (java.io.PrintWriter pw = new java.io.PrintWriter(icdFile)) {
                pw.println("{");
                pw.println("    \"file_format_version\": \"1.0.0\",");
                pw.println("    \"ICD\": {");
                pw.println("        \"library_path\": \"/usr/local/lib/libvulkan_freedreno.so\",");
                pw.println("        \"api_version\": \"1.3.0\"");
                pw.println("    }");
                pw.println("}");
            }
            Log.i(TAG, "Synced adrenotools driver: " + destSo);
        } catch (Exception e) {
            Log.e(TAG, "Failed to sync adrenotools driver", e);
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static long getPid(Process p) {
        try {
            java.lang.reflect.Field pidField = p.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            return pidField.getLong(p);
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean isArm64ecWine(File wineBin) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(wineBin, "r")) {
            if (raf.length() < 20) return false;
            byte[] magic = new byte[4];
            raf.readFully(magic);
            if (magic[0] != 0x7f || magic[1] != 'E' || magic[2] != 'L' || magic[3] != 'F')
                return false;
            raf.seek(18);
            int eMachine = raf.readUnsignedShort();
            return eMachine == 183; // EM_AARCH64
        } catch (Exception e) {
            return false;
        }
    }
}
