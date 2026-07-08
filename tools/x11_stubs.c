/* ---------------------------------------------------------------------------
 * x11_stubs.c — Provides linker symbol definitions for X11/libinput/udev
 * functions referenced by gamescope + wlroots when the actual X11/libinput
 * shared libraries aren't available (Android rootless).
 *
 * Strategy: We DON'T redefine any X11 type — we use the real X11 headers
 * from Debian's libX11-dev etc. Instead, we provide empty function bodies
 * with `__attribute__((weak))` so they satisfy the linker without
 * conflicting with any system-installed X11 libraries.
 *
 * At runtime, these functions are never called when gamescope runs in
 * pure-Wayland nested mode without Xwayland.
 *
 * Build: linked into the gamescope executable as an extra source file.
 * ------------------------------------------------------------------------- */

/* Pull in all the X11 type definitions and function DECLARATIONS.
 * We will then provide DEFINITIONS for the same functions. */
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/Xatom.h>
#include <X11/Xlibint.h>

/* Undefine Xutil.h macros that conflict with our function definitions */
#ifdef XDestroyImage
#undef XDestroyImage
#endif
#ifdef XGetPixel
#undef XGetPixel
#endif
#ifdef XPutPixel
#undef XPutPixel
#endif
#ifdef XSubImage
#undef XSubImage
#endif
#ifdef XAddPixel
#undef XAddPixel
#endif
#include <X11/extensions/XShm.h>
#include <X11/extensions/Xfixes.h>
#include <X11/extensions/Xcomposite.h>
#include <X11/extensions/Xdamage.h>
#include <X11/extensions/Xrender.h>
#include <X11/extensions/Xrandr.h>
#include <X11/Xcursor/Xcursor.h>
#include <X11/extensions/xf86vmode.h>
#include <X11/extensions/XTest.h>
#include <X11/extensions/XRes.h>
#include <X11/Xmu/CurUtil.h>
#include <X11/extensions/XInput.h>

/* Mark all our stub definitions as weak so they don't conflict with
 * system-installed X11 shared libraries (if any) at runtime. On Android
 * there are no such libraries, so the linker uses our definitions. */
#define WEAK __attribute__((weak))

#pragma GCC diagnostic ignored "-Wunused-parameter"

/* The trick: since the X11 headers already declare these functions as
 * `extern`, we just provide the definitions here. C linkage, no name
 * mangling. We use the EXACT signature from the header to avoid conflicts. */

WEAK Display *XOpenDisplay(_Xconst char *name) { return NULL; }
WEAK int XCloseDisplay(Display *d) { return 0; }
WEAK int XFlush(Display *d) { return 0; }
WEAK int XSync(Display *d, Bool discard) { return 0; }
WEAK Window XDefaultRootWindow(Display *d) { return 0; }
WEAK Visual *XDefaultVisual(Display *d, int s) { return NULL; }
WEAK int XDefaultScreen(Display *d) { return 0; }
WEAK Window XRootWindow(Display *d, int s) { return 0; }
WEAK Atom XInternAtom(Display *d, _Xconst char *n, Bool o) { return 0; }
WEAK char *XGetAtomName(Display *d, Atom a) { return NULL; }
WEAK int XChangeProperty(Display *d, Window w, Atom p, Atom t, int f, int m,
                         _Xconst unsigned char *data, int n) { return 0; }
WEAK int XDeleteProperty(Display *d, Window w, Atom p) { return 0; }
WEAK int XGetWindowProperty(Display *d, Window w, Atom p, long off, long len,
                            Bool del, Atom req, Atom *type, int *fmt,
                            unsigned long *nitems, unsigned long *bytes_after,
                            unsigned char **data) { return 1; }
WEAK Status XSetWMProtocols(Display *d, Window w, Atom *protocols, int count) { return 0; }
WEAK int XMapWindow(Display *d, Window w) { return 0; }
WEAK int XUnmapWindow(Display *d, Window w) { return 0; }
WEAK int XMoveWindow(Display *d, Window w, int x, int y) { return 0; }
WEAK int XResizeWindow(Display *d, Window w, unsigned int width, unsigned int height) { return 0; }
WEAK int XMoveResizeWindow(Display *d, Window w, int x, int y, unsigned int width, unsigned int height) { return 0; }
WEAK int XDestroyWindow(Display *d, Window w) { return 0; }
WEAK Window XCreateWindow(Display *d, Window parent, int x, int y,
                          unsigned int width, unsigned int height,
                          unsigned int border_width, int depth,
                          unsigned int class, Visual *visual,
                          unsigned long valuemask,
                          XSetWindowAttributes *attributes) { return 0; }
WEAK Window XCreateSimpleWindow(Display *d, Window parent, int x, int y,
                                unsigned int width, unsigned int height,
                                unsigned int border_width,
                                unsigned long border, unsigned long background) { return 0; }
WEAK int XChangeWindowAttributes(Display *d, Window w, unsigned long valuemask,
                                 XSetWindowAttributes *attrs) { return 0; }
WEAK int XGetWindowAttributes(Display *d, Window w, XWindowAttributes *attrs) { return 0; }
WEAK int XSetInputFocus(Display *d, Window w, int r, Time t) { return 0; }
WEAK int XGetInputFocus(Display *d, Window *focus, int *revert) { return 0; }
WEAK int XWarpPointer(Display *d, Window src, Window dst, int sx, int sy,
                      unsigned int sw, unsigned int sh, int dx, int dy) { return 0; }
WEAK int XGrabPointer(Display *d, Window w, Bool owner, unsigned int event_mask,
                      int p, int k, Window confine, Cursor c, Time t) { return GrabNotViewable; }
WEAK int XUngrabPointer(Display *d, Time t) { return 0; }
WEAK int XGrabKeyboard(Display *d, Window w, Bool owner, int p, int k, Time t) { return GrabNotViewable; }
WEAK int XUngrabKeyboard(Display *d, Time t) { return 0; }
WEAK int XDefineCursor(Display *d, Window w, Cursor c) { return 0; }
WEAK int XUndefineCursor(Display *d, Window w) { return 0; }
WEAK Cursor XCreateFontCursor(Display *d, unsigned int shape) { return 0; }
WEAK Cursor XCreatePixmapCursor(Display *d, Pixmap src, Pixmap mask,
                                XColor *fg, XColor *bg,
                                unsigned int x, unsigned int y) { return 0; }
WEAK int XFreeCursor(Display *d, Cursor c) { return 0; }
WEAK int XFree(void *data) { return 0; }
WEAK int XNextEvent(Display *d, XEvent *ev) { return 0; }
WEAK int XPeekEvent(Display *d, XEvent *ev) { return 0; }
WEAK int XSendEvent(Display *d, Window w, Bool prop, long mask, XEvent *ev) { return 0; }
WEAK int XPutBackEvent(Display *d, XEvent *ev) { return 0; }
WEAK int XPending(Display *d) { return 0; }
WEAK int XQLength(Display *d) { return 0; }
WEAK int XEventsQueued(Display *d, int mode) { return 0; }
WEAK int XSelectInput(Display *d, Window w, long mask) { return 0; }
WEAK XErrorHandler XSetErrorHandler(XErrorHandler h) { return NULL; }
WEAK XIOErrorHandler XSetIOErrorHandler(XIOErrorHandler h) { return NULL; }
WEAK unsigned long XAllPlanes(void) { return 0; }
WEAK unsigned long XBlackPixel(Display *d, int s) { return 0; }
WEAK unsigned long XWhitePixel(Display *d, int s) { return 0; }
WEAK int XConnectionNumber(Display *d) { return 0; }
WEAK int XDisplayWidth(Display *d, int s) { return 0; }
WEAK int XDisplayHeight(Display *d, int s) { return 0; }
WEAK void XSetWMName(Display *d, Window w, XTextProperty *tp) {}
WEAK void XSetWMIconName(Display *d, Window w, XTextProperty *tp) {}
WEAK XWMHints *XGetWMHints(Display *d, Window w) { return NULL; }
WEAK int XSetWMHints(Display *d, Window w, XWMHints *wmhints) { return 0; }
WEAK int XSetTransientForHint(Display *d, Window w, Window prop_window) { return 0; }
WEAK int XSetWindowBackgroundPixmap(Display *d, Window w, Pixmap p) { return 0; }
WEAK int XClearWindow(Display *d, Window w) { return 0; }
WEAK int XCopyArea(Display *d, Drawable src, Drawable dest, GC gc,
                   int sx, int sy, unsigned int w, unsigned int h,
                   int dx, int dy) { return 0; }
WEAK int XCopyPlane(Display *d, Drawable src, Drawable dest, GC gc,
                    int sx, int sy, unsigned int w, unsigned int h,
                    int dx, int dy, unsigned long plane) { return 0; }
WEAK int XSetFillStyle(Display *d, GC gc, int fill_style) { return 0; }
WEAK int XSetForeground(Display *d, GC gc, unsigned long fg) { return 0; }
WEAK GC XCreateGC(Display *d, Drawable dr, unsigned long mask, XGCValues *vals) { return NULL; }
WEAK int XFreeGC(Display *d, GC gc) { return 0; }
WEAK Pixmap XCreatePixmap(Display *d, Drawable dr, unsigned int w, unsigned int h, unsigned int depth) { return 0; }
WEAK int XFreePixmap(Display *d, Pixmap p) { return 0; }
WEAK int XSetClipMask(Display *d, GC gc, Pixmap p) { return 0; }
WEAK int XSetClipOrigin(Display *d, GC gc, int x, int y) { return 0; }
WEAK int XDrawPoint(Display *d, Drawable dr, GC gc, int x, int y) { return 0; }
WEAK int XDrawLine(Display *d, Drawable dr, GC gc, int x1, int y1, int x2, int y2) { return 0; }
WEAK int XDrawRectangle(Display *d, Drawable dr, GC gc, int x, int y, unsigned int w, unsigned int h) { return 0; }
WEAK int XFillRectangle(Display *d, Drawable dr, GC gc, int x, int y, unsigned int w, unsigned int h) { return 0; }
WEAK int XPutImage(Display *d, Drawable dr, GC gc, XImage *image,
                   int sx, int sy, int dx, int dy, unsigned int w, unsigned int h) { return 0; }
WEAK XImage *XCreateImage(Display *d, Visual *v, unsigned int depth, int format,
                          int offset, char *data, unsigned int w, unsigned int h,
                          int bitmap_pad, int bytes_per_line) { return NULL; }
WEAK XImage *XGetImage(Display *d, Drawable dr, int x, int y, unsigned int w, unsigned int h,
                       unsigned long plane, int format) { return NULL; }
WEAK int XDestroyImage(XImage *image) { return 0; }
WEAK int XGetGeometry(Display *d, Drawable dr, Window *root, int *x, int *y,
                      unsigned int *w, unsigned int *h, unsigned int *bw, unsigned int *depth) { return 0; }
WEAK int XQueryTree(Display *d, Window w, Window *root, Window *parent,
                    Window **children, unsigned int *nchildren) { return 0; }
WEAK int XTranslateCoordinates(Display *d, Window src, Window dest, int sx, int sy,
                               int *dx, int *dy, Window *child) { return 0; }
WEAK Bool XQueryPointer(Display *d, Window w, Window *root, Window *child,
                        int *rx, int *ry, int *wx, int *wy, unsigned int *mask) { return False; }
WEAK int XSetSelectionOwner(Display *d, Atom sel, Window w, Time t) { return 0; }
WEAK Window XGetSelectionOwner(Display *d, Atom sel) { return 0; }
WEAK int XConvertSelection(Display *d, Atom sel, Atom target, Atom prop, Window w, Time t) { return 0; }
WEAK int XGrabServer(Display *d) { return 0; }
WEAK int XUngrabServer(Display *d) { return 0; }
WEAK int XStoreName(Display *d, Window w, _Xconst char *name) { return 0; }
WEAK int XFetchName(Display *d, Window w, char **name) { return 0; }
WEAK Status XGetWMName(Display *d, Window w, XTextProperty *tp) { return 0; }
WEAK int XSetCommand(Display *d, Window w, char **argv, int argc) { return 0; }
WEAK Status XGetCommand(Display *d, Window w, char ***argv, int *argc) { return 0; }
WEAK int XKillClient(Display *d, XID resource) { return 0; }
WEAK int XRestackWindows(Display *d, Window *windows, int n) { return 0; }
WEAK int XLowerWindow(Display *d, Window w) { return 0; }
WEAK int XRaiseWindow(Display *d, Window w) { return 0; }
WEAK int XReparentWindow(Display *d, Window w, Window parent, int x, int y) { return 0; }
WEAK int XConfigureWindow(Display *d, Window w, unsigned int mask, XWindowChanges *changes) { return 0; }
WEAK int XSetWindowBorderWidth(Display *d, Window w, unsigned int width) { return 0; }
WEAK int XSetWindowBackground(Display *d, Window w, unsigned long pixel) { return 0; }
WEAK int XSetWindowBorder(Display *d, Window w, unsigned long pixel) { return 0; }
WEAK int XIconifyWindow(Display *d, Window w, int screen) { return 0; }
WEAK int XWithdrawWindow(Display *d, Window w, int screen) { return 0; }
WEAK int XReconfigureWMWindow(Display *d, Window w, int screen, unsigned int mask, XWindowChanges *changes) { return 0; }
WEAK Status XGetWMProtocols(Display *d, Window w, Atom **protocols, int *count) { return 0; }
WEAK void XSetWMProperties(Display *d, Window w, XTextProperty *window_name,
                           XTextProperty *icon_name, char **argv, int argc,
                           XSizeHints *normal_hints, XWMHints *wm_hints,
                           XClassHint *class_hints) {}
WEAK Status XGetWMNormalHints(Display *d, Window w, XSizeHints *hints, long *supplied) { return 0; }
WEAK void XSetWMNormalHints(Display *d, Window w, XSizeHints *hints) {}
WEAK XWMHints *XAllocWMHints(void) { return NULL; }
WEAK XSizeHints *XAllocSizeHints(void) { return NULL; }
WEAK XClassHint *XAllocClassHint(void) { return NULL; }
WEAK int XmbTextListToTextProperty(Display *d, char **list, int count,
                                   XICCEncodingStyle style, XTextProperty *tp) { return 0; }
WEAK int Xutf8TextListToTextProperty(Display *d, char **list, int count,
                                     XICCEncodingStyle style, XTextProperty *tp) { return 0; }
WEAK void XFreeStringList(char **list) {}
WEAK void XrmInitialize(void) {}
WEAK char *XResourceManagerString(Display *d) { return NULL; }
WEAK int XInitThreads(void) { return 0; }
WEAK void XLockDisplay(Display *d) {}
WEAK void XUnlockDisplay(Display *d) {}
WEAK Display *XDisplayOfScreen(Screen *s) { return NULL; }

/* libXext (MIT-SHM) */
WEAK Bool XShmQueryExtension(Display *d) { return False; }
WEAK Bool XShmQueryVersion(Display *d, int *maj, int *min, Bool *pixmaps) { return False; }
WEAK Bool XShmAttach(Display *d, XShmSegmentInfo *shminfo) { return False; }
WEAK Bool XShmDetach(Display *d, XShmSegmentInfo *shminfo) { return False; }
WEAK Bool XShmPutImage(Display *d, Drawable dr, GC gc, XImage *image,
                       int sx, int sy, int dx, int dy,
                       unsigned int w, unsigned int h, Bool send_event) { return False; }
WEAK XImage *XShmCreateImage(Display *d, Visual *v, unsigned int depth, int format,
                             char *data, XShmSegmentInfo *shminfo,
                             unsigned int w, unsigned int h) { return NULL; }
WEAK int XShmGetEventBase(Display *d) { return 0; }

/* libXfixes */
WEAK Bool XFixesQueryExtension(Display *d, int *ev, int *err) { return False; }
WEAK Status XFixesQueryVersion(Display *d, int *maj, int *min) { return 0; }
WEAK void XFixesHideCursor(Display *d, Window w) {}
WEAK void XFixesShowCursor(Display *d, Window w) {}
WEAK void XFixesSetCursorName(Display *d, Cursor c, _Xconst char *name) {}
WEAK _Xconst char *XFixesGetCursorName(Display *d, Cursor c, Atom *atom) { if(atom) *atom = 0; return NULL; }

/* libXcomposite */
WEAK Bool XCompositeQueryExtension(Display *d, int *ev, int *err) { return False; }
WEAK Status XCompositeQueryVersion(Display *d, int *maj, int *min) { return 0; }
WEAK void XCompositeRedirectWindow(Display *d, Window w, int update) {}
WEAK void XCompositeRedirectSubwindows(Display *d, Window w, int update) {}
WEAK void XCompositeUnredirectWindow(Display *d, Window w, int update) {}
WEAK void XCompositeUnredirectSubwindows(Display *d, Window w, int update) {}
WEAK Pixmap XCompositeNameWindowPixmap(Display *d, Window w) { return 0; }

/* libXdamage */
WEAK Bool XDamageQueryExtension(Display *d, int *ev, int *err) { return False; }
WEAK Status XDamageQueryVersion(Display *d, int *maj, int *min) { return 0; }
WEAK Damage XDamageCreate(Display *d, Drawable dr, int level) { return 0; }
WEAK void XDamageDestroy(Display *d, Damage damage) {}
WEAK void XDamageSubtract(Display *d, Damage damage, XserverRegion repair, XserverRegion parts) {}
WEAK void XDamageAdd(Display *d, Drawable dr, XserverRegion region) {}

/* libXrender */
WEAK Bool XRenderQueryExtension(Display *d, int *ev, int *err) { return False; }
WEAK Status XRenderQueryVersion(Display *d, int *maj, int *min) { return 0; }
WEAK Status XRenderQueryFormats(Display *d) { return 0; }
WEAK XRenderPictFormat *XRenderFindVisualFormat(Display *d, _Xconst Visual *v) { return NULL; }
WEAK XRenderPictFormat *XRenderFindStandardFormat(Display *d, int format) { return NULL; }
WEAK Picture XRenderCreatePicture(Display *d, Drawable dr, _Xconst XRenderPictFormat *fmt,
                                  unsigned long mask, _Xconst XRenderPictureAttributes *attrs) { return 0; }
WEAK void XRenderFreePicture(Display *d, Picture pic) {}
WEAK void XRenderSetPictureTransform(Display *d, Picture pic, XTransform *transform) {}
WEAK void XRenderComposite(Display *d, int op, Picture src, Picture mask, Picture dst,
                           int sx, int sy, int mx, int my, int dx, int dy,
                           unsigned int w, unsigned int h) {}
WEAK void XRenderSetPictureClipRectangles(Display *d, Picture pic, int x, int y,
                                          _Xconst XRectangle *rects, int n) {}
WEAK void XRenderFillRectangle(Display *d, int op, Picture dst, _Xconst XRenderColor *color,
                               int x, int y, unsigned int w, unsigned int h) {}

/* libXcursor */
WEAK Cursor XcursorLibraryLoadCursor(Display *d, _Xconst char *name) { return 0; }
WEAK Cursor XcursorImageCreateCursor(Display *d, _Xconst XcursorImage *image) { return 0; }
WEAK XcursorImage *XcursorImageCreate(int width, int height) { return NULL; }
WEAK void XcursorImageDestroy(XcursorImage *image) {}

/* libXxf86vm */
WEAK Bool XF86VidModeQueryExtension(Display *d, int *ev, int *err) { return False; }
WEAK Bool XF86VidModeQueryVersion(Display *d, int *maj, int *min) { return False; }
WEAK Bool XF86VidModeGetModeLine(Display *d, int screen, int *dotclock,
                                 XF86VidModeModeLine *modeline) { return False; }
WEAK Bool XF86VidModeGetAllModeLines(Display *d, int screen, int *modecount,
                                     XF86VidModeModeInfo ***modelinesPtr) { return False; }
WEAK Bool XF86VidModeSwitchToMode(Display *d, int screen, XF86VidModeModeInfo *modeline) { return False; }
WEAK Bool XF86VidModeSetViewPort(Display *d, int screen, int x, int y) { return False; }

/* libXtst */
WEAK Bool XTestQueryExtension(Display *d, int *ev, int *err, int *maj, int *min) { return False; }
WEAK Bool XTestFakeMotionEvent(Display *d, int screen, int x, int y, unsigned long delay) { return False; }
WEAK Bool XTestFakeButtonEvent(Display *d, unsigned int button, Bool is_press, unsigned long delay) { return False; }
WEAK Bool XTestFakeKeyEvent(Display *d, unsigned int keycode, Bool is_press, unsigned long delay) { return False; }
WEAK Bool XTestFakeRelativeMotionEvent(Display *d, int x, int y, unsigned long delay) { return False; }

/* libXres */
WEAK Bool XResQueryExtension(Display *d, int *ev, int *err) { return False; }
WEAK Status XResQueryVersion(Display *d, int *maj, int *min) { return 0; }
WEAK Status XResQueryClients(Display *d, int *num_clients, XResClient **clients) { return 0; }
WEAK Status XResQueryClientResources(Display *d, unsigned long xid, int *num_types, XResType **types) { return 0; }
WEAK Status XResQueryClientPixmapBytes(Display *d, unsigned long xid, unsigned long *pixbytes) { return 0; }

/* libXmu — provided by inline stubs in X11/Xmu/CurUtil.h */

/* libXi */
WEAK XDevice *XOpenDevice(Display *d, XID id) { return NULL; }
WEAK int XCloseDevice(Display *d, XDevice *device) { return 0; }
WEAK XDeviceInfo *XListInputDevices(Display *d, int *num) { return NULL; }
WEAK void XFreeDeviceList(XDeviceInfo *devices) {}
WEAK int XSelectExtensionEvent(Display *d, Window w, XEventClass *classes, int nclasses) { return 0; }

/* libXrandr */
WEAK Bool XRRQueryExtension(Display *d, int *ev, int *err) { return False; }
WEAK Status XRRQueryVersion(Display *d, int *maj, int *min) { return 0; }
WEAK XRRScreenConfiguration *XRRGetScreenInfo(Display *d, Window w) { return NULL; }
WEAK void XRRFreeScreenConfigInfo(XRRScreenConfiguration *config) {}
WEAK Status XRRSetScreenConfig(Display *d, XRRScreenConfiguration *config, Window w,
                               int size_index, Rotation rotation, Time timestamp) { return 0; }
WEAK XRRScreenSize *XRRConfigSizes(XRRScreenConfiguration *config, int *nsizes) { return NULL; }
WEAK short XRRConfigCurrentRate(XRRScreenConfiguration *config) { return 0; }
WEAK short *XRRConfigRates(XRRScreenConfiguration *config, int size_index, int *nrates) { return NULL; }
WEAK Rotation XRRConfigCurrentConfiguration(XRRScreenConfiguration *config, Rotation *rotation) { return 0; }
WEAK Bool XRRGetScreenSizeRange(Display *d, Window w, int *minWidth, int *minHeight,
                                int *maxWidth, int *maxHeight) { return False; }
WEAK void XRRSetScreenSize(Display *d, Window w, int width, int height, int mmWidth, int mmHeight) {}
