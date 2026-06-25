#define _GNU_SOURCE
#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stddef.h>
#include <stdint.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>
#include <wayland-server-core.h>
#include <wayland-server-protocol.h>
/* ----------------------------------------------------------------- */
/* X11 / XTest input injection is OPTIONAL.                          */
/*                                                                  */
/* When WAYLANDIE_NO_XTEST is defined (e.g. bionic builds that have */
/* no X11 libraries), the bridge compiles WITHOUT X11. All xtest_*  */
/* functions become no-op stubs and the struct fields become opaque */
/* pointers (never dereferenced). The Wayland compositor + dmabuf   */
/* pipeline is unchanged.                                           */
/* ----------------------------------------------------------------- */
#ifndef WAYLANDIE_NO_XTEST
#include <X11/Xlib.h>
#include <X11/Xatom.h>
#include <X11/keysym.h>
#include <X11/extensions/XTest.h>
#else
/* Stub X11 types — never dereferenced, only stored/compared. */
typedef struct _XDisplay Display;
typedef unsigned long Window;
typedef unsigned long Atom;
typedef unsigned long KeySym;
typedef int Bool;
typedef unsigned long XID;
typedef struct {
    int type;
    unsigned long serial;
    int send_event;
    int pad[6];
} XEvent;
/* Stub X11 constants. */
#define None 0L
#define NoSymbol 0L
#define Success 0
#define False 0
#define True 1
#define CurrentTime 0L
#define PropertyChangeMask 0L
#define AnyPropertyType 0L
#define SelectionNotify 0
#define XA_STRING 1L
/* Stub X11 keysyms — values irrelevant; xtest_key_sym() is a no-op stub. */
#define XK_BackSpace 0xFF08
#define XK_Tab 0xFF09
#define XK_Return 0xFF0D
#define XK_Escape 0xFF1B
#define XK_Delete 0xFFFF
#define XK_Home 0xFF50
#define XK_Left 0xFF51
#define XK_Up 0xFF52
#define XK_Right 0xFF53
#define XK_Down 0xFF54
#define XK_Shift_L 0xFFE1
#define XK_Shift_R 0xFFE2
#define XK_Control_L 0xFFE3
#define XK_Control_R 0xFFE4
#define XK_space 0x020
#define XK_0 0x030
#define XK_1 0x031
#define XK_2 0x032
#define XK_3 0x033
#define XK_4 0x034
#define XK_5 0x035
#define XK_6 0x036
#define XK_7 0x037
#define XK_8 0x038
#define XK_9 0x039
#define XK_a 0x061
#define XK_b 0x062
#define XK_c 0x063
#define XK_d 0x064
#define XK_e 0x065
#define XK_f 0x066
#define XK_g 0x067
#define XK_h 0x068
#define XK_i 0x069
#define XK_j 0x06a
#define XK_k 0x06b
#define XK_l 0x06c
#define XK_m 0x06d
#define XK_n 0x06e
#define XK_o 0x06f
#define XK_p 0x070
#define XK_q 0x071
#define XK_r 0x072
#define XK_s 0x073
#define XK_t 0x074
#define XK_u 0x075
#define XK_v 0x076
#define XK_w 0x077
#define XK_x 0x078
#define XK_y 0x079
#define XK_z 0x07a
#define XK_minus 0x02d
#define XK_equal 0x03d
#define XK_bracketleft 0x05b
#define XK_bracketright 0x05d
#define XK_backslash 0x05c
#define XK_semicolon 0x03b
#define XK_apostrophe 0x027
#define XK_comma 0x02c
#define XK_period 0x02e
#define XK_slash 0x02f
#define XK_grave 0x060
#endif /* WAYLANDIE_NO_XTEST */
#include "xdg-shell-server-protocol.h"
#include "linux-dmabuf-unstable-v1-server-protocol.h"
#include "presentation-time-server-protocol.h"
#include "viewporter-server-protocol.h"
#include "relative-pointer-unstable-v1-server-protocol.h"
#include "pointer-constraints-unstable-v1-server-protocol.h"

// ----------------------------------------------------------------------
// AHardwareBuffer support (bionic bridge only)
//
// When WAYLANDIE_HAS_AHARDWAREBUFFER is defined (set by CMake for the
// bionic bridge build), we can convert SHM buffers (from Wine's
// explorer.exe / desktop) to AHardwareBuffers, export their dmabuf fd,
// and present them via the SAME zero-copy dmabuf path that game frames
// use. This makes the Wine desktop visible.
//
// The cost is ONE CPU memcpy per desktop frame (XRGB8888 [B,G,R,X] →
// R8G8B8A8 [R,G,B,A] with byte swap). This is negligible for desktop
// UI (low FPS, mostly static). Game frames still use true zero-copy
// dmabuf — no memcpy.
// ----------------------------------------------------------------------
#ifdef WAYLANDIE_HAS_AHARDWAREBUFFER
#include <android/hardware_buffer.h>

// AHardwareBuffer_getNativeHandle is a platform API (in libandroid.so
// since API 26) but NOT exposed via NDK <android/hardware_buffer.h>.
// Forward-declare it. Layout of native_handle_t is stable.
struct waylandie_native_handle {
    int version;
    int numFds;
    int numInts;
    int data[];
};
extern const struct waylandie_native_handle* AHardwareBuffer_getNativeHandle(
        const AHardwareBuffer* buffer);
#endif

#define RESPONSE_PREFIX "waylandie-bridge dmabuf-present "
#define DEFAULT_ANDROID_VK_DRIVER "vulkan.waylandie.a8xx.so"
#define MAX_FDS 16
#define MAX_DMABUF_PLANES 4
#define BUFFER_KIND_SHM 1
#define BUFFER_KIND_DMABUF 2
#define DRM_FORMAT_XRGB8888 0x34325258U
#define DRM_FORMAT_ARGB8888 0x34325241U
#define DRM_FORMAT_XBGR8888 0x34324258U
#define DRM_FORMAT_ABGR8888 0x34324241U
#define DRM_FORMAT_MOD_LINEAR 0ULL
#define DRM_FORMAT_MOD_QCOM_COMPRESSED 0x0500000000000001ULL
#define WAYLANDIE_BTN_LEFT 0x110U
#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

struct server_state {
    struct wl_display *display;
    const char *bridge_socket_name;
    int bridge_sock;
    int bridge_frames_on_socket;
    int bridge_reconnect_frames;
    int pass_log_interval;
    int target_commits;
    int output_width;
    int output_height;
    int commit_count;
    int present_failures;
    int abort_requested;
    int clear_ahb_outside;
    int accept_client_complete;
    int client_seen;
    int completed_after_client_exit;
    int android_windows;
    int next_window_id;
    double total_present_ms;
    double total_app_wait_us;
    double total_app_slot_wait_us;
    int app_wait_samples;
    int app_slot_wait_samples;
    uint32_t input_serial;
    int input_sock;
    struct wl_event_source *input_source;
    char input_buffer[8192];
    size_t input_buffer_len;
    double pointer_x;
    double pointer_y;
    int focused_surface_width;
    int focused_surface_height;
    struct wl_list keyboard_resources;
    struct wl_list pointer_resources;
    struct wl_list touch_resources;
    struct wl_resource *focused_surface;
    struct wl_client *focused_client; /* survives surface destruction */
    /* Cursor tracking — Wine calls wl_pointer_set_cursor to set the
     * cursor surface + hotspot. We track the surface so we can extract
     * its buffer when it commits, and send the cursor image to Java. */
    struct wl_resource *cursor_surface;
    int32_t cursor_hotspot_x;
    int32_t cursor_hotspot_y;
    int cursor_visible;
    Display *xtest_display;
    Window xtest_window;
    Atom xtest_utf8_atom;
    Atom xtest_text_atom;
    Atom xtest_clipboard_atom;
    Atom xtest_primary_atom;
    Atom xtest_clipboard_property_atom;
    int xtest_enabled;
    int xtest_width;
    int xtest_height;
    double frame_interval_ms;
    double next_frame_callback_ms;
    uint32_t presentation_refresh_nsec;
    int last_frame_callback_commit;
    int accept_scaled_primary;
};

// Global monotonic ID counters for diagnostic logging. Each pool and buffer
// gets a unique ID so we can track their lifecycle in the trace file.
static uint64_t g_pool_id_counter = 1;
static uint64_t g_buffer_id_counter = 1;

// Global diagnostic counters — dumped at bridge exit for health summary.
static struct {
    uint64_t pools_created;
    uint64_t pools_destroyed;
    uint64_t buffers_created;
    uint64_t buffers_destroyed;
    uint64_t ahb_allocated;
    uint64_t ahb_released;
    uint64_t dmabuf_fds_exported;
    uint64_t dmabuf_fds_closed;
    uint64_t surfaces_committed;
    uint64_t surfaces_presented_ok;
    uint64_t surfaces_presented_fail;
    uint64_t shm_to_ahb_calls;
    uint64_t shm_to_ahb_failures;
    uint64_t bridge_reconnects;
    uint64_t use_after_free_detected;
} g_diag;

struct shm_pool_state {
    void *data;
    int32_t size;
    int fd;  // Original memfd/anonymous fd from wl_shm.create_pool.
             // Kept open so shm_pool_resize can mremap (if needed) and
             // closed when the pool is truly freed (refcount=0).
    int refcount;  // Number of buffers + 1 (for the pool resource itself)
                   // referencing this pool. The pool's memory (mmap'd data)
                   // is only munmap'd and the struct only freed when refcount
                   // reaches 0. This fixes a use-after-free where Wine destroys
                   // the pool resource BEFORE the bridge finishes using the
                   // buffer's pixel data (shm_to_ahb reads pool->data after
                   // the pool was already munmap'd and freed).
    uint64_t id;  // Monotonically increasing pool ID for diagnostic logging
};

struct shm_buffer_state {
    int kind;
    struct wl_resource *resource;
    struct shm_pool_state *pool;
    int32_t offset;
    int32_t width;
    int32_t height;
    int32_t stride;
    uint32_t format;
    uint32_t flags;
    uint64_t modifier;
    int dmabuf_fd;
    uint64_t id;  // Monotonically increasing buffer ID for diagnostic logging
};

struct dmabuf_params_state {
    int used;
    int fds[MAX_DMABUF_PLANES];
    uint32_t has_plane[MAX_DMABUF_PLANES];
    uint32_t offsets[MAX_DMABUF_PLANES];
    uint32_t strides[MAX_DMABUF_PLANES];
    uint64_t modifiers[MAX_DMABUF_PLANES];
};

struct dmabuf_feedback_state {
    int table_fd;
};

struct surface_state {
    struct server_state *server;
    struct wl_resource *resource;
    struct shm_buffer_state *pending_buffer;
    int32_t current_width;
    int32_t current_height;
    int has_pending_attach;
    int commit_count;
    int is_xdg_surface;
    int is_subsurface;
    struct surface_state *subsurface_parent;
    struct wl_list subsurface_children;
    struct wl_list subsurface_link;
    int subsurface_linked;
    int android_window_sent;
    char android_window_id[64];
    char title[128];
    char app_id[128];
    struct wl_list frame_callbacks;
    struct wl_list presentation_feedbacks;
};

struct frame_callback_state {
    struct wl_resource *resource;
    struct wl_list link;
};

struct presentation_feedback_state {
    struct wl_resource *resource;
    struct wl_list link;
};

struct input_resource_state {
    struct server_state *server;
    struct wl_resource *resource;
    struct wl_list link;
};

static void input_debug_log(const char *fmt, ...) {
    static int enabled = -1;
    if (enabled < 0) {
        const char *debug = getenv("WAYLANDIE_WAYLAND_INPUT_DEBUG");
        enabled = debug != NULL && strcmp(debug, "1") == 0;
    }
    if (!enabled) {
        return;
    }
    const char *path = getenv("WAYLANDIE_WAYLAND_INPUT_LOG");
    if (path == NULL || path[0] == '\0') {
        path = "/tmp/waylandie-wayland-input.log";
    }
    FILE *file = fopen(path, "a");
    if (file == NULL) {
        return;
    }
    va_list args;
    va_start(args, fmt);
    vfprintf(file, fmt, args);
    va_end(args);
    fputc('\n', file);
    fclose(file);
}

static int input_resource_count(struct wl_list *resources) {
    int count = 0;
    struct input_resource_state *input;
    wl_list_for_each(input, resources, link) {
        count++;
    }
    return count;
}

static void maybe_send_pointer_frame(struct wl_resource *resource);

#ifndef WAYLANDIE_NO_XTEST
static int xtest_input_enabled(void) {
    const char *enabled = getenv("WAYLANDIE_STEAM_XTEST_INPUT");
    return enabled != NULL && strcmp(enabled, "1") == 0;
}
#else
static int xtest_input_enabled(void) { return 0; }
#endif


#ifndef WAYLANDIE_NO_XTEST
static int xtest_ensure_window_and_atoms(struct server_state *state) {
    if (state->xtest_display == NULL) {
        return 0;
    }
    if (state->xtest_window != None) {
        return 1;
    }
    int screen = DefaultScreen(state->xtest_display);
    Window root = RootWindow(state->xtest_display, screen);
    state->xtest_window = XCreateSimpleWindow(
            state->xtest_display,
            root,
            0,
            0,
            1,
            1,
            0,
            0,
            0);
    if (state->xtest_window == None) {
        input_debug_log("xtest-window fail");
        return 0;
    }
    XSelectInput(state->xtest_display, state->xtest_window, PropertyChangeMask);
    state->xtest_utf8_atom = XInternAtom(state->xtest_display, "UTF8_STRING", False);
    state->xtest_text_atom = XInternAtom(state->xtest_display, "TEXT", False);
    state->xtest_clipboard_atom = XInternAtom(state->xtest_display, "CLIPBOARD", False);
    state->xtest_primary_atom = XInternAtom(state->xtest_display, "PRIMARY", False);
    state->xtest_clipboard_property_atom =
            XInternAtom(state->xtest_display, "WAYLANDIE_ANDROID_CLIPBOARD", False);
    XFlush(state->xtest_display);
    return 1;
}
#else
static int xtest_ensure_window_and_atoms(struct server_state *state) { return 0; }
#endif


#ifndef WAYLANDIE_NO_XTEST
static int xtest_ensure_display(struct server_state *state) {
    if (!state->xtest_enabled) {
        return 0;
    }
    if (state->xtest_display != NULL) {
        return xtest_ensure_window_and_atoms(state);
    }
    const char *display_name = getenv("WAYLANDIE_STEAM_XTEST_DISPLAY");
    if (display_name == NULL || display_name[0] == '\0') {
        display_name = ":0";
    }
    Display *display = XOpenDisplay(display_name);
    if (display == NULL) {
        input_debug_log("xtest-open fail display=%s", display_name);
        return 0;
    }
    int event_base = 0;
    int error_base = 0;
    int major = 0;
    int minor = 0;
    if (!XTestQueryExtension(display, &event_base, &error_base, &major, &minor)) {
        input_debug_log("xtest-open fail reason=no-extension display=%s", display_name);
        XCloseDisplay(display);
        return 0;
    }
    int screen = DefaultScreen(display);
    state->xtest_display = display;
    state->xtest_width = DisplayWidth(display, screen);
    state->xtest_height = DisplayHeight(display, screen);
    if (!xtest_ensure_window_and_atoms(state)) {
        XCloseDisplay(display);
        state->xtest_display = NULL;
        return 0;
    }
    input_debug_log(
            "xtest-open pass display=%s size=%dx%d version=%d.%d",
            display_name,
            state->xtest_width,
            state->xtest_height,
            major,
            minor);
    return 1;
}
#else
static int xtest_ensure_display(struct server_state *state) { return 0; }
#endif


static int xtest_clamp_coord(double value, int limit) {
    if (limit <= 0) {
        return 0;
    }
    if (value < 0.0) {
        return 0;
    }
    if (value > (double)(limit - 1)) {
        return limit - 1;
    }
    return (int)(value + 0.5);
}

#ifndef WAYLANDIE_NO_XTEST
static void xtest_pointer_move(struct server_state *state, double x, double y) {
    if (!xtest_ensure_display(state)) {
        return;
    }
    int xi = xtest_clamp_coord(x, state->xtest_width);
    int yi = xtest_clamp_coord(y, state->xtest_height);
    XTestFakeMotionEvent(state->xtest_display, -1, xi, yi, CurrentTime);
    XFlush(state->xtest_display);
    input_debug_log("xtest-motion x=%d y=%d", xi, yi);
}
#else
static void xtest_pointer_move(struct server_state *state, double x, double y) {  }
#endif


#ifndef WAYLANDIE_NO_XTEST
static void xtest_pointer_button(struct server_state *state, const char *button_state) {
    if (!xtest_ensure_display(state)) {
        return;
    }
    Bool is_press = strcmp(button_state, "down") == 0 ? True : False;
    XTestFakeButtonEvent(state->xtest_display, 1, is_press, CurrentTime);
    XFlush(state->xtest_display);
    input_debug_log("xtest-button state=%s", button_state);
}
#else
static void xtest_pointer_button(struct server_state *state, const char *button_state) {  }
#endif


#ifndef WAYLANDIE_NO_XTEST
static void xtest_key_sym(struct server_state *state, KeySym keysym, int press) {
    if (!xtest_ensure_display(state) || keysym == NoSymbol) {
        return;
    }
    KeyCode keycode = XKeysymToKeycode(state->xtest_display, keysym);
    if (keycode == 0) {
        input_debug_log("xtest-key drop=no-keycode keysym=0x%lx", (unsigned long)keysym);
        return;
    }
    XTestFakeKeyEvent(state->xtest_display, keycode, press ? True : False, CurrentTime);
}
#else
static void xtest_key_sym(struct server_state *state, KeySym keysym, int press) {  }
#endif


#ifndef WAYLANDIE_NO_XTEST
static void xtest_tap_key_sym(struct server_state *state, KeySym keysym) {
    xtest_key_sym(state, keysym, 1);
    xtest_key_sym(state, keysym, 0);
}
#else
static void xtest_tap_key_sym(struct server_state *state, KeySym keysym) {  }
#endif


static int ascii_to_keysym(unsigned char ch, KeySym *keysym, int *shift) {
    *shift = 0;
    if (ch >= 'a' && ch <= 'z') {
        *keysym = (KeySym)(XK_a + (ch - 'a'));
        return 1;
    }
    if (ch >= 'A' && ch <= 'Z') {
        *keysym = (KeySym)(XK_a + (ch - 'A'));
        *shift = 1;
        return 1;
    }
    if (ch >= '0' && ch <= '9') {
        *keysym = (KeySym)(XK_0 + (ch - '0'));
        return 1;
    }
    switch (ch) {
        case ' ': *keysym = XK_space; return 1;
        case '\n': case '\r': *keysym = XK_Return; return 1;
        case '\t': *keysym = XK_Tab; return 1;
        case '-': *keysym = XK_minus; return 1;
        case '_': *keysym = XK_minus; *shift = 1; return 1;
        case '=': *keysym = XK_equal; return 1;
        case '+': *keysym = XK_equal; *shift = 1; return 1;
        case '[': *keysym = XK_bracketleft; return 1;
        case '{': *keysym = XK_bracketleft; *shift = 1; return 1;
        case ']': *keysym = XK_bracketright; return 1;
        case '}': *keysym = XK_bracketright; *shift = 1; return 1;
        case '\\': *keysym = XK_backslash; return 1;
        case '|': *keysym = XK_backslash; *shift = 1; return 1;
        case ';': *keysym = XK_semicolon; return 1;
        case ':': *keysym = XK_semicolon; *shift = 1; return 1;
        case '\'': *keysym = XK_apostrophe; return 1;
        case '"': *keysym = XK_apostrophe; *shift = 1; return 1;
        case ',': *keysym = XK_comma; return 1;
        case '<': *keysym = XK_comma; *shift = 1; return 1;
        case '.': *keysym = XK_period; return 1;
        case '>': *keysym = XK_period; *shift = 1; return 1;
        case '/': *keysym = XK_slash; return 1;
        case '?': *keysym = XK_slash; *shift = 1; return 1;
        case '`': *keysym = XK_grave; return 1;
        case '~': *keysym = XK_grave; *shift = 1; return 1;
        case '!': *keysym = XK_1; *shift = 1; return 1;
        case '@': *keysym = XK_2; *shift = 1; return 1;
        case '#': *keysym = XK_3; *shift = 1; return 1;
        case '$': *keysym = XK_4; *shift = 1; return 1;
        case '%': *keysym = XK_5; *shift = 1; return 1;
        case '^': *keysym = XK_6; *shift = 1; return 1;
        case '&': *keysym = XK_7; *shift = 1; return 1;
        case '*': *keysym = XK_8; *shift = 1; return 1;
        case '(': *keysym = XK_9; *shift = 1; return 1;
        case ')': *keysym = XK_0; *shift = 1; return 1;
        default: return 0;
    }
}

#ifndef WAYLANDIE_NO_XTEST
static void xtest_type_ascii(struct server_state *state, unsigned char ch) {
    KeySym keysym = NoSymbol;
    int shift = 0;
    if (!ascii_to_keysym(ch, &keysym, &shift)) {
        input_debug_log("xtest-text drop=unsupported byte=0x%02x", ch);
        return;
    }
    if (shift) {
        xtest_key_sym(state, XK_Shift_L, 1);
    }
    xtest_tap_key_sym(state, keysym);
    if (shift) {
        xtest_key_sym(state, XK_Shift_L, 0);
    }
}
#else
static void xtest_type_ascii(struct server_state *state, unsigned char ch) {  }
#endif


static int hex_value(char c) {
    if (c >= '0' && c <= '9') {
        return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
        return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'F') {
        return c - 'A' + 10;
    }
    return -1;
}

#ifndef WAYLANDIE_NO_XTEST
static void xtest_type_text_hex(struct server_state *state, const char *hex) {
    if (hex == NULL || !xtest_ensure_display(state)) {
        return;
    }
    int count = 0;
    for (size_t i = 0; hex[i] != '\0' && hex[i + 1] != '\0'; i += 2) {
        int high = hex_value(hex[i]);
        int low = hex_value(hex[i + 1]);
        if (high < 0 || low < 0) {
        }
        unsigned char byte = (unsigned char)((high << 4) | low);
        if (byte < 0x80U) {
            xtest_type_ascii(state, byte);
            count++;
        }
    }
    XFlush(state->xtest_display);
    input_debug_log("xtest-text bytes=%d", count);
}
#else
static void xtest_type_text_hex(struct server_state *state, const char *hex) {  }
#endif


static KeySym android_keycode_to_keysym(int keycode) {
    switch (keycode) {
        case 19: return XK_Up;
        case 20: return XK_Down;
        case 21: return XK_Left;
        case 22: return XK_Right;
        case 61: return XK_Tab;
        case 62: return XK_space;
        case 66: return XK_Return;
        case 67: return XK_BackSpace;
        case 111: return XK_Escape;
        case 112: return XK_Delete;
        default: return NoSymbol;
    }
}

#ifndef WAYLANDIE_NO_XTEST
static void xtest_android_key(struct server_state *state, int keycode, const char *action) {
    if (action == NULL || !xtest_ensure_display(state)) {
        return;
    }
    KeySym keysym = android_keycode_to_keysym(keycode);
    if (keysym == NoSymbol) {
        return;
    }
    xtest_key_sym(state, keysym, strcmp(action, "down") == 0);
    XFlush(state->xtest_display);
    input_debug_log("xtest-key keycode=%d action=%s", keycode, action);
}
#else
static void xtest_android_key(struct server_state *state, int keycode, const char *action) {  }
#endif


#ifndef WAYLANDIE_NO_XTEST
static void xtest_copy_shortcut(struct server_state *state) {
    if (!xtest_ensure_display(state)) {
        return;
    }
    xtest_key_sym(state, XK_Control_L, 1);
    xtest_key_sym(state, XK_c, 1);
    xtest_key_sym(state, XK_c, 0);
    xtest_key_sym(state, XK_Control_L, 0);
    XFlush(state->xtest_display);
    input_debug_log("xtest-copy-shortcut");
}
#else
static void xtest_copy_shortcut(struct server_state *state) {  }
#endif


static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ((double)ts.tv_sec * 1000.0) + ((double)ts.tv_nsec / 1000000.0);
}

static uint32_t now_msec32(void) {
    return (uint32_t)now_ms();
}

static void sleep_ms_precise(double duration_ms) {
    if (duration_ms <= 0.0) {
        return;
    }
    struct timespec duration;
    duration.tv_sec = (time_t)(duration_ms / 1000.0);
    duration.tv_nsec = (long)((duration_ms - ((double)duration.tv_sec * 1000.0)) * 1000000.0);
    while (nanosleep(&duration, &duration) != 0 && errno == EINTR) {
    }
}

static int connect_abstract_socket(const char *name) {
    struct sockaddr_un addr;
    size_t name_len = strlen(name);
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        return -1;
    }
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    if (name_len + 1 > sizeof(addr.sun_path)) {
        close(fd);
        errno = ENAMETOOLONG;
        return -1;
    }
    memcpy(addr.sun_path + 1, name, name_len);
    socklen_t addr_len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + name_len);
    if (connect(fd, (struct sockaddr *)&addr, addr_len) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    return fd;
}

static int send_command_with_fd(int sock, const char *command, int fd) {
    char control[CMSG_SPACE(sizeof(int))];
    struct iovec iov = {
        .iov_base = (void *)command,
        .iov_len = strlen(command),
    };
    struct msghdr msg;
    memset(control, 0, sizeof(control));
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    if (cmsg == NULL) {
        errno = EINVAL;
        return -1;
    }
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &fd, sizeof(int));
    msg.msg_controllen = cmsg->cmsg_len;

    ssize_t sent = sendmsg(sock, &msg, MSG_NOSIGNAL);
    if (sent < 0) {
        return -1;
    }
    if ((size_t)sent != iov.iov_len) {
        errno = EPIPE;
        return -1;
    }
    return 0;
}

static int send_text_bridge_command(const char *socket_name, const char *command, char *response, size_t response_size) {
    int sock = connect_abstract_socket(socket_name);
    if (sock < 0) {
        return -1;
    }
    size_t command_len = strlen(command);
    ssize_t sent = send(sock, command, command_len, MSG_NOSIGNAL);
    if (sent < 0 || (size_t)sent != command_len) {
        int saved = sent < 0 ? errno : EPIPE;
        close(sock);
        errno = saved;
        return -1;
    }
    ssize_t response_len = read(sock, response, response_size > 0 ? response_size - 1U : 0U);
    if (response_len < 0) {
        int saved = errno;
        close(sock);
        errno = saved;
        return -1;
    }
    if (response_size > 0) {
        response[response_len < 0 ? 0 : response_len] = '\0';
    }
    close(sock);
    return 0;
}

static void append_bridge_token(char *out, size_t out_size, const char *text) {
    size_t used = 0;
    if (out_size == 0) {
        return;
    }
    if (text == NULL || text[0] == '\0') {
        snprintf(out, out_size, "empty");
        return;
    }
    for (size_t i = 0; text[i] != '\0' && used + 1U < out_size; i++) {
        unsigned char c = (unsigned char)text[i];
        if ((c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '-'
                || c == '.'
                || c == ':') {
            out[used++] = (char)c;
        } else {
            out[used++] = '_';
        }
    }
    out[used] = '\0';
}

static int response_is_pass(const char *response, size_t response_len) {
    return strstr(response, " status=pass ") != NULL
            || (response_len >= strlen("status=pass")
                    && memcmp(response + response_len - strlen("status=pass"),
                            "status=pass",
                            strlen("status=pass")) == 0);
}

static long long extract_response_us_field(const char *response, const char *field) {
    size_t field_len = strlen(field);
    const char *p = response;
    while (p != NULL && (p = strstr(p, field)) != NULL) {
        if (p > response && p[-1] == '-') {
            p += field_len;
            continue;
        }
        const char *q = p + field_len;
        if (*q == '=' || *q == '_' || *q == ' ') {
            q++;
            while (*q != '\0' && (*q < '0' || *q > '9')) {
                q++;
            }
            if (*q >= '0' && *q <= '9') {
                char *end = NULL;
                long long value = strtoll(q, &end, 10);
                if (end != q && end[0] == 'u' && end[1] == 's') {
                    return value;
                }
            }
        }
        p += field_len;
    }
    return -1;
}

static int ensure_android_window_for_surface(struct surface_state *surface, struct shm_buffer_state *buffer) {
    if (surface == NULL
            || surface->server == NULL
            || !surface->server->android_windows
            || surface->android_window_sent) {
        return 0;
    }
    if (surface->android_window_id[0] == '\0') {
        snprintf(
                surface->android_window_id,
                sizeof(surface->android_window_id),
                "wl%d",
                ++surface->server->next_window_id);
    }
    char title[128];
    char app_id[128];
    char response[1024];
    char command[512];
    append_bridge_token(
            title,
            sizeof(title),
            surface->title[0] != '\0' ? surface->title : surface->android_window_id);
    append_bridge_token(
            app_id,
            sizeof(app_id),
            surface->app_id[0] != '\0' ? surface->app_id : surface->android_window_id);
    int window_width = buffer != NULL && buffer->width > 0 ? buffer->width : surface->server->output_width;
    int window_height = buffer != NULL && buffer->height > 0 ? buffer->height : surface->server->output_height;
    if (window_width <= 0) {
        window_width = 960;
    }
    if (window_height <= 0) {
        window_height = 600;
    }
    int offset = (surface->server->next_window_id % 5) * 48;
    int command_len = snprintf(
            command,
            sizeof(command),
            "window-add id=%s app-id=%s title=%s x=%d y=%d width=%d height=%d\n",
            surface->android_window_id,
            app_id,
            title,
            80 + offset,
            80 + offset,
            window_width,
            window_height);
    if (command_len <= 0 || (size_t)command_len >= sizeof(command)) {
        printf("wayland-shm-ahb window-add id=%s status=fail reason=command-too-long\n",
                surface->android_window_id);
        return -1;
    }
    if (send_text_bridge_command(
            surface->server->bridge_socket_name,
            command,
            response,
            sizeof(response)) != 0) {
        printf("wayland-shm-ahb window-add id=%s status=fail reason=bridge errno=%d\n",
                surface->android_window_id,
                errno);
        return -1;
    }
    surface->android_window_sent = 1;
    response[strcspn(response, "\r\n")] = '\0';
    printf("wayland-shm-ahb window-add id=%s response=%s\n",
            surface->android_window_id,
            response);
    return 0;
}

static void close_android_window_for_surface(struct surface_state *surface) {
    if (surface == NULL
            || surface->server == NULL
            || !surface->server->android_windows
            || !surface->android_window_sent
            || surface->android_window_id[0] == '\0') {
        return;
    }
    char command[160];
    char response[512];
    int command_len = snprintf(
            command,
            sizeof(command),
            "window-remove id=%s\n",
            surface->android_window_id);
    if (command_len <= 0 || (size_t)command_len >= sizeof(command)) {
        return;
    }
    if (send_text_bridge_command(
            surface->server->bridge_socket_name,
            command,
            response,
            sizeof(response)) == 0) {
        response[strcspn(response, "\r\n")] = '\0';
        printf("wayland-shm-ahb window-remove id=%s response=%s\n",
                surface->android_window_id,
                response);
    }
    surface->android_window_sent = 0;
}

// ----------------------------------------------------------------------
// SHM → AHardwareBuffer conversion (bionic bridge only)
//
// Wine's explorer.exe / desktop renders into wl_shm buffers (CPU mmap'd
// memory). Without this function, the bridge skips SHM buffers entirely
// and the desktop is invisible.
//
// This function:
//   1. Allocates an AHardwareBuffer (R8G8B8A8_UNORM, GPU_SAMPLED_IMAGE)
//   2. CPU-memcpy's the SHM pixels into it (with byte swap XRGB→RGBA)
//   3. Exports the AHardwareBuffer's dmabuf fd
//   4. Returns a temporary shm_buffer_state (kind=DMABUF) the caller
//      can pass to present_buffer_to_android()
//
// The caller MUST release the AHardwareBuffer and close the dmabuf fd
// after present_buffer_to_android() returns (via shm_ahb_release()).
//
// Cost: ONE CPU memcpy per desktop frame. Negligible for low-FPS static
// UI. Game frames use true zero-copy dmabuf (no conversion needed).
// ----------------------------------------------------------------------
#ifdef WAYLANDIE_HAS_AHARDWAREBUFFER
struct shm_ahb_handle {
    AHardwareBuffer* ahb;
    int dmabuf_fd;
    struct shm_buffer_state temp_buffer;  // kind=DMABUF, ready to present
};

static int shm_to_ahb(struct shm_buffer_state *shm, int frame_index,
                       struct shm_ahb_handle *out) {
    out->ahb = NULL;
    out->dmabuf_fd = -1;
    memset(&out->temp_buffer, 0, sizeof(out->temp_buffer));

    if (shm == NULL || shm->kind != BUFFER_KIND_SHM) return -1;

    g_diag.shm_to_ahb_calls++;

    // Diagnostic: log the pool state BEFORE any checks, so we can see
    // exactly what shm_to_ahb is working with.
    printf("wayland-shm-ahb frame=%d shm-to-ahb-entry buf_id=%llu shm=%p pool=%p pool_id=%llu pool_data=%p pool_size=%d pool_fd=%d pool_refcount=%d "
           "buf=%dx%d stride=%d offset=%d kind=%d\n",
           frame_index, (unsigned long long)shm->id, (void*)shm, (void*)shm->pool,
           shm->pool ? (unsigned long long)shm->pool->id : 0,
           shm->pool ? shm->pool->data : NULL,
           shm->pool ? shm->pool->size : -1,
           shm->pool ? shm->pool->fd : -1,
           shm->pool ? shm->pool->refcount : -1,
           shm->width, shm->height, shm->stride, shm->offset, shm->kind);
    fflush(stdout);

    // Use-after-free detection: if pool_refcount <= 0, the pool was already
    // freed and shm->pool is a dangling pointer. This should NEVER happen
    // with the refcount fix, but if it does, we log it and abort.
    if (shm->pool != NULL && shm->pool->refcount <= 0) {
        g_diag.use_after_free_detected++;
        printf("wayland-shm-ahb frame=%d status=fail reason=USE-AFTER-FREE pool_id=%llu refcount=%d "
               "pool was freed before shm_to_ahb! buf_id=%llu\n",
               frame_index,
               (unsigned long long)shm->pool->id,
               shm->pool->refcount,
               (unsigned long long)shm->id);
        fflush(stdout);
        return -1;
    }

    if (shm->pool == NULL || shm->pool->data == NULL
            || shm->pool->data == MAP_FAILED) {
        printf("wayland-shm-ahb frame=%d status=fail reason=shm-no-pool buf_id=%llu\n",
               frame_index, (unsigned long long)shm->id);
        g_diag.shm_to_ahb_failures++;
        return -1;
    }
    if (shm->width <= 0 || shm->height <= 0 || shm->stride <= 0) {
        printf("wayland-shm-ahb frame=%d status=fail reason=shm-bad-dims %dx%d stride=%d buf_id=%llu\n",
               frame_index, shm->width, shm->height, shm->stride,
               (unsigned long long)shm->id);
        g_diag.shm_to_ahb_failures++;
        return -1;
    }
    // Sanity-check the SHM pool is large enough for the offset + size
    int64_t needed = (int64_t)shm->offset + (int64_t)shm->stride * shm->height;
    if (needed > shm->pool->size) {
        printf("wayland-shm-ahb frame=%d status=fail reason=shm-pool-overflow need=%lld have=%d "
               "pool_id=%llu pool=%p pool_data=%p buf_id=%llu\n",
               frame_index, (long long)needed, shm->pool->size,
               (unsigned long long)shm->pool->id,
               (void*)shm->pool, shm->pool->data,
               (unsigned long long)shm->id);
        g_diag.shm_to_ahb_failures++;
        return -1;
    }

    // 1. Allocate AHardwareBuffer
    AHardwareBuffer_Desc desc = {};
    desc.width = (uint32_t)shm->width;
    desc.height = (uint32_t)shm->height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN
               | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE
               | AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN;
    desc.rfu0 = 0;
    desc.rfu1 = 0;

    int rc = AHardwareBuffer_allocate(&desc, &out->ahb);
    if (rc != 0 || out->ahb == NULL) {
        printf("wayland-shm-ahb frame=%d status=fail reason=ahb-alloc rc=%d errno=%d %s "
               "buf_id=%llu %dx%d\n",
               frame_index, rc, errno, strerror(errno),
               (unsigned long long)shm->id, shm->width, shm->height);
        g_diag.shm_to_ahb_failures++;
        out->ahb = NULL;
        return -1;
    }
    g_diag.ahb_allocated++;

    // 2. Lock for CPU write
    void* dst = NULL;
    rc = AHardwareBuffer_lock(out->ahb,
                              AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN
                              | AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                              -1, NULL, &dst);
    if (rc != 0 || dst == NULL) {
        printf("wayland-shm-ahb frame=%d status=fail reason=ahb-lock rc=%d\n",
               frame_index, rc);
        AHardwareBuffer_release(out->ahb);
        out->ahb = NULL;
        return -1;
    }

    // 3. Get AHB stride
    AHardwareBuffer_Desc queried = {};
    AHardwareBuffer_describe(out->ahb, &queried);
    int dst_stride_px = queried.stride ? (int)queried.stride : shm->width;
    size_t dst_stride_bytes = (size_t)dst_stride_px * 4;

    // 4. Copy with byte swap:
    //    SHM XRGB8888 in memory = [B, G, R, X] per pixel
    //    AHB R8G8B8A8 in memory = [R, G, B, A] per pixel
    //    We also handle ARGB8888 SHM (same layout but A instead of X).
    uint8_t* src_base = (uint8_t*)shm->pool->data + shm->offset;
    int src_stride_bytes = shm->stride;
    for (int y = 0; y < shm->height; y++) {
        const uint8_t* src_row = src_base + (size_t)y * src_stride_bytes;
        uint8_t* dst_row = (uint8_t*)dst + (size_t)y * dst_stride_bytes;
        for (int x = 0; x < shm->width; x++) {
            uint8_t b = src_row[x * 4 + 0];
            uint8_t g = src_row[x * 4 + 1];
            uint8_t r = src_row[x * 4 + 2];
            // byte 3 is X (unused) or A — force opaque for the desktop
            dst_row[x * 4 + 0] = r;
            dst_row[x * 4 + 1] = g;
            dst_row[x * 4 + 2] = b;
            dst_row[x * 4 + 3] = 0xFF;
        }
    }

    AHardwareBuffer_unlock(out->ahb, NULL);

    // 5. Get dmabuf fd
    const struct waylandie_native_handle* h = AHardwareBuffer_getNativeHandle(out->ahb);
    if (h == NULL || h->numFds < 1) {
        printf("wayland-shm-ahb frame=%d status=fail reason=no-dmabuf-fd numFds=%d\n",
               frame_index, h ? h->numFds : -1);
        AHardwareBuffer_release(out->ahb);
        out->ahb = NULL;
        return -1;
    }
    out->dmabuf_fd = dup(h->data[0]);
    if (out->dmabuf_fd < 0) {
        printf("wayland-shm-ahb frame=%d status=fail reason=dup-fd errno=%d\n",
               frame_index, errno);
        AHardwareBuffer_release(out->ahb);
        out->ahb = NULL;
        return -1;
    }

    // 6. Build a temporary buffer_state that present_buffer_to_android can use
    out->temp_buffer.kind = BUFFER_KIND_DMABUF;
    out->temp_buffer.resource = shm->resource;  // so release events go to the right wl_buffer
    out->temp_buffer.pool = NULL;
    out->temp_buffer.offset = 0;
    out->temp_buffer.width = shm->width;
    out->temp_buffer.height = shm->height;
    out->temp_buffer.stride = dst_stride_px * 4;  // stride in bytes (what dmabuf path expects)
    // Use ABGR8888 — memory layout [R,G,B,A] matches our AHB R8G8B8A8.
    // Java side maps ABGR8888 → VK_FORMAT_R8G8B8A8_UNORM (see waylandie_display_native.c).
    out->temp_buffer.format = DRM_FORMAT_ABGR8888;
    out->temp_buffer.flags = 0;
    out->temp_buffer.modifier = DRM_FORMAT_MOD_LINEAR;
    out->temp_buffer.dmabuf_fd = out->dmabuf_fd;

    printf("wayland-shm-ahb frame=%d shm-to-ahb ok src_stride=%d dst_stride=%d "
           "%dx%d fd=%d\n",
           frame_index, src_stride_bytes, (int)dst_stride_bytes,
           shm->width, shm->height, out->dmabuf_fd);
    return 0;
}

static void shm_ahb_release(struct shm_ahb_handle *h) {
    if (h->dmabuf_fd >= 0) {
        close(h->dmabuf_fd);
        g_diag.dmabuf_fds_closed++;
        h->dmabuf_fd = -1;
    }
    if (h->ahb != NULL) {
        AHardwareBuffer_release(h->ahb);
        g_diag.ahb_released++;
        h->ahb = NULL;
    }
}
#endif  // WAYLANDIE_HAS_AHARDWAREBUFFER

static int present_buffer_to_android(struct surface_state *surface, struct shm_buffer_state *buffer, int frame_index) {
    struct server_state *state = surface == NULL ? NULL : surface->server;
    char response[4096];
    char command[1024];
    int status = -1;
    double start_ms = now_ms();

    if (state == NULL) {
        printf("wayland-shm-ahb frame=%d status=fail reason=no-server\n", frame_index);
        return -1;
    }

    // ----- SHM buffer path: convert to AHardwareBuffer, then present as dmabuf -----
    // Wine's explorer.exe / desktop uses wl_shm buffers (CPU mmap'd memory).
    // Without this conversion, the desktop would be invisible. We allocate
    // an AHardwareBuffer, CPU-copy the SHM pixels into it (with byte swap),
    // export its dmabuf fd, and present via the same zero-copy path as game
    // frames. Cost: ONE CPU memcpy per desktop frame (negligible for low-FPS
    // static UI). Game frames use true zero-copy dmabuf — no conversion.
#ifdef WAYLANDIE_HAS_AHARDWAREBUFFER
    struct shm_ahb_handle ahb_handle;
    if (buffer != NULL && buffer->kind == BUFFER_KIND_SHM) {
        if (shm_to_ahb(buffer, frame_index, &ahb_handle) != 0) {
            return -1;  // shm_to_ahb already printed the failure reason
        }
        // Present the converted AHB as a dmabuf. We temporarily swap the
        // buffer pointer so the rest of this function operates on the
        // AHB-backed dmabuf state.
        struct shm_buffer_state *orig_buffer = buffer;
        buffer = &ahb_handle.temp_buffer;
        int rc = present_buffer_to_android(surface, buffer, frame_index);
        shm_ahb_release(&ahb_handle);
        (void)orig_buffer;
        return rc;
    }
#endif  // WAYLANDIE_HAS_AHARDWAREBUFFER

    if (buffer == NULL || buffer->kind != BUFFER_KIND_DMABUF || buffer->dmabuf_fd < 0) {
        printf("wayland-shm-ahb frame=%d status=fail reason=not-dmabuf-zero-copy kind=%d\n",
               frame_index, buffer ? buffer->kind : -1);
        return -1;
    }
    if (buffer->width <= 0 || buffer->height <= 0 || buffer->stride <= 0 || buffer->offset < 0) {
        printf("wayland-shm-ahb frame=%d status=fail reason=invalid-dmabuf-meta\n", frame_index);
        return -1;
    }

    uint64_t required_size = (uint64_t)(uint32_t)buffer->offset
            + ((uint64_t)(uint32_t)buffer->stride * (uint64_t)(uint32_t)buffer->height);
    uint64_t dmabuf_size = required_size;
    struct stat st;
    if (fstat(buffer->dmabuf_fd, &st) == 0 && st.st_size > 0 && (uint64_t)st.st_size > dmabuf_size) {
        dmabuf_size = (uint64_t)st.st_size;
    }
    const char *driver_name = getenv("WAYLANDIE_ANDROID_VK_DRIVER");
    if (driver_name == NULL || driver_name[0] == '\0') {
        driver_name = DEFAULT_ANDROID_VK_DRIVER;
    }
    const char *target_window = state->android_windows && surface->android_window_id[0] != '\0'
            ? surface->android_window_id
            : "fullscreen";
    int command_len = snprintf(
            command,
            sizeof(command),
            "dmabuf-present fast=1 window=%s width=%d height=%d format=%" PRIu32 " modifier=0x%016" PRIx64 " planes=1 stride0=%d offset0=%d size=%" PRIu64 " driver=%s\n",
            target_window,
            buffer->width,
            buffer->height,
            buffer->format,
            buffer->modifier,
            buffer->stride,
            buffer->offset,
            dmabuf_size,
            driver_name);
    if (command_len <= 0 || (size_t)command_len >= sizeof(command)) {
        printf("wayland-shm-ahb frame=%d status=fail reason=command-too-long\n", frame_index);
        return -1;
    }

    if (state->bridge_sock >= 0
            && state->bridge_reconnect_frames > 0
            && state->bridge_frames_on_socket >= state->bridge_reconnect_frames) {
        close(state->bridge_sock);
        state->bridge_sock = -1;
        state->bridge_frames_on_socket = 0;
    }
    if (state->bridge_sock < 0) {
        state->bridge_sock = connect_abstract_socket(state->bridge_socket_name);
        if (state->bridge_sock >= 0) {
            g_diag.bridge_reconnects++;
            printf("wayland-shm-ahb frame=%d bridge-connected sock=%d\n", frame_index, state->bridge_sock);
            fflush(stdout);
        }
    }
    if (state->bridge_sock < 0) {
        printf("wayland-shm-ahb frame=%d status=fail reason=bridge-connect errno=%d socket=%s\n",
               frame_index, errno, state->bridge_socket_name);
        return -1;
    }
    if (frame_index == 0) {
        printf("wayland-shm-ahb frame=0 present-step sending dmabuf fd=%d to Java presenter sock=%d\n",
               buffer->dmabuf_fd, state->bridge_sock);
        fflush(stdout);
    }
    if (send_command_with_fd(state->bridge_sock, command, buffer->dmabuf_fd) != 0) {
        printf("wayland-shm-ahb frame=%d status=fail reason=dmabuf-send errno=%d\n", frame_index, errno);
        close(state->bridge_sock);
        state->bridge_sock = -1;
        state->bridge_frames_on_socket = 0;
        goto cleanup;
    }
    if (frame_index == 0) {
        printf("wayland-shm-ahb frame=0 present-step waiting for Java presenter response...\n");
        fflush(stdout);
    }
    ssize_t response_len = read(state->bridge_sock, response, sizeof(response) - 1U);
    if (response_len <= 0) {
        printf("wayland-shm-ahb frame=%d status=fail reason=response errno=%d response_len=%zd\n",
               frame_index, errno, response_len);
        close(state->bridge_sock);
        state->bridge_sock = -1;
        state->bridge_frames_on_socket = 0;
        goto cleanup;
    }
    response[response_len] = '\0';
    if (frame_index == 0) {
        printf("wayland-shm-ahb frame=0 present-step got response (%zd bytes): %.200s\n",
               response_len, response);
        fflush(stdout);
    }
    if (strstr(response, RESPONSE_PREFIX) == NULL || !response_is_pass(response, (size_t)response_len)) {
        printf("wayland-shm-ahb frame=%d status=fail reason=app-response response=%s\n", frame_index, response);
        goto cleanup;
    }
    double present_ms = now_ms() - start_ms;
    state->total_present_ms += present_ms;
    long long app_wait_us = extract_response_us_field(response, "wait");
    long long app_slot_wait_us = extract_response_us_field(response, "slot-wait");
    long long source_wait_us = extract_response_us_field(response, "source-wait");
    if (app_wait_us >= 0) {
        state->total_app_wait_us += (double)app_wait_us;
        state->app_wait_samples++;
    }
    if (app_slot_wait_us >= 0) {
        state->total_app_slot_wait_us += (double)app_slot_wait_us;
        state->app_slot_wait_samples++;
    }
    if (state->pass_log_interval > 0
            ? (frame_index % state->pass_log_interval) == 0
            : frame_index < 5) {  // Always log first 5 frames for diagnostics
        printf(
            "wayland-shm-ahb frame=%d status=pass kind=dmabuf client=%dx%d format=0x%08x modifier=0x%016" PRIx64 " stride=%d size=%" PRIu64 " zero-copy=gpu driver=%s present-ms=%.3f app-wait-us=%lld app-slot-wait-us=%lld source-wait-us=%lld\n",
            frame_index,
            buffer->width,
            buffer->height,
            buffer->format,
            buffer->modifier,
            buffer->stride,
            dmabuf_size,
            driver_name,
            present_ms,
            app_wait_us,
            app_slot_wait_us,
            source_wait_us);
    }
    state->bridge_frames_on_socket++;
    status = 0;
    g_diag.surfaces_presented_ok++;

cleanup:
    if (status != 0) {
        g_diag.surfaces_presented_fail++;
    }
    // Always log the first frame's present result (pass or fail) for diagnostics
    if (frame_index == 0) {
        printf("wayland-shm-ahb frame=0 present-result status=%s\n",
               status == 0 ? "pass" : "fail");
        fflush(stdout);
    }
    return status;
}

// Pool reference counting. The pool's mmap'd memory stays alive until ALL
// buffers referencing it are destroyed. This fixes the use-after-free where
// Wine destroys the pool resource BEFORE the bridge finishes reading the
// buffer's pixel data in shm_to_ahb.
static void pool_acquire(struct shm_pool_state *pool) {
    if (pool != NULL) {
        __atomic_fetch_add(&pool->refcount, 1, __ATOMIC_SEQ_CST);
    }
}

static void pool_release(struct shm_pool_state *pool) {
    if (pool == NULL) return;
    int new_count = __atomic_fetch_sub(&pool->refcount, 1, __ATOMIC_SEQ_CST) - 1;
    printf("wayland-shm-ahb pool-release id=%llu refcount=%d→%d size=%d\n",
           (unsigned long long)pool->id, new_count + 1, new_count, pool->size);
    fflush(stdout);
    if (new_count <= 0) {
        // Last reference — safe to munmap and free
        printf("wayland-shm-ahb pool-freed id=%llu size=%d fd=%d data=%p\n",
               (unsigned long long)pool->id, pool->size, pool->fd, pool->data);
        fflush(stdout);
        if (pool->data != NULL && pool->size > 0 && pool->data != MAP_FAILED) {
            munmap(pool->data, (size_t)pool->size);
        }
        if (pool->fd >= 0) {
            close(pool->fd);
            g_diag.dmabuf_fds_closed++;
        }
        g_diag.pools_destroyed++;
        free(pool);
    }
}

static void destroy_buffer_resource(struct wl_resource *resource) {
    struct shm_buffer_state *buffer = wl_resource_get_user_data(resource);
    if (buffer != NULL) {
        g_diag.buffers_destroyed++;
        printf("wayland-shm-ahb buffer-destroy id=%llu kind=%d %dx%d\n",
               (unsigned long long)buffer->id, buffer->kind,
               buffer->width, buffer->height);
        fflush(stdout);
        if (buffer->kind == BUFFER_KIND_DMABUF && buffer->dmabuf_fd >= 0) {
            close(buffer->dmabuf_fd);
            g_diag.dmabuf_fds_closed++;
        }
        // Release the pool reference. If this was the last reference,
        // pool_release will munmap and free the pool.
        if (buffer->pool != NULL) {
            pool_release(buffer->pool);
            buffer->pool = NULL;
        }
        free(buffer);
    }
}

static void buffer_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static const struct wl_buffer_interface buffer_impl = {
    .destroy = buffer_destroy,
};

static void destroy_pool_resource(struct wl_resource *resource) {
    struct shm_pool_state *pool = wl_resource_get_user_data(resource);
    if (pool != NULL) {
        printf("wayland-shm-ahb pool-destroy id=%llu size=%d fd=%d data=%p refcount=%d\n",
               (unsigned long long)pool->id, pool->size, pool->fd, pool->data, pool->refcount);
        fflush(stdout);
        // Release the pool resource's own reference. If buffers still
        // reference this pool, pool_release will keep the memory alive
        // until the last buffer is destroyed.
        pool_release(pool);
    }
}

static void shm_pool_create_buffer(
        struct wl_client *client,
        struct wl_resource *resource,
        uint32_t id,
        int32_t offset,
        int32_t width,
        int32_t height,
        int32_t stride,
        uint32_t format) {
    struct shm_pool_state *pool = wl_resource_get_user_data(resource);
    struct wl_resource *buffer_resource = wl_resource_create(client, &wl_buffer_interface, 1, id);
    struct shm_buffer_state *buffer = calloc(1, sizeof(*buffer));
    if (buffer_resource == NULL || buffer == NULL) {
        wl_client_post_no_memory(client);
        free(buffer);
        return;
    }
    if (pool == NULL) {
        wl_resource_destroy(buffer_resource);
        free(buffer);
        return;
    }
    buffer->kind = BUFFER_KIND_SHM;
    buffer->resource = buffer_resource;
    buffer->pool = pool;
    buffer->offset = offset;
    buffer->width = width;
    buffer->height = height;
    buffer->stride = stride;
    buffer->format = format;
    buffer->id = g_buffer_id_counter++;
    buffer->dmabuf_fd = -1;
    // Acquire a reference to the pool so it stays alive until this buffer
    // is destroyed, even if Wine destroys the pool resource first.
    pool_acquire(pool);
    g_diag.buffers_created++;

    // Validate buffer dimensions + stride + offset against pool size
    // (catches Wine bugs where stride doesn't match width*4)
    int64_t needed = (int64_t)offset + (int64_t)stride * height;
    if (needed > pool->size) {
        printf("wayland-shm-ahb pool-create-buffer id=%llu WARNING buffer exceeds pool: "
               "offset=%d stride=%d height=%d need=%lld pool_size=%d\n",
               (unsigned long long)buffer->id, offset, stride, height,
               (long long)needed, pool->size);
    }
    // Validate stride is at least width*4 (for XRGB8888/ARGB8888)
    int32_t min_stride = width * 4;
    if (stride < min_stride) {
        printf("wayland-shm-ahb pool-create-buffer id=%llu WARNING stride %d < min_stride %d "
               "(width=%d * 4) — buffer may be corrupt\n",
               (unsigned long long)buffer->id, stride, min_stride, width);
    }
    // Validate format (only XRGB8888 and ARGB8888 are advertised via wl_shm)
    if (format != 0 && format != 1) {  // 0=ARGB8888, 1=XRGB8888 in wl_shm_format enum
        printf("wayland-shm-ahb pool-create-buffer id=%llu WARNING unexpected format 0x%08x "
               "(expected 0=ARGB8888 or 1=XRGB8888)\n",
               (unsigned long long)buffer->id, format);
    }

    printf("wayland-shm-ahb pool-create-buffer id=%llu offset=%d %dx%d stride=%d fmt=0x%08x pool_id=%llu pool_size=%d pool_refcount=%d\n",
           (unsigned long long)buffer->id, offset, width, height, stride, format,
           (unsigned long long)pool->id, pool->size, pool->refcount);
    fflush(stdout);
    wl_resource_set_implementation(buffer_resource, &buffer_impl, buffer, destroy_buffer_resource);
}

static void shm_pool_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

// Resize the SHM pool. Wine's winewayland.drv creates a pool with a small
// initial size (e.g. 22 bytes for the header) and then resizes it to the
// full buffer size (e.g. 19MB for a 3200x1536 framebuffer).
//
// We use mremap() to grow the mapping in place. This works because the
// underlying fd is a memfd/anonymous file (created by Wine via memfd_create
// or similar), and mremap(MREMAP_MAYMOVE) handles the address change
// transparently. The fd must be kept open for this to work — we store it
// in shm_pool_state.fd.
//
// Without this, the bridge would see a 22-byte pool and reject the
// 19MB buffer with "shm-pool-overflow", making the Wine desktop invisible.
static void shm_pool_resize(struct wl_client *client, struct wl_resource *resource, int32_t size) {
    (void)client;
    struct shm_pool_state *pool = wl_resource_get_user_data(resource);
    if (pool == NULL || size <= 0) {
        return;
    }
    if (size == pool->size) {
        return;  // No-op
    }
    if (pool->fd < 0) {
        printf("wayland-shm-ahb pool-resize failed: no fd (pool was created without keeping fd)\n");
        fflush(stdout);
        return;
    }
    int32_t old_size = pool->size;
    // First, ftruncate the fd to the new size so the kernel knows the
    // backing file is larger. Without this, mremap may succeed but reads
    // beyond the old size would SIGBUS.
    if (ftruncate(pool->fd, (off_t)size) != 0) {
        printf("wayland-shm-ahb pool-resize ftruncate failed: errno=%d (%s) old=%d new=%d\n",
               errno, strerror(errno), old_size, size);
        fflush(stdout);
        // Continue anyway — some fds (e.g. regular files) don't need ftruncate
    }
    // mremap with MREMAP_MAYMOVE so the kernel can relocate the mapping
    // if it can't grow in place. Linux-only; bionic supports this.
    void *new_data = mremap(pool->data, (size_t)old_size, (size_t)size, MREMAP_MAYMOVE);
    if (new_data == MAP_FAILED) {
        printf("wayland-shm-ahb pool-resize mremap failed: errno=%d (%s) old=%d new=%d\n",
               errno, strerror(errno), old_size, size);
        fflush(stdout);
        // Don't update pool->size — leave the pool at its old size so the
        // caller's subsequent operations don't read past the mapping.
        return;
    }
    pool->data = new_data;
    pool->size = size;
    printf("wayland-shm-ahb pool-resize ok old=%d new=%d data=%p\n",
           old_size, size, new_data);
    fflush(stdout);
}

static const struct wl_shm_pool_interface shm_pool_impl = {
    .create_buffer = shm_pool_create_buffer,
    .destroy = shm_pool_destroy,
    .resize = shm_pool_resize,
};

static void shm_create_pool(
        struct wl_client *client,
        struct wl_resource *resource,
        uint32_t id,
        int32_t fd,
        int32_t size) {
    struct wl_resource *pool_resource = wl_resource_create(client, &wl_shm_pool_interface, 1, id);
    struct shm_pool_state *pool = calloc(1, sizeof(*pool));
    if (pool_resource == NULL || pool == NULL) {
        close(fd);
        wl_client_post_no_memory(client);
        free(pool);
        return;
    }
    if (size <= 0) {
        close(fd);
        free(pool);
        wl_resource_post_error(resource, WL_SHM_ERROR_INVALID_FD, "invalid shm size");
        return;
    }
    pool->data = mmap(NULL, (size_t)size, PROT_READ, MAP_SHARED, fd, 0);
    pool->size = size;
    pool->fd = fd;  // Keep fd open so shm_pool_resize can mremap.
    pool->refcount = 1;  // 1 for the pool resource itself.
    pool->id = g_pool_id_counter++;
    g_diag.pools_created++;
    if (pool->data == MAP_FAILED) {
        close(fd);
        pool->fd = -1;
        free(pool);
        wl_resource_post_error(resource, WL_SHM_ERROR_INVALID_FD, "mmap failed");
        return;
    }
    printf("wayland-shm-ahb pool-create id=%llu size=%d fd=%d data=%p refcount=1\n",
           (unsigned long long)pool->id, size, fd, pool->data);
    fflush(stdout);
    wl_resource_set_implementation(pool_resource, &shm_pool_impl, pool, destroy_pool_resource);
}

static const struct wl_shm_interface shm_impl = {
    .create_pool = shm_create_pool,
};

static void bind_shm(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    (void)data;
    struct wl_resource *resource = wl_resource_create(client, &wl_shm_interface, version > 1 ? 1 : version, id);
    if (resource == NULL) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(resource, &shm_impl, NULL, NULL);
    wl_shm_send_format(resource, WL_SHM_FORMAT_XRGB8888);
    wl_shm_send_format(resource, WL_SHM_FORMAT_ARGB8888);
}

static int dmabuf_format_supported(uint32_t format) {
    return format == DRM_FORMAT_XRGB8888
            || format == DRM_FORMAT_ARGB8888
            || format == DRM_FORMAT_XBGR8888
            || format == DRM_FORMAT_ABGR8888;
}

static void destroy_dmabuf_params_resource(struct wl_resource *resource) {
    struct dmabuf_params_state *params = wl_resource_get_user_data(resource);
    if (params != NULL) {
        for (int i = 0; i < MAX_DMABUF_PLANES; i++) {
            if (params->fds[i] >= 0) {
                close(params->fds[i]);
            }
        }
    }
    free(params);
}

static void dmabuf_params_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void dmabuf_params_add(
        struct wl_client *client,
        struct wl_resource *resource,
        int32_t fd,
        uint32_t plane_idx,
        uint32_t offset,
        uint32_t stride,
        uint32_t modifier_hi,
        uint32_t modifier_lo) {
    (void)client;
    struct dmabuf_params_state *params = wl_resource_get_user_data(resource);
    if (params == NULL || plane_idx >= MAX_DMABUF_PLANES) {
        close(fd);
        wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_PLANE_IDX, "invalid dmabuf plane");
        return;
    }
    if (params->has_plane[plane_idx]) {
        close(fd);
        wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_PLANE_SET, "dmabuf plane already set");
        return;
    }
    params->fds[plane_idx] = fd;
    params->has_plane[plane_idx] = 1;
    params->offsets[plane_idx] = offset;
    params->strides[plane_idx] = stride;
    params->modifiers[plane_idx] = ((uint64_t)modifier_hi << 32U) | (uint64_t)modifier_lo;
}

static struct wl_resource *create_dmabuf_wl_buffer(
        struct wl_client *client,
        struct wl_resource *params_resource,
        uint32_t buffer_id,
        int32_t width,
        int32_t height,
        uint32_t format,
        uint32_t flags) {
    struct dmabuf_params_state *params = wl_resource_get_user_data(params_resource);
    if (params == NULL || params->used) {
        wl_resource_post_error(params_resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_ALREADY_USED, "dmabuf params already used");
        return NULL;
    }
    params->used = 1;
    if (width <= 0 || height <= 0) {
        wl_resource_post_error(params_resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_DIMENSIONS, "invalid dmabuf size");
        return NULL;
    }
    if (!dmabuf_format_supported(format)) {
        wl_resource_post_error(params_resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_FORMAT, "unsupported dmabuf format");
        return NULL;
    }
    if (!params->has_plane[0] || params->fds[0] < 0) {
        wl_resource_post_error(params_resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INCOMPLETE, "missing dmabuf plane 0");
        return NULL;
    }
    if (params->strides[0] < (uint32_t)width * 4U) {
        wl_resource_post_error(params_resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_OUT_OF_BOUNDS, "invalid dmabuf stride");
        return NULL;
    }
    struct wl_resource *buffer_resource = wl_resource_create(client, &wl_buffer_interface, 1, buffer_id);
    struct shm_buffer_state *buffer = calloc(1, sizeof(*buffer));
    if (buffer_resource == NULL || buffer == NULL) {
        wl_client_post_no_memory(client);
        free(buffer);
        return NULL;
    }
    buffer->kind = BUFFER_KIND_DMABUF;
    buffer->resource = buffer_resource;
    buffer->pool = NULL;  // dmabuf buffers don't use a pool
    buffer->offset = (int32_t)params->offsets[0];
    buffer->width = width;
    buffer->height = height;
    buffer->stride = (int32_t)params->strides[0];
    buffer->format = format;
    buffer->flags = flags;
    buffer->modifier = params->modifiers[0];
    buffer->dmabuf_fd = params->fds[0];
    buffer->id = g_buffer_id_counter++;
    params->fds[0] = -1;
    g_diag.buffers_created++;
    g_diag.dmabuf_fds_exported++;
    wl_resource_set_implementation(buffer_resource, &buffer_impl, buffer, destroy_buffer_resource);
    printf(
        "wayland-shm-ahb dmabuf-buffer id=%llu width=%d height=%d stride=%d format=0x%08x modifier=0x%016" PRIx64 " flags=0x%x\n",
        (unsigned long long)buffer->id,
        buffer->width,
        buffer->height,
        buffer->stride,
        buffer->format,
        buffer->modifier,
        buffer->flags);
    return buffer_resource;
}

static void dmabuf_params_create(
        struct wl_client *client,
        struct wl_resource *resource,
        int32_t width,
        int32_t height,
        uint32_t format,
        uint32_t flags) {
    struct wl_resource *buffer = create_dmabuf_wl_buffer(client, resource, 0, width, height, format, flags);
    if (buffer != NULL) {
        zwp_linux_buffer_params_v1_send_created(resource, buffer);
    } else {
        zwp_linux_buffer_params_v1_send_failed(resource);
    }
}

static void dmabuf_params_create_immed(
        struct wl_client *client,
        struct wl_resource *resource,
        uint32_t buffer_id,
        int32_t width,
        int32_t height,
        uint32_t format,
        uint32_t flags) {
    (void)create_dmabuf_wl_buffer(client, resource, buffer_id, width, height, format, flags);
}

static const struct zwp_linux_buffer_params_v1_interface dmabuf_params_impl = {
    .destroy = dmabuf_params_destroy,
    .add = dmabuf_params_add,
    .create = dmabuf_params_create,
    .create_immed = dmabuf_params_create_immed,
};

static void linux_dmabuf_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void linux_dmabuf_create_params(struct wl_client *client, struct wl_resource *resource, uint32_t params_id) {
    struct wl_resource *params_resource = wl_resource_create(client, &zwp_linux_buffer_params_v1_interface, wl_resource_get_version(resource), params_id);
    struct dmabuf_params_state *params = calloc(1, sizeof(*params));
    if (params_resource == NULL || params == NULL) {
        wl_client_post_no_memory(client);
        free(params);
        return;
    }
    for (int i = 0; i < MAX_DMABUF_PLANES; i++) {
        params->fds[i] = -1;
    }
    wl_resource_set_implementation(params_resource, &dmabuf_params_impl, params, destroy_dmabuf_params_resource);
}

struct dmabuf_feedback_format_pair {
    uint32_t format;
    uint32_t padding;
    uint64_t modifier;
};

static const struct dmabuf_feedback_format_pair dmabuf_feedback_formats[] = {
    { DRM_FORMAT_XRGB8888, 0, DRM_FORMAT_MOD_LINEAR },
    { DRM_FORMAT_XRGB8888, 0, DRM_FORMAT_MOD_QCOM_COMPRESSED },
    { DRM_FORMAT_ARGB8888, 0, DRM_FORMAT_MOD_LINEAR },
    { DRM_FORMAT_ARGB8888, 0, DRM_FORMAT_MOD_QCOM_COMPRESSED },
    { DRM_FORMAT_XBGR8888, 0, DRM_FORMAT_MOD_LINEAR },
    { DRM_FORMAT_XBGR8888, 0, DRM_FORMAT_MOD_QCOM_COMPRESSED },
    { DRM_FORMAT_ABGR8888, 0, DRM_FORMAT_MOD_LINEAR },
    { DRM_FORMAT_ABGR8888, 0, DRM_FORMAT_MOD_QCOM_COMPRESSED },
};

static void destroy_dmabuf_feedback_resource(struct wl_resource *resource) {
    struct dmabuf_feedback_state *feedback = wl_resource_get_user_data(resource);
    if (feedback != NULL && feedback->table_fd >= 0) {
        close(feedback->table_fd);
    }
    free(feedback);
}

static void dmabuf_feedback_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static const struct zwp_linux_dmabuf_feedback_v1_interface dmabuf_feedback_impl = {
    .destroy = dmabuf_feedback_destroy,
};

static int create_dmabuf_feedback_table(size_t *size_out) {
    int fd = -1;
#ifdef MFD_CLOEXEC
    fd = memfd_create("waylandie-wayland-dmabuf-feedback", MFD_CLOEXEC);
#endif
    if (fd < 0) {
        char template[] = "/tmp/waylandie-wayland-dmabuf-feedback.XXXXXX";
        fd = mkstemp(template);
        if (fd >= 0) {
            unlink(template);
        }
    }
    if (fd < 0) {
        return -1;
    }
    size_t table_size = sizeof(dmabuf_feedback_formats);
    if (ftruncate(fd, (off_t)table_size) != 0) {
        close(fd);
        return -1;
    }
    const unsigned char *cursor = (const unsigned char *)dmabuf_feedback_formats;
    size_t written = 0;
    while (written < table_size) {
        ssize_t chunk = write(fd, cursor + written, table_size - written);
        if (chunk < 0) {
            if (errno == EINTR) {
                continue;
            }
            close(fd);
            return -1;
        }
        if (chunk == 0) {
            close(fd);
            errno = EIO;
            return -1;
        }
        written += (size_t)chunk;
    }
    lseek(fd, 0, SEEK_SET);
    *size_out = table_size;
    return fd;
}

static dev_t dmabuf_feedback_device(void) {
    const char *override = getenv("WAYLANDIE_WAYLAND_DMABUF_FEEDBACK_DEVICE");
    const char *fallbacks[] = {
        override != NULL ? override : "",
        "/dev/dri/renderD128",
        "/dev/kgsl-3d0",
        NULL,
    };
    for (int i = 0; fallbacks[i] != NULL; i++) {
        struct stat st;
        if (fallbacks[i][0] == '\0') {
            continue;
        }
        if (stat(fallbacks[i], &st) == 0 && S_ISCHR(st.st_mode)) {
            return st.st_rdev;
        }
    }
    return (dev_t)0;
}

static int wl_array_copy_bytes(struct wl_array *array, const void *data, size_t size) {
    void *dest = wl_array_add(array, size);
    if (dest == NULL) {
        return -1;
    }
    memcpy(dest, data, size);
    return 0;
}

static int send_dmabuf_feedback_events(struct wl_resource *feedback_resource) {
    struct dmabuf_feedback_state *feedback = wl_resource_get_user_data(feedback_resource);
    if (feedback == NULL) {
        return -1;
    }
    size_t table_size = 0;
    feedback->table_fd = create_dmabuf_feedback_table(&table_size);
    if (feedback->table_fd < 0 || table_size > UINT32_MAX) {
        return -1;
    }

    dev_t device = dmabuf_feedback_device();
    uint16_t indices[] = { 0, 1, 2, 3, 4, 5, 6, 7 };
    struct wl_array device_array;
    struct wl_array indices_array;
    wl_array_init(&device_array);
    wl_array_init(&indices_array);
    int status = 0;
    if (wl_array_copy_bytes(&device_array, &device, sizeof(device)) != 0
            || wl_array_copy_bytes(&indices_array, indices, sizeof(indices)) != 0) {
        status = -1;
        goto cleanup;
    }

    zwp_linux_dmabuf_feedback_v1_send_format_table(
            feedback_resource,
            feedback->table_fd,
            (uint32_t)table_size);
    zwp_linux_dmabuf_feedback_v1_send_main_device(feedback_resource, &device_array);
    zwp_linux_dmabuf_feedback_v1_send_tranche_target_device(feedback_resource, &device_array);
    zwp_linux_dmabuf_feedback_v1_send_tranche_flags(feedback_resource, 0);
    zwp_linux_dmabuf_feedback_v1_send_tranche_formats(feedback_resource, &indices_array);
    zwp_linux_dmabuf_feedback_v1_send_tranche_done(feedback_resource);
    zwp_linux_dmabuf_feedback_v1_send_done(feedback_resource);

cleanup:
    wl_array_release(&indices_array);
    wl_array_release(&device_array);
    return status;
}

static void linux_dmabuf_create_feedback(struct wl_client *client, uint32_t id) {
    struct wl_resource *feedback_resource =
            wl_resource_create(client, &zwp_linux_dmabuf_feedback_v1_interface, 1, id);
    struct dmabuf_feedback_state *feedback = calloc(1, sizeof(*feedback));
    if (feedback_resource == NULL || feedback == NULL) {
        wl_client_post_no_memory(client);
        free(feedback);
        return;
    }
    feedback->table_fd = -1;
    wl_resource_set_implementation(
            feedback_resource,
            &dmabuf_feedback_impl,
            feedback,
            destroy_dmabuf_feedback_resource);
    if (send_dmabuf_feedback_events(feedback_resource) != 0) {
        wl_client_post_no_memory(client);
    }
}

static void linux_dmabuf_get_default_feedback(
        struct wl_client *client,
        struct wl_resource *resource,
        uint32_t id) {
    (void)resource;
    linux_dmabuf_create_feedback(client, id);
}

static void linux_dmabuf_get_surface_feedback(
        struct wl_client *client,
        struct wl_resource *resource,
        uint32_t id,
        struct wl_resource *surface) {
    (void)resource;
    (void)surface;
    linux_dmabuf_create_feedback(client, id);
}

static const struct zwp_linux_dmabuf_v1_interface linux_dmabuf_impl = {
    .destroy = linux_dmabuf_destroy,
    .create_params = linux_dmabuf_create_params,
    .get_default_feedback = linux_dmabuf_get_default_feedback,
    .get_surface_feedback = linux_dmabuf_get_surface_feedback,
};

static void output_release(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static const struct wl_output_interface output_impl = {
    .release = output_release,
};

static void bind_output(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    struct server_state *state = data;
    int32_t width = state != NULL && state->output_width > 0 ? state->output_width : 2688;
    int32_t height = state != NULL && state->output_height > 0 ? state->output_height : 1216;
    int32_t refresh_millihz = 120000;
    if (state != NULL && state->presentation_refresh_nsec > 0) {
        double derived_refresh_millihz = 1000000000000.0
                / (double)state->presentation_refresh_nsec;
        if (derived_refresh_millihz > 0.0 && derived_refresh_millihz < 1000000.0) {
            refresh_millihz = (int32_t)(derived_refresh_millihz + 0.5);
        }
    }
    uint32_t bind_version = version > 4 ? 4 : version;
    fprintf(stderr, "[Bridge] bind_output: client=%p version=%u id=%u, output %dx%d @ %d mHz\n",
            (void*)client, version, id, width, height, refresh_millihz);
    struct wl_resource *resource = wl_resource_create(client, &wl_output_interface, bind_version, id);
    if (resource == NULL) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(resource, &output_impl, NULL, NULL);
    wl_output_send_geometry(
            resource,
            0,
            0,
            width,
            height,
            WL_OUTPUT_SUBPIXEL_UNKNOWN,
            "WayLandIE",
            "Android SurfaceControl",
            WL_OUTPUT_TRANSFORM_NORMAL);
    fprintf(stderr, "[Bridge] Sent wl_output_send_geometry %dx%d\n", width, height);
    wl_output_send_mode(
            resource,
            WL_OUTPUT_MODE_CURRENT | WL_OUTPUT_MODE_PREFERRED,
            width,
            height,
            refresh_millihz);
    fprintf(stderr, "[Bridge] Sent wl_output_send_mode %dx%d @ %d mHz\n", width, height, refresh_millihz);
    /* wl_output_send_done() was introduced in version 2.
     * Wine's driver binds version 2, so we must send done for >= 2.
     * Without the done event, the driver never finalizes the output
     * -> current_mode stays NULL -> lock_display_devices fails. */
    if (bind_version >= 2) {
        if (bind_version >= 3) {
            wl_output_send_scale(resource, 1);
        }
        wl_output_send_done(resource);
        fprintf(stderr, "[Bridge] Sent wl_output_send_done (version=%d)\n", bind_version);
    }
}

static void send_dmabuf_format(struct wl_resource *resource, uint32_t format) {
    zwp_linux_dmabuf_v1_send_format(resource, format);
    if (wl_resource_get_version(resource) >= 3) {
        zwp_linux_dmabuf_v1_send_modifier(resource, format, 0, 0);
    }
}

static void bind_linux_dmabuf(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    (void)data;
    uint32_t bind_version = version > 4 ? 4 : version;
    struct wl_resource *resource = wl_resource_create(client, &zwp_linux_dmabuf_v1_interface, bind_version, id);
    if (resource == NULL) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(resource, &linux_dmabuf_impl, NULL, NULL);
    if (bind_version < 4) {
        send_dmabuf_format(resource, DRM_FORMAT_XRGB8888);
        send_dmabuf_format(resource, DRM_FORMAT_ARGB8888);
        send_dmabuf_format(resource, DRM_FORMAT_XBGR8888);
        send_dmabuf_format(resource, DRM_FORMAT_ABGR8888);
    }
}

struct waylandie_xdg_surface_state {
    struct surface_state *surface;
    struct wl_resource *resource;
    struct wl_resource *toplevel_resource;
};

struct waylandie_xdg_toplevel_state {
    struct waylandie_xdg_surface_state *xdg_surface;
};

static void xdg_positioner_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void xdg_positioner_set_size(struct wl_client *client, struct wl_resource *resource, int32_t width, int32_t height) {
    (void)client; (void)resource; (void)width; (void)height;
}

static void xdg_positioner_set_anchor_rect(
        struct wl_client *client,
        struct wl_resource *resource,
        int32_t x,
        int32_t y,
        int32_t width,
        int32_t height) {
    (void)client; (void)resource; (void)x; (void)y; (void)width; (void)height;
}

static void xdg_positioner_set_anchor(struct wl_client *client, struct wl_resource *resource, uint32_t anchor) {
    (void)client; (void)resource; (void)anchor;
}

static void xdg_positioner_set_gravity(struct wl_client *client, struct wl_resource *resource, uint32_t gravity) {
    (void)client; (void)resource; (void)gravity;
}

static void xdg_positioner_set_constraint_adjustment(struct wl_client *client, struct wl_resource *resource, uint32_t adjustment) {
    (void)client; (void)resource; (void)adjustment;
}

static void xdg_positioner_set_offset(struct wl_client *client, struct wl_resource *resource, int32_t x, int32_t y) {
    (void)client; (void)resource; (void)x; (void)y;
}

static void xdg_positioner_set_reactive(struct wl_client *client, struct wl_resource *resource) {
    (void)client; (void)resource;
}

static void xdg_positioner_set_parent_size(struct wl_client *client, struct wl_resource *resource, int32_t width, int32_t height) {
    (void)client; (void)resource; (void)width; (void)height;
}

static void xdg_positioner_set_parent_configure(struct wl_client *client, struct wl_resource *resource, uint32_t serial) {
    (void)client; (void)resource; (void)serial;
}

static const struct xdg_positioner_interface xdg_positioner_impl = {
    .destroy = xdg_positioner_destroy,
    .set_size = xdg_positioner_set_size,
    .set_anchor_rect = xdg_positioner_set_anchor_rect,
    .set_anchor = xdg_positioner_set_anchor,
    .set_gravity = xdg_positioner_set_gravity,
    .set_constraint_adjustment = xdg_positioner_set_constraint_adjustment,
    .set_offset = xdg_positioner_set_offset,
    .set_reactive = xdg_positioner_set_reactive,
    .set_parent_size = xdg_positioner_set_parent_size,
    .set_parent_configure = xdg_positioner_set_parent_configure,
};

static void send_xdg_configure(struct waylandie_xdg_surface_state *xdg_surface) {
    if (xdg_surface == NULL
            || xdg_surface->resource == NULL
            || xdg_surface->surface == NULL
            || xdg_surface->surface->server == NULL) {
        return;
    }
    if (xdg_surface->toplevel_resource != NULL) {
        struct wl_array states;
        struct server_state *server = xdg_surface->surface->server;
        int32_t width = server != NULL && server->output_width > 0 ? server->output_width : 2688;
        int32_t height = server != NULL && server->output_height > 0 ? server->output_height : 1216;
        uint32_t *state_value;
        wl_array_init(&states);
        if (server == NULL || !server->android_windows) {
            state_value = wl_array_add(&states, sizeof(*state_value));
            if (state_value != NULL) {
                *state_value = XDG_TOPLEVEL_STATE_FULLSCREEN;
            }
        }
        state_value = wl_array_add(&states, sizeof(*state_value));
        if (state_value != NULL) {
            *state_value = XDG_TOPLEVEL_STATE_ACTIVATED;
        }
        xdg_toplevel_send_configure(xdg_surface->toplevel_resource, width, height, &states);
        wl_array_release(&states);
    }
    uint32_t serial = wl_display_next_serial(xdg_surface->surface->server->display);
    printf("wayland-shm-ahb xdg-configure serial=%u size=%dx%d\n",
            serial,
            xdg_surface->surface->server->output_width,
            xdg_surface->surface->server->output_height);
    fflush(stdout);
    xdg_surface_send_configure(xdg_surface->resource, serial);
}

static void destroy_xdg_toplevel_resource(struct wl_resource *resource) {
    struct waylandie_xdg_toplevel_state *toplevel = wl_resource_get_user_data(resource);
    if (toplevel != NULL && toplevel->xdg_surface != NULL) {
        toplevel->xdg_surface->toplevel_resource = NULL;
    }
    free(toplevel);
}

static void xdg_toplevel_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void xdg_toplevel_set_parent(struct wl_client *client, struct wl_resource *resource, struct wl_resource *parent) {
    (void)client; (void)resource; (void)parent;
}

static void xdg_toplevel_set_title(struct wl_client *client, struct wl_resource *resource, const char *title) {
    (void)client;
    struct waylandie_xdg_toplevel_state *toplevel = wl_resource_get_user_data(resource);
    struct surface_state *surface = toplevel != NULL && toplevel->xdg_surface != NULL
            ? toplevel->xdg_surface->surface
            : NULL;
    if (surface != NULL && title != NULL) {
        snprintf(surface->title, sizeof(surface->title), "%s", title);
    }
}

static void xdg_toplevel_set_app_id(struct wl_client *client, struct wl_resource *resource, const char *app_id) {
    (void)client;
    struct waylandie_xdg_toplevel_state *toplevel = wl_resource_get_user_data(resource);
    struct surface_state *surface = toplevel != NULL && toplevel->xdg_surface != NULL
            ? toplevel->xdg_surface->surface
            : NULL;
    if (surface != NULL && app_id != NULL) {
        snprintf(surface->app_id, sizeof(surface->app_id), "%s", app_id);
    }
}

static void xdg_toplevel_show_window_menu(
        struct wl_client *client,
        struct wl_resource *resource,
        struct wl_resource *seat,
        uint32_t serial,
        int32_t x,
        int32_t y) {
    (void)client; (void)resource; (void)seat; (void)serial; (void)x; (void)y;
}

static void xdg_toplevel_move(struct wl_client *client, struct wl_resource *resource, struct wl_resource *seat, uint32_t serial) {
    (void)client; (void)resource; (void)seat; (void)serial;
}

static void xdg_toplevel_resize(
        struct wl_client *client,
        struct wl_resource *resource,
        struct wl_resource *seat,
        uint32_t serial,
        uint32_t edges) {
    (void)client; (void)resource; (void)seat; (void)serial; (void)edges;
}

static void xdg_toplevel_set_max_size(struct wl_client *client, struct wl_resource *resource, int32_t width, int32_t height) {
    (void)client; (void)resource; (void)width; (void)height;
}

static void xdg_toplevel_set_min_size(struct wl_client *client, struct wl_resource *resource, int32_t width, int32_t height) {
    (void)client; (void)resource; (void)width; (void)height;
}

static void xdg_toplevel_set_maximized(struct wl_client *client, struct wl_resource *resource) {
    (void)client; (void)resource;
}

static void xdg_toplevel_unset_maximized(struct wl_client *client, struct wl_resource *resource) {
    (void)client; (void)resource;
}

static void xdg_toplevel_set_fullscreen(struct wl_client *client, struct wl_resource *resource, struct wl_resource *output) {
    (void)client; (void)resource; (void)output;
}

static void xdg_toplevel_unset_fullscreen(struct wl_client *client, struct wl_resource *resource) {
    (void)client; (void)resource;
}

static void xdg_toplevel_set_minimized(struct wl_client *client, struct wl_resource *resource) {
    (void)client; (void)resource;
}

static const struct xdg_toplevel_interface xdg_toplevel_impl = {
    .destroy = xdg_toplevel_destroy,
    .set_parent = xdg_toplevel_set_parent,
    .set_title = xdg_toplevel_set_title,
    .set_app_id = xdg_toplevel_set_app_id,
    .show_window_menu = xdg_toplevel_show_window_menu,
    .move = xdg_toplevel_move,
    .resize = xdg_toplevel_resize,
    .set_max_size = xdg_toplevel_set_max_size,
    .set_min_size = xdg_toplevel_set_min_size,
    .set_maximized = xdg_toplevel_set_maximized,
    .unset_maximized = xdg_toplevel_unset_maximized,
    .set_fullscreen = xdg_toplevel_set_fullscreen,
    .unset_fullscreen = xdg_toplevel_unset_fullscreen,
    .set_minimized = xdg_toplevel_set_minimized,
};

static void xdg_popup_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void xdg_popup_grab(struct wl_client *client, struct wl_resource *resource, struct wl_resource *seat, uint32_t serial) {
    (void)client; (void)resource; (void)seat; (void)serial;
}

static void xdg_popup_reposition(struct wl_client *client, struct wl_resource *resource, struct wl_resource *positioner, uint32_t token) {
    (void)client; (void)resource; (void)positioner; (void)token;
}

static const struct xdg_popup_interface xdg_popup_impl = {
    .destroy = xdg_popup_destroy,
    .grab = xdg_popup_grab,
    .reposition = xdg_popup_reposition,
};

static void destroy_xdg_surface_resource(struct wl_resource *resource) {
    struct waylandie_xdg_surface_state *xdg_surface = wl_resource_get_user_data(resource);
    free(xdg_surface);
}

static void xdg_surface_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void xdg_surface_get_toplevel(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct waylandie_xdg_surface_state *xdg_surface = wl_resource_get_user_data(resource);
    struct wl_resource *toplevel_resource = wl_resource_create(client, &xdg_toplevel_interface, wl_resource_get_version(resource), id);
    struct waylandie_xdg_toplevel_state *toplevel = calloc(1, sizeof(*toplevel));
    if (toplevel_resource == NULL || toplevel == NULL) {
        wl_client_post_no_memory(client);
        free(toplevel);
        return;
    }
    toplevel->xdg_surface = xdg_surface;
    if (xdg_surface != NULL) {
        xdg_surface->toplevel_resource = toplevel_resource;
    }
    wl_resource_set_implementation(toplevel_resource, &xdg_toplevel_impl, toplevel, destroy_xdg_toplevel_resource);
    send_xdg_configure(xdg_surface);
}

static void xdg_surface_get_popup(
        struct wl_client *client,
        struct wl_resource *resource,
        uint32_t id,
        struct wl_resource *parent,
        struct wl_resource *positioner) {
    (void)parent; (void)positioner;
    struct wl_resource *popup_resource = wl_resource_create(client, &xdg_popup_interface, wl_resource_get_version(resource), id);
    if (popup_resource == NULL) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(popup_resource, &xdg_popup_impl, NULL, NULL);
}

static void xdg_surface_set_window_geometry(
        struct wl_client *client,
        struct wl_resource *resource,
        int32_t x,
        int32_t y,
        int32_t width,
        int32_t height) {
    (void)client; (void)resource; (void)x; (void)y; (void)width; (void)height;
}

static void xdg_surface_ack_configure(struct wl_client *client, struct wl_resource *resource, uint32_t serial) {
    (void)client; (void)resource; (void)serial;
}

static const struct xdg_surface_interface xdg_surface_impl = {
    .destroy = xdg_surface_destroy,
    .get_toplevel = xdg_surface_get_toplevel,
    .get_popup = xdg_surface_get_popup,
    .set_window_geometry = xdg_surface_set_window_geometry,
    .ack_configure = xdg_surface_ack_configure,
};

static void xdg_wm_base_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void xdg_wm_base_create_positioner(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct wl_resource *positioner_resource = wl_resource_create(client, &xdg_positioner_interface, wl_resource_get_version(resource), id);
    if (positioner_resource == NULL) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(positioner_resource, &xdg_positioner_impl, NULL, NULL);
}

static void xdg_wm_base_get_xdg_surface(
        struct wl_client *client,
        struct wl_resource *resource,
        uint32_t id,
        struct wl_resource *surface_resource) {
    struct wl_resource *xdg_surface_resource = wl_resource_create(client, &xdg_surface_interface, wl_resource_get_version(resource), id);
    struct waylandie_xdg_surface_state *xdg_surface = calloc(1, sizeof(*xdg_surface));
    if (xdg_surface_resource == NULL || xdg_surface == NULL) {
        wl_client_post_no_memory(client);
        free(xdg_surface);
        return;
    }
    xdg_surface->surface = wl_resource_get_user_data(surface_resource);
    if (xdg_surface->surface != NULL) {
        xdg_surface->surface->is_xdg_surface = 1;
    }
    printf("wayland-shm-ahb xdg-surface surface=%p\n", (void *)xdg_surface->surface);
    fflush(stdout);
    xdg_surface->resource = xdg_surface_resource;
    wl_resource_set_implementation(xdg_surface_resource, &xdg_surface_impl, xdg_surface, destroy_xdg_surface_resource);
}

static void xdg_wm_base_pong(struct wl_client *client, struct wl_resource *resource, uint32_t serial) {
    (void)client; (void)resource; (void)serial;
}

static const struct xdg_wm_base_interface xdg_wm_base_impl = {
    .destroy = xdg_wm_base_destroy,
    .create_positioner = xdg_wm_base_create_positioner,
    .get_xdg_surface = xdg_wm_base_get_xdg_surface,
    .pong = xdg_wm_base_pong,
};

static void bind_xdg_wm_base(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    (void)data;
    uint32_t bind_version = version > 5 ? 5 : version;
    struct wl_resource *resource = wl_resource_create(client, &xdg_wm_base_interface, bind_version, id);
    if (resource == NULL) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(resource, &xdg_wm_base_impl, NULL, NULL);
}

static void destroy_frame_callback_resource(struct wl_resource *resource) {
    struct frame_callback_state *callback = wl_resource_get_user_data(resource);
    if (callback != NULL) {
        wl_list_remove(&callback->link);
    }
    free(callback);
}

static void destroy_presentation_feedback_resource(struct wl_resource *resource) {
    struct presentation_feedback_state *feedback = wl_resource_get_user_data(resource);
    if (feedback != NULL) {
        wl_list_remove(&feedback->link);
    }
    free(feedback);
}

static void send_surface_frame_callbacks(struct surface_state *surface) {
    if (surface == NULL) {
        return;
    }
    struct server_state *server = surface->server;
    if (server != NULL
            && server->frame_interval_ms > 0.0
            && server->last_frame_callback_commit != server->commit_count) {
        double now = now_ms();
        if (server->next_frame_callback_ms <= 0.0) {
            server->next_frame_callback_ms = now;
        }
        double target_ms = server->next_frame_callback_ms;
        if (now < target_ms) {
            sleep_ms_precise(target_ms - now);
            now = now_ms();
        }
        target_ms += server->frame_interval_ms;
        while (target_ms <= now) {
            target_ms += server->frame_interval_ms;
        }
        server->next_frame_callback_ms = target_ms;
        server->last_frame_callback_commit = server->commit_count;
    }
    struct frame_callback_state *callback;
    struct frame_callback_state *next;
    wl_list_for_each_safe(callback, next, &surface->frame_callbacks, link) {
        wl_callback_send_done(callback->resource, now_msec32());
        wl_resource_destroy(callback->resource);
    }
}

static void send_surface_presentation_feedback(struct surface_state *surface, int presented) {
    if (surface == NULL) {
        return;
    }
    struct presentation_feedback_state *feedback;
    struct presentation_feedback_state *next;
    wl_list_for_each_safe(feedback, next, &surface->presentation_feedbacks, link) {
        if (presented) {
            struct timespec ts;
            clock_gettime(CLOCK_MONOTONIC, &ts);
            uint64_t sec = (uint64_t)ts.tv_sec;
            uint64_t seq = surface->server != NULL && surface->server->commit_count >= 0
                    ? (uint64_t)surface->server->commit_count
                    : 0;
            uint32_t refresh_nsec = surface->server != NULL && surface->server->presentation_refresh_nsec > 0
                    ? surface->server->presentation_refresh_nsec
                    : 8333333U;
            wp_presentation_feedback_send_presented(
                    feedback->resource,
                    (uint32_t)(sec >> 32U),
                    (uint32_t)(sec & 0xffffffffU),
                    (uint32_t)ts.tv_nsec,
                    refresh_nsec,
                    (uint32_t)(seq >> 32U),
                    (uint32_t)(seq & 0xffffffffU),
                    WP_PRESENTATION_FEEDBACK_KIND_VSYNC);
        } else {
            wp_presentation_feedback_send_discarded(feedback->resource);
        }
        wl_resource_destroy(feedback->resource);
    }
}

static void presentation_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void presentation_feedback(
        struct wl_client *client,
        struct wl_resource *resource,
        struct wl_resource *surface_resource,
        uint32_t callback) {
    (void)resource;
    struct surface_state *surface = wl_resource_get_user_data(surface_resource);
    struct wl_resource *feedback_resource = wl_resource_create(
            client,
            &wp_presentation_feedback_interface,
            1,
            callback);
    struct presentation_feedback_state *feedback = calloc(1, sizeof(*feedback));
    if (feedback_resource == NULL || feedback == NULL || surface == NULL) {
        wl_client_post_no_memory(client);
        if (feedback_resource != NULL) {
            wl_resource_destroy(feedback_resource);
        }
        free(feedback);
        return;
    }
    feedback->resource = feedback_resource;
    wl_list_insert(surface->presentation_feedbacks.prev, &feedback->link);
    wl_resource_set_implementation(
            feedback_resource,
            NULL,
            feedback,
            destroy_presentation_feedback_resource);
}

static const struct wp_presentation_interface presentation_impl = {
    .destroy = presentation_destroy,
    .feedback = presentation_feedback,
};

static void bind_presentation(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    (void)data;
    uint32_t bind_version = version > 2 ? 2 : version;
    struct wl_resource *resource = wl_resource_create(client, &wp_presentation_interface, bind_version, id);
    if (resource == NULL) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(resource, &presentation_impl, NULL, NULL);
    wp_presentation_send_clock_id(resource, (uint32_t)CLOCK_MONOTONIC);
}

static void subsurface_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void subsurface_set_position(struct wl_client *client, struct wl_resource *resource, int32_t x, int32_t y) {
    (void)client; (void)resource; (void)x; (void)y;
}

static void subsurface_place_above(struct wl_client *client, struct wl_resource *resource, struct wl_resource *sibling) {
    (void)client; (void)resource; (void)sibling;
}

static void subsurface_place_below(struct wl_client *client, struct wl_resource *resource, struct wl_resource *sibling) {
    (void)client; (void)resource; (void)sibling;
}

static void subsurface_set_sync(struct wl_client *client, struct wl_resource *resource) {
    (void)client; (void)resource;
}

static void subsurface_set_desync(struct wl_client *client, struct wl_resource *resource) {
    (void)client; (void)resource;
}

static const struct wl_subsurface_interface subsurface_impl = {
    .destroy = subsurface_destroy,
    .set_position = subsurface_set_position,
    .place_above = subsurface_place_above,
    .place_below = subsurface_place_below,
    .set_sync = subsurface_set_sync,
    .set_desync = subsurface_set_desync,
};

static void subcompositor_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void subcompositor_get_subsurface(
        struct wl_client *client,
        struct wl_resource *resource,
        uint32_t id,
        struct wl_resource *surface,
        struct wl_resource *parent) {
    (void)resource;
    struct surface_state *surface_state = wl_resource_get_user_data(surface);
    struct surface_state *parent_state = wl_resource_get_user_data(parent);
    struct wl_resource *subsurface_resource = wl_resource_create(
            client,
            &wl_subsurface_interface,
            wl_resource_get_version(resource),
            id);
    if (subsurface_resource == NULL) {
        wl_client_post_no_memory(client);
        return;
    }
    if (surface_state != NULL) {
        if (surface_state->subsurface_linked) {
            wl_list_remove(&surface_state->subsurface_link);
            wl_list_init(&surface_state->subsurface_link);
            surface_state->subsurface_linked = 0;
        }
        surface_state->is_subsurface = 1;
        surface_state->subsurface_parent = parent_state;
        if (parent_state != NULL) {
            wl_list_insert(parent_state->subsurface_children.prev, &surface_state->subsurface_link);
            surface_state->subsurface_linked = 1;
        }
    }
    printf("wayland-shm-ahb subsurface child=%p parent=%p parent-xdg=%d\n",
            (void *)surface_state,
            (void *)parent_state,
            parent_state != NULL ? parent_state->is_xdg_surface : 0);
    fflush(stdout);
    wl_resource_set_implementation(subsurface_resource, &subsurface_impl, surface_state, NULL);
}

static const struct wl_subcompositor_interface subcompositor_impl = {
    .destroy = subcompositor_destroy,
    .get_subsurface = subcompositor_get_subsurface,
};

static void bind_subcompositor(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    (void)data;
    uint32_t bind_version = version > 1 ? 1 : version;
    struct wl_resource *resource = wl_resource_create(client, &wl_subcompositor_interface, bind_version, id);
    if (resource == NULL) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(resource, &subcompositor_impl, NULL, NULL);
}

static void viewport_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void viewport_set_source(
        struct wl_client *client,
        struct wl_resource *resource,
        wl_fixed_t x,
        wl_fixed_t y,
        wl_fixed_t width,
        wl_fixed_t height) {
    (void)client; (void)resource; (void)x; (void)y; (void)width; (void)height;
}

static void viewport_set_destination(
        struct wl_client *client,
        struct wl_resource *resource,
        int32_t width,
        int32_t height) {
    (void)client; (void)resource; (void)width; (void)height;
}

static const struct wp_viewport_interface viewport_impl = {
    .destroy = viewport_destroy,
    .set_source = viewport_set_source,
    .set_destination = viewport_set_destination,
};

static void viewporter_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void viewporter_get_viewport(
        struct wl_client *client,
        struct wl_resource *resource,
        uint32_t id,
        struct wl_resource *surface) {
    (void)surface;
    struct wl_resource *viewport_resource = wl_resource_create(
            client,
            &wp_viewport_interface,
            wl_resource_get_version(resource),
            id);
    if (viewport_resource == NULL) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(viewport_resource, &viewport_impl, NULL, NULL);
}

static const struct wp_viewporter_interface viewporter_impl = {
    .destroy = viewporter_destroy,
    .get_viewport = viewporter_get_viewport,
};

static void bind_viewporter(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    (void)data;
    uint32_t bind_version = version > 1 ? 1 : version;
    struct wl_resource *resource = wl_resource_create(client, &wp_viewporter_interface, bind_version, id);
    if (resource == NULL) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(resource, &viewporter_impl, NULL, NULL);
}

static void relative_pointer_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static const struct zwp_relative_pointer_v1_interface relative_pointer_impl = {
    .destroy = relative_pointer_destroy,
};

static void relative_pointer_manager_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void relative_pointer_manager_get_relative_pointer(
        struct wl_client *client,
        struct wl_resource *resource,
        uint32_t id,
        struct wl_resource *pointer) {
    (void)pointer;
    struct wl_resource *relative_pointer_resource = wl_resource_create(
            client,
            &zwp_relative_pointer_v1_interface,
            wl_resource_get_version(resource),
            id);
    if (relative_pointer_resource == NULL) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(relative_pointer_resource, &relative_pointer_impl, NULL, NULL);
}

static const struct zwp_relative_pointer_manager_v1_interface relative_pointer_manager_impl = {
    .destroy = relative_pointer_manager_destroy,
    .get_relative_pointer = relative_pointer_manager_get_relative_pointer,
};

static void bind_relative_pointer_manager(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    (void)data;
    uint32_t bind_version = version > 1 ? 1 : version;
    struct wl_resource *resource = wl_resource_create(client, &zwp_relative_pointer_manager_v1_interface, bind_version, id);
    if (resource == NULL) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(resource, &relative_pointer_manager_impl, NULL, NULL);
}

static void locked_pointer_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void locked_pointer_set_cursor_position_hint(
        struct wl_client *client,
        struct wl_resource *resource,
        wl_fixed_t surface_x,
        wl_fixed_t surface_y) {
    (void)client; (void)resource; (void)surface_x; (void)surface_y;
}

static void locked_pointer_set_region(struct wl_client *client, struct wl_resource *resource, struct wl_resource *region) {
    (void)client; (void)resource; (void)region;
}

static const struct zwp_locked_pointer_v1_interface locked_pointer_impl = {
    .destroy = locked_pointer_destroy,
    .set_cursor_position_hint = locked_pointer_set_cursor_position_hint,
    .set_region = locked_pointer_set_region,
};

static void confined_pointer_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void confined_pointer_set_region(struct wl_client *client, struct wl_resource *resource, struct wl_resource *region) {
    (void)client; (void)resource; (void)region;
}

static const struct zwp_confined_pointer_v1_interface confined_pointer_impl = {
    .destroy = confined_pointer_destroy,
    .set_region = confined_pointer_set_region,
};

static void pointer_constraints_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void pointer_constraints_lock_pointer(
        struct wl_client *client,
        struct wl_resource *resource,
        uint32_t id,
        struct wl_resource *surface,
        struct wl_resource *pointer,
        struct wl_resource *region,
        uint32_t lifetime) {
    (void)resource; (void)surface; (void)pointer; (void)region; (void)lifetime;
    struct wl_resource *locked_pointer_resource = wl_resource_create(
            client,
            &zwp_locked_pointer_v1_interface,
            1,
            id);
    if (locked_pointer_resource == NULL) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(locked_pointer_resource, &locked_pointer_impl, NULL, NULL);
    zwp_locked_pointer_v1_send_locked(locked_pointer_resource);
}

static void pointer_constraints_confine_pointer(
        struct wl_client *client,
        struct wl_resource *resource,
        uint32_t id,
        struct wl_resource *surface,
        struct wl_resource *pointer,
        struct wl_resource *region,
        uint32_t lifetime) {
    (void)resource; (void)surface; (void)pointer; (void)region; (void)lifetime;
    struct wl_resource *confined_pointer_resource = wl_resource_create(
            client,
            &zwp_confined_pointer_v1_interface,
            1,
            id);
    if (confined_pointer_resource == NULL) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(confined_pointer_resource, &confined_pointer_impl, NULL, NULL);
    zwp_confined_pointer_v1_send_confined(confined_pointer_resource);
}

static const struct zwp_pointer_constraints_v1_interface pointer_constraints_impl = {
    .destroy = pointer_constraints_destroy,
    .lock_pointer = pointer_constraints_lock_pointer,
    .confine_pointer = pointer_constraints_confine_pointer,
};

static void bind_pointer_constraints(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    (void)data;
    uint32_t bind_version = version > 1 ? 1 : version;
    struct wl_resource *resource = wl_resource_create(client, &zwp_pointer_constraints_v1_interface, bind_version, id);
    if (resource == NULL) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(resource, &pointer_constraints_impl, NULL, NULL);
}

static void destroy_input_resource(struct wl_resource *resource) {
    struct input_resource_state *input = wl_resource_get_user_data(resource);
    if (input != NULL) {
        wl_list_remove(&input->link);
    }
    free(input);
}

static uint32_t next_input_serial(struct server_state *state) {
    if (state == NULL) {
        return 0;
    }
    state->input_serial++;
    if (state->input_serial == 0) {
        state->input_serial = 1;
    }
    return state->input_serial;
}

static int resource_same_client(struct wl_resource *a, struct wl_resource *b) {
    return a != NULL
            && b != NULL
            && wl_resource_get_client(a) == wl_resource_get_client(b);
}

static int surface_is_displayable(struct surface_state *surface) {
    for (int depth = 0; surface != NULL && depth < 16; depth++) {
        if (surface->is_xdg_surface) {
            return 1;
        }
        surface = surface->subsurface_parent;
    }
    return 0;
}

static int64_t buffer_area(struct shm_buffer_state *buffer) {
    if (buffer == NULL || buffer->width <= 0 || buffer->height <= 0) {
        return 0;
    }
    return (int64_t)buffer->width * (int64_t)buffer->height;
}

static int buffer_is_primary_for_surface(struct surface_state *surface, struct shm_buffer_state *buffer) {
    int64_t area = buffer_area(buffer);
    if (area <= 0) {
        return 0;
    }
    if (surface == NULL || surface->server == NULL
            || surface->server->output_width <= 0
            || surface->server->output_height <= 0) {
        return 1;
    }
    if (surface->server->accept_scaled_primary
            && surface->is_xdg_surface
            && !surface->is_subsurface
            && buffer->kind == BUFFER_KIND_DMABUF) {
        return 1;
    }
    /* Accept any window >= 200x200 as primary. The old 80% threshold
     * (width*5 >= output_width*4) skipped the Mono installer dialog
     * (~400x300), the taskbar (1920x128), and game launcher windows.
     * With 200px minimum, all these windows are displayed. The first
     * primary window to commit gets presented; subsequent smaller
     * windows are still skipped to avoid flickering. */
    if (buffer->width < 200 || buffer->height < 200) {
        return 0;
    }
    return 1;
}

static struct surface_state *find_presentable_subsurface(struct surface_state *surface) {
    if (surface == NULL) {
        return NULL;
    }

    struct surface_state *child;
    struct surface_state *best = NULL;
    int64_t best_area = 0;
    wl_list_for_each(child, &surface->subsurface_children, subsurface_link) {
        if (child->has_pending_attach && child->pending_buffer != NULL) {
            int64_t area = buffer_area(child->pending_buffer);
            if (best == NULL || area > best_area) {
                best = child;
                best_area = area;
            }
        }

        struct surface_state *nested = find_presentable_subsurface(child);
        if (nested != NULL) {
            int64_t area = buffer_area(nested->pending_buffer);
            if (best == NULL || area > best_area) {
                best = nested;
                best_area = area;
            }
        }
    }
    return best;
}

static void send_surface_focus(
        struct surface_state *surface,
        struct wl_resource *surface_resource,
        int32_t surface_width,
        int32_t surface_height) {
    if (surface == NULL || surface->server == NULL || surface_resource == NULL) {
        return;
    }
    struct server_state *state = surface->server;
    if (surface_width <= 0) {
        surface_width = state->output_width;
    }
    if (surface_height <= 0) {
        surface_height = state->output_height;
    }
    state->focused_surface_width = surface_width > 0 ? surface_width : 1;
    state->focused_surface_height = surface_height > 0 ? surface_height : 1;
    if (state->pointer_x > (double)(state->focused_surface_width - 1)) {
        state->pointer_x = (double)(state->focused_surface_width - 1);
    }
    if (state->pointer_y > (double)(state->focused_surface_height - 1)) {
        state->pointer_y = (double)(state->focused_surface_height - 1);
    }
    if (state->focused_surface == surface_resource) {
        return;
    }
    struct wl_resource *old_focus = state->focused_surface;
    state->focused_surface = surface_resource;
    state->focused_client = wl_resource_get_client(surface_resource);
    int pointers_total = input_resource_count(&state->pointer_resources);
    int keyboards_total = input_resource_count(&state->keyboard_resources);
    int touches_total = input_resource_count(&state->touch_resources);
    printf("wayland-shm-ahb input-focus surface=%p size=%dx%d old=%p new=%p xdg=%d subsurface=%d pointers=%d keyboards=%d touches=%d pointer_xy=(%.1f,%.1f)\n",
            (void *)surface,
            state->focused_surface_width,
            state->focused_surface_height,
            (void *)old_focus,
            (void *)surface_resource,
            surface->is_xdg_surface,
            surface->is_subsurface,
            pointers_total, keyboards_total, touches_total,
            state->pointer_x, state->pointer_y);
    fflush(stdout);
    input_debug_log(
            "focus surface=%p resource=%p xdg=%d subsurface=%d size=%dx%d pointers=%d keyboards=%d touches=%d",
            (void *)surface,
            (void *)surface_resource,
            surface->is_xdg_surface,
            surface->is_subsurface,
            state->focused_surface_width,
            state->focused_surface_height,
            pointers_total, keyboards_total, touches_total);

    // For each existing pointer/keyboard that belongs to the SAME client as
    // the newly-focused surface, send an enter event. Count them so we can
    // verify in the trace whether any enters were actually sent.
    int pointer_enters_sent = 0;
    int keyboard_enters_sent = 0;

    struct input_resource_state *keyboard;
    wl_list_for_each(keyboard, &state->keyboard_resources, link) {
        if (!resource_same_client(keyboard->resource, surface_resource)) {
            continue;
        }
        int keymap_fd = open("/dev/null", O_RDONLY | O_CLOEXEC);
        if (keymap_fd >= 0) {
            wl_keyboard_send_keymap(
                    keyboard->resource,
                    WL_KEYBOARD_KEYMAP_FORMAT_NO_KEYMAP,
                    keymap_fd,
                    0);
            close(keymap_fd);
        }
        struct wl_array keys;
        wl_array_init(&keys);
        uint32_t kb_serial = next_input_serial(state);
        wl_keyboard_send_enter(
                keyboard->resource,
                kb_serial,
                surface_resource,
                &keys);
        wl_array_release(&keys);
        keyboard_enters_sent++;
        printf("wayland-shm-ahb keyboard-enter sent serial=%u kb=%p focused=%p\n",
                kb_serial, (void *)keyboard->resource, (void *)surface_resource);
        fflush(stdout);
    }

    struct input_resource_state *pointer;
    wl_list_for_each(pointer, &state->pointer_resources, link) {
        if (!resource_same_client(pointer->resource, surface_resource)) {
            continue;
        }
        uint32_t ptr_serial = next_input_serial(state);
        wl_pointer_send_enter(
                pointer->resource,
                ptr_serial,
                surface_resource,
                wl_fixed_from_double(state->pointer_x),
                wl_fixed_from_double(state->pointer_y));
        maybe_send_pointer_frame(pointer->resource);
        pointer_enters_sent++;
        printf("wayland-shm-ahb pointer-enter sent serial=%u ptr=%p focused=%p x=%.1f y=%.1f\n",
                ptr_serial, (void *)pointer->resource, (void *)surface_resource,
                state->pointer_x, state->pointer_y);
        fflush(stdout);
    }
    printf("wayland-shm-ahb input-focus enters-sent pointers=%d keyboards=%d focused=%p\n",
            pointer_enters_sent, keyboard_enters_sent, (void *)surface_resource);
    fflush(stdout);
}

static void send_focus_for_presentable(
        struct surface_state *fallback_surface,
        struct surface_state *presentable,
        struct shm_buffer_state *buffer) {
    (void)presentable;
    if (fallback_surface == NULL || fallback_surface->server == NULL) {
        return;
    }
    int32_t focus_width = fallback_surface->server->output_width;
    int32_t focus_height = fallback_surface->server->output_height;
    if (buffer != NULL && buffer->width > 0 && buffer->height > 0) {
        focus_width = buffer->width;
        focus_height = buffer->height;
    }
    send_surface_focus(
            fallback_surface,
            fallback_surface->resource,
            focus_width,
            focus_height);
}

static const char *find_input_token(const char *line, const char *key) {
    size_t key_len = strlen(key);
    const char *p = line;
    while (p != NULL && *p != '\0') {
        while (*p == ' ' || *p == '\t') {
            p++;
        }
        if (strncmp(p, key, key_len) == 0 && p[key_len] == '=') {
            return p + key_len + 1;
        }
        while (*p != '\0' && *p != ' ' && *p != '\t') {
            p++;
        }
    }
    return NULL;
}

static int input_token_string(const char *line, const char *key, char *out, size_t out_size) {
    const char *value = find_input_token(line, key);
    if (value == NULL || out_size == 0) {
        return 0;
    }
    size_t i = 0;
    while (value[i] != '\0' && value[i] != ' ' && value[i] != '\t' && i + 1U < out_size) {
        out[i] = value[i];
        i++;
    }
    out[i] = '\0';
    return i > 0;
}

static int input_token_double(const char *line, const char *key, double *out) {
    const char *value = find_input_token(line, key);
    char *end = NULL;
    if (value == NULL) {
        return 0;
    }
    double parsed = strtod(value, &end);
    if (end == value) {
        return 0;
    }
    *out = parsed;
    return 1;
}

static int input_token_int(const char *line, const char *key, int *out) {
    const char *value = find_input_token(line, key);
    char *end = NULL;
    if (value == NULL) {
        return 0;
    }
    long parsed = strtol(value, &end, 0);
    if (end == value) {
        return 0;
    }
    *out = (int)parsed;
    return 1;
}

static size_t clipboard_max_bytes(void) {
    const char *max_env = getenv("WAYLANDIE_ANDROID_CLIPBOARD_MAX_BYTES");
    long parsed = max_env == NULL || max_env[0] == '\0' ? 16384L : strtol(max_env, NULL, 10);
    if (parsed < 256L) {
        parsed = 256L;
    }
    if (parsed > 262144L) {
        parsed = 262144L;
    }
    return (size_t)parsed;
}

static int send_input_stream_line(struct server_state *state, const char *line) {
    if (state->input_sock < 0 || line == NULL) {
        return 0;
    }
    size_t len = strlen(line);
    ssize_t sent = send(state->input_sock, line, len, MSG_NOSIGNAL);
    if (sent < 0 || (size_t)sent != len) {
        input_debug_log("input-stream-send fail errno=%d", errno);
        return 0;
    }
    sent = send(state->input_sock, "\n", 1U, MSG_NOSIGNAL);
    return sent == 1;
}

static char *hex_encode_bytes(const unsigned char *bytes, size_t len) {
    static const char alphabet[] = "0123456789abcdef";
    char *hex = calloc((len * 2U) + 1U, 1U);
    if (hex == NULL) {
        return NULL;
    }
    for (size_t i = 0; i < len; i++) {
        hex[i * 2U] = alphabet[bytes[i] >> 4U];
        hex[(i * 2U) + 1U] = alphabet[bytes[i] & 0x0fU];
    }
    return hex;
}

static void send_clipboard_status(
        struct server_state *state,
        const char *action,
        const char *selection,
        const char *reason) {
    char line[256];
    snprintf(
            line,
            sizeof(line),
            "input-v1 kind=clipboard action=%s selection=%s reason=%s",
            action == NULL ? "fail" : action,
            selection == NULL || selection[0] == '\0' ? "auto" : selection,
            reason == NULL || reason[0] == '\0' ? "none" : reason);
    send_input_stream_line(state, line);
}

static int send_clipboard_text(
        struct server_state *state,
        const char *selection,
        const unsigned char *bytes,
        size_t len) {
    char *hex = hex_encode_bytes(bytes, len);
    if (hex == NULL) {
        send_clipboard_status(state, "fail", selection, "oom");
        return 0;
    }
    size_t line_len = strlen(hex) + 128U;
    char *line = calloc(line_len, 1U);
    if (line == NULL) {
        free(hex);
        send_clipboard_status(state, "fail", selection, "oom");
        return 0;
    }
    snprintf(
            line,
            line_len,
            "input-v1 kind=clipboard action=set selection=%s bytes=%zu text_hex=%s",
            selection == NULL || selection[0] == '\0' ? "auto" : selection,
            len,
            hex);
    int ok = send_input_stream_line(state, line);
    free(line);
    free(hex);
    return ok;
}

#ifndef WAYLANDIE_NO_XTEST
static char *xtest_read_selection_target(
        struct server_state *state,
        Atom selection,
        Atom target,
        const char *selection_name,
        size_t max_bytes,
        size_t *out_len) {
    if (!xtest_ensure_display(state) || target == None || selection == None) {
        return NULL;
    }
    Atom property = state->xtest_clipboard_property_atom;
    XDeleteProperty(state->xtest_display, state->xtest_window, property);
    XConvertSelection(
            state->xtest_display,
            selection,
            target,
            property,
            state->xtest_window,
            CurrentTime);
    XFlush(state->xtest_display);

    double start = now_ms();
    while ((now_ms() - start) < 250.0) {
        while (XPending(state->xtest_display) > 0) {
            XEvent event;
            XNextEvent(state->xtest_display, &event);
            if (event.type != SelectionNotify
                    || event.xselection.requestor != state->xtest_window
                    || event.xselection.selection != selection) {
                continue;
            }
            if (event.xselection.property == None) {
                input_debug_log(
                        "clipboard selection=%s target=%lu empty",
                        selection_name,
                        (unsigned long)target);
                return NULL;
            }
            Atom actual_type = None;
            int actual_format = 0;
            unsigned long nitems = 0;
            unsigned long bytes_after = 0;
            unsigned char *data = NULL;
            long max_longs = (long)((max_bytes + 3U) / 4U);
            int result = XGetWindowProperty(
                    state->xtest_display,
                    state->xtest_window,
                    property,
                    0L,
                    max_longs,
                    True,
                    AnyPropertyType,
                    &actual_type,
                    &actual_format,
                    &nitems,
                    &bytes_after,
                    &data);
            if (result != Success || data == NULL || actual_format != 8 || nitems == 0) {
                if (data != NULL) {
                    XFree(data);
                }
                input_debug_log(
                        "clipboard selection=%s target=%lu bad-result result=%d format=%d items=%lu",
                        selection_name,
                        (unsigned long)target,
                        result,
                        actual_format,
                        nitems);
                return NULL;
            }
            size_t copy_len = (size_t)nitems;
            if (copy_len > max_bytes) {
                copy_len = max_bytes;
            }
            char *copy = calloc(copy_len + 1U, 1U);
            if (copy != NULL) {
                memcpy(copy, data, copy_len);
                if (out_len != NULL) {
                    *out_len = copy_len;
                }
            }
            XFree(data);
            input_debug_log(
                    "clipboard selection=%s target=%lu bytes=%zu after=%lu",
                    selection_name,
                    (unsigned long)target,
                    copy_len,
                    bytes_after);
            return copy;
        }
        sleep_ms_precise(2.0);
    }
    input_debug_log("clipboard selection=%s timeout", selection_name);
    return NULL;
}
#else
static char * xtest_read_selection_target(
        struct server_state *state,
        Atom selection,
        Atom target,
        const char *selection_name,
        size_t max_bytes,
        size_t *out_len) { return NULL; }
#endif


#ifndef WAYLANDIE_NO_XTEST
static char *xtest_read_selection_text(
        struct server_state *state,
        Atom selection,
        const char *selection_name,
        size_t max_bytes,
        size_t *out_len) {
    Atom targets[3] = {
        state->xtest_utf8_atom,
        XA_STRING,
        state->xtest_text_atom,
    };
    for (size_t i = 0; i < 3U; i++) {
        size_t len = 0;
        char *text = xtest_read_selection_target(
                state,
                selection,
                targets[i],
                selection_name,
                max_bytes,
                &len);
        if (text != NULL && len > 0 && text[0] != '\0') {
            if (out_len != NULL) {
                *out_len = len;
            }
            return text;
        }
        free(text);
    }
    return NULL;
}
#else
static char * xtest_read_selection_text(
        struct server_state *state,
        Atom selection,
        const char *selection_name,
        size_t max_bytes,
        size_t *out_len) { return NULL; }
#endif


#ifndef WAYLANDIE_NO_XTEST
static char *xtest_read_clipboard_auto(
        struct server_state *state,
        const char *requested_selection,
        int prefer_clipboard,
        char *used_selection,
        size_t used_selection_size,
        size_t *out_len) {
    if (used_selection != NULL && used_selection_size > 0) {
        used_selection[0] = '\0';
    }
    if (!xtest_ensure_display(state)) {
        return NULL;
    }
    size_t max_bytes = clipboard_max_bytes();
    if (requested_selection != NULL && strcmp(requested_selection, "clipboard") == 0) {
        if (used_selection != NULL) {
            snprintf(used_selection, used_selection_size, "clipboard");
        }
        return xtest_read_selection_text(
                state,
                state->xtest_clipboard_atom,
                "clipboard",
                max_bytes,
                out_len);
    }
    if (requested_selection != NULL && strcmp(requested_selection, "primary") == 0) {
        if (used_selection != NULL) {
            snprintf(used_selection, used_selection_size, "primary");
        }
        return xtest_read_selection_text(
                state,
                state->xtest_primary_atom,
                "primary",
                max_bytes,
                out_len);
    }

    if (prefer_clipboard) {
        if (used_selection != NULL) {
            snprintf(used_selection, used_selection_size, "clipboard");
        }
        char *text = xtest_read_selection_text(
                state,
                state->xtest_clipboard_atom,
                "clipboard",
                max_bytes,
                out_len);
        if (text != NULL) {
            return text;
        }
        if (used_selection != NULL) {
            snprintf(used_selection, used_selection_size, "primary");
        }
        return xtest_read_selection_text(
                state,
                state->xtest_primary_atom,
                "primary",
                max_bytes,
                out_len);
    }

    if (used_selection != NULL) {
        snprintf(used_selection, used_selection_size, "primary");
    }
    char *text = xtest_read_selection_text(
            state,
            state->xtest_primary_atom,
            "primary",
            max_bytes,
            out_len);
    if (text != NULL) {
        return text;
    }
    if (used_selection != NULL) {
        snprintf(used_selection, used_selection_size, "clipboard");
    }
    return xtest_read_selection_text(
            state,
            state->xtest_clipboard_atom,
            "clipboard",
            max_bytes,
            out_len);
}
#else
static char * xtest_read_clipboard_auto(
        struct server_state *state,
        const char *requested_selection,
        int prefer_clipboard,
        char *used_selection,
        size_t used_selection_size,
        size_t *out_len) { return NULL; }
#endif


static void handle_clipboard_request(struct server_state *state, const char *line) {
    char selection[32] = "auto";
    char copy[8] = "0";
    input_token_string(line, "selection", selection, sizeof(selection));
    input_token_string(line, "copy", copy, sizeof(copy));
    if (!state->xtest_enabled) {
        send_clipboard_status(state, "fail", selection, "xtest-disabled");
        return;
    }
    int should_copy = strcmp(copy, "1") == 0 || strcmp(copy, "true") == 0;
    if (should_copy) {
        xtest_copy_shortcut(state);
        sleep_ms_precise(90.0);
    }
    char used_selection[32] = "auto";
    size_t len = 0;
    char *text = xtest_read_clipboard_auto(
            state,
            selection,
            should_copy,
            used_selection,
            sizeof(used_selection),
            &len);
    if (text == NULL || len == 0 || text[0] == '\0') {
        free(text);
        send_clipboard_status(state, "empty", used_selection, "no-selection");
        return;
    }
    send_clipboard_text(state, used_selection, (const unsigned char *)text, len);
    free(text);
}

static void clamp_pointer_to_output(struct server_state *state, double *x, double *y) {
    int clamp_width = state->focused_surface_width > 0
            ? state->focused_surface_width
            : state->output_width;
    int clamp_height = state->focused_surface_height > 0
            ? state->focused_surface_height
            : state->output_height;
    double max_x = clamp_width > 0 ? (double)(clamp_width - 1) : 0.0;
    double max_y = clamp_height > 0 ? (double)(clamp_height - 1) : 0.0;
    if (*x < 0.0) {
        *x = 0.0;
    } else if (*x > max_x) {
        *x = max_x;
    }
    if (*y < 0.0) {
        *y = 0.0;
    } else if (*y > max_y) {
        *y = max_y;
    }
}

static void map_input_to_focused_surface(
        struct server_state *state,
        double input_width,
        double input_height,
        double *x,
        double *y) {
    if (input_width <= 0.0) {
        input_width = state->output_width > 0 ? (double)state->output_width : 1.0;
    }
    if (input_height <= 0.0) {
        input_height = state->output_height > 0 ? (double)state->output_height : 1.0;
    }
    double target_width = state->focused_surface_width > 0
            ? (double)state->focused_surface_width
            : (state->output_width > 0 ? (double)state->output_width : input_width);
    double target_height = state->focused_surface_height > 0
            ? (double)state->focused_surface_height
            : (state->output_height > 0 ? (double)state->output_height : input_height);
    *x = (*x / input_width) * target_width;
    *y = (*y / input_height) * target_height;
}

static void maybe_send_pointer_frame(struct wl_resource *resource) {
    if (wl_resource_get_version(resource) >= WL_POINTER_FRAME_SINCE_VERSION) {
        wl_pointer_send_frame(resource);
    }
}

static void emit_pointer_enter_if_needed(struct server_state *state, struct wl_resource *pointer_resource) {
    if (state->focused_client == NULL || wl_resource_get_client(pointer_resource) != state->focused_client) {
        printf("wayland-shm-ahb pointer-enter skip focused=%p client=%p ptr=%p same-client=0 pointers=%d\n",
                (void *)state->focused_surface,
                (void *)pointer_resource,
                input_resource_count(&state->pointer_resources));
        fflush(stdout);
        return;
    }
    uint32_t serial = next_input_serial(state);
    wl_pointer_send_enter(
            pointer_resource,
            serial,
            state->focused_surface,
            wl_fixed_from_double(state->pointer_x),
            wl_fixed_from_double(state->pointer_y));
    maybe_send_pointer_frame(pointer_resource);
    printf("wayland-shm-ahb pointer-enter sent serial=%u focused=%p ptr=%p x=%.1f y=%.1f pointers=%d\n",
            serial,
            (void *)state->focused_surface,
            (void *)pointer_resource,
            state->pointer_x,
            state->pointer_y,
            input_resource_count(&state->pointer_resources));
    fflush(stdout);
}

static void emit_pointer_motion(struct server_state *state, double x, double y, uint32_t time_ms) {
    int total_pointers = input_resource_count(&state->pointer_resources);
    if (state->focused_client == NULL) {
        printf("wayland-shm-ahb pointer-motion drop=no-focus-client x=%.1f y=%.1f pointers=%d\n",
                x, y, total_pointers);
        fflush(stdout);
        return;
    }
    clamp_pointer_to_output(state, &x, &y);
    state->pointer_x = x;
    state->pointer_y = y;
    int emitted = 0;
    int skipped = 0;
    struct input_resource_state *pointer;
    wl_list_for_each(pointer, &state->pointer_resources, link) {
        if (!resource_same_client(pointer->resource, state->focused_surface)) {
            skipped++;
            continue;
        }
        wl_pointer_send_motion(
                pointer->resource,
                time_ms,
                wl_fixed_from_double(x),
                wl_fixed_from_double(y));
        maybe_send_pointer_frame(pointer->resource);
        emitted++;
    }
    // Always log pointer motion (no cap) — needed to diagnose click-vs-drag
    printf("wayland-shm-ahb pointer-motion x=%.1f y=%.1f emitted=%d skipped=%d total=%d focused=%p time=%u\n",
           x, y, emitted, skipped, total_pointers,
           (void *)state->focused_surface, time_ms);
    fflush(stdout);
    /* ALSO log to wayland-input.log for reliable diagnostics (bridge stdout
     * capture has been unreliable in some traces). Matches the pointer-button
     * log line so motion and button events can be cross-referenced in the
     * same file. */
    input_debug_log("pointer-motion x=%.1f y=%.1f emitted=%d skipped=%d total=%d focused=%p client=%p time=%u",
           x, y, emitted, skipped, total_pointers,
           (void *)state->focused_surface, (void *)state->focused_client, time_ms);
}

static void emit_pointer_button(struct server_state *state, const char *button_state, uint32_t time_ms) {
    int total_pointers = input_resource_count(&state->pointer_resources);
    /* Click-through fix: use focused_surface for the NULL check and the
     * same-client filter, CONSISTENT with emit_pointer_motion().
     *
     * The cursor-focus-fix (cd36fb0) introduced focused_client and used it
     * for both the NULL check AND the filter in emit_pointer_button, while
     * emit_pointer_motion was left using focused_surface for the filter.
     * This inconsistency broke clicks even though motion worked: in some
     * surface-recreation races, focused_client ends up stale relative to
     * focused_surface, so the button filter `ptr_client != focused_client`
     * skipped ALL pointers while the motion filter `same_client(ptr,
     * focused_surface)` still emitted to the matching pointer. The result:
     * the cursor moved (motion delivered) but taps did nothing (button
     * skipped). Cursor highlight on hover requires only motion+enter, so
     * the user saw the button highlight but no click.
     *
     * The fix: make button use the SAME filter as motion. When
     * focused_surface is non-NULL, both filters produce identical results
     * (because focused_client is set together with focused_surface in
     * send_surface_focus, so they're in sync). When focused_surface is
     * NULL (destroyed), both filters skip all pointers — but motion is
     * ALSO skipped in that case, so the user wouldn't see the cursor move
     * anyway, and there's no point emitting a button to a surface that
     * doesn't exist. The cursor-focus-fix's benefit (preserving
     * focused_client) is retained for the NULL-check early-return path,
     * which lets us log the drop reason instead of silently returning. */
    if (state->focused_surface == NULL) {
        printf("wayland-shm-ahb pointer-button drop=no-focus-surface state=%s pointers=%d client=%p time=%u\n",
                button_state, total_pointers, (void *)state->focused_client, time_ms);
        fflush(stdout);
        input_debug_log("pointer-button drop=no-focus-surface state=%s pointers=%d client=%p time=%u",
                button_state, total_pointers, (void *)state->focused_client, time_ms);
        return;
    }
    uint32_t wl_state = strcmp(button_state, "down") == 0
            ? WL_POINTER_BUTTON_STATE_PRESSED
            : WL_POINTER_BUTTON_STATE_RELEASED;
    uint32_t serial_before = state->input_serial;
    int emitted = 0;
    int skipped = 0;
    struct input_resource_state *pointer;
    wl_list_for_each(pointer, &state->pointer_resources, link) {
        /* SAME filter as emit_pointer_motion: skip pointers whose client
         * differs from the focused surface's client. */
        if (!resource_same_client(pointer->resource, state->focused_surface)) {
            skipped++;
            continue;
        }
        uint32_t this_serial = next_input_serial(state);
        wl_pointer_send_button(
                pointer->resource,
                this_serial,
                time_ms,
                WAYLANDIE_BTN_LEFT,
                wl_state);
        maybe_send_pointer_frame(pointer->resource);
        emitted++;
        printf("wayland-shm-ahb pointer-button-send serial=%u state=%s btn=%d wl_state=%u ptr=%p time=%u\n",
                this_serial, button_state, WAYLANDIE_BTN_LEFT, wl_state,
                (void *)pointer->resource, time_ms);
        fflush(stdout);
        input_debug_log("pointer-button-send serial=%u state=%s ptr=%p focused=%p client=%p time=%u",
                this_serial, button_state, (void *)pointer->resource,
                (void *)state->focused_surface, (void *)state->focused_client, time_ms);
    }
    printf("wayland-shm-ahb pointer-button state=%s emitted=%d skipped=%d total=%d focused=%p client=%p serial_start=%u time=%u\n",
            button_state, emitted, skipped, total_pointers,
            (void *)state->focused_surface, (void *)state->focused_client, serial_before, time_ms);
    fflush(stdout);
    /* ALSO log to wayland-input.log via input_debug_log — this is the
     * KEY diagnostic improvement. The bridge stdout capture has been
     * unreliable (only 8 lines captured in the 00-08-36 trace, likely
     * because the wl-bridge-output thread's reader was outpaced by the
     * bridge's per-event printf spam). wayland-input.log is written
     * synchronously by input_debug_log (open+write+close per call), so
     * it's reliable. With this log line in wayland-input.log, we can
     * diagnose future click failures without depending on bridge stdout. */
    input_debug_log("pointer-button state=%s emitted=%d skipped=%d total=%d focused=%p client=%p time=%u",
            button_state, emitted, skipped, total_pointers,
            (void *)state->focused_surface, (void *)state->focused_client, time_ms);
}

static void emit_pointer_scroll(struct server_state *state, double hscroll, double vscroll, uint32_t time_ms) {
    if (state->focused_surface == NULL) {
        return;
    }
    struct input_resource_state *pointer;
    wl_list_for_each(pointer, &state->pointer_resources, link) {
        if (!resource_same_client(pointer->resource, state->focused_surface)) {
            continue;
        }
        if (vscroll != 0.0) {
            wl_pointer_send_axis(
                    pointer->resource,
                    time_ms,
                    WL_POINTER_AXIS_VERTICAL_SCROLL,
                    wl_fixed_from_double(-vscroll * 120.0));
        }
        if (hscroll != 0.0) {
            wl_pointer_send_axis(
                    pointer->resource,
                    time_ms,
                    WL_POINTER_AXIS_HORIZONTAL_SCROLL,
                    wl_fixed_from_double(hscroll * 120.0));
        }
        maybe_send_pointer_frame(pointer->resource);
    }
}

static void maybe_send_touch_frame(struct wl_resource *resource) {
    wl_touch_send_frame(resource);
}

static void emit_touch_event(
        struct server_state *state,
        const char *action,
        int touch_id,
        double x,
        double y,
        uint32_t time_ms) {
    if (state->focused_surface == NULL) {
        input_debug_log("touch drop=no-focus action=%s id=%d x=%.1f y=%.1f", action, touch_id, x, y);
        return;
    }
    clamp_pointer_to_output(state, &x, &y);
    int emitted = 0;
    struct input_resource_state *touch;
    wl_list_for_each(touch, &state->touch_resources, link) {
        if (!resource_same_client(touch->resource, state->focused_surface)) {
            continue;
        }
        if (strcmp(action, "down") == 0) {
            wl_touch_send_down(
                    touch->resource,
                    next_input_serial(state),
                    time_ms,
                    state->focused_surface,
                    touch_id,
                    wl_fixed_from_double(x),
                    wl_fixed_from_double(y));
        } else if (strcmp(action, "move") == 0) {
            wl_touch_send_motion(
                    touch->resource,
                    time_ms,
                    touch_id,
                    wl_fixed_from_double(x),
                    wl_fixed_from_double(y));
        } else if (strcmp(action, "up") == 0) {
            wl_touch_send_up(
                    touch->resource,
                    next_input_serial(state),
                    time_ms,
                    touch_id);
        } else if (strcmp(action, "cancel") == 0) {
            wl_touch_send_cancel(touch->resource);
        }
        maybe_send_touch_frame(touch->resource);
        emitted++;
    }
    input_debug_log(
            "touch action=%s id=%d x=%.1f y=%.1f emitted=%d touches=%d focused=%p",
            action,
            touch_id,
            x,
            y,
            emitted,
            input_resource_count(&state->touch_resources),
            (void *)state->focused_surface);
}

static void handle_input_line(struct server_state *state, const char *line) {
    char kind[32];
    char action[32];
    char button_state[16];
    char text_hex[2048];
    double x = state->pointer_x;
    double y = state->pointer_y;
    double input_width = state->output_width > 0 ? (double)state->output_width : 1.0;
    double input_height = state->output_height > 0 ? (double)state->output_height : 1.0;
    double hscroll = 0.0;
    double vscroll = 0.0;
    int touch_id = 0;
    int keycode = 0;
    int event_time = (int)now_msec32();
    if (strncmp(line, "input-v1 ", 9) != 0
            || !input_token_string(line, "kind", kind, sizeof(kind))
            || !input_token_string(line, "action", action, sizeof(action))) {
        return;
    }
    input_token_double(line, "x", &x);
    input_token_double(line, "y", &y);
    input_token_double(line, "width", &input_width);
    input_token_double(line, "height", &input_height);
    input_token_double(line, "hscroll", &hscroll);
    input_token_double(line, "vscroll", &vscroll);
    input_token_int(line, "id", &touch_id);
    input_token_int(line, "keycode", &keycode);
    input_token_int(line, "time", &event_time);
    double pre_map_x = x;
    double pre_map_y = y;
    map_input_to_focused_surface(state, input_width, input_height, &x, &y);
    int pointers_total = input_resource_count(&state->pointer_resources);
    int keyboards_total = input_resource_count(&state->keyboard_resources);
    int touches_total = input_resource_count(&state->touch_resources);
    printf("wayland-shm-ahb input-line kind=%s action=%s pre_map=(%.1f,%.1f) post_map=(%.1f,%.1f) iw=%.0f ih=%.0f focused=%p client=%p pointers=%d keyboards=%d touches=%d time=%d\n",
            kind, action, pre_map_x, pre_map_y, x, y,
            input_width, input_height,
            (void *)state->focused_surface, (void *)state->focused_client,
            pointers_total, keyboards_total, touches_total, event_time);
    fflush(stdout);
    input_debug_log(
            "input-line kind=%s action=%s x=%.1f y=%.1f width=%.1f height=%.1f focused=%p",
            kind,
            action,
            x,
            y,
            input_width,
            input_height,
            (void *)state->focused_surface);

    if (strcmp(kind, "pointer") == 0) {
        if (strcmp(action, "move") == 0) {
            emit_pointer_motion(state, x, y, (uint32_t)event_time);
            xtest_pointer_move(state, x, y);
        } else if (strcmp(action, "button") == 0
                && input_token_string(line, "state", button_state, sizeof(button_state))) {
            // Log EVERY input-button event (no cap) — this is the entry point
            // for tap-to-click and is critical for diagnosing click failures.
            printf("wayland-shm-ahb input-button state=%s x=%.1f y=%.1f focused=%p client=%p pointers=%d time=%d\n",
                   button_state, x, y, (void*)state->focused_surface, (void*)state->focused_client,
                   pointers_total, event_time);
            fflush(stdout);
            emit_pointer_motion(state, x, y, (uint32_t)event_time);
            xtest_pointer_move(state, x, y);
            emit_pointer_button(state, button_state, (uint32_t)event_time);
            xtest_pointer_button(state, button_state);
        } else if (strcmp(action, "scroll") == 0) {
            emit_pointer_motion(state, x, y, (uint32_t)event_time);
            xtest_pointer_move(state, x, y);
            emit_pointer_scroll(state, hscroll, vscroll, (uint32_t)event_time);
        }
    } else if (strcmp(kind, "touch") == 0) {
        emit_touch_event(state, action, touch_id, x, y, (uint32_t)event_time);
    } else if (strcmp(kind, "text") == 0) {
        if (strcmp(action, "commit") == 0
                && input_token_string(line, "text_hex", text_hex, sizeof(text_hex))) {
            xtest_type_text_hex(state, text_hex);
        }
    } else if (strcmp(kind, "key") == 0) {
        xtest_android_key(state, keycode, action);
    } else if (strcmp(kind, "clipboard") == 0) {
        if (strcmp(action, "request") == 0) {
            handle_clipboard_request(state, line);
        }
    }
}

static int input_stream_fd_event(int fd, uint32_t mask, void *data) {
    struct server_state *state = data;
    if ((mask & (WL_EVENT_HANGUP | WL_EVENT_ERROR)) != 0) {
        if (state->input_source != NULL) {
            wl_event_source_remove(state->input_source);
            state->input_source = NULL;
        }
        close(fd);
        state->input_sock = -1;
        state->input_buffer_len = 0;
        printf("wayland-shm-ahb input-stream=closed mask=0x%x\n", mask);
        return 0;
    }
    char buffer[1024];
    while (1) {
        ssize_t read_count = read(fd, buffer, sizeof(buffer));
        if (read_count < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
                break;
            }
            if (state->input_source != NULL) {
                wl_event_source_remove(state->input_source);
                state->input_source = NULL;
            }
            close(fd);
            state->input_sock = -1;
            state->input_buffer_len = 0;
            printf("wayland-shm-ahb input-stream=read-error errno=%d\n", errno);
            return 0;
        }
        if (read_count == 0) {
            if (state->input_source != NULL) {
                wl_event_source_remove(state->input_source);
                state->input_source = NULL;
            }
            close(fd);
            state->input_sock = -1;
            state->input_buffer_len = 0;
            printf("wayland-shm-ahb input-stream=eof\n");
            return 0;
        }
        for (ssize_t i = 0; i < read_count; i++) {
            char c = buffer[i];
            if (c == '\n') {
                state->input_buffer[state->input_buffer_len] = '\0';
                handle_input_line(state, state->input_buffer);
                state->input_buffer_len = 0;
            } else if (c != '\r' && state->input_buffer_len + 1U < sizeof(state->input_buffer)) {
                state->input_buffer[state->input_buffer_len++] = c;
            } else if (state->input_buffer_len + 1U >= sizeof(state->input_buffer)) {
                state->input_buffer_len = 0;
            }
        }
    }
    return 0;
}

static int connect_bridge_input_stream(struct server_state *state) {
    int fd = connect_abstract_socket(state->bridge_socket_name);
    if (fd < 0) {
        printf("wayland-shm-ahb input-stream=connect-fail errno=%d\n", errno);
        return -1;
    }
    const char command[] = "input-stream\n";
    ssize_t sent = send(fd, command, sizeof(command) - 1U, MSG_NOSIGNAL);
    if (sent < 0 || (size_t)sent != sizeof(command) - 1U) {
        int saved = sent < 0 ? errno : EPIPE;
        close(fd);
        errno = saved;
        printf("wayland-shm-ahb input-stream=command-fail errno=%d\n", errno);
        return -1;
    }
    char response[256];
    ssize_t response_len = read(fd, response, sizeof(response) - 1U);
    if (response_len <= 0) {
        int saved = response_len < 0 ? errno : EPIPE;
        close(fd);
        errno = saved;
        printf("wayland-shm-ahb input-stream=response-fail errno=%d\n", errno);
        return -1;
    }
    response[response_len] = '\0';
    response[strcspn(response, "\r\n")] = '\0';
    if (strstr(response, "status=pass") == NULL) {
        close(fd);
        printf("wayland-shm-ahb input-stream=response-reject response=%s\n", response);
        errno = EPROTO;
        return -1;
    }
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0) {
        fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    }
    printf("wayland-shm-ahb input-stream=ready response=%s\n", response);
    return fd;
}

static void pointer_set_cursor(
        struct wl_client *client,
        struct wl_resource *resource,
        uint32_t serial,
        struct wl_resource *surface,
        int32_t hotspot_x,
        int32_t hotspot_y) {
    struct input_resource_state *input = wl_resource_get_user_data(resource);
    if (input == NULL || input->server == NULL) return;
    struct server_state *state = input->server;

    if (surface == NULL) {
        /* Wine is hiding the cursor */
        state->cursor_surface = NULL;
        state->cursor_visible = 0;
        printf("wayland-shm-ahb cursor-hide\n");
        fflush(stdout);
        /* Notify Java to hide cursor */
        if (state->input_sock >= 0) {
            char msg[256];
            int len = snprintf(msg, sizeof(msg), "input-v1 kind=cursor action=hide\n");
            write(state->input_sock, msg, len);
        }
        return;
    }

    /* Wine is showing a cursor on 'surface' with hotspot (hotspot_x, hotspot_y) */
    state->cursor_surface = surface;
    state->cursor_hotspot_x = hotspot_x;
    state->cursor_hotspot_y = hotspot_y;
    state->cursor_visible = 1;
    printf("wayland-shm-ahb cursor-set surface=%p hotspot=(%d,%d)\n",
           (void *)surface, hotspot_x, hotspot_y);
    fflush(stdout);

    /* The cursor surface will commit a buffer. When it does, the commit
     * handler will detect it as the cursor surface and send the image to Java.
     * For now, notify Java that a cursor is visible with the hotspot. */
    if (state->input_sock >= 0) {
        char msg[256];
        int len = snprintf(msg, sizeof(msg),
                "input-v1 kind=cursor action=set hotspot_x=%d hotspot_y=%d\n",
                hotspot_x, hotspot_y);
        write(state->input_sock, msg, len);
    }
}

static void pointer_release(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static const struct wl_pointer_interface pointer_impl = {
    .set_cursor = pointer_set_cursor,
    .release = pointer_release,
};

static void keyboard_release(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static const struct wl_keyboard_interface keyboard_impl = {
    .release = keyboard_release,
};

static void touch_release(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static const struct wl_touch_interface touch_impl = {
    .release = touch_release,
};

static void seat_get_pointer(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct server_state *state = wl_resource_get_user_data(resource);
    struct wl_resource *pointer_resource = wl_resource_create(client, &wl_pointer_interface, wl_resource_get_version(resource), id);
    struct input_resource_state *input = calloc(1, sizeof(*input));
    if (pointer_resource == NULL || input == NULL) {
        wl_client_post_no_memory(client);
        free(input);
        return;
    }
    input->server = state;
    input->resource = pointer_resource;
    wl_list_insert(&state->pointer_resources, &input->link);
    wl_resource_set_implementation(pointer_resource, &pointer_impl, input, destroy_input_resource);
    int total_pointers = input_resource_count(&state->pointer_resources);
    int same_client = resource_same_client(pointer_resource, state->focused_surface);
    printf("wayland-shm-ahb seat-get-pointer ptr=%p focused=%p same-client=%d total=%d pointer_x=%.1f pointer_y=%.1f\n",
           (void *)pointer_resource,
           (void *)state->focused_surface,
           same_client, total_pointers,
           state->pointer_x, state->pointer_y);
    fflush(stdout);
    input_debug_log("seat-get-pointer ptr=%p focused=%p same-client=%d total=%d pointer_x=%.1f pointer_y=%.1f",
           (void *)pointer_resource,
           (void *)state->focused_surface,
           same_client, total_pointers,
           state->pointer_x, state->pointer_y);
    if (same_client) {
        uint32_t enter_serial = next_input_serial(state);
        wl_pointer_send_enter(
                pointer_resource,
                enter_serial,
                state->focused_surface,
                wl_fixed_from_double(state->pointer_x),
                wl_fixed_from_double(state->pointer_y));
        maybe_send_pointer_frame(pointer_resource);
        printf("wayland-shm-ahb seat-get-pointer enter-sent serial=%u ptr=%p focused=%p x=%.1f y=%.1f\n",
                enter_serial, (void *)pointer_resource,
                (void *)state->focused_surface,
                state->pointer_x, state->pointer_y);
        fflush(stdout);
    }
}

static void seat_get_keyboard(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct server_state *state = wl_resource_get_user_data(resource);
    struct wl_resource *keyboard_resource = wl_resource_create(client, &wl_keyboard_interface, wl_resource_get_version(resource), id);
    struct input_resource_state *input = calloc(1, sizeof(*input));
    if (keyboard_resource == NULL || input == NULL) {
        wl_client_post_no_memory(client);
        free(input);
        return;
    }
    input->server = state;
    input->resource = keyboard_resource;
    wl_list_insert(&state->keyboard_resources, &input->link);
    wl_resource_set_implementation(keyboard_resource, &keyboard_impl, input, destroy_input_resource);
    input_debug_log(
            "seat-get-keyboard resource=%p focus=%p same-client=%d total=%d",
            (void *)keyboard_resource,
            (void *)state->focused_surface,
            resource_same_client(keyboard_resource, state->focused_surface),
            input_resource_count(&state->keyboard_resources));
    if (resource_same_client(keyboard_resource, state->focused_surface)) {
        int keymap_fd = open("/dev/null", O_RDONLY | O_CLOEXEC);
        if (keymap_fd >= 0) {
            wl_keyboard_send_keymap(
                    keyboard_resource,
                    WL_KEYBOARD_KEYMAP_FORMAT_NO_KEYMAP,
                    keymap_fd,
                    0);
            close(keymap_fd);
        }
        struct wl_array keys;
        wl_array_init(&keys);
        wl_keyboard_send_enter(
                keyboard_resource,
                next_input_serial(state),
                state->focused_surface,
                &keys);
        wl_array_release(&keys);
    }
}

static void seat_get_touch(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct server_state *state = wl_resource_get_user_data(resource);
    struct wl_resource *touch_resource = wl_resource_create(client, &wl_touch_interface, 1, id);
    struct input_resource_state *input = calloc(1, sizeof(*input));
    if (touch_resource == NULL || input == NULL) {
        wl_client_post_no_memory(client);
        free(input);
        return;
    }
    input->server = state;
    input->resource = touch_resource;
    wl_list_insert(&state->touch_resources, &input->link);
    wl_resource_set_implementation(touch_resource, &touch_impl, input, destroy_input_resource);
    input_debug_log(
            "seat-get-touch resource=%p focus=%p same-client=%d total=%d",
            (void *)touch_resource,
            (void *)state->focused_surface,
            resource_same_client(touch_resource, state->focused_surface),
            input_resource_count(&state->touch_resources));
}

static void seat_release(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static const struct wl_seat_interface seat_impl = {
    .get_pointer = seat_get_pointer,
    .get_keyboard = seat_get_keyboard,
    .get_touch = seat_get_touch,
    .release = seat_release,
};

static void bind_seat(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    uint32_t bind_version = version > 8 ? 8 : version;
    struct wl_resource *resource = wl_resource_create(client, &wl_seat_interface, bind_version, id);
    if (resource == NULL) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(resource, &seat_impl, data, NULL);
    uint32_t caps = WL_SEAT_CAPABILITY_POINTER | WL_SEAT_CAPABILITY_KEYBOARD | WL_SEAT_CAPABILITY_TOUCH;
    wl_seat_send_capabilities(resource, caps);
    printf("wayland-shm-ahb bind-seat client=%p version=%u bind_version=%u id=%u caps=%u (ptr=%d kb=%d touch=%d)\n",
           (void *)client, version, bind_version, id, caps,
           !!(caps & WL_SEAT_CAPABILITY_POINTER),
           !!(caps & WL_SEAT_CAPABILITY_KEYBOARD),
           !!(caps & WL_SEAT_CAPABILITY_TOUCH));
    fflush(stdout);
    input_debug_log("bind-seat client=%p version=%u bind_version=%u caps=%u (ptr=%d kb=%d touch=%d)",
           (void *)client, version, bind_version, caps,
           !!(caps & WL_SEAT_CAPABILITY_POINTER),
           !!(caps & WL_SEAT_CAPABILITY_KEYBOARD),
           !!(caps & WL_SEAT_CAPABILITY_TOUCH));
    /* Only send name if the CLIENT bound with version >= 7.
     * Wine's winewayland.drv caps at version 5 (see wayland.c:
     *   wl_registry_bind(..., version < 5 ? version : 5)
     * Sending wl_seat.name (opcode 2, added in v7) to a v5 client
     * causes the client to misparse the event stream — the name event
     * gets interpreted as a different opcode, and subsequent events
     * (including capabilities re-sends) are missed. This prevents
     * Wine from calling wl_seat_get_pointer, which is why pointer
     * events never reach Wine (the cursor/arrow cursor bug). */
    if (bind_version >= 7) {
        wl_seat_send_name(resource, "waylandie-android-seat");
        printf("wayland-shm-ahb bind-seat name-sent (v>=7)\n");
        fflush(stdout);
    } else {
        printf("wayland-shm-ahb bind-seat name-skipped (v=%u < 7)\n", bind_version);
        fflush(stdout);
    }
}

static void destroy_surface_resource(struct wl_resource *resource) {
    struct surface_state *surface = wl_resource_get_user_data(resource);
    if (surface != NULL) {
        if (surface->subsurface_linked) {
            wl_list_remove(&surface->subsurface_link);
            wl_list_init(&surface->subsurface_link);
            surface->subsurface_linked = 0;
        }
        struct surface_state *child;
        struct surface_state *tmp;
        wl_list_for_each_safe(child, tmp, &surface->subsurface_children, subsurface_link) {
            wl_list_remove(&child->subsurface_link);
            wl_list_init(&child->subsurface_link);
            child->subsurface_linked = 0;
            child->subsurface_parent = NULL;
            child->is_subsurface = 0;
        }
        if (surface->server != NULL && surface->server->focused_surface == surface->resource) {
            /* Don't clear focused_client — it survives surface destruction.
             * Input events use focused_client for the same-client check,
             * so they'll still be delivered to pointers from the same Wine
             * process even after the focused surface is destroyed.
             * When a new surface from the same client commits a buffer,
             * send_surface_focus will update focused_surface. */
            surface->server->focused_surface = NULL;
            surface->server->focused_surface_width = 0;
            surface->server->focused_surface_height = 0;
            /* focused_client is KEPT — this is the key fix for cursor */
            printf("wayland-shm-ahb focus-surface-destroyed surface=%p client=%p (focus preserved)\n",
                   (void *)surface->resource, (void *)surface->server->focused_client);
            fflush(stdout);
        }
        close_android_window_for_surface(surface);
        send_surface_presentation_feedback(surface, 0);
        send_surface_frame_callbacks(surface);
    }
    free(surface);
}

static void surface_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void surface_attach(struct wl_client *client, struct wl_resource *resource, struct wl_resource *buffer, int32_t x, int32_t y) {
    (void)client; (void)x; (void)y;
    struct surface_state *surface = wl_resource_get_user_data(resource);
    if (surface == NULL) {
        return;
    }
    surface->has_pending_attach = 1;
    if (surface->pending_buffer != NULL && surface->pending_buffer->resource != NULL) {
        wl_buffer_send_release(surface->pending_buffer->resource);
    }
    surface->pending_buffer = buffer == NULL ? NULL : wl_resource_get_user_data(buffer);
    if (surface->pending_buffer != NULL) {
        surface->current_width = surface->pending_buffer->width;
        surface->current_height = surface->pending_buffer->height;
        printf("wayland-shm-ahb attach xdg=%d subsurface=%d displayable=%d kind=%d size=%dx%d\n",
                surface->is_xdg_surface,
                surface->is_subsurface,
                surface_is_displayable(surface),
                surface->pending_buffer->kind,
                surface->pending_buffer->width,
                surface->pending_buffer->height);
        fflush(stdout);
    }
}

static void surface_damage(struct wl_client *client, struct wl_resource *resource, int32_t x, int32_t y, int32_t width, int32_t height) {
    (void)client; (void)resource; (void)x; (void)y; (void)width; (void)height;
}

static void surface_frame(struct wl_client *client, struct wl_resource *resource, uint32_t callback) {
    struct surface_state *surface = wl_resource_get_user_data(resource);
    struct wl_resource *cb = wl_resource_create(client, &wl_callback_interface, 1, callback);
    struct frame_callback_state *callback_state = calloc(1, sizeof(*callback_state));
    if (cb == NULL || callback_state == NULL || surface == NULL) {
        wl_client_post_no_memory(client);
        if (cb != NULL) {
            wl_resource_destroy(cb);
        }
        free(callback_state);
        return;
    }
    callback_state->resource = cb;
    wl_list_insert(surface->frame_callbacks.prev, &callback_state->link);
    wl_resource_set_implementation(cb, NULL, callback_state, destroy_frame_callback_resource);
}

static void surface_set_opaque_region(struct wl_client *client, struct wl_resource *resource, struct wl_resource *region) {
    (void)client; (void)resource; (void)region;
}

static void surface_set_input_region(struct wl_client *client, struct wl_resource *resource, struct wl_resource *region) {
    (void)client; (void)resource; (void)region;
}

static void surface_commit(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    struct surface_state *surface = wl_resource_get_user_data(resource);
    if (surface == NULL || surface->server == NULL) {
        return;
    }
    g_diag.surfaces_committed++;
    if (!surface_is_displayable(surface)) {
        printf("wayland-shm-ahb commit=ignored-non-displayable xdg=%d subsurface=%d kind=%d\n",
                surface->is_xdg_surface,
                surface->is_subsurface,
                surface->has_pending_attach && surface->pending_buffer != NULL ? surface->pending_buffer->kind : 0);
        fflush(stdout);
        if (surface->has_pending_attach && surface->pending_buffer != NULL && surface->pending_buffer->resource != NULL) {
            wl_buffer_send_release(surface->pending_buffer->resource);
        }
        surface->has_pending_attach = 0;
        surface->pending_buffer = NULL;
        surface->commit_count++;
        send_surface_presentation_feedback(surface, 0);
        send_surface_frame_callbacks(surface);
        return;
    }
    if (surface->is_subsurface && surface->subsurface_parent != NULL) {
        if (surface->has_pending_attach && surface->pending_buffer == NULL) {
            printf("wayland-shm-ahb commit=subsurface-clear\n");
            surface->has_pending_attach = 0;
        } else if (surface->has_pending_attach && surface->pending_buffer != NULL) {
            printf("wayland-shm-ahb commit=subsurface-pending kind=%d size=%dx%d primary=%d\n",
                    surface->pending_buffer->kind,
                    surface->pending_buffer->width,
                    surface->pending_buffer->height,
                    buffer_is_primary_for_surface(surface, surface->pending_buffer));
        } else {
            printf("wayland-shm-ahb commit=subsurface-no-buffer\n");
        }
        fflush(stdout);
        surface->commit_count++;
        send_surface_frame_callbacks(surface);
        return;
    }
    if (!surface->has_pending_attach) {
        printf("wayland-shm-ahb commit=no-buffer\n");
        fflush(stdout);
        surface->commit_count++;
        send_surface_focus(surface, resource, surface->current_width, surface->current_height);
        send_surface_presentation_feedback(surface, 0);
        send_surface_frame_callbacks(surface);
        return;
    }
    if (surface->pending_buffer == NULL) {
        struct surface_state *presentable = find_presentable_subsurface(surface);
        if (presentable != NULL
                && presentable->pending_buffer != NULL
                && buffer_is_primary_for_surface(surface, presentable->pending_buffer)) {
            int frame_index = surface->server->commit_count;
            int present_failed = 0;
            printf("wayland-shm-ahb commit=subsurface-latched xdg=%d subsurface=%d kind=%d size=%dx%d\n",
                    presentable->is_xdg_surface,
                    presentable->is_subsurface,
                    presentable->pending_buffer->kind,
                    presentable->pending_buffer->width,
                    presentable->pending_buffer->height);
            fflush(stdout);
            if (ensure_android_window_for_surface(surface, presentable->pending_buffer) != 0
                    || present_buffer_to_android(surface, presentable->pending_buffer, frame_index) != 0) {
                surface->server->present_failures++;
                /* SHM buffers (from Wine desktop/explorer) are now converted to
                 * AHardwareBuffer and presented via the same dmabuf path as game
                 * frames. If we get here, the conversion or present failed — log
                 * it but don't abort the bridge. Game dmabuf buffers will still
                 * present correctly. */
                send_surface_presentation_feedback(presentable, 0);
                present_failed = 1;
                fflush(stdout);
            }
            send_focus_for_presentable(surface, presentable, presentable->pending_buffer);
            if (presentable->pending_buffer->resource != NULL) {
                wl_buffer_send_release(presentable->pending_buffer->resource);
            }
            presentable->has_pending_attach = 0;
            presentable->pending_buffer = NULL;
            surface->has_pending_attach = 0;
            surface->server->commit_count++;
            surface->commit_count++;
            presentable->commit_count++;
            if (!present_failed) {
                send_surface_presentation_feedback(presentable, 1);
            }
            send_surface_frame_callbacks(presentable);
            send_surface_presentation_feedback(surface, present_failed ? 0 : 1);
            send_surface_frame_callbacks(surface);
            return;
        }
        printf("wayland-shm-ahb commit=configure-only\n");
        fflush(stdout);
        surface->has_pending_attach = 0;
        surface->commit_count++;
        send_surface_focus(surface, resource, surface->current_width, surface->current_height);
        send_surface_presentation_feedback(surface, 0);
        send_surface_frame_callbacks(surface);
        return;
    }
    int frame_index = surface->server->commit_count;
    int present_failed = 0;
    struct surface_state *presentable = surface;
    struct surface_state *child_presentable = find_presentable_subsurface(surface);
    if (child_presentable != NULL
            && child_presentable->pending_buffer != NULL
            && buffer_area(child_presentable->pending_buffer) > buffer_area(surface->pending_buffer)) {
        presentable = child_presentable;
        printf("wayland-shm-ahb commit=subsurface-preferred parent=%dx%d child=%dx%d\n",
                surface->pending_buffer != NULL ? surface->pending_buffer->width : 0,
                surface->pending_buffer != NULL ? surface->pending_buffer->height : 0,
                presentable->pending_buffer->width,
                presentable->pending_buffer->height);
        fflush(stdout);
    }
    struct shm_buffer_state *buffer_to_present = presentable->pending_buffer;
    if (!buffer_is_primary_for_surface(surface, buffer_to_present)) {
        printf("wayland-shm-ahb commit=nonprimary-skipped size=%dx%d\n",
                buffer_to_present != NULL ? buffer_to_present->width : 0,
                buffer_to_present != NULL ? buffer_to_present->height : 0);
        fflush(stdout);
        if (presentable != surface && surface->pending_buffer != NULL && surface->pending_buffer->resource != NULL) {
            wl_buffer_send_release(surface->pending_buffer->resource);
        }
        if (presentable == surface && buffer_to_present != NULL && buffer_to_present->resource != NULL) {
            wl_buffer_send_release(buffer_to_present->resource);
        }
        surface->has_pending_attach = 0;
        surface->pending_buffer = NULL;
        surface->commit_count++;
        send_surface_focus(surface, resource, surface->current_width, surface->current_height);
        send_surface_presentation_feedback(surface, 0);
        send_surface_frame_callbacks(surface);
        return;
    }
    if (ensure_android_window_for_surface(surface, buffer_to_present) != 0
            || present_buffer_to_android(surface, buffer_to_present, frame_index) != 0) {
        surface->server->present_failures++;
        /* SHM present failure is non-fatal — see comment above. */
        send_surface_presentation_feedback(presentable, 0);
        present_failed = 1;
        fflush(stdout);
    }
    if (presentable != surface && surface->pending_buffer != NULL && surface->pending_buffer->resource != NULL) {
        wl_buffer_send_release(surface->pending_buffer->resource);
    }
    if (buffer_to_present != NULL && buffer_to_present->resource != NULL) {
        wl_buffer_send_release(buffer_to_present->resource);
    }
    surface->has_pending_attach = 0;
    surface->pending_buffer = NULL;
    if (presentable != surface) {
        presentable->has_pending_attach = 0;
        presentable->pending_buffer = NULL;
    }
    surface->server->commit_count++;
    surface->commit_count++;
    if (presentable != surface) {
        presentable->commit_count++;
    }
    send_focus_for_presentable(surface, presentable, buffer_to_present);
    if (!present_failed) {
        send_surface_presentation_feedback(presentable, 1);
    }
    if (presentable != surface) {
        send_surface_frame_callbacks(presentable);
        send_surface_presentation_feedback(surface, present_failed ? 0 : 1);
    }
    send_surface_frame_callbacks(surface);
}

static void surface_set_buffer_transform(struct wl_client *client, struct wl_resource *resource, int32_t transform) {
    (void)client; (void)resource; (void)transform;
}

static void surface_set_buffer_scale(struct wl_client *client, struct wl_resource *resource, int32_t scale) {
    (void)client; (void)resource; (void)scale;
}

static void surface_damage_buffer(struct wl_client *client, struct wl_resource *resource, int32_t x, int32_t y, int32_t width, int32_t height) {
    (void)client; (void)resource; (void)x; (void)y; (void)width; (void)height;
}

static void surface_offset(struct wl_client *client, struct wl_resource *resource, int32_t x, int32_t y) {
    (void)client; (void)resource; (void)x; (void)y;
}

static const struct wl_surface_interface surface_impl = {
    .destroy = surface_destroy,
    .attach = surface_attach,
    .damage = surface_damage,
    .frame = surface_frame,
    .set_opaque_region = surface_set_opaque_region,
    .set_input_region = surface_set_input_region,
    .commit = surface_commit,
    .set_buffer_transform = surface_set_buffer_transform,
    .set_buffer_scale = surface_set_buffer_scale,
    .damage_buffer = surface_damage_buffer,
    .offset = surface_offset,
};

static void compositor_create_surface(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct server_state *state = wl_resource_get_user_data(resource);
    struct wl_resource *surface_resource = wl_resource_create(client, &wl_surface_interface, 4, id);
    struct surface_state *surface = calloc(1, sizeof(*surface));
    if (surface_resource == NULL || surface == NULL) {
        wl_client_post_no_memory(client);
        free(surface);
        return;
    }
    surface->server = state;
    surface->resource = surface_resource;
    wl_list_init(&surface->frame_callbacks);
    wl_list_init(&surface->presentation_feedbacks);
    wl_list_init(&surface->subsurface_children);
    wl_list_init(&surface->subsurface_link);
    wl_resource_set_implementation(surface_resource, &surface_impl, surface, destroy_surface_resource);
}

static void region_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void region_add(struct wl_client *client, struct wl_resource *resource, int32_t x, int32_t y, int32_t width, int32_t height) {
    (void)client; (void)resource; (void)x; (void)y; (void)width; (void)height;
}

static void region_subtract(struct wl_client *client, struct wl_resource *resource, int32_t x, int32_t y, int32_t width, int32_t height) {
    (void)client; (void)resource; (void)x; (void)y; (void)width; (void)height;
}

static const struct wl_region_interface region_impl = {
    .destroy = region_destroy,
    .add = region_add,
    .subtract = region_subtract,
};

static void compositor_create_region(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct wl_resource *region = wl_resource_create(client, &wl_region_interface, 1, id);
    (void)resource;
    if (region == NULL) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(region, &region_impl, NULL, NULL);
}

static const struct wl_compositor_interface compositor_impl = {
    .create_surface = compositor_create_surface,
    .create_region = compositor_create_region,
};

static void bind_compositor(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    struct wl_resource *resource = wl_resource_create(client, &wl_compositor_interface, version > 4 ? 4 : version, id);
    struct server_state *state = data;
    if (state != NULL) {
        state->client_seen = 1;
    }
    if (resource == NULL) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(resource, &compositor_impl, data, NULL);
}

// =====================================================================
// BRIDGE SELF-TEST — runs at startup, validates all invariants.
// Logs results as 'self-test' lines so the trace file shows which
// checks passed/failed. This catches configuration issues BEFORE
// Wine connects, so we don't waste time debugging downstream symptoms.
// =====================================================================
#ifdef WAYLANDIE_HAS_AHARDWAREBUFFER
static int bridge_self_test(void) {
    int failures = 0;
    printf("wayland-shm-ahb self-test starting\n");
    fflush(stdout);

    // Test 1: AHardwareBuffer allocation with the format/usage we use
    {
        AHardwareBuffer_Desc desc = {};
        desc.width = 64;
        desc.height = 64;
        desc.layers = 1;
        desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN
                   | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE
                   | AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN;
        AHardwareBuffer* ahb = NULL;
        int rc = AHardwareBuffer_allocate(&desc, &ahb);
        if (rc != 0 || ahb == NULL) {
            printf("wayland-shm-ahb self-test FAIL ahb-alloc rc=%d errno=%d %s\n",
                   rc, errno, strerror(errno));
            failures++;
        } else {
            // Test 2: Lock for CPU write
            void* dst = NULL;
            rc = AHardwareBuffer_lock(ahb,
                                      AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN
                                      | AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                                      -1, NULL, &dst);
            if (rc != 0 || dst == NULL) {
                printf("wayland-shm-ahb self-test FAIL ahb-lock rc=%d\n", rc);
                failures++;
            } else {
                // Test 3: Write + read back (verify CPU access works)
                memset(dst, 0x42, 64 * 4);  // first row
                uint8_t* bytes = (uint8_t*)dst;
                if (bytes[0] != 0x42) {
                    printf("wayland-shm-ahb self-test FAIL ahb-write-readback byte0=0x%02x\n", bytes[0]);
                    failures++;
                } else {
                    printf("wayland-shm-ahb self-test PASS ahb-alloc+lock+write+readback\n");
                }
                AHardwareBuffer_unlock(ahb, NULL);
            }

            // Test 4: Get dmabuf fd via AHardwareBuffer_getNativeHandle
            const struct waylandie_native_handle* h = AHardwareBuffer_getNativeHandle(ahb);
            if (h == NULL || h->numFds < 1) {
                printf("wayland-shm-ahb self-test FAIL ahb-native-handle numFds=%d\n",
                       h ? h->numFds : -1);
                failures++;
            } else {
                int fd = dup(h->data[0]);
                if (fd < 0) {
                    printf("wayland-shm-ahb self-test FAIL ahb-dup-fd errno=%d\n", errno);
                    failures++;
                } else {
                    // Test 5: fstat the dmabuf fd (verify it's a valid fd)
                    struct stat st;
                    if (fstat(fd, &st) != 0) {
                        printf("wayland-shm-ahb self-test FAIL ahb-fstat errno=%d\n", errno);
                        failures++;
                    } else {
                        printf("wayland-shm-ahb self-test PASS ahb-native-handle fd=%d size=%lld\n",
                               fd, (long long)st.st_size);
                    }
                    close(fd);
                }
            }
            AHardwareBuffer_release(ahb);
        }
    }

    // Test 6: SHM pool create + resize + create_buffer lifecycle
    // (Simulates what Wine does, without needing a Wayland client)
    {
        // Create a memfd for the pool
        int fd = syscall(SYS_memfd_create, "selftest", 0);
        if (fd < 0) {
            printf("wayland-shm-ahb self-test SKIP memfd-create (errno=%d) — syscall not available\n", errno);
        } else {
            // Start with small size, then grow (simulates Wine's pattern)
            if (ftruncate(fd, 4096) != 0) {
                printf("wayland-shm-ahb self-test FAIL ftruncate-1 errno=%d\n", errno);
                failures++;
                close(fd);
            } else {
                void* data = mmap(NULL, 4096, PROT_READ, MAP_SHARED, fd, 0);
                if (data == MAP_FAILED) {
                    printf("wayland-shm-ahb self-test FAIL mmap errno=%d\n", errno);
                    failures++;
                    close(fd);
                } else {
                    // Test resize via mremap (simulates shm_pool_resize)
                    if (ftruncate(fd, 65536) != 0) {
                        printf("wayland-shm-ahb self-test FAIL ftruncate-2 errno=%d\n", errno);
                        failures++;
                    } else {
                        void* new_data = mremap(data, 4096, 65536, MREMAP_MAYMOVE);
                        if (new_data == MAP_FAILED) {
                            printf("wayland-shm-ahb self-test FAIL mremap errno=%d %s\n",
                                   errno, strerror(errno));
                            failures++;
                        } else {
                            printf("wayland-shm-ahb self-test PASS memfd+ftruncate+mremap old=%p new=%p\n",
                                   data, new_data);
                            data = new_data;
                        }
                    }
                    munmap(data, 65536);
                    close(fd);
                }
            }
        }
    }

    // Test 7: Abstract socket bind + connect (verify we can create the bridge socket)
    {
        int s = socket(AF_UNIX, SOCK_STREAM, 0);
        if (s < 0) {
            printf("wayland-shm-ahb self-test FAIL socket-create errno=%d\n", errno);
            failures++;
        } else {
            struct sockaddr_un addr = {};
            addr.sun_family = AF_UNIX;
            addr.sun_path[0] = '\0';
            strncpy(addr.sun_path + 1, "waylandie.selftest", sizeof(addr.sun_path) - 2);
            socklen_t len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen("waylandie.selftest");
            if (bind(s, (struct sockaddr*)&addr, len) < 0) {
                printf("wayland-shm-ahb self-test FAIL socket-bind errno=%d\n", errno);
                failures++;
            } else {
                printf("wayland-shm-ahb self-test PASS abstract-socket-bind\n");
            }
            close(s);
        }
    }

    // Test 8: Verify sizeof critical structs (catches accidental field changes)
    {
        printf("wayland-shm-ahb self-test sizeof shm_pool_state=%zu shm_buffer_state=%zu surface_state=%zu\n",
               sizeof(struct shm_pool_state),
               sizeof(struct shm_buffer_state),
               sizeof(struct surface_state));
        // Sanity: pool must have at least data+size+fd+refcount+id
        if (sizeof(struct shm_pool_state) < 32) {
            printf("wayland-shm-ahb self-test FAIL pool-struct-too-small %zu\n",
                   sizeof(struct shm_pool_state));
            failures++;
        }
    }

    // Summary
    if (failures == 0) {
        printf("wayland-shm-ahb self-test ALL-PASS\n");
    } else {
        printf("wayland-shm-ahb self-test %d FAILURES — bridge may not work correctly\n", failures);
    }
    fflush(stdout);
    return failures;
}
#endif  // WAYLANDIE_HAS_AHARDWAREBUFFER

int main(int argc, char **argv) {
    if (argc != 9) {
        fprintf(stderr, "usage: %s <bridge-socket> <target-commits> <socket-file> <timeout-ms> <clear-ahb-outside> <accept-client-complete> <output-width> <output-height>\n", argv[0]);
        return 2;
    }

    // Run self-test at startup (logs results to stdout, which goes to trace file)
#ifdef WAYLANDIE_HAS_AHARDWAREBUFFER
    bridge_self_test();
#endif
    struct server_state state;
    memset(&state, 0, sizeof(state));
    state.input_sock = -1;
    state.xtest_enabled = xtest_input_enabled();
    wl_list_init(&state.keyboard_resources);
    wl_list_init(&state.pointer_resources);
    wl_list_init(&state.touch_resources);
    state.bridge_sock = -1;
    state.last_frame_callback_commit = -1;
    const char *bridge_reconnect_frames_env = getenv("BRIDGE_RECONNECT_FRAMES");
    state.bridge_reconnect_frames = bridge_reconnect_frames_env == NULL
            ? 4096
            : atoi(bridge_reconnect_frames_env);
    if (state.bridge_reconnect_frames < 0) {
        state.bridge_reconnect_frames = 0;
    }
    const char *pass_log_interval_env = getenv("PASS_LOG_INTERVAL");
    state.pass_log_interval = pass_log_interval_env == NULL
            ? 0
            : atoi(pass_log_interval_env);
    if (state.pass_log_interval < 0) {
        state.pass_log_interval = 0;
    }
    const char *android_windows_env = getenv("WAYLANDIE_ANDROID_MULTI_WINDOW");
    state.android_windows = android_windows_env != NULL
            && android_windows_env[0] != '\0'
            && strcmp(android_windows_env, "0") != 0;
    const char *accept_scaled_primary_env = getenv("WAYLANDIE_WAYLAND_ACCEPT_SCALED_PRIMARY");
    state.accept_scaled_primary = accept_scaled_primary_env == NULL
            || accept_scaled_primary_env[0] == '\0'
            || strcmp(accept_scaled_primary_env, "0") != 0;
    state.bridge_socket_name = argv[1];
    state.target_commits = atoi(argv[2]);
    const char *socket_file = argv[3];
    double timeout_ms = atof(argv[4]);
    state.clear_ahb_outside = atoi(argv[5]) != 0;
    state.accept_client_complete = atoi(argv[6]) != 0;
    state.output_width = argc > 7 ? atoi(argv[7]) : 2688;
    state.output_height = argc > 8 ? atoi(argv[8]) : 1216;
    if (state.target_commits <= 0) {
        state.target_commits = 1;
    }
    if (state.output_width <= 0) {
        state.output_width = 2688;
    }
    if (state.output_height <= 0) {
        state.output_height = 1216;
    }
    if (timeout_ms <= 0.0) {
        timeout_ms = 15000.0;
    }
    const char *refresh_env = getenv("WAYLANDIE_WAYLAND_REFRESH_HZ");
    double refresh_hz = refresh_env != NULL && refresh_env[0] != '\0'
            ? atof(refresh_env)
            : 120.0;
    if (refresh_hz > 0.0) {
        state.presentation_refresh_nsec = (uint32_t)((1000000000.0 / refresh_hz) + 0.5);
    }
    const char *callback_mode_env = getenv("WAYLANDIE_WAYLAND_FRAME_CALLBACK_MODE");
    const char *callback_mode = callback_mode_env != NULL && callback_mode_env[0] != '\0'
            ? callback_mode_env
            : "paced";
    const char *frame_interval_env = getenv("FRAME_INTERVAL_MS");
    double frame_interval_override_ms =
            frame_interval_env != NULL && frame_interval_env[0] != '\0'
                    ? atof(frame_interval_env)
                    : -1.0;
    if (strcmp(callback_mode, "immediate") == 0
            || strcmp(callback_mode, "none") == 0
            || frame_interval_override_ms == 0.0) {
        state.frame_interval_ms = 0.0;
    } else if (frame_interval_override_ms > 0.0) {
        state.frame_interval_ms = frame_interval_override_ms;
    } else if (refresh_hz > 0.0) {
        state.frame_interval_ms = 1000.0 / refresh_hz;
    }
    state.display = wl_display_create();
    if (state.display == NULL) {
        printf("wayland-shm-ahb server=fail reason=display-create\n");
        return 1;
    }
    if (wl_global_create(state.display, &wl_compositor_interface, 4, &state, bind_compositor) == NULL
            || wl_global_create(state.display, &wl_subcompositor_interface, 1, NULL, bind_subcompositor) == NULL
            || wl_global_create(state.display, &wl_seat_interface, 8, &state, bind_seat) == NULL
            || wl_global_create(state.display, &wl_shm_interface, 1, NULL, bind_shm) == NULL
             || wl_global_create(state.display, &wl_output_interface, 4, &state, bind_output) == NULL
             || wl_global_create(state.display, &xdg_wm_base_interface, 5, NULL, bind_xdg_wm_base) == NULL
            || wl_global_create(state.display, &wp_presentation_interface, 1, NULL, bind_presentation) == NULL
            || wl_global_create(state.display, &wp_viewporter_interface, 1, NULL, bind_viewporter) == NULL
            || wl_global_create(state.display, &zwp_relative_pointer_manager_v1_interface, 1, NULL, bind_relative_pointer_manager) == NULL
            || wl_global_create(state.display, &zwp_pointer_constraints_v1_interface, 1, NULL, bind_pointer_constraints) == NULL
            || wl_global_create(state.display, &zwp_linux_dmabuf_v1_interface, 4, NULL, bind_linux_dmabuf) == NULL) {
        printf("wayland-shm-ahb server=fail reason=globals\n");
        wl_display_destroy(state.display);
        return 1;
    }
    const char *socket_name = wl_display_add_socket_auto(state.display);
    if (socket_name == NULL) {
        printf("wayland-shm-ahb server=fail reason=add-socket errno=%d\n", errno);
        wl_display_destroy(state.display);
        return 1;
    }
    FILE *socket_output = fopen(socket_file, "w");
    if (socket_output == NULL) {
        printf("wayland-shm-ahb server=fail reason=socket-file errno=%d\n", errno);
        wl_display_destroy(state.display);
        return 1;
    }
    fprintf(socket_output, "%s\n", socket_name);
    fclose(socket_output);
    printf("wayland-shm-ahb server=ready socket=%s target=%d timeout-ms=%.0f clear-ahb-outside=%d accept-client-complete=%d bridge-reconnect-frames=%d pass-log-interval=%d android-windows=%d accept-scaled-primary=%d frame-callback-mode=%s frame-interval-ms=%.3f presentation-refresh-nsec=%u\n",
            socket_name,
            state.target_commits,
            timeout_ms,
            state.clear_ahb_outside,
            state.accept_client_complete,
            state.bridge_reconnect_frames,
            state.pass_log_interval,
            state.android_windows,
            state.accept_scaled_primary,
            callback_mode,
            state.frame_interval_ms,
            state.presentation_refresh_nsec);
    fflush(stdout);

    struct wl_event_loop *loop = wl_display_get_event_loop(state.display);
    state.input_sock = connect_bridge_input_stream(&state);
    if (state.input_sock >= 0) {
        state.input_source = wl_event_loop_add_fd(
                loop,
                state.input_sock,
                WL_EVENT_READABLE | WL_EVENT_HANGUP | WL_EVENT_ERROR,
                input_stream_fd_event,
                &state);
        if (state.input_source == NULL) {
            printf("wayland-shm-ahb input-stream=event-source-fail\n");
            close(state.input_sock);
            state.input_sock = -1;
        }
    }
    double start_ms = now_ms();
    while (!state.abort_requested
            && (now_ms() - start_ms) < 3600000.0) {  /* 1 hour timeout */
        wl_event_loop_dispatch(loop, 20);
        wl_display_flush_clients(state.display);
    }
    if (state.input_source != NULL) {
        wl_event_source_remove(state.input_source);
        state.input_source = NULL;
    }
    if (state.input_sock >= 0) {
        close(state.input_sock);
        state.input_sock = -1;
    }
    wl_display_destroy_clients(state.display);
    wl_display_destroy(state.display);
    if (state.bridge_sock >= 0) {
        close(state.bridge_sock);
        state.bridge_sock = -1;
    }
#ifndef WAYLANDIE_NO_XTEST
    if (state.xtest_display != NULL) {
        if (state.xtest_window != None) {
            XDestroyWindow(state.xtest_display, state.xtest_window);
            state.xtest_window = None;
        }
        XCloseDisplay(state.xtest_display);
        state.xtest_display = NULL;
    }
#endif
    double elapsed_ms = now_ms() - start_ms;
    double avg_present = state.commit_count > 0 ? state.total_present_ms / (double)state.commit_count : 0.0;
    double avg_app_wait = state.app_wait_samples > 0 ? state.total_app_wait_us / (double)state.app_wait_samples : 0.0;
    double avg_app_slot_wait = state.app_slot_wait_samples > 0 ? state.total_app_slot_wait_us / (double)state.app_slot_wait_samples : 0.0;
    printf(
        "wayland-shm-ahb summary commits=%d target=%d failures=%d elapsed-ms=%.2f avg-gpu-present-ms=%.3f avg-app-wait-us=%.1f avg-app-slot-wait-us=%.1f zero-copy=dmabuf-present\n",
        state.commit_count,
        state.target_commits,
        state.present_failures,
        elapsed_ms,
        avg_present,
        avg_app_wait,
        avg_app_slot_wait);

    // === BRIDGE HEALTH SUMMARY ===
    // Dump all diagnostic counters so the trace file has a complete picture
    // of what happened during this bridge session. This helps diagnose
    // current AND future bugs without needing additional builds.
    printf("\n");
    printf("=== BRIDGE HEALTH SUMMARY ===\n");
    printf("wayland-shm-ahb diag pools_created=%llu pools_destroyed=%llu\n",
           (unsigned long long)g_diag.pools_created,
           (unsigned long long)g_diag.pools_destroyed);
    printf("wayland-shm-ahb diag buffers_created=%llu buffers_destroyed=%llu\n",
           (unsigned long long)g_diag.buffers_created,
           (unsigned long long)g_diag.buffers_destroyed);
    printf("wayland-shm-ahb diag ahb_allocated=%llu ahb_released=%llu\n",
           (unsigned long long)g_diag.ahb_allocated,
           (unsigned long long)g_diag.ahb_released);
    printf("wayland-shm-ahb diag dmabuf_fds_exported=%llu dmabuf_fds_closed=%llu\n",
           (unsigned long long)g_diag.dmabuf_fds_exported,
           (unsigned long long)g_diag.dmabuf_fds_closed);
    printf("wayland-shm-ahb diag shm_to_ahb_calls=%llu shm_to_ahb_failures=%llu\n",
           (unsigned long long)g_diag.shm_to_ahb_calls,
           (unsigned long long)g_diag.shm_to_ahb_failures);
    printf("wayland-shm-ahb diag use_after_free_detected=%llu\n",
           (unsigned long long)g_diag.use_after_free_detected);
    printf("wayland-shm-ahb diag bridge_reconnects=%llu\n",
           (unsigned long long)g_diag.bridge_reconnects);
    // Leak detection: if created != destroyed, something is leaking.
    if (g_diag.pools_created != g_diag.pools_destroyed) {
        printf("wayland-shm-ahb diag WARNING pool leak: %llu created but only %llu destroyed\n",
               (unsigned long long)g_diag.pools_created,
               (unsigned long long)g_diag.pools_destroyed);
    }
    if (g_diag.buffers_created != g_diag.buffers_destroyed) {
        printf("wayland-shm-ahb diag WARNING buffer leak: %llu created but only %llu destroyed\n",
               (unsigned long long)g_diag.buffers_created,
               (unsigned long long)g_diag.buffers_destroyed);
    }
    if (g_diag.ahb_allocated != g_diag.ahb_released) {
        printf("wayland-shm-ahb diag WARNING AHB leak: %llu allocated but only %llu released\n",
               (unsigned long long)g_diag.ahb_allocated,
               (unsigned long long)g_diag.ahb_released);
    }
    if (g_diag.dmabuf_fds_exported != g_diag.dmabuf_fds_closed) {
        printf("wayland-shm-ahb diag WARNING fd leak: %llu exported but only %llu closed\n",
               (unsigned long long)g_diag.dmabuf_fds_exported,
               (unsigned long long)g_diag.dmabuf_fds_closed);
    }
    printf("wayland-shm-ahb diag present_ok=%llu present_fail=%llu\n",
           (unsigned long long)g_diag.surfaces_presented_ok,
           (unsigned long long)g_diag.surfaces_presented_fail);
    printf("=== END BRIDGE HEALTH SUMMARY ===\n");
    fflush(stdout);

    if ((state.commit_count >= state.target_commits || state.completed_after_client_exit)
            && state.present_failures == 0) {
        printf("wayland-shm-ahb verdict=pass\n");
        return 0;
    }
    printf("wayland-shm-ahb verdict=fail\n");
    return 1;
}
