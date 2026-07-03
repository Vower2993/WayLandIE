# WayLandIE Project Handoff Document

## CRITICAL: GitHub PAT for Autonomous Operation

The GitHub PAT is saved at `/home/z/.config/git/credentials` and configured via `git config --global credential.helper store`. It is already set up — just use `git push origin main` and it will work.

If the credential store is missing, ask the user for the PAT and save it:
```bash
mkdir -p /home/z/.config/git
cat > /home/z/.config/git/credentials << 'EOF'
https://x-access-token:<PAT_HERE>@github.com
EOF
chmod 600 /home/z/.config/git/credentials
git config --global credential.helper store
```

Repository: `Vower2993/WayLandIE`

## Project Overview

WayLandIE is a fork of WinNative (Wine + DXVK + FEXCore on ARM64 Android) that aims to enable x86_64 Windows games to run on ARM64 Android devices with hardware-accelerated Vulkan rendering. The specific goal is zero-copy dmabuf rendering via a Wayland display compositor, bypassing X11 forwarding and CPU-based screen blits.

The target game for testing is LIMBO (a DX9 title). The test device is a Samsung S24 running Android 14 (API 34+).

## Current Blocker: Layer Not Loading

### The Immediate Problem

The custom Vulkan layer (`libvk_layer_waylandie_dmabuf.so`) is installed but **never loads at runtime**. Zero `WayLandIE/Layer` log lines appear in any test. This causes winevulkan's `vkEnumerateInstanceExtensionProperties` to hit an assertion failure at `loader.c:466` because the layer swallows the call instead of forwarding to the HOST driver.

### Root Cause

Android's Vulkan loader (`libvulkan.so`) has **limited layer support** compared to desktop Linux:

1. **`VK_LAYER_PATH` is not respected** for app-writable directories on Android. The loader only searches `/system/lib64` and `/vendor/lib64` (both read-only).

2. **Manifest `library_path` is relative** — it's just `libvk_layer_waylandie_dmabuf.so`. The loader resolves this against the manifest's directory (`/usr/share/vulkan/implicit_layer.d/`), but the `.so` is at `/usr/lib/`. They're in different directories.

3. **adrenotools creates an isolated linker namespace** — `adrenotools_open_libvulkan()` loads `libvulkan.so` in a custom namespace that doesn't respect standard layer discovery.

### The Fix (Not Yet Implemented)

There are two possible approaches:

**Option A: LD_PRELOAD (Recommended)**
Set `LD_PRELOAD=/path/to/libvk_layer_waylandie_dmabuf.so` in the Wine process environment. This force-loads the layer into the process address space before any other library, bypassing the loader's layer discovery entirely. The layer's exported `vkGetInstanceProcAddr` and `vkGetDeviceProcAddr` symbols will interpose the system Vulkan loader's versions.

File to modify: `app/src/main/runtime/display/environment/components/GuestProgramLauncherComponent.java` (around line 895 where env vars are set) or `app/src/main/runtime/display/XServerDisplayActivity.java` (around line 6580 where WAYLANDIE_DMABUF_LAYER_ENABLE is set).

Add:
```java
envVars.put("LD_PRELOAD", rootDir.getPath() + "/usr/lib/libvk_layer_waylandie_dmabuf.so");
```

**Option B: Copy .so next to manifest**
Copy the `.so` into the same directory as the manifest JSON:
```java
// In WaylandDriverInstaller.java installDmabufLayer()
File layerSo = new File(layerDir, "libvk_layer_waylandie_dmabuf.so"); // layerDir = implicit_layer.d
copyFile(new File(libDir, "libvk_layer_waylandie_dmabuf.so"), layerSo);
```

This makes the relative `library_path` resolvable. However, this may still not work if Android's loader doesn't search app-writable paths even for manifests it finds.

**Option A (LD_PRELOAD) is strongly recommended** because it's guaranteed to work regardless of loader behavior.

## Architecture

### Call Chain
```
DXVK (PE, in Wine process)
  → winevulkan.dll (PE side, Proton's original)
    → winevulkan.so (Unix side, Proton's original)
      → libvulkan.so (Android system Vulkan loader)
        → [OUR LAYER: libvk_layer_waylandie_dmabuf.so]  ← NOT LOADING
          → libvulkan_freedreno.so (Turnip driver, loaded by adrenotools)
```

### Key Components

1. **Vulkan Layer** (`app/src/main/cpp/vulkan_layer/waylandie_dmabuf_layer.c`)
   - Intercepts `vkGetInstanceProcAddr` / `vkGetDeviceProcAddr`
   - Injects `vkCreateWin32SurfaceKHR` at runtime (winevulkan doesn't have it)
   - Intercepts swapchain/present for dmabuf export
   - Built with official Khronos Vulkan headers + stub platform headers

2. **winevulkan** (Proton 11.0, NOT rebuilt)
   - PE side (`winevulkan.dll`) — Proton's original, patched at install time (wayland_surface → xlib_surface string replacement)
   - Unix side (`winevulkan.so`) — Proton's original
   - Does NOT have `vkCreateWin32SurfaceKHR` (VK_USE_PLATFORM_WIN32_KHR not defined at build time)

3. **winewayland.drv** (source-built)
   - `vulkan.c` — maps win32_surface → xlib_surface at the flag level
   - `wayland_vulkan_surface_create()` — calls `vkCreateXlibSurfaceKHR` on HOST with ANativeWindow

4. **adrenotools** — loads Turnip driver in isolated linker namespace
   - `adrenotools_open_libvulkan()` creates custom namespace
   - Does NOT respect VK_LAYER_PATH

5. **WaylandIE Bridge** — abstract socket `waylandie.display.bridge.v1`
   - Receives dmabuf fds via SCM_RIGHTS
   - Presents via SurfaceControl

6. **WaylandDriverInstaller.java** — installs at runtime:
   - winewayland.drv + winewayland.so → winePath/lib/wine/
   - ntdll.dll → winePath/lib/wine/ (FEX stubs)
   - winevulkan.dll/so → winePath/lib/wine/ (source-built)
   - libvk_layer_waylandie_dmabuf.so → rootDir/usr/lib/
   - manifest JSON → rootDir/usr/share/vulkan/implicit_layer.d/
   - Binary patches VK_KHR_wayland_surface → VK_KHR_xlib_surface in winevulkan.dll

### ANativeWindow Flow
- Java side: `WaylandBridgeServer.nativeSetAnativeWindow(Surface)` sets env var `WAYLANDIE_ANATIVE_WINDOW` to the ANativeWindow pointer
- Native: `waylandie_display_native.c:5982` calls `setenv("WAYLANDIE_ANATIVE_WINDOW", buf, 1)`
- Layer: `layer_create_win32_surface()` reads this env var and passes it to `vkCreateXlibSurfaceKHR`

## Build System

### CI Workflow (`.github/workflows/pr-ci.yml`)
- **Single-target**: Only Pubg variant builds (was 3 variants, now 1 for faster iteration)
- **Build time**: ~8 minutes
- **Runner**: ubuntu-latest
- **NDK**: 27.3.13750724
- **Vulkan headers**: KhronosGroup/Vulkan-Headers cloned to `/tmp/vulkan-headers-install/`

### Build Scripts
1. `.github/scripts/build-winewayland-driver.sh` — builds winewayland.drv, winewayland.so, ntdll.dll, winevulkan.dll, winevulkan.so from proton-wine source
2. `.github/scripts/build-waylandie-dmabuf-layer.sh` — builds the Vulkan layer .so
3. `.github/scripts/build-fex-emu.sh` — builds FEXCore's libarm64ecfex.dll

### Layer Build Details
- Compiled with: `-DVK_USE_PLATFORM_ANDROID_KHR -DVK_USE_PLATFORM_WIN32_KHR -DVK_USE_PLATFORM_XLIB_KHR`
- Stub headers at `app/src/main/cpp/vulkan_layer/stub_includes/` provide `windows.h`, `X11/Xlib.h`, `android/hardware_buffer.h`, `android/log.h` for cross-compilation
- Links against: `-landroid -llog -ldl -lc`

## Diagnostic Tracing (Already Enabled)

The runtime environment forces full stack visibility:
- `WINEDEBUG=+vulkan,+winewayland,+x11` — Wine subsystem traces
- `DXVK_LOG_LEVEL=info` — DXVK extension probing
- `DXVK_HUD=fps,compiler` — pipeline compilation stalls
- Layer logs: `WAYLANDIE_ANATIVE_WINDOW` lookup, pointer address, VkResult codes

All traces route to stderr/stdout, captured in `wine_limbo_*.txt` logs.

## Environment Variables

| Variable | Value | Purpose |
|----------|-------|---------|
| `WAYLANDIE_DMABUF_LAYER_ENABLE` | `1` | Enables the Vulkan layer (manifest enable_environment) |
| `WAYLANDIE_DMABUF_LAYER_PASSTHROUGH` | `1` | Passthrough mode (layer does nothing, just delegates) |
| `WAYLANDIE_BRIDGE_SOCKET` | `waylandie.display.bridge.v1` | Bridge socket name |
| `WAYLANDIE_ANATIVE_WINDOW` | (pointer value) | ANativeWindow for surface creation |
| `WINEDEBUG` | `+vulkan,+winewayland,+x11` | Wine tracing |
| `DXVK_LOG_LEVEL` | `info` | DXVK tracing |
| `DXVK_HUD` | `fps,compiler` | DXVK HUD |
| `VK_LAYER_PATH` | `.../usr/share/vulkan/implicit_layer.d` | Layer manifest path (may not work on Android) |
| `WINEDLLOVERRIDES` | `winewayland.drv=b;explorer.exe=` | Force builtin winewayland.drv |

## How to Monitor CI

```bash
PAT=$(grep -oP "x-access-token:\K[^@]+" /home/z/.config/git/credentials)
REPO="Vower2993/WayLandIE"

# List recent runs
curl -s -H "Authorization: token $PAT" \
  "https://api.github.com/repos/$REPO/actions/runs?per_page=3" \
  | python3 -c "import json,sys; [print(f\"#{r['run_number']} {r['status']}/{r['conclusion']} sha={r['head_sha'][:8]}\") for r in json.load(sys.stdin)['workflow_runs']]"

# Check specific run status
RUN_ID="<run_id>"
curl -s -H "Authorization: token $PAT" \
  "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(f\"status={d['status']} conclusion={d['conclusion']}\")"

# Download logs
curl -s -L -H "Authorization: token $PAT" \
  "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/logs" \
  -o /tmp/ci_logs.zip
```

## How to Push Commits

```bash
cd /home/z/my-project/WayLandIE
git add -A
git commit -m "fix: description"
git push origin main
```

The PAT is saved in git credential store — no need to specify it each time.

## Iteration History (20+ attempts)

### Approaches Tried and Failed
1. **AHB (Android Hardware Buffer)** — `VK_ANDROID_external_memory_android_hardware_buffer` not supported by adrenotools
2. **Runtime dispatch table patching** — hardcoded indices 253-257 corrupted memory, caused deadlocks
3. **Source-level winevulkan hooks** (MANUAL_UNIX_THUNKS) — PE/Unix enum mismatch crashes
4. **Rebuilding winevulkan with VK_USE_PLATFORM_WIN32_KHR** — config.h patch didn't take effect; winevulkan.dll size identical across builds (3604480 bytes = Proton's original)
5. **Runtime surface override** (layer injects vkCreateWin32SurfaceKHR) — layer never loads because Android loader doesn't find it

### Current State
- CI passes (single-target Pubg APK builds in ~8 min)
- Layer compiles with official Khronos headers
- Layer installed correctly (manifest + .so deployed)
- Layer **does not load at runtime** — Android Vulkan loader doesn't search app-writable paths
- winevulkan asserts at `loader.c:466` because layer's `vkEnumerateInstanceExtensionProperties` swallows the call

## Key Files

| File | Purpose |
|------|---------|
| `app/src/main/cpp/vulkan_layer/waylandie_dmabuf_layer.c` | The Vulkan layer (1200+ lines) |
| `app/src/main/cpp/vulkan_layer/stub_includes/` | Stub platform headers for cross-compilation |
| `app/src/main/cpp/vulkan_layer/waylandie_dmabuf_layer.json` | Layer manifest |
| `app/src/main/runtime/wine/WaylandDriverInstaller.java` | Installs driver + layer at runtime |
| `app/src/main/runtime/display/XServerDisplayActivity.java` | Sets env vars (line ~6580) |
| `app/src/main/runtime/display/environment/components/GuestProgramLauncherComponent.java` | Sets LD_LIBRARY_PATH, VK_LAYER_PATH (line ~895) |
| `.github/workflows/pr-ci.yml` | CI workflow (single-target Pubg) |
| `.github/scripts/build-winewayland-driver.sh` | Builds winevulkan, winewayland.drv, ntdll |
| `.github/scripts/build-waylandie-dmabuf-layer.sh` | Builds the layer .so |
| `app/src/main/cpp/winewayland-drv/vulkan.c` | winewayland.drv Vulkan surface creation |
| `app/src/main/cpp/waylandie_display_native.c` | JNI native code (sets WAYLANDIE_ANATIVE_WINDOW) |

## Next Steps for the Next Agent

### Step 1: Fix Layer Loading (CRITICAL)
Implement LD_PRELOAD in `GuestProgramLauncherComponent.java` or `XServerDisplayActivity.java`:
```java
envVars.put("LD_PRELOAD", rootDir.getPath() + "/usr/lib/libvk_layer_waylandie_dmabuf.so");
```

This will force the layer into the Wine process address space. Verify by checking for `WayLandIE/Layer: WayLandIE dmabuf layer ENABLED` in the logs.

### Step 2: Verify Surface Creation
Once the layer loads, DXVK should query `vkCreateWin32SurfaceKHR`. Our layer intercepts and calls `vkCreateXlibSurfaceKHR` with the ANativeWindow. Look for:
```
layer_create_win32_surface: called — instance=... hwnd=... WAYLANDIE_ANATIVE_WINDOW=0x...
layer_create_win32_surface: resolved ANativeWindow pointer=0x...
layer_create_win32_surface: resolved vkCreateXlibSurfaceKHR=0x...
layer_create_win32_surface: vkCreateXlibSurfaceKHR returned res=0 surface=0x...
```

### Step 3: Verify Swapchain Creation
After surface creation, DXVK should create a swapchain. Look for:
```
create_swapchain: success real=0x... staging=3
```

### Step 4: Verify Present Pipeline
The layer should blit real_image → Image B, export dmabuf fd, send to bridge, then call real present. Look for:
```
present #1: 1024x768 stride=4096 fd=12
```

### Step 5: Debug Any Remaining Issues
If the game still doesn't display, check:
- Is `WAYLANDIE_ANATIVE_WINDOW` set? (layer logs it)
- Did `vkCreateXlibSurfaceKHR` return VK_SUCCESS?
- Did the bridge receive the dmabuf fd?
- Did real `vkQueuePresentKHR` succeed?

## Log Analysis Quick Reference

```bash
# Extract logs
mkdir -p /tmp/logs && cd /tmp/logs && unzip -q -o /path/to/winnative_logs_*.zip

# Check if layer loaded
grep -c "WayLandIE/Layer" application.log

# Check surface creation
grep "layer_create_win32_surface" application.log

# Check swapchain/present
grep -E "create_swapchain|present #" application.log

# Check winevulkan assertion
grep "loader.c" wine_limbo_*.txt

# Check DXVK init
grep -E "DXVK|dxgi|D3D9" wine_limbo_*.txt | head -20

# Count VK calls
grep -oE "thunk32_vk[A-Za-z0-9]+" wine_limbo_*.txt | sort | uniq -c | sort -rn | head -20
```

## Rules for the Next Agent

1. **Always verify root cause before pushing** — don't guess. Read the logs, find the exact error, trace it to the source.
2. **Never push without a syntax check** — use `gcc -fsyntax-only` with stub headers locally before pushing.
3. **Monitor CI autonomously** — push, wait, check status, download logs if failed, diagnose, fix, repeat.
4. **Single-target CI only** — don't re-enable the 3-variant matrix until the display pipeline works.
5. **Keep diagnostic tracing ON** — `WINEDEBUG=+vulkan,+winewayland,+x11`, `DXVK_LOG_LEVEL=info` must stay enabled until the game displays video.
6. **Save the PAT** — it's at `/home/z/.config/git/credentials`. Use `git config --global credential.helper store`.
7. **Update worklog.md** — append a section after each CI run with what happened and what you learned.
8. **Don't rebuild winevulkan** — it doesn't work (config.h patches don't take effect). Use the runtime layer approach instead.
9. **LD_PRELOAD is the key** — Android's Vulkan loader doesn't respect VK_LAYER_PATH for app-writable paths. Force-load the layer with LD_PRELOAD.
10. **The layer's vkEnumerateInstanceExtensionProperties MUST forward to HOST** when layer==NULL, via `dlsym(RTLD_NEXT, ...)`. Otherwise winevulkan asserts.

## Consolidated Documentation

All project-level documentation is in `PROJECT_DOCUMENTATION.md` (merged from `docs/winevulkan-architecture-analysis.md` and `README.md`).
