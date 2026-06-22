# Hybrid X11 + Wayland Architecture for WayLandIE

## Motivation

The pure-Wayland approach (`winewayland.drv`) is bleeding-edge and has been
fighting us for weeks: bridge timeouts, SHM vs dmabuf confusion,
`lock_display_devices` errors, etc. Wine's `winex11.drv` has 15+ years of
maturation, is what Wine's `explorer.exe` desktop was designed for, and is
what every other Wine-on-Android project (Winlator, Mobox, etc.) uses.

**User's insight**: Display the Wine desktop/explorer via X11 (proven path),
then route game frames through Wayland + dmabuf (zero-copy path).

## Why this works

Wine loads ONE display driver at startup based on the `GraphicsDriver`
registry key. That driver handles ALL windows — desktop and games alike.
So we can't simultaneously use `winex11.drv` for the desktop and
`winewayland.drv` for the game in a single Wine process.

BUT — we can get the same effect by running Wine with `winex11.drv` against
a **custom X server whose root-window framebuffer is an AHardwareBuffer**
(which on Android IS a dmabuf). The custom X server then hands that dmabuf
fd to our existing Wayland bridge, which displays it on the Android
`SurfaceView` exactly as it does today.

Result:
- Desktop/explorer works reliably (X11 is mature)
- Game frames also flow through dmabuf → Android Surface (zero-copy)
- The existing Wayland bridge code is REUSED unchanged
- No more `winewayland.drv` debugging

## Architecture

```
Android App
+-------------+    +---------------+    +------------------+
| SurfaceView |<-- | Wayland Bridge|<-- | Custom X Server  |
| (Display)   |    | (existing,    |    | (NEW:            |
|             |    |  UNCHANGED)   |    |  libwaylandie_   |
|             |    |               |    |  xserver.so)     |
+-------------+    +---------------+    +------------------+
                        ^                       ^
                        | dmabuf fd             | X11 socket
                        | (sent via              | /tmp/.
                        |  SCM_RIGHTS)           | X11-unix/X0
                        |                       |
                   +-------------------------------------+
                   |  Wine Process (winex11.drv)         |
                   |  - explorer.exe (desktop)           |
                   |  - wineserver                       |
                   |  - <game>.exe (when launched)       |
                   +-------------------------------------+
```

## Component responsibilities

### 1. Custom X Server (`libwaylandie_xserver.so`, NEW)

A minimal X server written in C++ that implements enough of the X11 core
protocol + key extensions to satisfy Wine's `winex11.drv`. It is NOT a
full X.org server - it is closer to Winlator's Java XServer, but in C++
for direct AHardwareBuffer access.

**Framebuffer**: The root window's pixels live in an `AHardwareBuffer`
allocated with format `AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM` and usage
`AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | CPU_WRITE_OFTEN | GPU_SAMPLED_IMAGE`
(the GPU_SAMPLED_IMAGE flag is what allows the buffer to be imported as a
Vulkan external memory / dmabuf on the bridge side).

When Wine calls `PutImage` / `CopyArea` / `PolyFillRectangle` etc. on
the root window, the X server renders into the AHardwareBuffer's mapped
address.

**dmabuf export**: After every damage event, the X server sends the
AHardwareBuffer's dmabuf fd to the bridge via the existing Unix socket.
The bridge imports it as Vulkan external memory and presents it on the
Android Surface.

**Input**: The Java side forwards Android `MotionEvent`/`KeyEvent` to the
X server via a JNI call. The X server synthesizes X11 events and queues
them on the focused window's event queue.

### 2. Wayland Bridge (EXISTING, mostly unchanged)

The existing `waylandie-wayland-bridge.c` already does:
- Listen on Unix socket `waylandie.display.bridge.v1`
- Receive dmabuf fds via `SCM_RIGHTS`
- Import them as Vulkan external memory
- Present on Android `SurfaceControl`

We add ONE new code path: when the X server sends a dmabuf directly
(no Wayland client), the bridge accepts it. This is a tiny addition.

### 3. WineRunner (MODIFIED)

Changes in `execWine()`:
1. Change `GraphicsDriver` registry from `winewayland.drv` to `winex11.drv`
2. Set `DISPLAY=:0` in the Wine env
3. Remove `winex11.drv=d` from `WINEDLLOVERRIDES` (it was disabled!)
4. Start the custom X server (`libwaylandie_xserver.so`) BEFORE Wine
5. Wait for `/tmp/.X11-unix/X0` to appear, then start Wine

### 4. Input routing (NEW Java glue)

A new `XServerController.java` wraps the native X server:
- `start(width, height, bridgeSocket)` - starts the X server thread
- `stop()` - shuts it down
- `sendMouseEvent(x, y, button, isDown)` - JNI -> X server
- `sendKeyEvent(keyCode, isDown)` - JNI -> X server
- `sendTouchEvent(x, y, action)` - JNI -> X server

## Implementation phases

### Phase 1 (this session): Minimal X server + desktop display
- Design doc (this file)
- `libwaylandie_xserver.so` C++ skeleton with:
  - Unix socket listener on `/tmp/.X11-unix/X0`
  - X11 protocol parser (request dispatch table)
  - Core protocol: CreateWindow, MapWindow, PutImage, CopyArea,
    PolyFillRectangle, GetImage, InternAtom, QueryExtension,
    GetGeometry, ChangeProperty, GetProperty
  - AHardwareBuffer root framebuffer
  - dmabuf fd -> bridge socket
- `XServerController.java` for lifecycle + input routing
- `WineRunner.java` updates (registry, env, startup order)
- CMake target for the new library
- Input hooks in `LinuxWindowActivity.java`

### Phase 2 (next session): Protocol coverage + extensions
- Add RANDR, SHAPE, MIT-SHM, BIG-REQUESTS extensions
- Add full GC + pixmap management
- Add text rendering (ImageText8, PolyText8)

### Phase 3 (later): Game optimization
- Detect fullscreen game window
- Switch to direct Vulkan WSI (DXVK) -> Android Surface, bypassing X
- Desktop stays on X11 path, game gets true zero-copy
