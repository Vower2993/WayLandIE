/* Stub X11/Xlib.h for Android cross-compilation.
 * Provides minimal types needed by vulkan_xlib.h.
 * We never call Xlib APIs — the adrenotools wrapper ignores dpy and
 * uses the ANativeWindow directly. */
#ifndef _STUB_XLIB_H
#define _STUB_XLIB_H

typedef struct _XDisplay Display;
typedef unsigned long Window;
typedef unsigned long XID;
typedef unsigned long VisualID;
typedef int Bool;

typedef struct {
    int ext_data;
    VisualID visualid;
    int class;
    unsigned long red_mask, green_mask, blue_mask;
    int bits_per_rgb;
    int map_entries;
} Visual;

#endif /* _STUB_XLIB_H */
