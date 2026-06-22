// WayLandIE minimal X server — main implementation.
//
// Implements just enough of the X11 core wire protocol to satisfy Wine's
// winex11.drv. The root window's framebuffer is an AHardwareBuffer, which
// is exported as a dmabuf fd to the existing Wayland bridge after damage.
//
// Wire protocol reference: X11 Protocol Specification (X Consortium).
// All multi-byte integers on the wire are BIG-ENDIAN.
//
// NOTE: This is intentionally minimal. We implement only the requests that
// Wine's winex11.drv is known to issue during startup + explorer.exe
// rendering. Missing requests return a no-op (we send no reply for
// requests that expect one, then synthesize a fake reply on the next
// read — TODO: send proper errors).
#include "x11_protocol.h"
#include "xserver_state.h"

#include <android/hardware_buffer.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#define TAG "WayLandIE/XServer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// AHardwareBuffer_getNativeHandle is a platform API (present in libandroid.so
// since API 26) but is NOT exposed via the NDK <android/hardware_buffer.h>
// header. We forward-declare it ourselves. The struct layout of native_handle_t
// is stable and defined in hardware/libhardware/include/hardware/native_handle.h.
// We don't include that header (it's platform-only) — instead we replicate the
// minimal layout we need.
struct native_handle_t_fwd {
    int version;    // sizeof(native_handle_t)
    int numFds;     // number of file descriptors in data[]
    int numInts;    // number of ints in data[] after the fds
    int data[];     // numFds fds followed by numInts ints
};
extern "C" const native_handle_t_fwd* AHardwareBuffer_getNativeHandle(
    const AHardwareBuffer* buffer);

namespace waylandie_x11 {

// =====================================================================
// Cursor helpers — read/write big-endian values from a byte buffer
// =====================================================================

static inline uint16_t rd_be16(const uint8_t* p) {
    return ((uint16_t)p[0] << 8) | p[1];
}
static inline uint32_t rd_be32(const uint8_t* p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16)
         | ((uint32_t)p[2] << 8)  | p[3];
}
static inline void wr_be16(uint8_t* p, uint16_t v) {
    p[0] = (v >> 8) & 0xff; p[1] = v & 0xff;
}
static inline void wr_be32(uint8_t* p, uint32_t v) {
    p[0] = (v >> 24) & 0xff; p[1] = (v >> 16) & 0xff;
    p[2] = (v >> 8) & 0xff;  p[3] = v & 0xff;
}

// =====================================================================
// AHardwareBuffer framebuffer
// =====================================================================

static bool fb_init(Framebuffer* fb, int width, int height) {
    AHardwareBuffer_Desc desc = {};
    desc.width = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN
               | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN
               | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
    desc.rfu0 = 0;
    desc.rfu1 = 0;

    int rc = AHardwareBuffer_allocate(&desc, &fb->ahb);
    if (rc != 0) {
        LOGE("AHardwareBuffer_allocate failed: rc=%d errno=%d (%s)",
             rc, errno, strerror(errno));
        return false;
    }
    fb->width = width;
    fb->height = height;

    // Map for CPU access (we'll lock/unlock on each drawing operation)
    AHardwareBuffer_Planes planes = {};
    rc = AHardwareBuffer_lock(fb->ahb,
                              AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN
                              | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
                              -1, nullptr, &fb->mapped);
    if (rc != 0) {
        LOGE("AHardwareBuffer_lock failed: rc=%d", rc);
        AHardwareBuffer_release(fb->ahb);
        fb->ahb = nullptr;
        return false;
    }

    // Get stride (in pixels)
    AHardwareBuffer_Desc queried = {};
    AHardwareBuffer_describe(fb->ahb, &queried);
    fb->stride_pixels = queried.stride ? queried.stride : width;
    LOGI("Framebuffer: %dx%d stride=%d", width, height, fb->stride_pixels);

    // Clear to black
    if (fb->mapped) {
        memset(fb->mapped, 0, (size_t)fb->stride_pixels * height * 4);
    }
    return true;
}

static int fb_get_dmabuf_fd(Framebuffer* fb) {
    if (fb->dmabuf_fd >= 0) return fb->dmabuf_fd;
    if (!fb->ahb) return -1;

    // AHardwareBuffer_getNativeHandle returns the native_handle_t which
    // contains the dmabuf fd(s) for the buffer. On Qualcomm/Adreno:
    //   handle->numFds = 1, handle->data[0] = dmabuf fd
    const native_handle_t_fwd* h = AHardwareBuffer_getNativeHandle(fb->ahb);
    if (!h || h->numFds < 1) {
        LOGE("AHardwareBuffer_getNativeHandle returned no fds");
        return -1;
    }
    // dup it so we can return it without closing the original
    fb->dmabuf_fd = dup(h->data[0]);
    LOGI("Framebuffer dmabuf fd=%d (from handle numFds=%d numInts=%d)",
         fb->dmabuf_fd, h->numFds, h->numInts);
    return fb->dmabuf_fd;
}

// =====================================================================
// Bridge socket — send dmabuf fd after damage
// =====================================================================

static bool bridge_connect(XServerState* st, const std::string& sock_name) {
    int s = socket(AF_UNIX, SOCK_STREAM, 0);
    if (s < 0) {
        LOGE("bridge socket() failed: %s", strerror(errno));
        return false;
    }
    struct sockaddr_un addr = {};
    addr.sun_family = AF_UNIX;
    // Abstract namespace: leading null byte + name
    addr.sun_path[0] = '\0';
    strncpy(addr.sun_path + 1, sock_name.c_str(),
            sizeof(addr.sun_path) - 2);
    socklen_t len = offsetof(struct sockaddr_un, sun_path) + 1 + sock_name.size();

    if (connect(s, (struct sockaddr*)&addr, len) < 0) {
        LOGE("bridge connect(%s) failed: %s", sock_name.c_str(), strerror(errno));
        close(s);
        return false;
    }
    st->bridge_sock = s;
    st->bridge_sock_name = sock_name;
    LOGI("Connected to bridge socket: %s (fd=%d)", sock_name.c_str(), s);
    return true;
}

static void bridge_send_dmabuf(XServerState* st) {
    if (st->bridge_sock < 0) return;
    int fd = fb_get_dmabuf_fd(&st->fb);
    if (fd < 0) return;

    // Protocol matches what waylandie-wayland-bridge.c expects for a
    // "dmabuf-present" command (RESPONSE_PREFIX "waylandie-bridge dmabuf-present ...").
    // Format: "waylandie-bridge dmabuf-present fast=1 window=root width=W height=H
    //          format=0x1 modifier=0x0 planes=1 stride0=S offset0=0 size=N driver=xserver\n"
    char cmd[256];
    int n = snprintf(cmd, sizeof(cmd),
        "waylandie-bridge dmabuf-present fast=1 window=root "
        "width=%d height=%d format=0x1 modifier=0x0000000000000000 "
        "planes=1 stride0=%d offset0=0 size=%lld driver=xserver\n",
        st->fb.width, st->fb.height,
        st->fb.stride_pixels * 4,
        (long long)st->fb.stride_pixels * st->fb.height * 4);

    // Send cmd + fd via SCM_RIGHTS
    struct iovec iov = { .iov_base = cmd, .iov_len = (size_t)n };
    union {
        char buf[CMSG_SPACE(sizeof(int))];
        struct cmsghdr align;
    } u;
    struct msghdr msg = {};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = u.buf;
    msg.msg_controllen = sizeof(u.buf);

    struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &fd, sizeof(int));

    ssize_t sent = sendmsg(st->bridge_sock, &msg, 0);
    if (sent < 0) {
        LOGW("bridge sendmsg failed: %s", strerror(errno));
    } else {
        LOGD("Sent dmabuf fd %d to bridge (%d bytes)", fd, (int)sent);
    }
}

// =====================================================================
// Atom table
// =====================================================================

static uint32_t atom_intern(XServerState* st, const std::string& name, bool only_if_exists) {
    auto it = st->atoms_by_name.find(name);
    if (it != st->atoms_by_name.end()) return it->second;
    if (only_if_exists) return 0;

    uint32_t id = st->next_atom_id++;
    XAtom a; a.id = id; a.name = name;
    st->atoms[id] = a;
    st->atoms_by_name[name] = id;
    LOGD("Interned atom '%s' = %u", name.c_str(), id);
    return id;
}

// =====================================================================
// Connection setup — the X11 handshake
// =====================================================================

// The setup request from the client (X11 Protocol §2 "Connection Setup").
// After connecting, the client sends:
//   byte 0: 'l' (little-endian) or 'B' (big-endian)
//   byte 1: unused
//   uint16: protocol-major-version (BE or LE per byte 0)
//   uint16: protocol-minor-version
//   uint16: length of auth-protocol-name (n)
//   uint16: length of auth-protocol-data (d)
//   uint16: unused
//   then n bytes name + d bytes data, padded to 4-byte boundary
// We reply with SetupSuccess.

static bool do_connection_setup(XServerState* st, XClient* client) {
    // Read the 12-byte setup prefix
    uint8_t prefix[12];
    ssize_t got = 0;
    while (got < 12) {
        ssize_t r = read(client->fd, prefix + got, 12 - got);
        if (r <= 0) {
            LOGE("setup: read failed: %s", strerror(errno));
            return false;
        }
        got += r;
    }

    uint8_t byte_order = prefix[0];  // 'l' or 'B'
    bool client_is_be = (byte_order == 'B');
    (void)client_is_be;  // we always speak BE on the wire; client's order
                          // only affects how we parse the setup request

    auto rd16 = [client_is_be](const uint8_t* p) -> uint16_t {
        return client_is_be ? ((uint16_t)p[0] << 8) | p[1]
                            : ((uint16_t)p[1] << 8) | p[0];
    };

    uint16_t major = rd16(prefix + 2);
    uint16_t minor = rd16(prefix + 4);
    uint16_t auth_name_len = rd16(prefix + 6);
    uint16_t auth_data_len = rd16(prefix + 8);
    uint16_t unused = rd16(prefix + 10);
    (void)unused;

    // Skip auth (we don't check it)
    size_t auth_total = (auth_name_len + 3) & ~3u;
    auth_total += (auth_data_len + 3) & ~3u;
    if (auth_total > 0) {
        std::vector<uint8_t> skip(auth_total);
        got = 0;
        while ((size_t)got < auth_total) {
            ssize_t r = read(client->fd, skip.data() + got, auth_total - got);
            if (r <= 0) return false;
            got += r;
        }
    }

    // Build the SetupSuccess reply.
    //
    // Layout (all BE since we always speak BE on wire):
    //   1 byte  status = 2 (success)
    //   1 byte  pad
    //   2 bytes protocol-major-version (we claim 11)
    //   2 bytes protocol-minor-version (we claim 0)
    //   2 bytes length (additional data in 4-byte units)
    //   4 bytes release-number
    //   4 bytes resource-id-base
    //   4 bytes resource-id-mask
    //   4 bytes motion-buffer-size
    //   2 bytes vendor-length
    //   2 bytes max-request-size (in 4-byte units, max 65535)
    //   1 byte  num-screen-roots
    //   1 byte  num-pixmap-formats
    //   1 byte  image-byte-order (0=LSB, 1=MSB)
    //   1 byte  bitmap-bit-order (0=LSB, 1=MSB)
    //   1 byte  bitmap-scanline-unit
    //   1 byte  bitmap-scanline-pad
    //   1 byte  min-keycode (8)
    //   1 byte  max-keycode (255)
    //   4 bytes pad
    // Then:
    //   vendor string "WayLandIE" (padded to 4 bytes)
    //   num_pixmap_formats * 8 bytes (PixmapFormatWire)
    //   num_screen_roots * ScreenWire (with depths and visuals)

    // We assign this client a resource ID range. For simplicity, each client
    // gets a 16-bit range (mask 0xffff). Bases are assigned starting at 0x01000000.
    static uint32_t next_resource_base = 0x01000000;
    client->resource_id_base = next_resource_base;
    client->resource_id_mask = 0x0000ffff;
    next_resource_base += 0x00010000;

    // Vendor
    const char* vendor = "WayLandIE";
    uint16_t vendor_len = strlen(vendor);
    uint16_t vendor_padded = (vendor_len + 3) & ~3u;

    // Pixmap formats: one entry for depth 24, 32 bpp, 32 pad
    uint8_t num_formats = 1;
    // Screen: one root
    uint8_t num_screens = 1;

    // Build the screen block (40 bytes header + depths)
    // We have depth 24 with one visual (TrueColor, 32bpp).
    // Depth 1 (bitmaps) — Wine asks for it.
    uint8_t num_depths = 2;  // depth 1 and depth 24

    // Calculate sizes:
    // depth block 1 (depth=1): 8 bytes header, 0 visuals
    // depth block 2 (depth=24): 8 bytes header, 1 visual (24 bytes)
    size_t depth1_size = 8;
    size_t depth24_size = 8 + 24;
    size_t screen_size = 40 + depth1_size + depth24_size;

    size_t additional = vendor_padded + num_formats * 8 + screen_size;
    uint16_t length_units = (additional + 3) / 4;

    // Total reply size: 40 bytes header (SetupSuccess) + additional
    std::vector<uint8_t> reply;
    reply.reserve(40 + additional);

    auto put8 = [&](uint8_t v) { reply.push_back(v); };
    auto put16 = [&](uint16_t v) {
        reply.push_back((v >> 8) & 0xff);
        reply.push_back(v & 0xff);
    };
    auto put32 = [&](uint32_t v) {
        reply.push_back((v >> 24) & 0xff);
        reply.push_back((v >> 16) & 0xff);
        reply.push_back((v >> 8) & 0xff);
        reply.push_back(v & 0xff);
    };

    // Header (40 bytes)
    put8(2);                                    // status = success
    put8(0);                                    // pad
    put16(11);                                  // protocol-major-version
    put16(0);                                   // protocol-minor-version
    put16(length_units);                        // length
    put32(1180000);                             // release-number (arbitrary)
    put32(client->resource_id_base);            // resource-id-base
    put32(client->resource_id_mask);            // resource-id-mask
    put32(65536);                               // motion-buffer-size
    put16(vendor_len);                          // vendor-length
    put16(65535);                               // max-request-size
    put8(num_screens);                          // num-screen-roots
    put8(num_formats);                          // num-pixmap-formats
    put8(0);                                    // image-byte-order = LSBFirst
    put8(0);                                    // bitmap-bit-order = LSBFirst
    put8(32);                                   // bitmap-scanline-unit
    put8(32);                                   // bitmap-scanline-pad
    put8(8);                                    // min-keycode
    put8(255);                                  // max-keycode
    put32(0);                                   // pad

    // Vendor string (padded to 4 bytes)
    for (int i = 0; i < vendor_len; i++) put8(vendor[i]);
    for (int i = vendor_len; i < vendor_padded; i++) put8(0);

    // Pixmap formats (1 entry: depth=24, bpp=32, pad=32)
    put8(24);                                   // depth
    put8(32);                                   // bits-per-pixel
    put8(32);                                   // scanline-pad
    put8(0); put8(0); put8(0); put8(0); put8(0); // pad[5]

    // Screen block (40 bytes header)
    uint32_t root_id = 0x00000057;  // conventional root window id
    uint32_t root_colormap = 0x00000020;
    uint32_t root_visual = 0x00000021;
    st->root_window_id = root_id;
    st->root_colormap_id = root_colormap;
    st->root_visual_id = root_visual;

    put32(root_id);                             // root window id
    put32(root_colormap);                       // default-colormap
    put32(0x00ffffff);                          // white-pixel
    put32(0x00000000);                          // black-pixel
    put32(0x00ffffff);                          // current-input-masks (ExposureMask|StructureNotifyMask|SubstructureNotifyMask|SubstructureRedirectMask|FocusChangeMask|PropertyChangeMask)
    put16((uint16_t)st->fb.width);              // width-in-pixels
    put16((uint16_t)st->fb.height);             // height-in-pixels
    put16((uint16_t)(st->fb.width * 254 / 960));   // width-in-mm (roughly 96 DPI)
    put16((uint16_t)(st->fb.height * 254 / 960));  // height-in-mm
    put16(1);                                   // min-installed-maps
    put16(1);                                   // max-installed-maps
    put32(root_visual);                         // root-visual
    put8(0);                                    // backing-stores (Never=0)
    put8(0);                                    // save-unders (false)
    put8(24);                                   // root-depth
    put8(num_depths);                           // num-depths

    // Depth 1 (bitmap) — 0 visuals
    put8(1);                                    // depth
    put8(0);                                    // pad
    put16(0);                                   // num-visuals
    put32(0);                                   // pad

    // Depth 24 — 1 TrueColor visual
    put8(24);                                   // depth
    put8(0);                                    // pad
    put16(1);                                   // num-visuals
    put32(0);                                   // pad

    // Visual (24 bytes)
    put32(root_visual);                         // visual-id
    put8(4);                                    // class = TrueColor
    put8(8);                                    // bits-per-rgb-value
    put16(256);                                 // colormap-entries
    put32(0x00ff0000);                          // red-mask
    put32(0x0000ff00);                          // green-mask
    put32(0x000000ff);                          // blue-mask
    put32(0);                                   // pad

    // Send the reply
    ssize_t sent = write(client->fd, reply.data(), reply.size());
    if (sent < 0 || (size_t)sent != reply.size()) {
        LOGE("setup: write failed: %s", strerror(errno));
        return false;
    }

    // Register the root window in our window table
    XWindow root = {};
    root.id = root_id;
    root.parent = 0;
    root.x = 0; root.y = 0;
    root.width = (uint16_t)st->fb.width;
    root.height = (uint16_t)st->fb.height;
    root.depth = 24;
    root.class_ = 1;  // InputOutput
    root.mapped = true;
    root.event_mask = 0;
    root.pixels = (uint32_t*)st->fb.mapped;  // root draws straight into AHardwareBuffer
    root.owns_pixels = false;
    st->windows[root_id] = root;

    client->authenticated = true;
    LOGI("Setup complete: client=%d root=0x%08x %dx%d vendor='%s'",
         client->fd, root_id, st->fb.width, st->fb.height, vendor);
    return true;
}

// =====================================================================
// Reply & error helpers
// =====================================================================

static void send_error(XClient* c, uint8_t code, uint32_t bad_value,
                       uint8_t major_op, uint16_t minor_op) {
    uint8_t err[32] = {};
    err[0] = 0;                  // is_error
    err[1] = code;
    wr_be16(err + 2, c->sequence);
    wr_be32(err + 4, bad_value);
    wr_be16(err + 8, minor_op);
    err[10] = major_op;
    // err[11..31] = pad
    c->outbuf.insert(c->outbuf.end(), err, err + 32);
}

static void send_noop_reply(XClient* c, uint32_t additional_bytes = 0) {
    // For requests that expect a reply but we don't implement properly,
    // send a minimal valid reply: 32 bytes, with length=additional_bytes/4.
    uint8_t reply[32] = {};
    reply[0] = 1;  // is_reply
    wr_be16(reply + 2, c->sequence);
    wr_be32(reply + 4, additional_bytes / 4);
    c->outbuf.insert(c->outbuf.end(), reply, reply + 32);
}

static void flush_output(XClient* c) {
    if (c->outbuf.empty()) return;
    ssize_t sent = write(c->fd, c->outbuf.data(), c->outbuf.size());
    if (sent < 0) {
        LOGW("flush_output: write failed: %s", strerror(errno));
    } else if ((size_t)sent != c->outbuf.size()) {
        LOGW("flush_output: partial write %zd/%zu", sent, c->outbuf.size());
    }
    c->outbuf.clear();
}

// =====================================================================
// Drawing helpers — operate on the AHardwareBuffer-mapped framebuffer
// =====================================================================

static void draw_put_zpixmap(XServerState* st, XWindow* win,
                              int dst_x, int dst_y,
                              int src_w, int src_h,
                              const uint8_t* src_data, int src_stride_bytes) {
    if (!win || !win->pixels) return;
    if (win->id != st->root_window_id) {
        // For non-root windows, just draw into their backing store (if any).
        // We don't currently allocate backing stores for child windows —
        // Wine typically draws to the root or to a pixmap, then copies to root.
        LOGD("put_image on non-root window 0x%x (drawing skipped)", win->id);
        return;
    }

    pthread_mutex_lock(&st->fb.lock);
    uint32_t* dst = (uint32_t*)st->fb.mapped;
    int dst_stride = st->fb.stride_pixels;
    int fb_w = st->fb.width, fb_h = st->fb.height;

    // Clip to framebuffer bounds
    int x0 = dst_x < 0 ? 0 : dst_x;
    int y0 = dst_y < 0 ? 0 : dst_y;
    int x1 = dst_x + src_w > fb_w ? fb_w : dst_x + src_w;
    int y1 = dst_y + src_h > fb_h ? fb_h : dst_y + src_h;
    if (x1 <= x0 || y1 <= y0) {
        pthread_mutex_unlock(&st->fb.lock);
        return;
    }

    for (int y = y0; y < y1; y++) {
        int src_y = y - dst_y;
        const uint8_t* src_row = src_data + (size_t)src_y * src_stride_bytes;
        uint32_t* dst_row = dst + (size_t)y * dst_stride;
        for (int x = x0; x < x1; x++) {
            int src_x = x - dst_x;
            // ZPixmap format for depth 24, bpp 32: 0x00RRGGBB (or 0xAARRGGBB)
            // We assume client uses LSBFirst byte order (which we declared).
            uint32_t b = src_row[src_x * 4 + 0];
            uint32_t g = src_row[src_x * 4 + 1];
            uint32_t r = src_row[src_x * 4 + 2];
            uint32_t a = src_row[src_x * 4 + 3];
            dst_row[x] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }
    pthread_mutex_unlock(&st->fb.lock);

    pthread_mutex_lock(&st->damage_lock);
    st->damaged = true;
    pthread_mutex_unlock(&st->damage_lock);
}

static void draw_clear_area(XServerState* st, XWindow* win,
                             int x, int y, int w, int h,
                             uint32_t pixel) {
    if (!win || !win->pixels) return;
    if (win->id != st->root_window_id) return;

    pthread_mutex_lock(&st->fb.lock);
    uint32_t* dst = (uint32_t*)st->fb.mapped;
    int dst_stride = st->fb.stride_pixels;
    int fb_w = st->fb.width, fb_h = st->fb.height;

    int x0 = x < 0 ? 0 : x;
    int y0 = y < 0 ? 0 : y;
    int x1 = x + w > fb_w ? fb_w : x + w;
    int y1 = y + h > fb_h ? fb_h : y + h;
    for (int yy = y0; yy < y1; yy++) {
        uint32_t* row = dst + (size_t)yy * dst_stride;
        for (int xx = x0; xx < x1; xx++) row[xx] = pixel;
    }
    pthread_mutex_unlock(&st->fb.lock);

    pthread_mutex_lock(&st->damage_lock);
    st->damaged = true;
    pthread_mutex_unlock(&st->damage_lock);
}

// =====================================================================
// Request dispatchers
// =====================================================================

static void handle_create_window(XServerState* st, XClient* c,
                                  const uint8_t* req, size_t len) {
    if (len < 32) { send_error(c, ERR_Length, 0, X_CreateWindow, 0); return; }
    uint32_t wid = rd_be32(req + 4);
    uint32_t parent = rd_be32(req + 8);
    int16_t  x = (int16_t)rd_be16(req + 12);
    int16_t  y = (int16_t)rd_be16(req + 14);
    uint16_t w = rd_be16(req + 16);
    uint16_t h = rd_be16(req + 18);
    uint16_t bw = rd_be16(req + 20);
    uint8_t  depth = req[1];
    uint8_t  class_ = req[24];
    uint32_t mask = rd_be32(req + 28);

    XWindow win = {};
    win.id = wid;
    win.parent = parent;
    win.x = x; win.y = y;
    win.width = w; win.height = h;
    win.border_width = bw;
    win.depth = depth ? depth : 24;
    win.class_ = class_;
    win.mapped = false;

    // Parse value-list (only present if mask != 0)
    const uint8_t* vals = req + 32;
    size_t avail = len - 32;
    auto take32 = [&](uint32_t bit) -> uint32_t {
        if (!(mask & bit)) return 0;
        size_t idx = __builtin_popcount(mask & (bit - 1));
        if ((idx + 1) * 4 > avail) return 0;
        return rd_be32(vals + idx * 4);
    };
    if (mask & WA_BackgroundPixel)  win.background_pixel = take32(WA_BackgroundPixel);
    if (mask & WA_BorderPixel)      win.border_pixel = take32(WA_BorderPixel);
    if (mask & WA_OverrideRedirect) win.override_redirect = (take32(WA_OverrideRedirect) != 0);
    if (mask & WA_SaveUnder)        win.save_under = (take32(WA_SaveUnder) != 0);
    if (mask & WA_EventMask)        win.event_mask = take32(WA_EventMask);
    if (mask & WA_Colormap)         win.colormap = take32(WA_Colormap);
    if (mask & WA_BackingPixel)     win.backing_pixel = take32(WA_BackingPixel);

    st->windows[wid] = win;
    LOGD("CreateWindow id=0x%x parent=0x%x %dx%d+%d+%d depth=%d",
         wid, parent, w, h, x, y, win.depth);
}

static void handle_change_window_attrs(XServerState* st, XClient* c,
                                        const uint8_t* req, size_t len) {
    if (len < 12) { send_error(c, ERR_Length, 0, X_ChangeWindowAttributes, 0); return; }
    uint32_t wid = rd_be32(req + 4);
    uint32_t mask = rd_be32(req + 8);
    auto it = st->windows.find(wid);
    if (it == st->windows.end()) {
        send_error(c, ERR_Window, wid, X_ChangeWindowAttributes, 0);
        return;
    }
    const uint8_t* vals = req + 12;
    size_t avail = len - 12;
    auto take32 = [&](uint32_t bit) -> uint32_t {
        if (!(mask & bit)) return 0;
        size_t idx = __builtin_popcount(mask & (bit - 1));
        if ((idx + 1) * 4 > avail) return 0;
        return rd_be32(vals + idx * 4);
    };
    if (mask & WA_BackgroundPixel)  it->second.background_pixel = take32(WA_BackgroundPixel);
    if (mask & WA_BorderPixel)      it->second.border_pixel = take32(WA_BorderPixel);
    if (mask & WA_OverrideRedirect) it->second.override_redirect = (take32(WA_OverrideRedirect) != 0);
    if (mask & WA_EventMask)        it->second.event_mask = take32(WA_EventMask);
    if (mask & WA_Colormap)         it->second.colormap = take32(WA_Colormap);
}

static void handle_map_window(XServerState* st, XClient* c,
                               const uint8_t* req, size_t len) {
    if (len < 8) { send_error(c, ERR_Length, 0, X_MapWindow, 0); return; }
    uint32_t wid = rd_be32(req + 4);
    auto it = st->windows.find(wid);
    if (it == st->windows.end()) {
        send_error(c, ERR_Window, wid, X_MapWindow, 0);
        return;
    }
    it->second.mapped = true;
    LOGD("MapWindow id=0x%x", wid);

    // Send MapNotify event (event type 19) to clients listening for it.
    // For now, just send to this client.
    uint8_t ev[32] = {};
    ev[0] = EV_MapNotify;
    ev[1] = 0;  // override-redirect
    wr_be32(ev + 4, wid);         // event window
    wr_be32(ev + 8, wid);         // window
    wr_be16(ev + 12, 0);          // override-redirect (false)
    wr_be16(ev + 14, 0);          // pad
    c->outbuf.insert(c->outbuf.end(), ev, ev + 32);

    // Also send Expose event so Wine redraws
    if (it->second.event_mask & EM_ExposureMask) {
        uint8_t ex[32] = {};
        ex[0] = EV_Expose;
        ex[1] = 0;
        wr_be32(ex + 4, wid);
        wr_be16(ex + 8, 0);  // x
        wr_be16(ex + 10, 0); // y
        wr_be16(ex + 12, it->second.width);
        wr_be16(ex + 14, it->second.height);
        ex[16] = 0;  // count
        c->outbuf.insert(c->outbuf.end(), ex, ex + 32);
    }
}

static void handle_unmap_window(XServerState* st, XClient* c,
                                  const uint8_t* req, size_t len) {
    if (len < 8) return;
    uint32_t wid = rd_be32(req + 4);
    auto it = st->windows.find(wid);
    if (it != st->windows.end()) {
        it->second.mapped = false;
    }
}

static void handle_destroy_window(XServerState* st, XClient* c,
                                    const uint8_t* req, size_t len) {
    if (len < 8) return;
    uint32_t wid = rd_be32(req + 4);
    st->windows.erase(wid);
    LOGD("DestroyWindow id=0x%x", wid);
}

static void handle_configure_window(XServerState* st, XClient* c,
                                     const uint8_t* req, size_t len) {
    if (len < 12) return;
    uint32_t wid = rd_be32(req + 4);
    uint32_t mask = rd_be16(req + 8);
    auto it = st->windows.find(wid);
    if (it == st->windows.end()) return;
    const uint8_t* vals = req + 12;
    size_t avail = len - 12;
    auto take32 = [&](uint32_t bit) -> uint32_t {
        if (!(mask & bit)) return 0;
        size_t idx = __builtin_popcount(mask & (bit - 1));
        if ((idx + 1) * 4 > avail) return 0;
        return rd_be32(vals + idx * 4);
    };
    if (mask & CW_X)      it->second.x = (int16_t)take32(CW_X);
    if (mask & CW_Y)      it->second.y = (int16_t)take32(CW_Y);
    if (mask & CW_Width)  it->second.width = (uint16_t)take32(CW_Width);
    if (mask & CW_Height) it->second.height = (uint16_t)take32(CW_Height);
}

static void handle_intern_atom(XServerState* st, XClient* c,
                                const uint8_t* req, size_t len) {
    if (len < 8) { send_error(c, ERR_Length, 0, X_InternAtom, 0); return; }
    uint16_t name_len = rd_be16(req + 4);
    bool only_if_exists = req[1] != 0;
    if (len < (size_t)(8 + ((name_len + 3) & ~3u))) {
        send_error(c, ERR_Length, 0, X_InternAtom, 0);
        return;
    }
    std::string name((const char*)(req + 8), name_len);
    uint32_t atom = atom_intern(st, name, only_if_exists);

    uint8_t reply[32] = {};
    reply[0] = 1;
    wr_be16(reply + 2, c->sequence);
    wr_be32(reply + 8, atom);
    c->outbuf.insert(c->outbuf.end(), reply, reply + 32);
}

static void handle_get_atom_name(XServerState* st, XClient* c,
                                  const uint8_t* req, size_t len) {
    if (len < 8) return;
    uint32_t atom = rd_be32(req + 4);
    auto it = st->atoms.find(atom);
    std::string name = (it != st->atoms.end()) ? it->second.name : "";
    uint16_t name_len = name.size();
    uint32_t padded = (name_len + 3) & ~3u;
    uint32_t additional = padded;

    uint8_t reply[32] = {};
    reply[0] = 1;
    wr_be16(reply + 2, c->sequence);
    wr_be32(reply + 4, additional / 4);
    wr_be16(reply + 8, name_len);
    c->outbuf.insert(c->outbuf.end(), reply, reply + 32);

    std::vector<uint8_t> buf(padded, 0);
    memcpy(buf.data(), name.data(), name_len);
    c->outbuf.insert(c->outbuf.end(), buf.begin(), buf.end());
}

static void handle_change_property(XServerState* st, XClient* c,
                                    const uint8_t* req, size_t len) {
    if (len < 24) return;
    uint8_t  mode = req[1];
    uint32_t wid = rd_be32(req + 4);
    uint32_t atom = rd_be32(req + 8);
    uint32_t type = rd_be32(req + 12);
    uint8_t  format = req[16];
    uint32_t n_units = rd_be32(req + 20);

    XProperty prop = {};
    prop.window = wid;
    prop.atom = atom;
    prop.type = type;
    prop.format = format;
    size_t data_bytes = (size_t)n_units * (format / 8);
    if (24 + data_bytes > len) {
        LOGW("ChangeProperty: truncated data (%zu > %zu)", 24 + data_bytes, len);
        return;
    }
    if (mode == PropMode_Replace) {
        prop.data.assign(req + 24, req + 24 + data_bytes);
    } else if (mode == PropMode_Append) {
        auto it = st->properties.find(wid << 16 | atom);
        if (it != st->properties.end()) {
            prop.data = it->second.data;
            prop.data.insert(prop.data.end(), req + 24, req + 24 + data_bytes);
        } else {
            prop.data.assign(req + 24, req + 24 + data_bytes);
        }
    }  // Prepend not implemented
    st->properties[(uint64_t)wid << 16 | atom] = prop;
    LOGD("ChangeProperty win=0x%x atom=%u type=%u fmt=%d n=%u",
         wid, atom, type, format, n_units);
}

static void handle_get_property(XServerState* st, XClient* c,
                                  const uint8_t* req, size_t len) {
    if (len < 24) return;
    bool     delete_ = req[1] != 0;
    uint32_t wid = rd_be32(req + 4);
    uint32_t atom = rd_be32(req + 8);
    uint32_t type = rd_be32(req + 12);
    uint32_t long_offset = rd_be32(req + 16);
    uint32_t long_length = rd_be32(req + 20);
    (void)type; (void)long_offset; (void)long_length;

    auto it = st->properties.find((uint64_t)wid << 16 | atom);
    if (it == st->properties.end() || it->second.data.empty()) {
        // Property doesn't exist — reply with type=None
        uint8_t reply[32] = {};
        reply[0] = 1;
        wr_be16(reply + 2, c->sequence);
        // type=0 (None), format=0, bytes-after=0, n=0
        c->outbuf.insert(c->outbuf.end(), reply, reply + 32);
        return;
    }

    XProperty& prop = it->second;
    uint32_t n_units = prop.data.size() / (prop.format / 8);
    uint32_t padded = (prop.data.size() + 3) & ~3u;

    uint8_t reply[32] = {};
    reply[0] = 1;
    wr_be16(reply + 2, c->sequence);
    wr_be32(reply + 4, padded / 4);
    wr_be32(reply + 8, prop.type);
    reply[12] = prop.format;
    wr_be32(reply + 16, 0);  // bytes-after
    wr_be32(reply + 20, n_units);
    c->outbuf.insert(c->outbuf.end(), reply, reply + 32);

    std::vector<uint8_t> buf(padded, 0);
    memcpy(buf.data(), prop.data.data(), prop.data.size());
    c->outbuf.insert(c->outbuf.end(), buf.begin(), buf.end());

    if (delete_) st->properties.erase(it);
}

static void handle_query_extension(XServerState* st, XClient* c,
                                    const uint8_t* req, size_t len) {
    if (len < 8) return;
    uint16_t name_len = rd_be16(req + 4);
    if (len < (size_t)(8 + ((name_len + 3) & ~3u))) return;
    std::string name((const char*)(req + 8), name_len);

    // We pretend to support a few key extensions. Most return present=true
    // with a fake opcode but no-op handlers. Wine degrades gracefully.
    bool present = false;
    uint8_t major_opcode = 0;
    uint8_t first_event = 0;
    uint8_t first_error = 0;

    // Wine asks for these during init:
    //   BIG-REQUESTS, MIT-SHM, RANDR, SHAPE, RENDER, XInputExtension,
    //   XFIXES, DAMAGE, Generic Event Extension, XKB, XFree86-VidModeExtension
    if (name == "BIG-REQUESTS") {
        present = true; major_opcode = 130;
    } else if (name == "MIT-SHM") {
        present = true; major_opcode = 131; first_event = 64;
    } else if (name == "RANDR") {
        present = true; major_opcode = 132; first_event = 80;
    } else if (name == "SHAPE") {
        present = true; major_opcode = 133; first_event = 90;
    } else if (name == "RENDER") {
        present = false;  // not implemented — Wine falls back to core PutImage
    } else if (name == "XFIXES") {
        present = false;
    } else if (name == "DAMAGE") {
        present = false;
    } else if (name == "Generic Event Extension") {
        present = false;
    } else if (name == "XInputExtension") {
        present = false;
    } else if (name == "XKEYBOARD" || name == "XKB") {
        present = false;
    }

    uint8_t reply[32] = {};
    reply[0] = 1;
    wr_be16(reply + 2, c->sequence);
    reply[8] = present ? 1 : 0;
    reply[9] = major_opcode;
    reply[10] = first_event;
    reply[11] = first_error;
    c->outbuf.insert(c->outbuf.end(), reply, reply + 32);
    LOGD("QueryExtension '%s' -> present=%d opcode=%d",
         name.c_str(), present, major_opcode);
}

static void handle_list_extensions(XServerState* st, XClient* c,
                                    const uint8_t* req, size_t len) {
    // Return an empty list (we claim no extensions in the list reply,
    // even though QueryExtension returns present=true for some — Wine
    // doesn't actually require ListExtensions to match).
    uint8_t reply[32] = {};
    reply[0] = 1;
    wr_be16(reply + 2, c->sequence);
    reply[1] = 0;  // num extensions
    c->outbuf.insert(c->outbuf.end(), reply, reply + 32);
}

static void handle_get_geometry(XServerState* st, XClient* c,
                                  const uint8_t* req, size_t len) {
    if (len < 8) return;
    uint32_t did = rd_be32(req + 4);
    // Look up as window first, then pixmap
    XWindow* win = nullptr;
    auto wit = st->windows.find(did);
    if (wit != st->windows.end()) win = &wit->second;
    XPixmap* pix = nullptr;
    auto pit = st->pixmaps.find(did);
    if (pit != st->pixmaps.end()) pix = &pit->second;

    uint16_t w, h; uint8_t depth; uint32_t root;
    if (win) {
        w = win->width; h = win->height; depth = win->depth;
        root = st->root_window_id;
    } else if (pix) {
        w = pix->width; h = pix->height; depth = pix->depth;
        root = st->root_window_id;
    } else {
        // Default to root
        w = (uint16_t)st->fb.width; h = (uint16_t)st->fb.height;
        depth = 24; root = st->root_window_id;
    }

    uint8_t reply[32] = {};
    reply[0] = 1;
    wr_be16(reply + 2, c->sequence);
    wr_be32(reply + 8, root);       // root
    reply[12] = depth;
    reply[13] = 0;                  // x
    reply[14] = 0;                  // y
    wr_be16(reply + 16, w);
    wr_be16(reply + 18, h);
    wr_be16(reply + 20, 0);         // border-width
    c->outbuf.insert(c->outbuf.end(), reply, reply + 32);
}

static void handle_query_tree(XServerState* st, XClient* c,
                                const uint8_t* req, size_t len) {
    if (len < 8) return;
    uint32_t wid = rd_be32(req + 4);
    // Return: root, parent=0, no children
    uint8_t reply[32] = {};
    reply[0] = 1;
    wr_be16(reply + 2, c->sequence);
    wr_be32(reply + 4, 0);                // no children data
    wr_be32(reply + 8, st->root_window_id);  // root
    wr_be32(reply + 12, (wid == st->root_window_id) ? 0 : st->root_window_id);  // parent
    wr_be16(reply + 16, 0);               // num children
    c->outbuf.insert(c->outbuf.end(), reply, reply + 32);
}

static void handle_get_window_attributes(XServerState* st, XClient* c,
                                          const uint8_t* req, size_t len) {
    if (len < 8) return;
    uint32_t wid = rd_be32(req + 4);
    auto it = st->windows.find(wid);
    XWindow* win = (it != st->windows.end()) ? &it->second : nullptr;

    uint8_t reply[32] = {};
    reply[0] = 1;
    wr_be16(reply + 2, c->sequence);
    reply[8] = win ? win->class_ : 1;     // InputOutput
    reply[9] = win ? (win->mapped ? 1 : 0) : 0;  // IsViewable (if mapped)
    // backing-store = Never(0)
    reply[12] = 0;
    reply[13] = 0;  // save-under = false
    wr_be32(reply + 16, st->root_visual_id);
    wr_be32(reply + 20, 0);  // colormap (None for non-root, but we don't track)
    reply[24] = 0;  // all-event-masks (low byte)
    reply[25] = 0;  // all-event-masks (high byte)
    reply[26] = 0;  // your-event-mask (low)
    reply[27] = 0;  // your-event-mask (high)
    reply[28] = 0;  // do-not-propagate-mask
    reply[29] = 0;  // override-redirect
    c->outbuf.insert(c->outbuf.end(), reply, reply + 32);
}

static void handle_create_gc(XServerState* st, XClient* c,
                              const uint8_t* req, size_t len) {
    if (len < 12) return;
    uint32_t gcid = rd_be32(req + 4);
    uint32_t drawable = rd_be32(req + 8);
    uint32_t mask = rd_be32(req + 12);
    XGC gc = {};
    gc.id = gcid;
    // (drawable unused — we don't clip to it)
    const uint8_t* vals = req + 16;
    size_t avail = len - 16;
    auto take32 = [&](uint32_t bit) -> uint32_t {
        if (!(mask & bit)) return 0;
        size_t idx = __builtin_popcount(mask & (bit - 1));
        if ((idx + 1) * 4 > avail) return 0;
        return rd_be32(vals + idx * 4);
    };
    if (mask & GC_Function)     gc.function = take32(GC_Function);
    if (mask & GC_PlaneMask)    gc.plane_mask = take32(GC_PlaneMask);
    if (mask & GC_Foreground)   gc.foreground = take32(GC_Foreground);
    if (mask & GC_Background)   gc.background = take32(GC_Background);
    if (mask & GC_LineWidth)    gc.line_width = take32(GC_LineWidth);
    if (mask & GC_ClipXOrigin)  gc.clip_x_origin = (int16_t)take32(GC_ClipXOrigin);
    if (mask & GC_ClipYOrigin)  gc.clip_y_origin = (int16_t)take32(GC_ClipYOrigin);
    if (mask & GC_ClipMask)     gc.clip_mask = take32(GC_ClipMask);
    if (mask & GC_SubwindowMode) gc.subwindow_mode = take32(GC_SubwindowMode);
    if (mask & GC_GraphicsExposures) gc.graphics_exposures = take32(GC_GraphicsExposures) != 0;
    st->gcs[gcid] = gc;
}

static void handle_change_gc(XServerState* st, XClient* c,
                              const uint8_t* req, size_t len) {
    if (len < 8) return;
    uint32_t gcid = rd_be32(req + 4);
    uint32_t mask = rd_be32(req + 8);
    auto it = st->gcs.find(gcid);
    if (it == st->gcs.end()) {
        send_error(c, ERR_GContext, gcid, X_ChangeGC, 0);
        return;
    }
    const uint8_t* vals = req + 12;
    size_t avail = len - 12;
    auto take32 = [&](uint32_t bit) -> uint32_t {
        if (!(mask & bit)) return 0;
        size_t idx = __builtin_popcount(mask & (bit - 1));
        if ((idx + 1) * 4 > avail) return 0;
        return rd_be32(vals + idx * 4);
    };
    if (mask & GC_Function)     it->second.function = take32(GC_Function);
    if (mask & GC_Foreground)   it->second.foreground = take32(GC_Foreground);
    if (mask & GC_Background)   it->second.background = take32(GC_Background);
    if (mask & GC_PlaneMask)    it->second.plane_mask = take32(GC_PlaneMask);
    if (mask & GC_ClipXOrigin)  it->second.clip_x_origin = (int16_t)take32(GC_ClipXOrigin);
    if (mask & GC_ClipYOrigin)  it->second.clip_y_origin = (int16_t)take32(GC_ClipYOrigin);
}

static void handle_free_gc(XServerState* st, XClient* c,
                            const uint8_t* req, size_t len) {
    if (len < 8) return;
    uint32_t gcid = rd_be32(req + 4);
    st->gcs.erase(gcid);
}

static void handle_create_pixmap(XServerState* st, XClient* c,
                                   const uint8_t* req, size_t len) {
    if (len < 16) return;
    uint8_t  depth = req[1];
    uint32_t pid = rd_be32(req + 4);
    uint32_t drawable = rd_be32(req + 8);
    uint16_t w = rd_be16(req + 12);
    uint16_t h = rd_be16(req + 14);
    XPixmap pix = {};
    pix.id = pid;
    pix.drawable = drawable;
    pix.width = w; pix.height = h;
    pix.depth = depth;
    // Allocate backing pixels (so CopyArea from this pixmap works)
    if (w > 0 && h > 0 && w < 8192 && h < 8192) {
        pix.pixels = (uint32_t*)calloc((size_t)w * h, sizeof(uint32_t));
        pix.owns_pixels = true;
    }
    st->pixmaps[pid] = pix;
}

static void handle_free_pixmap(XServerState* st, XClient* c,
                                 const uint8_t* req, size_t len) {
    if (len < 8) return;
    uint32_t pid = rd_be32(req + 4);
    auto it = st->pixmaps.find(pid);
    if (it != st->pixmaps.end()) {
        if (it->second.owns_pixels && it->second.pixels) {
            free(it->second.pixels);
        }
        st->pixmaps.erase(it);
    }
}

static void handle_put_image(XServerState* st, XClient* c,
                              const uint8_t* req, size_t len) {
    if (len < 24) { send_error(c, ERR_Length, 0, X_PutImage, 0); return; }
    uint8_t  format = req[1];
    uint32_t drawable = rd_be32(req + 4);
    uint32_t gcid = rd_be32(req + 8);
    uint16_t w = rd_be16(req + 12);
    uint16_t h = rd_be16(req + 14);
    int16_t  dst_x = (int16_t)rd_be16(req + 16);
    int16_t  dst_y = (int16_t)rd_be16(req + 18);
    uint8_t  depth = req[21];
    (void)gcid; (void)depth;

    // The image data starts at offset 24.
    // For ZPixmap format with depth=24, each pixel is 4 bytes (we claimed 32bpp).
    // Total image bytes = w * h * 4 (for depth 24, scanline_pad=32).
    size_t row_bytes = (size_t)w * 4;
    size_t image_bytes = row_bytes * h;
    if (24 + image_bytes > len) {
        LOGW("PutImage: truncated (need %zu, have %zu)", 24 + image_bytes, len);
        return;
    }

    auto it = st->windows.find(drawable);
    if (it != st->windows.end()) {
        draw_put_zpixmap(st, &it->second, dst_x, dst_y, w, h,
                         req + 24, (int)row_bytes);
    } else {
        auto pit = st->pixmaps.find(drawable);
        if (pit != st->pixmaps.end() && pit->second.pixels) {
            // Copy into pixmap backing
            for (int y = 0; y < h; y++) {
                uint32_t* dst_row = pit->second.pixels + (size_t)y * pit->second.width;
                const uint8_t* src_row = req + 24 + (size_t)y * row_bytes;
                for (int x = 0; x < w && x < pit->second.width; x++) {
                    uint32_t b = src_row[x*4 + 0];
                    uint32_t g = src_row[x*4 + 1];
                    uint32_t r = src_row[x*4 + 2];
                    uint32_t a = src_row[x*4 + 3];
                    dst_row[x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
        }
    }
}

static void handle_copy_area(XServerState* st, XClient* c,
                              const uint8_t* req, size_t len) {
    if (len < 28) return;
    uint32_t src_drawable = rd_be32(req + 4);
    uint32_t dst_drawable = rd_be32(req + 8);
    uint32_t gcid = rd_be32(req + 12);
    int16_t  src_x = (int16_t)rd_be16(req + 16);
    int16_t  src_y = (int16_t)rd_be16(req + 18);
    int16_t  dst_x = (int16_t)rd_be16(req + 20);
    int16_t  dst_y = (int16_t)rd_be16(req + 22);
    uint16_t w = rd_be16(req + 24);
    uint16_t h = rd_be16(req + 26);
    (void)gcid;

    // Find source
    uint32_t* src_pixels = nullptr; int src_stride = 0;
    auto swit = st->windows.find(src_drawable);
    if (swit != st->windows.end() && swit->second.id == st->root_window_id) {
        src_pixels = (uint32_t*)st->fb.mapped;
        src_stride = st->fb.stride_pixels;
    } else {
        auto spit = st->pixmaps.find(src_drawable);
        if (spit != st->pixmaps.end()) {
            src_pixels = spit->second.pixels;
            src_stride = spit->second.width;
        }
    }
    // Find dest
    uint32_t* dst_pixels = nullptr; int dst_stride = 0;
    auto dwit = st->windows.find(dst_drawable);
    if (dwit != st->windows.end() && dwit->second.id == st->root_window_id) {
        dst_pixels = (uint32_t*)st->fb.mapped;
        dst_stride = st->fb.stride_pixels;
    } else {
        auto dpit = st->pixmaps.find(dst_drawable);
        if (dpit != st->pixmaps.end()) {
            dst_pixels = dpit->second.pixels;
            dst_stride = dpit->second.width;
        }
    }
    if (!src_pixels || !dst_pixels) return;

    pthread_mutex_lock(&st->fb.lock);
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            dst_pixels[(size_t)(dst_y + y) * dst_stride + (dst_x + x)] =
                src_pixels[(size_t)(src_y + y) * src_stride + (src_x + x)];
        }
    }
    pthread_mutex_unlock(&st->fb.lock);

    if (dst_drawable == st->root_window_id) {
        pthread_mutex_lock(&st->damage_lock);
        st->damaged = true;
        pthread_mutex_unlock(&st->damage_lock);
    }
}

static void handle_poly_fill_rectangle(XServerState* st, XClient* c,
                                         const uint8_t* req, size_t len) {
    if (len < 16) return;
    uint32_t drawable = rd_be32(req + 4);
    uint32_t gcid = rd_be32(req + 8);
    (void)gcid;
    auto it = st->windows.find(drawable);
    if (it == st->windows.end() || it->second.id != st->root_window_id) return;
    auto gcit = st->gcs.find(gcid);
    uint32_t fill = (gcit != st->gcs.end()) ? gcit->second.foreground : 0;

    size_t n_rects = (len - 16) / 8;
    for (size_t i = 0; i < n_rects; i++) {
        const uint8_t* r = req + 16 + i * 8;
        int16_t  x = (int16_t)rd_be16(r);
        int16_t  y = (int16_t)rd_be16(r + 2);
        uint16_t w = rd_be16(r + 4);
        uint16_t h = rd_be16(r + 6);
        draw_clear_area(st, &it->second, x, y, w, h, fill);
    }
}

static void handle_clear_area(XServerState* st, XClient* c,
                                const uint8_t* req, size_t len) {
    if (len < 16) return;
    bool exposures = req[1] != 0;
    uint32_t wid = rd_be32(req + 4);
    int16_t  x = (int16_t)rd_be16(req + 8);
    int16_t  y = (int16_t)rd_be16(req + 10);
    uint16_t w = rd_be16(req + 12);
    uint16_t h = rd_be16(req + 14);
    auto it = st->windows.find(wid);
    if (it == st->windows.end() || it->second.id != st->root_window_id) return;
    draw_clear_area(st, &it->second, x, y, w, h, 0);  // clear to background

    if (exposures && (it->second.event_mask & EM_ExposureMask)) {
        uint8_t ex[32] = {};
        ex[0] = EV_Expose;
        wr_be32(ex + 4, wid);
        wr_be16(ex + 8, (uint16_t)x);
        wr_be16(ex + 10, (uint16_t)y);
        wr_be16(ex + 12, w);
        wr_be16(ex + 14, h);
        ex[16] = 0;
        c->outbuf.insert(c->outbuf.end(), ex, ex + 32);
    }
}

static void handle_query_pointer(XServerState* st, XClient* c,
                                   const uint8_t* req, size_t len) {
    // Reply: same-root, root-x/y, win-x/y, mask=0, child=root
    uint8_t reply[32] = {};
    reply[0] = 1;
    wr_be16(reply + 2, c->sequence);
    reply[8] = 1;  // same-screen
    wr_be32(reply + 12, st->root_window_id);  // root
    wr_be32(reply + 16, st->root_window_id);  // child
    wr_be16(reply + 20, 0);  // root-x
    wr_be16(reply + 22, 0);  // root-y
    wr_be16(reply + 24, 0);  // win-x
    wr_be16(reply + 26, 0);  // win-y
    wr_be16(reply + 28, 0);  // mask
    c->outbuf.insert(c->outbuf.end(), reply, reply + 32);
}

static void handle_get_image(XServerState* st, XClient* c,
                              const uint8_t* req, size_t len) {
    if (len < 20) return;
    uint32_t drawable = rd_be32(req + 4);
    int16_t  x = (int16_t)rd_be16(req + 8);
    int16_t  y = (int16_t)rd_be16(req + 10);
    uint16_t w = rd_be16(req + 12);
    uint16_t h = rd_be16(req + 14);
    // For simplicity, return zeros (black). Wine uses this for screenshots
    // and rarely for normal rendering.
    size_t image_bytes = (size_t)w * h * 4;
    uint32_t padded = (image_bytes + 3) & ~3u;

    uint8_t reply[32] = {};
    reply[0] = 1;
    wr_be16(reply + 2, c->sequence);
    wr_be32(reply + 4, padded / 4);
    reply[8] = 24;  // depth
    wr_be32(reply + 12, st->root_visual_id);  // visual
    c->outbuf.insert(c->outbuf.end(), reply, reply + 32);

    // If querying root, return actual pixels
    if (drawable == st->root_window_id) {
        pthread_mutex_lock(&st->fb.lock);
        std::vector<uint8_t> buf(padded, 0);
        uint32_t* src = (uint32_t*)st->fb.mapped;
        int stride = st->fb.stride_pixels;
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                uint32_t p = src[(size_t)(y + yy) * stride + (x + xx)];
                buf[(size_t)yy * w * 4 + xx * 4 + 0] = p & 0xff;  // B
                buf[(size_t)yy * w * 4 + xx * 4 + 1] = (p >> 8) & 0xff;  // G
                buf[(size_t)yy * w * 4 + xx * 4 + 2] = (p >> 16) & 0xff;  // R
                buf[(size_t)yy * w * 4 + xx * 4 + 3] = (p >> 24) & 0xff;  // A
            }
        }
        pthread_mutex_unlock(&st->fb.lock);
        c->outbuf.insert(c->outbuf.end(), buf.begin(), buf.end());
    } else {
        std::vector<uint8_t> buf(padded, 0);
        c->outbuf.insert(c->outbuf.end(), buf.begin(), buf.end());
    }
}

// =====================================================================
// Request dispatcher — called for each complete request in the input
// =====================================================================

static void dispatch_request(XServerState* st, XClient* c,
                              const uint8_t* req, size_t len) {
    c->sequence++;
    if (len < 4) return;
    uint8_t op = req[0];

    switch (op) {
        case X_CreateWindow:           handle_create_window(st, c, req, len); break;
        case X_ChangeWindowAttributes: handle_change_window_attrs(st, c, req, len); break;
        case X_GetWindowAttributes:    handle_get_window_attributes(st, c, req, len); break;
        case X_DestroyWindow:          handle_destroy_window(st, c, req, len); break;
        case X_MapWindow:              handle_map_window(st, c, req, len); break;
        case X_UnmapWindow:            handle_unmap_window(st, c, req, len); break;
        case X_ConfigureWindow:        handle_configure_window(st, c, req, len); break;
        case X_GetGeometry:            handle_get_geometry(st, c, req, len); break;
        case X_QueryTree:              handle_query_tree(st, c, req, len); break;
        case X_InternAtom:             handle_intern_atom(st, c, req, len); break;
        case X_GetAtomName:            handle_get_atom_name(st, c, req, len); break;
        case X_ChangeProperty:         handle_change_property(st, c, req, len); break;
        case X_GetProperty:            handle_get_property(st, c, req, len); break;
        case X_QueryExtension:         handle_query_extension(st, c, req, len); break;
        case X_ListExtensions:         handle_list_extensions(st, c, req, len); break;
        case X_CreateGC:               handle_create_gc(st, c, req, len); break;
        case X_ChangeGC:               handle_change_gc(st, c, req, len); break;
        case X_FreeGC:                 handle_free_gc(st, c, req, len); break;
        case X_CreatePixmap:           handle_create_pixmap(st, c, req, len); break;
        case X_FreePixmap:             handle_free_pixmap(st, c, req, len); break;
        case X_PutImage:               handle_put_image(st, c, req, len); break;
        case X_CopyArea:               handle_copy_area(st, c, req, len); break;
        case X_PolyFillRectangle:      handle_poly_fill_rectangle(st, c, req, len); break;
        // NOTE: X_CopyArea handled above in the real-dispatch section. The
        // duplicate entry below in the no-op section was removed.
        case X_ClearArea:              handle_clear_area(st, c, req, len); break;
        case X_QueryPointer:           handle_query_pointer(st, c, req, len); break;
        case X_GetImage:               handle_get_image(st, c, req, len); break;

        // Requests we silently accept and ignore:
        case X_GrabServer:
        case X_UngrabServer:
        case X_ChangeSaveSet:
        case X_ReparentWindow:
        case X_MapSubwindows:
        case X_UnmapSubwindows:
        case X_DestroySubwindows:
        case X_CirculateWindow:
        case X_DeleteProperty:
        case X_ListProperties:
        case X_SetSelectionOwner:
        case X_GetSelectionOwner:
        case X_ConvertSelection:
        case X_SendEvent:
        case X_GrabPointer:
        case X_UngrabPointer:
        case X_GrabButton:
        case X_UngrabButton:
        case X_ChangeActivePointerGrab:
        case X_GrabKeyboard:
        case X_UngrabKeyboard:
        case X_GrabKey:
        case X_UngrabKey:
        case X_AllowEvents:
        case X_GetMotionEvents:
        case X_TranslateCoordinates:
        case X_WarpPointer:
        case X_SetInputFocus:
        case X_ChangeKeyboardMapping:
        case X_ChangeKeyboardControl:
        case X_Bell:
        case X_ChangePointerControl:
        case X_SetScreenSaver:
        case X_ChangeHosts:
        case X_SetAccessControl:
        case X_SetCloseDownMode:
        case X_KillClient:
        case X_RotateProperties:
        case X_ForceScreenSaver:
        case X_SetPointerMapping:
        case X_SetModifierMapping:
        case X_CopyPlane:
        case X_PolyPoint:
        case X_PolyLine:
        case X_PolySegment:
        case X_PolyRectangle:
        case X_PolyArc:
        case X_FillPoly:
        case X_PolyFillArc:
        case X_PolyText8:
        case X_PolyText16:
        case X_ImageText8:
        case X_ImageText16:
        case X_CreateColormap:
        case X_FreeColormap:
        case X_CopyColormapAndFree:
        case X_InstallColormap:
        case X_UninstallColormap:
        case X_ListInstalledColormaps:
        case X_AllocColor:
        case X_AllocNamedColor:
        case X_AllocColorCells:
        case X_AllocColorPlanes:
        case X_FreeColors:
        case X_StoreColors:
        case X_StoreNamedColor:
        case X_QueryColors:
        case X_LookupColor:
        case X_CreateCursor:
        case X_CreateGlyphCursor:
        case X_FreeCursor:
        case X_RecolorCursor:
        case X_NoOperation:
            break;

        // Requests that expect a reply but we don't fully implement —
        // send a minimal no-op reply so the client doesn't hang:
        case X_GetInputFocus: {
            uint8_t reply[32] = {};
            reply[0] = 1;
            wr_be16(reply + 2, c->sequence);
            reply[1] = 1;  // revert-to = PointerRoot
            wr_be32(reply + 4, st->focus_window);
            c->outbuf.insert(c->outbuf.end(), reply, reply + 32);
            break;
        }
        case X_QueryKeymap:        send_noop_reply(c); break;
        case X_GetKeyboardMapping: send_noop_reply(c); break;
        case X_GetKeyboardControl: send_noop_reply(c); break;
        case X_GetPointerControl:  send_noop_reply(c); break;
        case X_GetScreenSaver:     send_noop_reply(c); break;
        case X_ListHosts:          send_noop_reply(c); break;
        case X_GetPointerMapping:  send_noop_reply(c); break;
        case X_GetModifierMapping: send_noop_reply(c); break;
        case X_QueryBestSize: {
            // Reply with width=height=1 (so Wine doesn't pick a 0x0 cursor)
            uint8_t reply[32] = {};
            reply[0] = 1;
            wr_be16(reply + 2, c->sequence);
            wr_be16(reply + 8, 1);
            wr_be16(reply + 10, 1);
            c->outbuf.insert(c->outbuf.end(), reply, reply + 32);
            break;
        }
        case X_OpenFont:
        case X_CloseFont:
            break;  // ignore — we don't render text
        case X_QueryFont:        send_noop_reply(c); break;
        case X_QueryTextExtents: send_noop_reply(c); break;
        case X_ListFonts:        send_noop_reply(c); break;
        case X_ListFontsWithInfo: send_noop_reply(c); break;
        case X_GetFontPath:      send_noop_reply(c); break;
        case X_SetFontPath:      break;

        default:
            LOGD("Unhandled request opcode=%d len=%zu", op, len);
            // Don't send an error — Wine sometimes probes extensions and
            // getting an error for an unhandled extension request would
            // confuse it. Just no-op.
            break;
    }
}

// =====================================================================
// Client handler thread
// =====================================================================

static void* client_thread(void* arg) {
    struct ClientArg { XServerState* st; XClient* client; };
    ClientArg* ca = (ClientArg*)arg;
    XServerState* st = ca->st;
    XClient* client = ca->client;
    delete ca;

    LOGI("Client connected fd=%d", client->fd);

    // Step 1: connection setup
    if (!do_connection_setup(st, client)) {
        close(client->fd);
        delete client;
        return nullptr;
    }

    // Step 2: request loop
    while (st->running) {
        // Read more bytes
        uint8_t buf[16384];
        ssize_t got = read(client->fd, buf, sizeof(buf));
        if (got <= 0) {
            LOGI("Client fd=%d disconnected (got=%zd errno=%d)",
                 client->fd, got, errno);
            break;
        }
        client->inbuf.insert(client->inbuf.end(), buf, buf + got);

        // Process complete requests
        while (client->inbuf.size() >= 4) {
            const uint8_t* p = client->inbuf.data();
            uint16_t req_len_units = rd_be16(p + 2);
            // BIG-REQUESTS: if req_len_units == 0, the actual length is in
            // bytes 4-7 as a 4-byte BE int.
            size_t req_len_bytes;
            if (req_len_units == 0) {
                if (client->inbuf.size() < 8) break;
                req_len_bytes = (size_t)rd_be32(p + 4) * 4;
                if (req_len_bytes < 8) {
                    LOGW("Bogus BIG-REQUESTS length %zu", req_len_bytes);
                    client->inbuf.clear();
                    break;
                }
            } else {
                req_len_bytes = (size_t)req_len_units * 4;
            }

            if (client->inbuf.size() < req_len_bytes) break;  // wait for more

            dispatch_request(st, client, p, req_len_bytes);

            // Consume
            client->inbuf.erase(client->inbuf.begin(),
                                client->inbuf.begin() + req_len_bytes);
        }

        // Flush any pending output
        flush_output(client);

        // If we damaged the framebuffer, send it to the bridge
        bool damaged = false;
        pthread_mutex_lock(&st->damage_lock);
        damaged = st->damaged;
        st->damaged = false;
        pthread_mutex_unlock(&st->damage_lock);
        if (damaged) {
            bridge_send_dmabuf(st);
        }
    }

    close(client->fd);
    pthread_mutex_lock(&st->clients_lock);
    for (auto it = st->clients.begin(); it != st->clients.end(); ++it) {
        if (*it == client) { st->clients.erase(it); break; }
    }
    pthread_mutex_unlock(&st->clients_lock);
    delete client;
    return nullptr;
}

// =====================================================================
// Listen socket (Unix domain)
// =====================================================================

static bool listen_init(XServerState* st, const std::string& display) {
    // display = ":0" → socket path = /tmp/.X11-unix/X0
    int disp_num = 0;
    if (!display.empty() && display[0] == ':') {
        disp_num = atoi(display.c_str() + 1);
    }
    char sock_path[64];
    snprintf(sock_path, sizeof(sock_path), "/tmp/.X11-unix/X%d", disp_num);

    // Remove stale socket
    unlink(sock_path);
    // Make sure directory exists
    mkdir("/tmp/.X11-unix", 0777);

    int s = socket(AF_UNIX, SOCK_STREAM, 0);
    if (s < 0) {
        LOGE("listen socket() failed: %s", strerror(errno));
        return false;
    }
    struct sockaddr_un addr = {};
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, sock_path, sizeof(addr.sun_path) - 1);

    if (bind(s, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("listen bind(%s) failed: %s", sock_path, strerror(errno));
        close(s);
        return false;
    }
    if (listen(s, 4) < 0) {
        LOGE("listen() failed: %s", strerror(errno));
        close(s);
        return false;
    }
    chmod(sock_path, 0777);
    st->listen_sock = s;
    LOGI("X server listening on %s (fd=%d)", sock_path, s);

    // Also set DISPLAY env for child processes (Wine)
    setenv("DISPLAY", display.c_str(), 1);
    return true;
}

static void* accept_thread_fn(void* arg) {
    XServerState* st = (XServerState*)arg;
    while (st->running) {
        int cfd = accept(st->listen_sock, nullptr, nullptr);
        if (cfd < 0) {
            if (errno == EINTR) continue;
            if (!st->running) break;
            LOGW("accept failed: %s", strerror(errno));
            continue;
        }
        XClient* client = new XClient();
        client->fd = cfd;
        client->sequence = 0;
        client->authenticated = false;

        pthread_mutex_lock(&st->clients_lock);
        st->clients.push_back(client);
        pthread_mutex_unlock(&st->clients_lock);

        struct ClientArg { XServerState* st; XClient* client; };
        ClientArg* ca = new ClientArg{st, client};
        pthread_t th;
        pthread_create(&th, nullptr, client_thread, ca);
        pthread_detach(th);
    }
    return nullptr;
}

// =====================================================================
// Public API — called from JNI
// =====================================================================

static XServerState g_state;

// Public API — called from JNI. These have C linkage so JNI can find
// them via name mangling, but they live inside our namespace so they
// can see g_state and the helper functions.
extern "C" {

// Start the X server. Called from Java.
//   width, height: framebuffer size
//   display: ":0" or ":1" etc.
//   bridge_socket: abstract socket name (e.g. "waylandie.display.bridge.v1")
// Returns 0 on success, -1 on failure.
int waylandie_xserver_start(int width, int height,
                              const char* display,
                              const char* bridge_socket) {
    if (g_state.running) {
        LOGW("X server already running");
        return 0;
    }
    if (!fb_init(&g_state.fb, width, height)) {
        return -1;
    }
    if (!listen_init(&g_state, display ? display : ":0")) {
        return -1;
    }
    if (bridge_socket && bridge_socket[0]) {
        bridge_connect(&g_state, bridge_socket);
        // Don't fail if bridge isn't up yet — Wine can still use X without
        // display. We'll reconnect lazily.
    }

    g_state.running = true;
    pthread_create(&g_state.accept_thread, nullptr, accept_thread_fn, &g_state);
    LOGI("X server started: %dx%d display=%s bridge=%s",
         width, height, display ? display : ":0",
         bridge_socket ? bridge_socket : "(none)");
    return 0;
}

// Stop the X server.
void waylandie_xserver_stop() {
    if (!g_state.running) return;
    g_state.running = false;
    if (g_state.listen_sock >= 0) {
        close(g_state.listen_sock);
        g_state.listen_sock = -1;
    }
    if (g_state.bridge_sock >= 0) {
        close(g_state.bridge_sock);
        g_state.bridge_sock = -1;
    }
    pthread_join(g_state.accept_thread, nullptr);

    pthread_mutex_lock(&g_state.clients_lock);
    for (auto* c : g_state.clients) {
        if (c->fd >= 0) close(c->fd);
        delete c;
    }
    g_state.clients.clear();
    pthread_mutex_unlock(&g_state.clients_lock);

    // Release framebuffer
    if (g_state.fb.mapped) {
        AHardwareBuffer_unlock(g_state.fb.ahb, nullptr);
        g_state.fb.mapped = nullptr;
    }
    if (g_state.fb.ahb) {
        AHardwareBuffer_release(g_state.fb.ahb);
        g_state.fb.ahb = nullptr;
    }
    if (g_state.fb.dmabuf_fd >= 0) {
        close(g_state.fb.dmabuf_fd);
        g_state.fb.dmabuf_fd = -1;
    }
    LOGI("X server stopped");
}

// Inject a mouse event from Android input.
//   x, y: coordinates in framebuffer space
//   button: 1=left, 2=middle, 3=right, 0=motion only
//   is_down: true for press, false for release (ignored for motion)
void waylandie_xserver_send_mouse(int x, int y, int button, bool is_down) {
    XServerState* st = &g_state;
    pthread_mutex_lock(&st->clients_lock);
    for (auto* c : st->clients) {
        uint8_t ev[32] = {};
        if (button == 0) {
            ev[0] = EV_MotionNotify;
        } else {
            ev[0] = is_down ? EV_ButtonPress : EV_ButtonRelease;
            ev[1] = (uint8_t)button;
        }
        wr_be32(ev + 4, st->root_window_id);  // event window
        wr_be32(ev + 8, st->root_window_id);  // child
        wr_be16(ev + 12, (uint16_t)x);  // root-x
        wr_be16(ev + 14, (uint16_t)y);  // root-y
        wr_be16(ev + 16, (uint16_t)x);  // event-x
        wr_be16(ev + 18, (uint16_t)y);  // event-y
        wr_be16(ev + 20, 0);  // state (no modifiers)
        ev[22] = 1;  // same-screen
        c->outbuf.insert(c->outbuf.end(), ev, ev + 32);
        flush_output(c);
    }
    pthread_mutex_unlock(&st->clients_lock);
}

// Inject a keyboard event.
//   keycode: X11 keycode (8-255). We use the Linux/Android keycode + 8.
//   is_down: true for press, false for release
void waylandie_xserver_send_key(int keycode, bool is_down) {
    XServerState* st = &g_state;
    pthread_mutex_lock(&st->clients_lock);
    for (auto* c : st->clients) {
        uint8_t ev[32] = {};
        ev[0] = is_down ? EV_KeyPress : EV_KeyRelease;
        ev[1] = 0;  // detail (same-screen)
        wr_be32(ev + 4, st->root_window_id);
        wr_be32(ev + 8, st->root_window_id);
        wr_be16(ev + 12, 0);  // root-x
        wr_be16(ev + 14, 0);  // root-y
        wr_be16(ev + 16, 0);  // event-x
        wr_be16(ev + 18, 0);  // event-y
        wr_be16(ev + 20, 0);  // state
        ev[22] = 1;  // same-screen
        ev[23] = (uint8_t)keycode;
        c->outbuf.insert(c->outbuf.end(), ev, ev + 32);
        flush_output(c);
    }
    pthread_mutex_unlock(&st->clients_lock);
}

}  // extern "C"

}  // namespace waylandie_x11
