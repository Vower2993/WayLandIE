package com.winlator.cmod.runtime.display.environment.components;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import java.io.*;
import java.nio.ByteBuffer;

/**
 * Listens on abstract socket 'waylandie.display.bridge.v1' for dmabuf-present
 * commands from the bionic bridge process. Receives dmabuf fd via SCM_RIGHTS,
 * then calls nativePresentAhbVkDmaBufFrame to present it via SurfaceControl.
 *
 * Full pipeline: Wine (winewayland.drv) → dmabuf → Bridge → AHB → SurfaceFlinger
 */
public class WaylandBridgeServer {
    private static final String TAG = "WaylandBridgeServer";
    private static final String SOCKET_NAME = "waylandie.display.bridge.v1";

    private LocalServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running = false;
    private SurfaceControl presentLayer;
    private SurfaceView hostView;
    private int width = 1920;
    private int height = 1080;
    private int frameIndex = 0;

    // Native methods — implemented in waylandie_display_native.c
    private static native String nativePresentAhbVkDmaBufFrame(
            SurfaceControl surfaceControl,
            int dmabufFd,
            int sourceWidth, int sourceHeight,
            long drmFormat, long modifier, int planes,
            long stride0, long offset0, long size,
            int targetWidth, int targetHeight,
            long frameIndex,
            String tmpDir, String hookLibDir,
            String driverDir, String driverName);

    static {
        try {
            System.loadLibrary("waylandie_bridge");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load waylandie_bridge", e);
        }
    }

    public void start(SurfaceView view) {
        this.hostView = view;
        running = true;
        try {
            serverSocket = new LocalServerSocket(SOCKET_NAME);
            Log.i(TAG, "Listening on abstract socket: " + SOCKET_NAME);
        } catch (IOException e) {
            Log.e(TAG, "Failed to create server socket", e);
            return;
        }
        acceptThread = new Thread(this::acceptLoop, "wl-bridge-server");
        acceptThread.start();
    }

    public void stop() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }
        if (presentLayer != null) {
            try {
                new SurfaceControl.Transaction()
                    .setVisibility(presentLayer, false).apply();
            } catch (Exception ignored) {}
            presentLayer.release();
            presentLayer = null;
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                LocalSocket client = serverSocket.accept();
                Log.i(TAG, "Bridge client connected");
                handleClient(client);
            } catch (IOException e) {
                if (running) Log.w(TAG, "Accept error: " + e.getMessage());
            }
        }
    }

    private void handleClient(LocalSocket client) {
        try {
            // Use the socket's FileDescriptor for ancillary data (SCM_RIGHTS)
            java.io.FileDescriptor fd = client.getFileDescriptor();
            InputStream is = client.getInputStream();
            OutputStream os = client.getOutputStream();

            while (running) {
                // Read command line
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                int b;
                while ((b = is.read()) >= 0) {
                    if (b == '\n') break;
                    baos.write(b);
                }
                if (b < 0) break;

                String command = baos.toString().trim();
                if (command.isEmpty()) continue;

                // Check for ancillary data (dmabuf fd sent via SCM_RIGHTS)
                // LocalSocket ancillary data is available via getAncillaryFileDescriptors
                java.io.FileDescriptor[] ancillary = client.getAncillaryFileDescriptors();
                int dmabufFd = -1;
                if (ancillary != null && ancillary.length > 0) {
                    try (android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor.dup(ancillary[0])) {
                        dmabufFd = pfd.getFd();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to get ancillary fd", e);
                    }
                    Log.i(TAG, "Received dmabuf fd=" + dmabufFd);
                }

                String response = handleCommand(command, dmabufFd);
                os.write((response + "\n").getBytes());
                os.flush();
            }
        } catch (IOException e) {
            Log.w(TAG, "Client error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private String handleCommand(String command, int dmabufFd) {
        if (command.startsWith("waylandie-bridge hello") || command.startsWith("hello")) {
            return "waylandie-bridge hello-ack version=1 features=dmabuf-present";
        }
        if (command.startsWith("waylandie-bridge ping") || command.startsWith("ping")) {
            return "waylandie-bridge pong version=1";
        }
        if (command.startsWith("waylandie-bridge caps") || command.startsWith("caps")) {
            return "waylandie-bridge caps version=1 features=dmabuf-present,fdtest";
        }
        if (command.contains("dmabuf-present")) {
            return handleDmaBufPresent(command, dmabufFd);
        }
        return "waylandie-bridge unknown-command";
    }

    private String handleDmaBufPresent(String command, int dmabufFd) {
        try {
            int srcWidth = parseIntField(command, "width=");
            int srcHeight = parseIntField(command, "height=");
            long format = parseLongField(command, "format=");
            long modifier = parseLongField(command, "modifier=");
            int stride0 = parseIntField(command, "stride0=");
            long offset0 = parseLongField(command, "offset0=");
            long size = parseLongField(command, "size=");
            String driverName = parseStringField(command, "driver=");

            Log.i(TAG, "dmabuf-present: " + srcWidth + "x" + srcHeight +
                    " fd=" + dmabufFd + " format=0x" + Long.toHexString(format) +
                    " stride=" + stride0 + " size=" + size);

            if (dmabufFd < 0) {
                return "waylandie-bridge dmabuf-present status=fail reason=no-fd";
            }

            // Ensure presentLayer exists
            ensurePresentLayer(srcWidth, srcHeight);
            if (presentLayer == null) {
                return "waylandie-bridge dmabuf-present status=fail reason=no-surfacecontrol";
            }

            // Get paths for native present
            String tmpDir = "/data/local/tmp";
            String hookLibDir = "/system/lib64";
            String driverDir = "/data/user/0/io.waylandie.display/files/adrenotools-driver";
            if (driverName == null || driverName.isEmpty()) {
                driverName = "vulkan.waylandie.a8xx.so";
            }

            // Call native present
            String result = nativePresentAhbVkDmaBufFrame(
                    presentLayer,
                    dmabufFd,
                    srcWidth, srcHeight,
                    format, modifier, 1,
                    stride0, offset0, size,
                    srcWidth, srcHeight,
                    frameIndex++,
                    tmpDir, hookLibDir,
                    driverDir, driverName);

            Log.i(TAG, "Present result: " + result);

            // Check if present passed
            if (result != null && result.contains("status=pass")) {
                return "waylandie-bridge dmabuf-present status=pass";
            } else {
                return "waylandie-bridge dmabuf-present status=fail reason=" + result;
            }
        } catch (Exception e) {
            Log.e(TAG, "dmabuf-present error", e);
            return "waylandie-bridge dmabuf-present status=fail reason=" + e.getMessage();
        }
    }

    private void ensurePresentLayer(int w, int h) {
        if (presentLayer != null && width == w && height == h) return;
        if (presentLayer != null) {
            presentLayer.release();
            presentLayer = null;
        }
        if (hostView == null || hostView.getSurfaceControl() == null) {
            Log.w(TAG, "Cannot create presentLayer — hostView SurfaceControl is null");
            return;
        }
        try {
            presentLayer = new SurfaceControl.Builder()
                .setName("waylandie-present")
                .setParent(hostView.getSurfaceControl())
                .setBufferSize(w, h)
                .build();
            width = w;
            height = h;
            new SurfaceControl.Transaction()
                .setLayer(presentLayer, 10)
                .setVisibility(presentLayer, true)
                .setPosition(presentLayer, 0, 0)
                .apply();
            Log.i(TAG, "Created presentLayer: " + w + "x" + h);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create presentLayer", e);
        }
    }

    private static int parseIntField(String s, String key) {
        int idx = s.indexOf(key);
        if (idx < 0) return 0;
        int start = idx + key.length();
        int end = start;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '-')) end++;
        try { return Integer.parseInt(s.substring(start, end)); } catch (Exception e) { return 0; }
    }

    private static long parseLongField(String s, String key) {
        int idx = s.indexOf(key);
        if (idx < 0) return 0;
        int start = idx + key.length();
        int end = start;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '-' ||
                s.charAt(end) == 'x' || (s.charAt(end) >= 'a' && s.charAt(end) <= 'f') ||
                (s.charAt(end) >= 'A' && s.charAt(end) <= 'F'))) end++;
        String num = s.substring(start, end);
        try {
            if (num.startsWith("0x")) return Long.parseUnsignedLong(num.substring(2), 16);
            return Long.parseLong(num);
        } catch (Exception e) { return 0; }
    }

    private static String parseStringField(String s, String key) {
        int idx = s.indexOf(key);
        if (idx < 0) return "";
        int start = idx + key.length();
        int end = start;
        while (end < s.length() && s.charAt(end) != ' ' && s.charAt(end) != '\n') end++;
        return s.substring(start, end);
    }
}
