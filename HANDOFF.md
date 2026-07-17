# WayLandIE Handoff — Wayland Display Compositor Rendering

## What We're Trying to Achieve

WayLandIE is a fork of WinNative (Wine + DXVK + FEXCore on ARM64 Android). The **primary goal** is to make the Wayland display compositor render actual game graphics — not just the Wine desktop, but actual DXVK-rendered game frames — through a zero-copy dmabuf pipeline.

### The Two Render Paths

**PATH A (game rendering — THE GOAL):**
```
DXVK → winevulkan → adrenotools → Turnip → vkCreateXlibSurfaceKHR(ANativeWindow)
  → vkCreateSwapchainKHR → vkQueuePresentKHR
    → ANativeWindow queueBuffer → SurfaceFlinger (direct, zero-copy)
```
The game's DXVK swapchain renders directly to the SurfaceView's ANativeWindow. This is the **only path that produces actual game graphics in Wayland mode**. No Wayland bridge involved — the bridge is for the desktop, not the game.

**PATH B (desktop rendering — works):**
```
Wine explorer.exe → winewayland.drv → wl_surface (SHM buffer)
  → Wayland bridge compositor (waylandie-wayland-bridge.c, 5059 lines)
    → SHM→AHB conversion (WAYLANDIE_HAS_AHARDWAREBUFFER)
      → dmabuf fd exported
        → Java presenter (WaylandBridgeServer.java)
          → adrenotools + Turnip imports dmabuf as VkImage
            → ASurfaceControl presents to SurfaceFlinger
```
PATH B works — the Wine desktop (explorer.exe shell) renders through the Wayland bridge to SurfaceFlinger. But PATH B is NOT the game render path.

## Current State (commit 9ac1c92)

### What Works
- ✅ winewayland.drv loads and connects to the bridge compositor (all Wayland globals bound)
- ✅ Wine desktop renders via SHM buffers through the bridge (PATH B)
- ✅ vkCreateInstance succeeds for wineboot (res=0)
- ✅ vkEnumeratePhysicalDevices NULL guards prevent the assertion crash
- ✅ DXVK vkCreateInstance succeeds (res=0)
- ✅ ANativeWindow pointer reaches Wine (via env var + file fallback)
- ✅ Turnip driver found dynamically for AHB→Vulkan import
- ✅ DXVK initializes: "D3D9 detected, pipeline libraries supported"
- ✅ winewayland.drv init.c patched to fake ChangeDisplaySettingsEx success
- ✅ DXVK gets past "Setting display mode" (was crashing with page fault at 0x00000000)

### What's Broken — THE BLOCKER

**DXVK initializes successfully but produces no visible game graphics.** After "Setting display mode" succeeds, DXVK should:
1. Call `vkCreateWin32SurfaceKHR` (translated to `vkCreateXlibSurfaceKHR` with ANativeWindow)
2. Call `vkCreateSwapchainKHR`
3. Call `vkQueuePresentKHR` to render frames

**What actually happens:** The game process either:
- Stalls after "Setting display mode" with no swapchain creation
- OR creates a swapchain but `vkQueuePresentKHR` goes nowhere (no frames reach SurfaceFlinger)

**No game graphics appear on screen.** The Wine desktop (PATH B) renders fine, but the game window never shows.

## Root Cause Hypotheses (untested)

### Hypothesis 1: Surface creation fails silently
DXVK calls `vkCreateWin32SurfaceKHR`. Wine's winewayland.drv should translate this to a Wayland surface OR an Xlib surface with the ANativeWindow. If the translation fails, DXVK gets a null surface and can't create a swapchain.

**Test:** Add `fprintf(stderr, ...)` logging to `winewayland.drv/vulkan.c` in the `vkCreateWin32SurfaceKHR` path to see if it's called and what it returns.

### Hypothesis 2: Swapchain present goes to wrong destination
DXVK's `vkQueuePresentKHR` might be presenting to a Wayland surface (via the bridge) instead of directly to the ANativeWindow. In PATH A, the game should present DIRECTLY to ANativeWindow — bypassing the bridge entirely.

**Test:** Check if `vkQueuePresentKHR` in winevulkan.so routes through the bridge or directly to ANativeWindow. The bridge is for desktop (PATH B), not game frames (PATH A).

### Hypothesis 3: ANativeWindow not properly bound to Vulkan surface
The ANativeWindow pointer is passed via `WAYLANDIE_ANATIVE_WINDOW` env var. `vkCreateXlibSurfaceKHR` uses it as a fake Xlib Window handle. But Turnip's `vkCreateXlibSurfaceKHR` might not accept an ANativeWindow pointer — it might need `vkCreateAndroidSurfaceKHR` instead.

**Test:** Try `vkCreateAndroidSurfaceKHR` with the ANativeWindow instead of `vkCreateXlibSurfaceKHR`. This was attempted in commit `b6f4259` (system Vulkan driver) but reverted in `fca1a58` because Wine's `convert_instance_create_info` can't translate `win32_surface → android_surface`.

### Hypothesis 4: winewayland.drv creates a headless surface
winewayland.drv's `vkCreateWin32SurfaceKHR` implementation might create a headless Wayland surface (no display) instead of binding to the ANativeWindow. This would explain why DXVK creates a swapchain but no frames appear.

**Test:** Read `app/src/main/cpp/winewayland-drv/vulkan.c` and check what `wayland_create_win32_surface` actually does. Does it use the ANativeWindow? Or does it create a wl_surface backed by the bridge?

## Key Files

| File | Purpose |
|------|---------|
| `app/src/main/cpp/winewayland-drv/vulkan.c` | winewayland.drv Vulkan surface creation — **PRIMARY SUSPECT** |
| `app/src/main/cpp/waylandie-wayland-bridge.c` | Wayland compositor (5059 lines) — for PATH B desktop |
| `app/src/main/cpp/waylandie_display_native.c` | JNI native code for AHB presentation |
| `app/src/main/cpp/winlator/vulkan.c` | adrenotools + Turnip loading + ANativeWindow |
| `app/src/main/runtime/display/environment/components/WaylandBridgeServer.java` | Java presenter for dmabuf (PATH B) |
| `app/src/main/runtime/display/ui/XServerSurfaceView.java` | SurfaceView, sets WAYLANDIE_ANATIVE_WINDOW |
| `app/src/main/runtime/display/XServerDisplayActivity.java` | Main activity, env vars, launch command |
| `app/src/main/runtime/display/environment/components/GuestProgramLauncherComponent.java` | Sets LD_LIBRARY_PATH, VK_LAYER_PATH, env vars |
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
We spent 7 commits (`ce599f0` through `a6080bf`) porting PR #584's Direct Composition (ASurfaceControl) fast path from Vower2993/WinNative-wayland. This was a **tangent** — DC optimizes the X11/DRI3 path, NOT the Wayland path. It was reverted because:
- DC only works in X11 mode (Wayland direct-render bypasses VulkanRenderer entirely)
- The AHBs from DRI3 socket-import lack COMPOSER_OVERLAY, causing DC to self-disable
- Wine-side ADPF with 8ms target caused 90%+ CPU and 45°C heat
- The user wanted Wayland rendering, not X11 optimization

### System Vulkan driver — REVERTED
Commit `b6f4259` tried using the system Vulkan driver with `vkCreateAndroidSurfaceKHR`. Reverted in `fca1a58` because Wine's `convert_instance_create_info` can't translate `win32_surface → android_surface` (only supports win32↔xlib↔wayland).

### PE proxy DLL (vulkan-1.dll) — FAILED
Commit `e0f2cef` tried a PE proxy DLL for DAC zero-copy. CI compilation failed. Abandoned.

### ELF Vulkan layer — BLOCKED
adrenotools isolated namespace blocks VK_LAYER_PATH. The dmabuf layer dlopens but never enters the dispatch chain.

## Immediate Next Steps for the Next Agent

1. **Read `app/src/main/cpp/winewayland-drv/vulkan.c` completely.** This is the primary suspect. Understand what `wayland_create_win32_surface` does — does it bind to ANativeWindow or create a headless wl_surface?

2. **Add diagnostic logging** to winewayland.drv's Vulkan surface creation path. Use `fprintf(stderr, ...)` — Wine's `ERR()` macro and `__android_log_print` don't work reliably in the wine/FEX process.

3. **Test with LIMBO (32-bit DX9)** — it's simpler than DMC5 and faster to iterate. If LIMBO renders, the path works for DX9. If only DMC5 fails, it's a DX11-specific issue.

4. **Check if vkQueuePresentKHR reaches ANativeWindow.** The game's present path should go DIRECTLY to ANativeWindow (SurfaceFlinger), NOT through the Wayland bridge. If it goes through the bridge, that's the bug.

5. **Consider vkCreateAndroidSurfaceKHR** — despite the `convert_instance_create_info` limitation, maybe we can patch winevulkan.so to call `vkCreateAndroidSurfaceKHR` directly when it detects an ANativeWindow. This would bypass the win32_surface→xlib_surface translation entirely.

6. **DO NOT add DC/ADPF/ASurfaceControl features.** Stay focused on the core goal: get game frames to render in Wayland mode. X11 mode already works — don't touch it.

7. **DO NOT merge with WinNative 0.3.1-beta yet.** The merge is a separate 55-commit rebase that would take a full session. Get Wayland rendering working first.

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

**Why does DXVK create a swapchain but no game frames appear on screen?**

Answer that, and Wayland mode renders games. Everything else is secondary.
