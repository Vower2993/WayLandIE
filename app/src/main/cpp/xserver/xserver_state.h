// WayLandIE minimal X server — server state structures.
//
// Tracks windows, pixmaps, GCs, atoms, properties — the bare minimum
// Wine's winex11.drv needs.
//
// The root window's framebuffer is backed by an AHardwareBuffer, which
// on Android IS a dmabuf. After any drawing operation that damages the
// root window, we notify the bridge (via the existing Unix socket) so
// it can import the dmabuf and present it on the Android Surface.
#pragma once

#include <android/hardware_buffer.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <string>
#include <unordered_map>
#include <vector>

namespace waylandie_x11 {

// Forward decl
struct XServerState;

// ----- X11 Window (matches a subset of the protocol's window attrs) -----
struct XWindow {
    uint32_t id = 0;
    uint32_t parent = 0;        // 0 for root
    int16_t  x = 0, y = 0;
    uint16_t width = 0, height = 0;
    uint16_t border_width = 0;
    uint8_t  depth = 24;        // we only support 24-bit (X's RGB)
    uint8_t  class_ = 0;        // InputOutput=1, InputOnly=2
    bool     mapped = false;
    bool     override_redirect = false;
    bool     save_under = false;
    uint32_t backing_pixel = 0;
    uint32_t background_pixel = 0;  // 0 = black by default
    uint32_t border_pixel = 0;
    uint32_t colormap = 0;
    uint32_t event_mask = 0;        // bitmask of EventMask values
    uint32_t do_not_propagate_mask = 0;

    // If non-null, this window has its own backing store (for child windows
    // that Wine renders to before copying to root). For the root window,
    // this points to the AHardwareBuffer's mapped memory.
    uint32_t* pixels = nullptr;
    bool owns_pixels = false;       // true if we malloc'd pixels (child windows)
};

// ----- X11 Pixmap -----
struct XPixmap {
    uint32_t id = 0;
    uint32_t drawable = 0;          // root or a window
    uint16_t width = 0, height = 0;
    uint8_t  depth = 24;
    uint32_t* pixels = nullptr;
    bool owns_pixels = false;
};

// ----- X11 Graphics Context -----
struct XGC {
    uint32_t id = 0;
    uint32_t function = GX_copy;
    uint32_t plane_mask = 0xffffffff;
    uint32_t foreground = 0;
    uint32_t background = 1;
    uint32_t line_width = 0;
    uint32_t line_style = 0;        // LineSolid=0
    uint32_t fill_style = 0;        // FillSolid=0
    int16_t  clip_x_origin = 0, clip_y_origin = 0;
    uint32_t clip_mask = 0;         // 0 = none
    uint32_t subwindow_mode = 0;    // ClipByChildren=0
    bool     graphics_exposures = true;
    uint16_t dash_offset = 0;
};

// ----- X11 Atom (dynamic) -----
struct XAtom {
    uint32_t id = 0;
    std::string name;
};

// ----- X11 Property -----
struct XProperty {
    uint32_t window = 0;
    uint32_t atom = 0;
    uint32_t type = 0;        // e.g. XA_STRING
    uint8_t  format = 8;      // 8, 16, or 32
    std::vector<uint8_t> data;
};

// ----- Client connection state -----
struct XClient {
    int fd = -1;
    uint16_t sequence = 0;
    bool authenticated = false;
    // Client's resource ID range (from setup)
    uint32_t resource_id_base = 0;
    uint32_t resource_id_mask = 0;
    // Buffer for partial requests (X11 requests can span multiple reads)
    std::vector<uint8_t> inbuf;
    // Buffer for pending output (replies, events, errors)
    std::vector<uint8_t> outbuf;
};

// ----- AHardwareBuffer-backed framebuffer -----
struct Framebuffer {
    AHardwareBuffer* ahb = nullptr;
    void* mapped = nullptr;          // AHardwareBuffer_lock() result
    int stride_pixels = 0;           // stride in pixels (NOT bytes)
    int width = 0, height = 0;
    int dmabuf_fd = -1;              // exported from AHardwareBuffer
    // Mutex protects mapped pointer (lock before any CPU drawing)
    pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
};

// ----- The whole server state -----
struct XServerState {
    // Connection / lifecycle
    int listen_sock = -1;
    bool running = false;
    pthread_t accept_thread;

    // Bridge socket (sends dmabuf fds after damage)
    int bridge_sock = -1;
    std::string bridge_sock_name;

    // Framebuffer (root window's backing store = AHardwareBuffer)
    Framebuffer fb;

    // Resource tables (id -> resource)
    std::unordered_map<uint32_t, XWindow> windows;
    std::unordered_map<uint32_t, XPixmap> pixmaps;
    std::unordered_map<uint32_t, XGC> gcs;
    std::unordered_map<uint32_t, XProperty> properties;

    // Atom table
    std::unordered_map<uint32_t, XAtom> atoms;
    std::unordered_map<std::string, uint32_t> atoms_by_name;
    uint32_t next_atom_id = 100;  // X reserves 1-68; dynamic atoms start at 68+

    // Client connections
    std::vector<XClient*> clients;
    pthread_mutex_t clients_lock = PTHREAD_MUTEX_INITIALIZER;

    // Root window id (assigned at startup, always 0x00000057 by convention
    // but we use whatever we allocate first)
    uint32_t root_window_id = 0;
    uint32_t root_colormap_id = 0x20;
    uint32_t root_visual_id = 0x21;

    // Input focus
    uint32_t focus_window = 0;     // 0 = none, 1 = pointer root

    // Damage tracking — when true, send dmabuf to bridge after next flush
    bool damaged = false;
    pthread_mutex_t damage_lock = PTHREAD_MUTEX_INITIALIZER;
};

}  // namespace waylandie_x11
