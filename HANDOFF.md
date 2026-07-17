# WayLandIE Handoff — Wayland Display Bridge + Compositor + Zero-Copy

## What We're Trying to Achieve

WayLandIE is a fork of WinNative (Wine + DXVK + FEXCore on ARM64 Android). The **primary goal** is:

**Display games through the Wayland display bridge and display compositor with zero buffer copy.**

The entire render path must go through our Wayland compositor (`waylandie-wayland-bridge.c`) — NOT direct to ANativeWindow. The bridge receives dmabuf frames from Wine and presents them to SurfaceFlinger via ASurfaceControl, with zero CPU memcpy.

### The Intended Render Path (NOT YET WORKING)

```
DXVK → winevulkan → winewayland.drv
  → zwp_linux_dmabuf_v1 (dmabuf fd exported from VkImage)
    → Wayland bridge compositor (waylandie-wayland-bridge.c)
      → receives dmabuf fd via Unix socket
        → Java presenter (WaylandBridgeServer.java)
          → adrenotools + Turnip imports dmabuf as VkImage
            → ASurfaceControl presents to SurfaceFlinger
              → HWC direct scanout (zero copy)
```

**Critical:** The game frames MUST go through the Wayland bridge, not direct to ANativeWindow. The bridge is the zero-copy dmabuf pipeline. Direct-to-ANativeWindow defeats the entire purpose.

## Current State (commit 352b3e8, reverted to 9ac1c92)

### What Works: NOTHING

**Nothing works in Wayland mode.** Specifically:
- ❌ No stable desktop — we see flashes of the Wine desktop, then it crashes
- ❌ No game has ever rendered through the Wayland display compositor
- ❌ No stable rendering of any kind through the bridge

We have never seen:
- A stable Wine desktop rendered through the Wayland bridge
- A single game frame rendered through the Wayland bridge
- The bridge compositor successfully presenting frames for more than a few seconds

### What's Been Achieved (infrastructure only — no visible output)
- ✅ winewayland.drv loads and connects to the bridge compositor (all Wayland globals bound)
- ✅ Bridge compositor starts and accepts connections
- ✅ vkCreateInstance succeeds for wineboot (res=0)
- ✅ DXVK vkCreateInstance succeeds (res=0)
- ✅ ANativeWindow pointer reaches Wine (via env var + file fallback)
- ✅ Turnip driver found dynamically for AHB→Vulkan import
- ✅ DXVK initializes: "D3D9 detected, pipeline libraries supported"
- ✅ winewayland.drv init.c patched to fake ChangeDisplaySettingsEx success
- ✅ DXVK gets past "Setting display mode" (was crashing with page fault at 0x00000000)

**But none of this produces visible output.** The desktop flashes and crashes. No game has ever rendered.

## Root Cause Hypotheses (untested)

### Hypothesis 1: Bridge compositor crashes on first real frame
The bridge receives SHM buffers from explorer.exe and tries to convert them to AHB. This conversion may crash (segfault in native code) after a few frames, causing the desktop to flash and disappear.

**Test:** Run with `WAYLANDIE_HAS_AHARDWAREBUFFER` disabled. If the desktop renders stably without AHB conversion, the crash is in the SHM→AHB path.

### Hypothesis 2: winewayland.drv creates wrong surface type
winewayland.drv's `vkCreateWin32SurfaceKHR` should create a Wayland surface (wl_surface) that the bridge compositor can receive dmabuf on. If it creates an Xlib surface or headless surface instead, DXVK frames never reach the bridge.

**Test:** Add `fprintf(stderr, ...)` logging to `winewayland.drv/vulkan.c` in the `vkCreateWin32SurfaceKHR` path. Check what surface type is actually created.

### Hypothesis 3: dmabuf export from DXVK's VkImage fails
Even if the surface is correct, DXVK's `vkQueuePresentKHR` needs to export the rendered VkImage as a dmabuf fd and send it to the bridge via `zwp_linux_dmabuf_v1`. If the dmabuf export fails (Turnip doesn't support `VK_EXT_image_drm_format_modifier` or `vkGetMemoryFdKHR`), no frame reaches the bridge.

**Test:** Check if `vkGetMemoryFdKHR` is available and succeeds in winevulkan.so. Add logging to the present path.

### Hypothesis 4: Bridge socket connection drops under load
The bridge uses a Unix socket (`waylandie.display.bridge.v1`). Under frame delivery load, the socket buffer may overflow or the connection may drop, causing the compositor to lose its client and crash.

**Test:** Add socket error handling and reconnection logic. Check `waylandie-wayland-bridge.c` for socket buffer size and error recovery.

### Hypothesis 5: Java presenter crashes on dmabuf import
`WaylandBridgeServer.java` receives the dmabuf fd and imports it via adrenotools + Turnip. If the import fails (wrong format, wrong usage flags, missing extension), the Java side may throw an unhandled exception that kills the bridge.

**Test:** Wrap the dmabuf import in try/catch with detailed logging. Check `WaylandBridgeServer.java` native present path.

## Key Files

| File | Purpose |
|------|---------|
| `app/src/main/cpp/waylandie-wayland-bridge.c` | Wayland compositor (5059 lines) — THE BRIDGE |
| `app/src/main/cpp/waylandie_display_native.c` | JNI native code for AHB presentation |
| `app/src/main/cpp/winewayland-drv/vulkan.c` | winewayland.drv Vulkan surface creation |
| `app/src/main/cpp/winewayland-drv/wayland_dmabuf.c` | dmabuf handling in winewayland.drv |
| `app/src/main/cpp/winlator/vulkan.c` | adrenotools + Turnip loading |
| `app/src/main/runtime/display/environment/components/WaylandBridgeServer.java` | Java presenter for dmabuf |
| `app/src/main/runtime/display/environment/components/WaylandBridgeComponent.java` | Bridge process launcher |
| `app/src/main/runtime/display/ui/XServerSurfaceView.java` | SurfaceView, ANativeWindow |
| `app/src/main/runtime/display/XServerDisplayActivity.java` | Main activity, env vars, launch command |
| `.github/scripts/build-winewayland-driver.sh` | Builds winevulkan, applies ALL patches |

## Environment Variables

| Variable | Value | Purpose |
|----------|-------|---------|
| `WAYLANDIE_ANATIVE_WINDOW` | (pointer value) | ANativeWindow for surface creation |
| `WAYLANDIE_ANATIVE_WINDOW_FILE` | (file path) | Fallback: file containing ANativeWindow pointer |
| `WAYLANDIE_DMABUF_LAYER_ENABLE` | `1` | Enables the dmabuf forwarding layer |
| `WAYLANDIE_BRIDGE_SOCKET` | `waylandie.display.bridge.v1` | Bridge socket name |
| `WINEDEBUG` | `+waylanddrv` | Wine subsystem traces |
| `WINEDLLOVERRIDES` | `winewayland.drv=b` | Force builtin winewayland.drv |
| `DXVK_D3D9_ALLOW_FULLSCREEN` | `false` | Prevent DXVK display mode change crash |
| `DXVK_D3D11_ALLOW_FULLSCREEN` | `false` | Same for D3D11 |
| `WINE_DISABLE_FULLSCREEN_HACK` | `1` | Disable Wine WM fullscreen hack |

## What Was Tried and Abandoned

### Direct Composition (DC) / ASurfaceControl — REVERTED
7 commits added Direct Composition (ASurfaceControl) from PR #584. This was a **tangent** — DC bypasses the Wayland bridge entirely and goes direct to SurfaceFlinger via the X11/DRI3 path. Reverted because it defeats the purpose of the Wayland bridge.

### System Vulkan driver — REVERTED
Tried using `vkCreateAndroidSurfaceKHR` with the system Vulkan driver. Reverted because Wine's `convert_instance_create_info` can't translate `win32_surface → android_surface`.

### PE proxy DLL (vulkan-1.dll) — FAILED
Tried a PE proxy DLL for DAC zero-copy. CI compilation failed. Abandoned.

### ELF Vulkan layer — BLOCKED
adrenotools isolated namespace blocks VK_LAYER_PATH. The dmabuf layer dlopens but never enters the dispatch chain.

## Immediate Next Steps for the Next Agent

1. **Get the desktop to render STABLY first.** Before attempting games, the Wine desktop (explorer.exe) must render through the bridge without crashing. Currently it flashes and dies. Fix the desktop crash before anything else.

2. **Read `app/src/main/cpp/waylandie-wayland-bridge.c` completely.** This is the core compositor. Look for:
   - SHM→AHB conversion crash points
   - Socket buffer overflow handling
   - Frame lifecycle (receive → convert → present → release)
   - Error recovery on client disconnect

3. **Read `app/src/main/cpp/winewayland-drv/vulkan.c`.** Understand what surface type `wayland_create_win32_surface` creates. It MUST create a wl_surface that the bridge can receive dmabuf on.

4. **Add diagnostic logging** using `fprintf(stderr, ...)` — Wine's `ERR()` macro and `__android_log_print` don't work reliably in the wine/FEX process.

5. **Test with the desktop only first** — no game. Just `explorer /desktop=shell,1280x720`. If the desktop renders stably, move to LIMBO (32-bit DX9). If the desktop crashes, fix that before anything else.

6. **The dmabuf path is the goal.** Once the desktop is stable, ensure DXVK frames go through the bridge as dmabuf (not SHM). The bridge's `zwp_linux_dmabuf_v1` handler must receive and present them.

7. **DO NOT add DC/ADPF/ASurfaceControl features.** Stay focused on the core goal: stable rendering through the Wayland bridge with zero-copy dmabuf.

8. **DO NOT merge with WinNative 0.3.1-beta yet.** The merge is a separate 55-commit rebase. Get Wayland rendering working first.

## Build System

- CI builds the `pubg` variant — do NOT change `.github/workflows/pr-ci.yml`
- Push directly to `origin main`
- CI takes ~8-12 minutes
- NDK: 27.3.13750724
- GitHub PAT: see `/home/z/.config/git/credentials` (may need refresh)

## Test Device

- Samsung S25 Ultra, Android 16 (API 36)
- Snapdragon 8 Elite (Adreno 830)
- Test games: LIMBO (32-bit DX9), Devil May Cry 5 (64-bit DX11), Rise of the Tomb Raider (64-bit DX11)

## The Single Question to Answer

**Why does the Wine desktop flash and crash instead of rendering stably through the Wayland bridge?**

Fix the desktop stability first. Then: why do DXVK game frames not reach the bridge as dmabuf? Answer both, and Wayland mode works.
