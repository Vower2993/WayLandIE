# WayLandIE Project Handoff

## Project Overview

WayLandIE is a fork of WinNative (Wine + DXVK + FEXCore on ARM64 Android) that adds a **Wayland display compositor** for zero-copy dmabuf rendering. The goal is to replace X11 forwarding with a Wayland bridge that passes dmabuf buffers directly to SurfaceFlinger via Android's ASurfaceTransaction API.

- **Target device**: Samsung S25 Ultra, Android 16, Adreno 750
- **Target games**: LIMBO (DX9), Rise of the Tomb Raider (DX11)
- **Repo**: `Vower2993/WayLandIE` on GitHub
- **CI**: Push to `main` triggers build (pubg variant), ~20 min build time
- **PAT**: Stored at `/home/z/.config/git/credentials`

## Architecture

```
Wine (winewayland.drv)
  → SHM buffer → wl_surface.attach + wl_surface.commit
    → Wayland bridge (C, socket: wayland-0)
      → SHM→AHB conversion (AHardwareBuffer pool, 8 slots per dimension)
        → dmabuf fd via SCM_RIGHTS to Java
          → Java presenter (nativePresentAhbVkDmaBufFrame)
            → ASurfaceTransaction_setBufferWithRelease on presentLayer
              → SurfaceFlinger
```

### Key Components

| Component | File | Description |
|-----------|------|-------------|
| Wayland bridge (C) | `app/src/main/cpp/waylandie-wayland-bridge.c` | Wayland compositor + SHM→AHB conversion + dmabuf forwarding |
| JNI native present | `app/src/main/cpp/waylandie_display_native.c` | ASurfaceTransaction present, AHB Vulkan blit, adrenotools loader |
| Java bridge server | `app/src/main/runtime/display/environment/components/WaylandBridgeServer.java` | Socket server, presentLayer creation, dmabuf-present handling |
| SurfaceView | `app/src/main/runtime/display/ui/XServerSurfaceView.java` | SurfaceView with Wayland mode (skips VulkanRenderer) |
| Wine driver installer | `app/src/main/runtime/wine/WaylandDriverInstaller.java` | Installs winewayland.drv/so, patches binaries |
| Build script | `.github/scripts/build-winewayland-driver.sh` | Cross-compiles winewayland.drv + .so from proton-wine source |
| Bionic bridge build | `tools/build-bionic-bridge.sh` | Builds libwayland-server.a with patches |
| libwayland patch | `patches/libwayland-server-ignore-unknown-object.patch` | Drops unknown-object requests instead of disconnecting |

## Current State (commit `23431bc`)

### What Works
- **No crash**: libwayland-server patch + NtGdiGetRegionData neutralization + wl_buffer_destroy no-op + AHB pool = no crashes after 50+ frames
- **Bridge renders**: 51-59 frames presented with `status=pass` per session
- **Preloader dismissed**: First frame callback fires
- **No freeze**: AHB pool reuses buffers, no GPU memory exhaustion
- **Normal exit**: Wine exits with status=0 (NORMAL_EXIT), no SIGTERM/SIGABRT
- **DXVK diagnostic**: `VK_EXT_surface_maintenance1` found in `win32u.so` (1x, patched) and `winevulkan.dll` (7x aarch64 + 4x i386, patched in CI #327+)

### What Doesn't Work
- **Desktop is NOT visible on screen**. The bridge presents frames via `ASurfaceTransaction_setBufferWithRelease` on a child presentLayer (child of SurfaceView's SurfaceControl), but SurfaceFlinger does not composite them. The presentLayer's buffer IDs never appear in SurfaceFlinger's "first frame" logs.

### The Display Problem (CRITICAL — must solve first)

**The core issue**: In Wayland mode, the VulkanRenderer render thread is skipped (no X11 content to render). The SurfaceView's SurfaceControl has no buffer queue activity. The child presentLayer uses `ASurfaceTransaction` to set buffers, but SurfaceFlinger doesn't composite child layers of an inactive parent.

**What was tried (all failed for display, though crash fixes are correct)**:

1. **Skip render thread + child presentLayer** (commit b627579): Desktop was visible for a SPLIT SECOND before crashing. This proves ASurfaceTransaction CAN work when BLASTBufferQueue is idle. The crash was fixed by subsequent patches, but the display issue persisted in later builds because the crash prevented sustained testing.

2. **Skip render thread + top-level presentLayer** (commit 8be6123): SurfaceFlinger doesn't composite app-created top-level SurfaceControls.

3. **Skip render thread + use SurfaceView's own SurfaceControl** (commit 1ecbb79): BLASTBufferQueue owns the SurfaceControl — ASurfaceTransaction conflicts with it.

4. **lockCanvas dummy frame** (commit d39cbea): `Surface.lockCanvas()` crashes on Samsung S25/Android 16 when `setZOrderOnTop(true)` is set.

5. **VulkanRenderer CONTINUOUS mode** (commit 78948f2): BLASTBufferQueue competes with ASurfaceTransaction, making presentLayer invisible.

6. **VulkanRenderer WHEN_DIRTY for 1 frame** (commit 043425b): Timing issue — surface not ready when frame is requested.

**The most promising path**: Approach #1 (skip render thread + child presentLayer) — it DID display the desktop briefly. The crash that followed is now fixed. The current code (commit 23431bc) restores this exact configuration. **It needs to be tested** — the user hasn't tested this exact combination of (crash fixes + skip render thread + child presentLayer) yet.

## Patches Applied (all in current commit)

### 1. libwayland-server patch (`patches/libwayland-server-ignore-unknown-object.patch`)
- **What**: Makes `wl_client_connection_data` drop requests to destroyed objects instead of disconnecting the client
- **Why**: Wine's GUI thread queues `wl_surface.attach(buffer)` while the event thread concurrently destroys the buffer. libwayland-server sees "unknown object" and kills Wine. This mirrors X11's `BadWindow` error suppression.
- **Applied in**: `tools/build-bionic-bridge.sh` after wayland 1.22.0 source extraction

### 2. NtGdiGetRegionData neutralization (in `build-winewayland-driver.sh`)
- **What**: `#define NtGdiGetRegionData(...) 0` inserted after last `#include` in `window_surface.c`
- **Why**: `copy_pixel_region → get_region_data → NtGdiGetRegionData` re-enters win32u and triggers `user_check_not_lock` when called during `flush_window_surfaces` (USER lock held in Proton 11.0)
- **Effect**: `get_region_data` returns NULL → `copy_pixel_region` does full-frame copy instead of per-region damage. Desktop still renders.

### 3. wl_buffer_destroy no-op (in `build-winewayland-driver.sh`)
- **What**: Python regex replaces `wl_buffer_destroy(x)` with `(void)x` in `wayland_surface.c`
- **Why**: Wine's event thread destroys `wl_buffer` proxy while GUI thread still uses it in `wl_surface_attach` → `wl_proxy_unref` assertion → abort
- **Effect**: Leaks ~64 bytes per buffer (bounded by resize events, not frame count)

### 4. AHB pool (in `waylandie-wayland-bridge.c`)
- **What**: 8-slot pool per dimension (max 4 dimensions), reuses AHardwareBuffers
- **Why**: Without pool, each frame allocates a NEW AHB → GPU memory exhausts after ~55 frames → freeze
- **Effect**: Caps GPU memory at 8 buffers per dimension

### 5. Frame rate limiter REMOVED (in `waylandie-wayland-bridge.c`)
- **What**: Removed the 66ms (15fps) limiter from `present_buffer_to_android`
- **Why**: The limiter sent immediate `wl_buffer_send_release` for skipped frames, narrowing the race window between GUI thread attach and event thread proxy destruction. The AHB pool makes the limiter unnecessary.

### 6. XKB ruleset non-fatal (in `build-winewayland-driver.sh`)
- **What**: `wayland_keyboard_init` continues even if `rxkb_context_parse_default_ruleset` fails
- **Why**: Android container has no XKB data files. The failure caused `wayland_keyboard_init` to return early without registering `wl_keyboard` listener → driver partially initialized
- **Effect**: Keyboard works with "us" layout fallback

### 7. win32u.so + winevulkan.dll surface_maintenance1 patch (in `WaylandDriverInstaller.java`)
- **What**: Binary-patches `VK_EXT_surface_maintenance1` → `VK_EXT_surface_maintenance0` in win32u.so and winevulkan.dll
- **Why**: Turnip driver advertises this extension but doesn't support it → win32u auto-enables it → DXVK's `vkCreateInstance` returns -7
- **Effect**: DXVK can create Vulkan instance for games (ROTTR, LIMBO)
- **Diagnostic scanner** (`scanAllSoForString`) also included — logs which files contain the string

### 8. Game presentation via bridge (`WAYLANDIE_GAME_VIA_BRIDGE` env var, in `vulkan.c`)
- **What**: When set to "1", Wine creates a Wayland surface (vkCreateWaylandSurfaceKHR) for games instead of Xlib surface
- **Why**: Routes game frames through the bridge for zero-copy dmabuf presentation
- **Default**: Off (games use direct Xlib rendering). Enable after desktop display works.

## Build System

### CI Workflow (`.github/workflows/pr-ci.yml`)
- Push to `main` triggers CI
- Builds pubg variant
- ~20 min total build time
- Steps: bionic-bridge deps → winewayland driver → dmabuf Vulkan layer → Gradle APK

### Key Build Scripts
1. `tools/build-bionic-bridge.sh` — builds libffi + libwayland-server (with patch) + xkbcommon
2. `.github/scripts/build-winewayland-driver.sh` — cross-compiles winewayland.drv + .so from proton-wine proton_11.0 source, applies all patches
3. `.github/scripts/build-waylandie-dmabuf-layer.sh` — builds the Vulkan dmabuf layer .so

### Environment Variables
| Variable | Value | Purpose |
|----------|-------|---------|
| `WAYLANDIE_DMABUF_LAYER_ENABLE` | `1` | Enables the Vulkan dmabuf layer |
| `WAYLANDIE_BRIDGE_SOCKET` | `waylandie.display.bridge.v1` | Bridge socket name |
| `WAYLANDIE_ANATIVE_WINDOW` | (pointer) | ANativeWindow for Vulkan surface creation |
| `WAYLANDIE_GAME_VIA_BRIDGE` | `1` (optional) | Route game frames through bridge |
| `WINEDEBUG` | `+waylanddrv` | Wine Wayland driver trace |
| `WINEDLLOVERRIDES` | `winewayland.drv=b` | Force builtin winewayland.drv |
| `Graphics` | `wayland` | Wine graphics driver |

## Key Files

| File | Purpose |
|------|---------|
| `app/src/main/cpp/waylandie-wayland-bridge.c` | Wayland compositor (5300+ lines) |
| `app/src/main/cpp/waylandie_display_native.c` | JNI native present code (5900+ lines) |
| `app/src/main/runtime/display/environment/components/WaylandBridgeServer.java` | Java bridge server |
| `app/src/main/runtime/display/ui/XServerSurfaceView.java` | SurfaceView with Wayland mode |
| `app/src/main/runtime/wine/WaylandDriverInstaller.java` | Driver installer + binary patcher |
| `app/src/main/runtime/display/XServerDisplayActivity.java` | Main activity, env setup (line ~6680 for Wayland) |
| `.github/scripts/build-winewayland-driver.sh` | Build script with all patches |
| `tools/build-bionic-bridge.sh` | Bionic bridge build with libwayland patch |
| `patches/libwayland-server-ignore-unknown-object.patch` | libwayland-server patch |

## How to Monitor CI

```bash
PAT=$(grep -oP "x-access-token:\K[^@]+" /home/z/.config/git/credentials)
curl -s -H "Authorization: token $PAT" \
  "https://api.github.com/repos/Vower2993/WayLandIE/actions/runs?per_page=5" \
  | python3 -c "import json,sys; [print(f'#{r[\"run_number\"]} {r[\"status\"]}/{r[\"conclusion\"]} sha={r[\"head_sha\"][:8]}') for r in json.load(sys.stdin)['workflow_runs']]"
```

## How to Push

```bash
cd /home/z/my-project/WayLandIE
git add -A
git commit -m "fix: description"
git push origin main
# CI will build automatically
```

## Rules for the Next Agent

1. **Test commit 23431bc first** — it restores the exact display configuration from b627579 (where desktop was visible) plus all crash fixes. The user may not have tested this exact combination yet.
2. **Don't run the VulkanRenderer in CONTINUOUS mode in Wayland mode** — it competes with ASurfaceTransaction and makes the presentLayer invisible.
3. **Don't use lockCanvas** — it crashes on Samsung S25/Android 16 with setZOrderOnTop.
4. **All crash fixes are correct** — libwayland patch, NtGdiGetRegionData, wl_buffer_destroy no-op, AHB pool. Don't revert these.
5. **The win32u.so + winevulkan.dll patches for VK_EXT_surface_maintenance1 are needed for games** (DXVK vkCreateInstance). The diagnostic scanner confirmed the string locations.
6. **Always use Python for source patching** in build scripts (sed is unreliable for multi-line patterns).
7. **The bridge is single-threaded** — `present_buffer_to_android` does a blocking `read()` from the Java socket inside `surface_commit`. This stalls the event loop. Not a crash, but causes latency.
8. **X11 mode works fine** — all issues are Wayland-specific. If Wayland display can't be fixed, X11 mode is the fallback.
