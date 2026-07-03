# WayLandIE Project Handoff Document

## ⚠️ CRITICAL: WORK IN A SEPARATE BRANCH

**You MUST create and work in a separate branch — do NOT push to `main`.**

```bash
cd /home/z/my-project/WayLandIE
git checkout -b wayland-v3
# Do ALL your work in this branch
# Only merge to main after the game renders frames and the user approves
```

The `main` branch is at commit `7d8cb49` and has a CI pipeline that builds the APK. Do not break it. Create a feature branch, push your changes there, and let CI build from that branch for testing.

## ⚠️ BUILD LUDASHI VARIANT ONLY

**The CI workflow currently builds the `pubg` variant. You MUST change it to build the `ludashi` variant only.**

In `.github/workflows/pr-ci.yml`, change the build matrix to use `ludashi` instead of `pubg`:
- `gradleTask`: `assembleLudashiDebug` (not `assemblePubgDebug`)
- `apkPath`: `app/build/outputs/apk/ludashi/debug/ludashi.apk` (not `pubg.apk`)

The `ludashi` variant is the correct build flavor for this project. Do NOT build `pubg` or `standard` variants — they waste CI time and produce APKs the user cannot use.

---

## GitHub PAT

The GitHub PAT is saved at `/home/z/.config/git/credentials` and configured via `git config --global credential.helper store`. It is already set up — just use `git push origin <your-branch>` and it will work.

Repository: `Vower2993/WayLandIE`

---

## Project Overview

WayLandIE is a fork of WinNative (Wine + DXVK + FEXCore on ARM64 Android) that aims to enable x86_64 Windows games to run on ARM64 Android devices with hardware-accelerated Vulkan rendering. The specific goal is **zero-copy dmabuf rendering via a Wayland display compositor**, bypassing X11 forwarding and CPU-based screen blits.

- **Target test games**: LIMBO (32-bit DX9), Rise of the Tomb Raider (64-bit DX11)
- **Test device**: Samsung S25 Ultra, Android 16
- **Target audience**: Android 13+ users
- **Original upstream**: https://github.com/WinNative-Emu/WinNative

---

## Current State (as of commit 7d8cb49)

### What's Implemented

1. **winevulkan NULL-guard** (Phase 6, commit 0862a28)
   - Patches `wine_vkEnumeratePhysicalDeviceGroups` + KHR variant in `dlls/winevulkan/vulkan.c` to return `VK_ERROR_INITIALIZATION_FAILED` when `client_instance == NULL` instead of crashing.
   - Applied via Python heredoc in `.github/scripts/build-winewayland-driver.sh` (idempotent).

2. **Chain construction patch** (commit a678896, updated a476f00)
   - Patches `wine_vkCreateInstance` in `dlls/winevulkan/vulkan.c` to:
     - `dlopen("libvk_layer_waylandie_dmabuf.so", RTLD_NOW)`
     - `dlsym` the layer's `vkGetInstanceProcAddr`
     - Construct a `VkLayerInstanceCreateInfo` chain node with HOST GIPA/GDPA
     - Call the layer's `vkCreateInstance` (which uses PATH 1 chain walk)
   - Uses `fprintf(stderr, ...)` for diagnostics (bypasses wine's ERR() debug channel)
   - Wine's vulkan.h does NOT include vk_layer.h, so layer chain types are defined manually with `waylandie_` prefix
   - Applied via Python heredoc in build script (idempotent)

3. **`-ldl` linkage** (commit 581de14, d95f4a3)
   - Patches `dlls/winevulkan/Makefile.in` to add `-ldl` to `UNIX_LIBS`
   - Required because the chain construction patch calls `dlopen`/`dlsym`/`dlerror`
   - Build script verifies `-ldl` is in the actual clang link command

4. **PE/Unix enum sync** (commits 754a667, d7ca61c, 7d8cb49)
   - `copyToSystem32()` now copies source-built `winevulkan.dll` to `system32/` (64-bit games)
   - `ensureDriverInstalled()` copies source-built `winevulkan.dll` to `syswow64/` (32-bit games)
   - Both PE sides now match the Unix side (all from proton_11.0 source)
   - **CRITICAL FIX** (commit 7d8cb49): Removed `"android"` from `UNEXPOSED_PLATFORMS` in `make_vulkan`. The previous agent's patch was BACKWARDS — adding "android" to `UNEXPOSED_PLATFORMS` actually EXPOSED `vkCreateAndroidSurfaceKHR`, shifting the enum and causing `vkCreateInstance` to dispatch to the wrong Unix thunk function.

5. **Handle-transparent layer** (commit a4405ea)
   - Layer returns raw HOST `VkSwapchainKHR` (not wrapped pointer)
   - `find_swapchain()` compares `s->real_swapchain == sw` (not pointer cast)
   - `layer_queue_present()` passes `pSwapchains` straight through (no rewriting)
   - Compatible with winevulkan's thunk handle wrapping

6. **Layer source** (`app/src/main/cpp/vulkan_layer/waylandie_dmabuf_layer.c`)
   - Constructor logging (`__attribute__((constructor))`)
   - `get_host_vulkan_handle()` with `pthread_once` lazy init (dlopen(NULL) + fallback)
   - Fat-layer bootstrap in `layer_create_instance` (PATH 1 + PATH 2 fallback)
   - Fat-layer bootstrap in `layer_create_device` (PATH 1 + PATH 2 fallback)
   - `vkEnumerateInstanceExtensionProperties` uses `dlopen(NULL)` + `dlsym` (no `dlsym(RTLD_NEXT)`)
   - dmabuf export via `vkGetMemoryFdKHR`, bridge socket `waylandie.display.bridge.v1`
   - Built with `-DVK_USE_PLATFORM_ANDROID_KHR -DVK_USE_PLATFORM_WIN32_KHR -DVK_USE_PLATFORM_XLIB_KHR`

7. **Layer manifest + installer**
   - `WaylandDriverInstaller.installDmabufLayer()` copies `.so` to `usr/lib/` AND `usr/share/vulkan/implicit_layer.d/`
   - Manifest at `app/src/main/cpp/vulkan_layer/waylandie_dmabuf_layer.json`
   - `VK_LAYER_PATH` set in `GuestProgramLauncherComponent.java`

### What's NOT Working (As of Last Test — CI #203)

**LIMBO (32-bit)**: `vkCreateInstance` still dispatches to `thunk32_vkCreateOpticalFlowSessionNV` (wrong function). The `loader.c:424` assert fires. The `UNEXPOSED_PLATFORMS` fix (commit 7d8cb49) has NOT been tested yet — CI #204 is building.

**ROTR (64-bit)**: `vkCreateInstance` fails with "Failed to create Vulkan instance". Same root cause — the enum was shifted by the incorrectly-exposed `android_surface` functions.

**Neither game has ever rendered a single frame.**

### The Actual Root Cause (Found in commit 7d8cb49)

The `UNEXPOSED_PLATFORMS` patch was **BACKWARDS** since the beginning of the project. The `make_vulkan` logic:

```python
if platform != "win32" and platform not in UNEXPOSED_PLATFORMS:
    skip  # Don't expose
```

- **Default** (android NOT in set): android is skipped ✓
- **After patch** (android IN set): android is EXPOSED ✗ (shifts the enum)

This caused `vkCreateAndroidSurfaceKHR` to appear in the dispatch table, shifting all subsequent indices. `vkCreateInstance` was at the wrong index, so the PE side dispatched to the wrong Unix function.

**CI #204** (commit 7d8cb49) is the first build with the correct enum. It has NOT been tested yet.

---

## Architecture

### Call Chain (Intended)
```
DXVK (PE, in Wine process)
  → winevulkan.dll (PE side, source-built from proton_11.0)
    → UNIX_CALL(vkCreateInstance) → wine_vkCreateInstance (Unix side)
      → waylandie_wrapped_create_instance (our patch)
        → dlopen("libvk_layer_waylandie_dmabuf.so")
        → construct VkLayerInstanceCreateInfo chain
        → layer's layer_create_instance (PATH 1: chain walk)
          → host vkCreateInstance (via chain's pfnNextGetInstanceProcAddr)
            → adrenotools → libvulkan_freedreno.so (Turnip driver)
```

### Key Components

1. **Vulkan Layer** (`app/src/main/cpp/vulkan_layer/waylandie_dmabuf_layer.c`, ~1470 lines)
   - Intercepts `vkCreateInstance`, `vkCreateDevice`, `vkCreateSwapchainKHR`, `vkQueuePresentKHR`, etc.
   - Handle-transparent: returns raw HOST handles
   - dmabuf export: creates LINEAR staging images, exports via `vkGetMemoryFdKHR`
   - Bridge: sends dmabuf fds via SCM_RIGHTS to `waylandie.display.bridge.v1`
   - Built by `.github/scripts/build-waylandie-dmabuf-layer.sh`

2. **winevulkan** (source-built from proton_11.0)
   - Both PE (`winevulkan.dll`) and Unix (`winevulkan.so`) sides built from same source
   - Patches applied by `.github/scripts/build-winewayland-driver.sh`:
     - NULL-guard for `wine_vkEnumeratePhysicalDeviceGroups`
     - Chain construction in `wine_vkCreateInstance`
     - `-ldl` in `UNIX_LIBS`
     - `VK_USE_PLATFORM_WIN32_KHR` in config.h
     - `android` NOT in `UNEXPOSED_PLATFORMS` (default behavior is correct)

3. **adrenotools** — loads Turnip driver in isolated linker namespace
   - `adrenotools_open_libvulkan()` creates custom namespace
   - Does NOT respect `VK_LAYER_PATH` (definitively ruled out)
   - Layer injection via `dlopen` in `wine_vkCreateInstance` is the working approach

4. **WaylandIE Bridge** — abstract socket `waylandie.display.bridge.v1`
   - Receives dmabuf fds via SCM_RIGHTS
   - Presents via SurfaceControl

5. **WaylandDriverInstaller.java** — installs at runtime:
   - winewayland.drv + winewayland.so → winePath/lib/wine/
   - ntdll.dll → winePath/lib/wine/ (FEX stubs)
   - winevulkan.dll/so → winePath/lib/wine/ + system32/ + syswow64/
   - libvk_layer_waylandie_dmabuf.so → rootDir/usr/lib/ + implicit_layer.d/
   - Binary patches VK_KHR_wayland_surface → VK_KHR_xlib_surface in winevulkan.dll

### ANativeWindow Flow
- Java side: `WaylandBridgeServer.nativeSetAnativeWindow(Surface)` sets env var `WAYLANDIE_ANATIVE_WINDOW`
- Layer: `layer_create_win32_surface()` reads this env var, passes ANativeWindow to `vkCreateXlibSurfaceKHR`

---

## Build System

### CI Workflow (`.github/workflows/pr-ci.yml`)
- Single-target: Pubg variant builds
- Build time: ~8-12 minutes
- Runner: ubuntu-latest
- NDK: 27.3.13750724
- Vulkan headers: KhronosGroup/Vulkan-Headers cloned to `/tmp/vulkan-headers-install/`
- Builds from ANY branch (push triggers CI)

### Build Scripts
1. `.github/scripts/build-winewayland-driver.sh` — builds winewayland.drv, winewayland.so, ntdll.dll, winevulkan.dll, winevulkan.so from proton-wine source. Applies all winevulkan patches.
2. `.github/scripts/build-waylandie-dmabuf-layer.sh` — builds the Vulkan layer .so
3. `.github/scripts/build-fex-emu.sh` — builds FEXCore's libarm64ecfex.dll

### Layer Build Details
- Compiled with: `-DVK_USE_PLATFORM_ANDROID_KHR -DVK_USE_PLATFORM_WIN32_KHR -DVK_USE_PLATFORM_XLIB_KHR`
- Stub headers at `app/src/main/cpp/vulkan_layer/stub_includes/` provide `windows.h`, `X11/Xlib.h`, `android/hardware_buffer.h`, `android/log.h`
- Links against: `-landroid -llog -ldl -lc`

### Local Syntax Check
A reusable syntax-check harness exists at `/home/z/my-project/scripts/syntax_check/`:
```bash
gcc -fsyntax-only -Wall -Wextra \
  -DVK_USE_PLATFORM_ANDROID_KHR -DVK_USE_PLATFORM_WIN32_KHR -DVK_USE_PLATFORM_XLIB_KHR \
  -I scripts/syntax_check -I WayLandIE/app/src/main/cpp/vulkan_layer/stub_includes \
  WayLandIE/app/src/main/cpp/vulkan_layer/waylandie_dmabuf_layer.c
```

---

## Diagnostic Tracing

### Environment Variables
| Variable | Value | Purpose |
|----------|-------|---------|
| `WAYLANDIE_DMABUF_LAYER_ENABLE` | `1` | Enables the Vulkan layer |
| `WAYLANDIE_DMABUF_LAYER_PASSTHROUGH` | `1` | Passthrough mode (layer does nothing) |
| `WAYLANDIE_BRIDGE_SOCKET` | `waylandie.display.bridge.v1` | Bridge socket name |
| `WAYLANDIE_ANATIVE_WINDOW` | (pointer value) | ANativeWindow for surface creation |
| `WINEDEBUG` | `+vulkan,+winewayland,+x11` | Wine subsystem traces |
| `DXVK_LOG_LEVEL` | `info` | DXVK extension probing |
| `WINEDLLOVERRIDES` | `winewayland.drv=b;explorer.exe=` | Force builtin winewayland.drv |

### Log Analysis Quick Reference
```bash
# Check if layer loaded
grep "WayLandIE/Layer" application.log
grep "WayLandIE wrapper" wine_*.txt

# Check if chain construction fired
grep "WayLandIE wrapper: ENTER" wine_*.txt
grep "WayLandIE wrapper: dlopen" wine_*.txt

# Check vkCreateInstance result
grep "Created instance\|Failed to create" wine_*.txt

# Check for thunk mismatch (should be ZERO)
grep "thunk.*vkCreateOpticalFlow" wine_*.txt

# Check for asserts (should be ZERO)
grep "loader.c:424\|loader.c:466" wine_*.txt

# Check surface creation
grep "layer_create_win32_surface" application.log

# Check swapchain/present
grep -E "create_swapchain|present #" application.log

# Check winevulkan.so size (should be ~1074232 bytes)
grep "copied to system32: winevulkan.so" application.log

# Check system32 winevulkan.dll was copied
grep "copied to system32: winevulkan.dll" application.log

# Check syswow64 was replaced
grep "replaced syswow64" application.log

# Check android NOT in UNEXPOSED_PLATFORMS
grep "vkCreateAndroidSurfaceKHR count" # should be 0 in CI build log
```

---

## Iteration History (30+ attempts)

### Approaches Tried and Failed
1. **AHB (Android Hardware Buffer)** — `VK_ANDROID_external_memory_android_hardware_buffer` not supported by adrenotools
2. **Runtime dispatch table patching** — hardcoded indices corrupted memory
3. **Source-level winevulkan hooks** (MANUAL_UNIX_THUNKS) — PE/Unix enum mismatch crashes
4. **Rebuilding winevulkan with VK_USE_PLATFORM_WIN32_KHR** — config.h patch didn't take effect
5. **Runtime surface override** (layer injects vkCreateWin32SurfaceKHR) — layer never loaded
6. **LD_PRELOAD** — causes fork-exec-die loop (wine/FEX preloader incompatible)
7. **VK_LAYER_PATH** — adrenotools isolated namespace doesn't read it
8. **Dispatch table replacement** (replacing `vk_funcs->p_vkXxx`) — Wine-handle vs host-handle mismatch
9. **`dlsym(RTLD_NEXT, ...)`** — Protocol #1 violation, non-deterministic
10. **Adding "android" to UNEXPOSED_PLATFORMS** — BACKWARDS logic, EXPOSED android_surface instead of excluding it

### Root Causes Found (in order of discovery)
1. `dlsym(RTLD_NEXT)` in `vkEnumerateInstanceExtensionProperties` — Protocol #1 violation
2. winevulkan `loader.c:466` assert — NULL VkInstance dereference in `wine_vkEnumeratePhysicalDeviceGroups`
3. LD_PRELOAD incompatible with wine/FEX preloader
4. adrenotools isolated namespace blocks VK_LAYER_PATH
5. Dispatch table replacement has Wine-handle vs host-handle mismatch
6. winevulkan.so not linked against `-ldl` — dlopen crashes
7. system32/winevulkan.dll was Proton 9.0's (64-bit enum mismatch)
8. syswow64/winevulkan.dll was Proton 9.0's (32-bit enum mismatch)
9. **`UNEXPOSED_PLATFORMS` patch was BACKWARDS** — the ACTUAL root cause of ALL enum mismatches since project inception

---

## Key Files

| File | Purpose |
|------|---------|
| `app/src/main/cpp/vulkan_layer/waylandie_dmabuf_layer.c` | The Vulkan layer (~1470 lines) |
| `app/src/main/cpp/vulkan_layer/stub_includes/` | Stub platform headers for cross-compilation |
| `app/src/main/cpp/vulkan_layer/waylandie_dmabuf_layer.json` | Layer manifest |
| `app/src/main/runtime/wine/WaylandDriverInstaller.java` | Installs driver + layer at runtime |
| `app/src/main/runtime/display/XServerDisplayActivity.java` | Sets env vars (line ~6580) |
| `app/src/main/runtime/display/environment/components/GuestProgramLauncherComponent.java` | Sets LD_LIBRARY_PATH, VK_LAYER_PATH (line ~895) |
| `.github/workflows/pr-ci.yml` | CI workflow |
| `.github/scripts/build-winewayland-driver.sh` | Builds winevulkan, winewayland.drv, ntdll |
| `.github/scripts/build-waylandie-dmabuf-layer.sh` | Builds the layer .so |
| `app/src/main/cpp/winewayland-drv/vulkan.c` | winewayland.drv Vulkan surface creation |
| `app/src/main/cpp/waylandie_display_native.c` | JNI native code (sets WAYLANDIE_ANATIVE_WINDOW) |

---

## How to Monitor CI

```bash
PAT=$(grep -oP "x-access-token:\K[^@]+" /home/z/.config/git/credentials)
REPO="Vower2993/WayLandIE"

# List recent runs
curl -s -H "Authorization: token $PAT" \
  "https://api.github.com/repos/$REPO/actions/runs?per_page=5" \
  | python3 -c "import json,sys; [print(f\"#{r['run_number']} {r['status']}/{r['conclusion']} sha={r['head_sha'][:8]} branch={r['head_branch']}\") for r in json.load(sys.stdin)['workflow_runs']]"

# Download logs
RUN_ID="<run_id>"
curl -s -L -H "Authorization: token $PAT" \
  "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/logs" \
  -o /tmp/ci_logs.zip
```

## How to Push to a Branch

```bash
cd /home/z/my-project/WayLandIE
git checkout -b wayland-v3
# Make changes...
git add -A
git commit -m "fix: description"
git push origin wayland-v3
# CI will build from the branch automatically
```

---

## Next Steps for the Next Agent

### Step 1: Test CI #204 (commit 7d8cb49)
This is the first build with the `UNEXPOSED_PLATFORMS` fix. Install the APK and test both LIMBO and ROTR. Check:
```bash
# Layer wrapper should fire now:
grep "WayLandIE wrapper: ENTER" wine_*.txt
# vkCreateInstance should succeed:
grep "Created instance" wine_*.txt
# No thunk mismatch:
grep "thunk.*vkCreateOpticalFlow" wine_*.txt  # should be ZERO
# No asserts:
grep "loader.c:424\|loader.c:466" wine_*.txt  # should be ZERO
```

### Step 2: If vkCreateInstance Succeeds
If the layer loads and `vkCreateInstance` succeeds, the next blocker will likely be:
- **Surface creation**: `WAYLANDIE_ANATIVE_WINDOW` env var must be set before `vkCreateWin32SurfaceKHR` is called
- **Swapchain creation**: The layer's `layer_create_swapchain` must successfully create staging images and export dmabuf fds
- **Present pipeline**: The layer's `layer_queue_present` must blit, export dmabuf, and send to bridge

### Step 3: If vkCreateInstance Still Fails
If the enum is STILL misaligned, verify:
```bash
# Check CI build log for android_surface count
grep "vkCreateAndroidSurfaceKHR count" <ci_build_log>  # should be 0
# Check the generated vulkan.h
grep "vkCreateAndroidSurfaceKHR" /tmp/proton-wine/include/wine/vulkan.h  # should be empty
```

### Step 4: Debug Remaining Issues
- Enable `WAYLANDIE_DMABUF_LAYER_PASSTHROUGH=1` to test if the layer itself causes crashes
- Check `WAYLANDIE_ANATIVE_WINDOW` is set at surface creation time
- Check bridge socket connectivity (`waylandie.display.bridge.v1`)
- Check dmabuf export (`vkGetMemoryFdKHR` return code)

---

## Rules for the Next Agent

1. **WORK IN A SEPARATE BRANCH** — do not push to main until the game renders frames
2. **Always verify root cause before pushing** — read logs, find the exact error, trace to source
3. **Never push without a syntax check** — use `gcc -fsyntax-only` with stub headers locally
4. **Monitor CI autonomously** — push, wait, check status, download logs, diagnose, fix, repeat
5. **Keep diagnostic tracing ON** — `WINEDEBUG=+vulkan`, `DXVK_LOG_LEVEL=info` must stay enabled
6. **Save the PAT** — it's at `/home/z/.config/git/credentials`
7. **Update worklog.md** — append a section after each CI run
8. **Don't rebuild winevulkan unnecessarily** — the build takes 8+ minutes
9. **The `UNEXPOSED_PLATFORMS` fix (commit 7d8cb49) is the KEY fix** — do not re-add "android" to it
10. **Both PE (winevulkan.dll) and Unix (winevulkan.so) sides MUST be from the same proton_11.0 source** — never mix Proton 9.0 PE with proton_11.0 Unix

---

## Consolidated Documentation

- `PROJECT_DOCUMENTATION.md` — original project documentation
- `HANDOFF.md` — this file (replaces the original handoff)
- `/home/z/my-project/worklog.md` — detailed worklog of all sessions (11 task IDs, 600+ lines)
- `/home/z/my-project/winevulkan_src/` — fetched proton-wine source files for reference
- `/home/z/my-project/winevulkan_gen/` — generated thunk code from make_vulkan
- `/home/z/my-project/scripts/syntax_check/` — reusable syntax-check harness
