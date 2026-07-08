/* ---------------------------------------------------------------------------
 * android_stubs.c — Stub implementations of Linux-specific functions that
 * are referenced by gamescope + wlroots but not available on Android bionic.
 *
 * These are LINK-TIME stubs. At runtime, the LD_PRELOAD shim
 * (android_sysvshm.so) provides real working implementations of shm_open /
 * shm_unlink backed by memfd_create(). The stubs here are only fallbacks
 * for the case where the shim isn't loaded (which shouldn't happen in
 * production, but provides a safety net).
 *
 * The libinput_* and udev_* stubs return failure values — gamescope's
 * libinput code path is dead on Android (we don't have /dev/input/event*
 * access). The wlserver_touch_associate_connector function that used these
 * is #if 0'd out in wlserver.cpp.
 *
 * Compile: ${NDK_CLANG} -c android_stubs.c -o android_stubs.o
 * Link: linked into the gamescope executable.
 * ------------------------------------------------------------------------- */

#include <stddef.h>
#include <stdint.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <sys/syscall.h>

/* ---- shm_open / shm_unlink ---- */
/* These match the LD_PRELOAD shim signatures. The shim takes precedence
 * at runtime; these are fallbacks. */
#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif
#ifndef MFD_ALLOW_SEALING
#define MFD_ALLOW_SEALING 0x0002U
#endif
#ifndef __NR_memfd_create
#define __NR_memfd_create 279  /* aarch64 */
#endif

static int memfd_create_raw(const char *name, unsigned int flags) {
    return (int)syscall(__NR_memfd_create, name, flags);
}

int shm_open(const char *name, int oflag, mode_t mode) {
    (void)oflag; (void)mode;
    if (!name) name = "anon";
    while (*name == '/') name++;
    int fd = memfd_create_raw(name, MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (fd < 0) {
        errno = ENOMEM;
        return -1;
    }
    return fd;
}

int shm_unlink(const char *name) {
    (void)name;
    /* memfd_create'd fds are anonymous; nothing to unlink. */
    return 0;
}

/* ---- libinput stubs ---- */
/* All return NULL / 0 / no-op. gamescope's libinput path is dead on Android. */

struct libinput;
struct libinput_device;
struct libinput_event;
struct libinput_event_pointer;
struct udev;
struct udev_device;

enum libinput_event_type { LIBINPUT_EVENT_NONE = 0 };

struct libinput *libinput_udev_create_context(const void *interface,
                                              void *user_data,
                                              struct udev *udev) {
    (void)interface; (void)user_data; (void)udev;
    return NULL;
}

int libinput_udev_assign_seat(struct libinput *ctx, const char *seat) {
    (void)ctx; (void)seat;
    return -1;
}

struct libinput *libinput_unref(struct libinput *ctx) {
    (void)ctx;
    return NULL;
}

int libinput_get_fd(struct libinput *ctx) {
    (void)ctx;
    return -1;
}

int libinput_dispatch(struct libinput *ctx) {
    (void)ctx;
    return 0;
}

struct libinput_event *libinput_get_event(struct libinput *ctx) {
    (void)ctx;
    return NULL;
}

enum libinput_event_type libinput_event_get_type(struct libinput_event *event) {
    (void)event;
    return LIBINPUT_EVENT_NONE;
}

struct libinput_event_pointer *libinput_event_get_pointer_event(struct libinput_event *event) {
    (void)event;
    return NULL;
}

double libinput_event_pointer_get_dx(struct libinput_event_pointer *pev) {
    (void)pev; return 0.0;
}
double libinput_event_pointer_get_dy(struct libinput_event_pointer *pev) {
    (void)pev; return 0.0;
}
double libinput_event_pointer_get_absolute_x(struct libinput_event_pointer *pev) {
    (void)pev; return 0.0;
}
double libinput_event_pointer_get_absolute_y(struct libinput_event_pointer *pev) {
    (void)pev; return 0.0;
}
uint32_t libinput_event_pointer_get_button(struct libinput_event_pointer *pev) {
    (void)pev; return 0;
}
enum { LIBINPUT_BUTTON_STATE_RELEASED = 0, LIBINPUT_BUTTON_STATE_PRESSED = 1 };
uint32_t libinput_event_pointer_get_button_state(struct libinput_event_pointer *pev) {
    (void)pev; return LIBINPUT_BUTTON_STATE_RELEASED;
}
int libinput_event_pointer_has_axis(struct libinput_event_pointer *pev, uint32_t axis) {
    (void)pev; (void)axis; return 0;
}
double libinput_event_pointer_get_scroll_value_v120(struct libinput_event_pointer *pev, uint32_t axis) {
    (void)pev; (void)axis; return 0.0;
}
double libinput_event_pointer_get_axis_value(struct libinput_event_pointer *pev, uint32_t axis) {
    (void)pev; (void)axis; return 0.0;
}
void libinput_event_destroy(struct libinput_event *event) { (void)event; }
const char *libinput_device_get_name(struct libinput_device *dev) {
    (void)dev; return "stub";
}
struct udev_device *libinput_device_get_udev_device(struct libinput_device *dev) {
    (void)dev; return NULL;
}
struct libinput_device *libinput_device_ref(struct libinput_device *dev) { return dev; }
struct libinput_device *libinput_device_unref(struct libinput_device *dev) {
    (void)dev; return NULL;
}
void libinput_log_set_priority(struct libinput *ctx, int priority) { (void)ctx; (void)priority; }
int libinput_log_get_priority(struct libinput *ctx) { (void)ctx; return 0; }
void libinput_log_set_handler(struct libinput *ctx, void *handler) {
    (void)ctx; (void)handler;
}

/* ---- udev stubs ---- */
struct udev *udev_new(void) { return NULL; }
struct udev *udev_unref(struct udev *u) { (void)u; return NULL; }
struct udev *udev_ref(struct udev *u) { return u; }
struct udev_device *udev_device_unref(struct udev_device *d) { (void)d; return NULL; }
struct udev_device *udev_device_ref(struct udev_device *d) { return d; }
const char *udev_device_get_subsystem(struct udev_device *d) { (void)d; return NULL; }
struct udev_device *udev_device_get_parent(struct udev_device *d) { (void)d; return NULL; }
const char *udev_device_get_sysattr_value(struct udev_device *d, const char *sysattr) {
    (void)d; (void)sysattr; return NULL;
}

/* ---- wlr_libinput_get_device_handle stub ---- */
/* wlroots exports this but on Android build it's compiled out. Provide
 * a stub so the linker is happy. */
struct libinput_device *wlr_libinput_get_device_handle(void *wlr_input_device) {
    (void)wlr_input_device;
    return NULL;
}

/* ---- libdecor stubs ---- */
/* gamescope uses libdecor for window decoration on the nested Wayland backend.
 * On Android, the WaylandIE bridge handles window management directly — libdecor
 * is just a linker stub. All functions return failure / NULL. */
#include <libdecor.h>
#include <stdbool.h>

#pragma GCC diagnostic ignored "-Wunused-parameter"

struct libdecor *libdecor_new(struct wl_display *display, struct libdecor_interface *iface) {
    (void)display; (void)iface;
    return NULL;
}
void libdecor_unref(struct libdecor *ctx) { (void)ctx; }
int libdecor_get_fd(struct libdecor *ctx) { (void)ctx; return -1; }
int libdecor_dispatch(struct libdecor *ctx, int timeout) { (void)ctx; (void)timeout; return 0; }
struct libdecor_frame *libdecor_decorate(struct libdecor *ctx, struct wl_surface *surface,
                                          struct libdecor_frame_interface *iface, void *user_data) {
    (void)ctx; (void)surface; (void)iface; (void)user_data;
    return NULL;
}
void libdecor_frame_ref(struct libdecor_frame *frame) { (void)frame; }
void libdecor_frame_unref(struct libdecor_frame *frame) { (void)frame; }
void libdecor_frame_set_visibility(struct libdecor_frame *frame, bool visible) { (void)frame; (void)visible; }
bool libdecor_frame_is_visible(struct libdecor_frame *frame) { (void)frame; return false; }
void libdecor_frame_set_parent(struct libdecor_frame *frame, struct libdecor_frame *parent) { (void)frame; (void)parent; }
void libdecor_frame_set_title(struct libdecor_frame *frame, const char *title) { (void)frame; (void)title; }
const char *libdecor_frame_get_title(struct libdecor_frame *frame) { (void)frame; return NULL; }
void libdecor_frame_set_app_id(struct libdecor_frame *frame, const char *app_id) { (void)frame; (void)app_id; }
void libdecor_frame_set_capabilities(struct libdecor_frame *frame, enum libdecor_capabilities caps) { (void)frame; (void)caps; }
void libdecor_frame_unset_capabilities(struct libdecor_frame *frame, enum libdecor_capabilities caps) { (void)frame; (void)caps; }
bool libdecor_frame_has_capability(struct libdecor_frame *frame, enum libdecor_capabilities caps) { (void)frame; (void)caps; return false; }
void libdecor_frame_show_window_menu(struct libdecor_frame *frame, struct wl_seat *wl_seat, uint32_t serial, int x, int y) { (void)frame; (void)wl_seat; (void)serial; (void)x; (void)y; }
void libdecor_frame_set_fullscreen(struct libdecor_frame *frame, struct wl_output *output) { (void)frame; (void)output; }
void libdecor_frame_unset_fullscreen(struct libdecor_frame *frame) { (void)frame; }
bool libdecor_frame_is_floating(struct libdecor_frame *frame) { (void)frame; return true; }
void libdecor_frame_close(struct libdecor_frame *frame) { (void)frame; }
void libdecor_frame_map(struct libdecor_frame *frame) { (void)frame; }
void libdecor_frame_commit(struct libdecor_frame *frame, struct libdecor_state *state, struct libdecor_configuration *configuration) { (void)frame; (void)state; (void)configuration; }
void libdecor_frame_dismiss_popup(struct libdecor_frame *frame, const char *popup_name) { (void)frame; (void)popup_name; }
struct xdg_surface *libdecor_frame_get_xdg_surface(struct libdecor_frame *frame) { (void)frame; return NULL; }
struct xdg_toplevel *libdecor_frame_get_xdg_toplevel(struct libdecor_frame *frame) { (void)frame; return NULL; }
void libdecor_frame_translate_coordinate(struct libdecor_frame *frame, int coordinate_x, int coordinate_y, int *translated_x, int *translated_y) { (void)frame; (void)coordinate_x; (void)coordinate_y; if (translated_x) *translated_x = coordinate_x; if (translated_y) *translated_y = coordinate_y; }

struct libdecor_state *libdecor_state_new(int width, int height) { (void)width; (void)height; return NULL; }
void libdecor_state_free(struct libdecor_state *state) { (void)state; }

bool libdecor_configuration_get_content_size(struct libdecor_configuration *configuration, struct libdecor_frame *frame, int *width, int *height) { (void)configuration; (void)frame; if (width) *width = 0; if (height) *height = 0; return false; }
bool libdecor_configuration_get_window_state(struct libdecor_configuration *configuration, enum libdecor_window_state *window_state) { (void)configuration; if (window_state) *window_state = (enum libdecor_window_state)0; return false; }


/* ---- DRMBackend stubs (DRMBackend.cpp not compiled on Android) ---- */
namespace gamescope {
    /* ConVar<bool> stub — DRMBackend defines the real one. We just need a
     * symbol to satisfy the linker. */
    template<typename T> class ConVarStub {
    public:
        T value;
        ConVarStub(const char *name, T def, const char *desc) : value(def) { (void)name; (void)desc; }
        operator T() const { return value; }
        T Get() const { return value; }
    };
}

/* Use a bool stub for cv_drm_debug_disable_explicit_sync.
 * The real ConVar<bool> type is gamescope::ConVar<bool> from convar.h.
 * We can't easily reproduce that template here; instead we provide
 * a plain bool with the same symbol name via C-style extern. */
extern "C" {
    /* gamescope::ConVar<bool> has operator bool() and Get(). We can't
     * easily make a C stub match C++ ABI. Easiest: provide a C++ stub
     * using a minimal ConVar<bool>-compatible struct. */
}

/* The simplest workaround: include convar.h and define a real ConVar<bool>. */
#include "convar.h"
namespace gamescope {
    ConVar<bool> cv_drm_debug_disable_explicit_sync("drm_debug_disable_explicit_sync", false, "Stub - DRM backend not built");
}

/* drm_sleep_screen stub */
#include "backend.h"
extern "C" void drm_sleep_screen(gamescope::GamescopeScreenType eType, bool bSleep) {
    (void)eType; (void)bSleep;
}

/* ---- Additional libinput stubs ---- */
struct libinput_event_keyboard;
enum libinput_keyboard_key_state { LIBINPUT_KEYBOARD_KEY_STATE_RELEASED = 0, LIBINPUT_KEYBOARD_KEY_STATE_PRESSED = 1 };
struct libinput_event_keyboard *libinput_event_get_keyboard_event(struct libinput_event *event) { (void)event; return NULL; }
uint32_t libinput_event_keyboard_get_key(struct libinput_event_keyboard *kev) { (void)kev; return 0; }
enum libinput_keyboard_key_state libinput_event_keyboard_get_key_state(struct libinput_event_keyboard *kev) { (void)kev; return LIBINPUT_KEYBOARD_KEY_STATE_RELEASED; }
struct libinput_event_touch *libinput_event_get_touch_event(struct libinput_event *event) { (void)event; return NULL; }
double libinput_event_touch_get_x(struct libinput_event_touch *tev) { (void)tev; return 0.0; }
double libinput_event_touch_get_y(struct libinput_event_touch *tev) { (void)tev; return 0.0; }
int32_t libinput_event_touch_get_seat_slot(struct libinput_event_touch *tev) { (void)tev; return 0; }

/* ---- Additional X11 stubs ---- */
XFixesCursorImage *XFixesGetCursorImage(Display *d) { (void)d; return NULL; }
KeyCode XKeysymToKeycode(Display *d, KeySym k) { (void)d; (void)k; return 0; }
Cursor XRenderCreateCursor(Display *d, Picture src, unsigned int x, unsigned int y) { (void)d; (void)src; (void)x; (void)y; return 0; }

/* ---- wlroots xwayland stubs (we built wlroots without xwayland) ---- */
struct wlr_xwayland_server;
struct wlr_xwayland_server *wlr_xwayland_server_create(struct wl_display *display, const void *options) {
    (void)display; (void)options;
    return NULL;
}
void wlr_xwayland_server_destroy(struct wlr_xwayland_server *server) { (void)server; }

