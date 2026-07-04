# WayLandIE Project Handoff — Complete Agent Briefing

## PROJECT OVERVIEW

WayLandIE is a fork of WinNative (Wine + DXVK + FEXCore on ARM64 Android) that aims to enable x86_64 Windows games to run on ARM64 Android devices with hardware-accelerated Vulkan rendering via a custom Wayland display compositor with zero-copy dmabuf.

- **Repository**: `Vower2993/WayLandIE` on GitHub
- **Target test game**: Rise of the Tomb Raider (ROTTR) — 64-bit DX11
- **Test device**: Samsung S25 Ultra, Android 16
- **Target audience**: Android 13+ users
- **Original upstream**: https://github.com/WinNative-Emu/WinNative
- **End goal**: gamescope + Steam running on Android via the Wayland compositor

## GITHUB ACCESS

The GitHub PAT is saved at `/home/z/.config/git/credentials`:
```
https://x-access-token:<PAT_HERE>@github.com
```

Clone the repo:
```bash
cd /home/z/my-project
git clone https://github.com/Vower2993/WayLandIE.git
cd WayLandIE
git config user.email "waylandie-bot@users.noreply.github.com"
git config user.name "WayLandIE Bot"
```

## OPERATIONAL PROTOCOLS (NON-NEGOTIABLE)

1. **ARCHITECTURAL CORRECTNESS OVER SPEED** — Never use `dlsym(RTLD_NEXT, ...)`. Adhere to official Khronos Vulkan Layer specification. Never bypass `vk_funcs->p_vkCreateInstance`.

2. **STANDARDS-COMPLIANT COMPILATION** — All layer logic must rely on official Khronos Vulkan headers. The `make_vulkan` Python script's `UNEXPOSED_PLATFORMS` logic is COUNTERINTUITIVE: `if platform != "win32" and platform not in UNEXPOSED_PLATFORMS: skip`. Do NOT add "android" to this set — the default behavior already excludes android_surface.

3. **LOGGING & DIAGNOSTICS ARE MANDATORY** — Use `fprintf(stderr, ...)` in winevulkan.so patches — wine's `ERR()` macro and `__android_log_print` do NOT work reliably in the wine/FEX process. Every major hook milestone must output explicitly. NOTE: `ERR()` DOES work for winevulkan.so patches and is captured by WINEDEBUG.

4. **THINK BEFORE YOU BUILD** — For every code modification, first provide a concise "Analysis of Impact" detailing exactly which spec rule or memory alignment constraint your change addresses.

5. **PUSH TO MAIN, BUILD PUBG VARIANT** — Push directly to `origin main`. The CI workflow (`.github/workflows/pr-ci.yml`) builds the `pubg` variant — do NOT change it.

## CURRENT STATE (as of commit 4bae418c, CI #227)

### What Works
- ✅ winewayland.drv loads and connects to the bridge compositor (all Wayland globals bound)
- ✅ Wine desktop renders via SHM buffers through the bridge
- ✅ vkCreateInstance succeeds for wineboot (res=0)
- ✅ vkEnumeratePhysicalDevices NULL guards prevent the assertion crash
- ✅ DXVK vkCreateInstance succeeds in X11 mode (res=0)
- ✅ ROTTR.exe loads and starts executing in X11 mode
- ✅ Game gets far enough to hit "Protection initialization failed" (Denuvo DRM)
- ✅ Bridge SHM→AHB conversion works (WAYLANDIE_HAS_AHARDWAREBUFFER enabled)
- ✅ Turnip driver found dynamically for AHB→Vulkan import

### What's Broken
- ❌ Wayland mode DXVK: vkCreateInstance returns res=-7 (VK_ERROR_EXTENSION_NOT_PRESENT)
- ❌ Wayland mode desktop: not visible (libadrenotools.so path was wrong — fixed in commit 4bae418c but NOT YET TESTED)
- ❌ X11 mode ROTTR: memory keeps growing until crash (likely OOM kill)
- ❌ X11 mode: intermittent vkEnumeratePhysicalDevices assertion on first launch
- ❌ dmabuf layer NOT in dispatch chain — game renders direct to SurfaceFlinger, not through bridge
- ❌ File uploads from user are broken (last successful upload was winnative_logs_20260704_115453.zip)

### Current CI Status
- CI #227 (commit 4bae418c) PASSED — APK available
- CI #226 (commit 2473bbc) PASSED — X11 mode confirmed working past vkEnumeratePhysicalDevices

## ALL 21 ROOT CAUSES FOUND (in discovery order)

1. `dlsym(RTLD_NEXT)` — Protocol #1 violation, replaced with `dlopen(NULL)` + fallback
2. NULL VkInstance dereference in `wine_vkEnumeratePhysicalDeviceGroups` — added NULL guard
3. LD_PRELOAD incompatible with wine/FEX preloader — reverted, uses dlopen in winevulkan instead
4. adrenotools isolated namespace blocks VK_LAYER_PATH — ruled out, uses winevulkan patch instead
5. Dispatch table replacement had Wine-handle vs host-handle mismatch — abandoned approach
6. winevulkan.so not linked against `-ldl` — added `-ldl` to UNIX_LIBS
7. system32/winevulkan.dll was Proton 9.0's — replaced with source-built proton_11.0
8. syswow64/winevulkan.dll was Proton 9.0's — replaced with source-built proton_11.0
9. UNEXPOSED_PLATFORMS patch was BACKWARDS — adding "android" EXPOSED it instead of excluding. Removed the patch.
10. sType=24 should be 47 — `VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO = 47` in Khronos `vulkan_core.h`
11. Layer bypassed win32u_vkCreateInstance — fixed by calling `vk_funcs->p_vkCreateInstance` directly
12. init_physical_device crash in `if (zero_bits && ...)` block — VK_EXT_map_memory_placed structs stripped by layer
13. nulldrv creates headless surface (no display) — patched nulldrv to create Xlib surface with ANativeWindow (later reverted)
14. explorer.exe was disabled (`explorer.exe=` in WINEDLLOVERRIDES) — re-enabled, required for graphics driver loading
15. vkEnumeratePhysicalDevices c0000005 at addr=0x0 — NULL pointer in instance->physical_devices
16. Wayland mode skipped explorer.exe launch — changed to `wine explorer /desktop=shell` (same as X11)
17. Wrapper translated VK_KHR_win32_surface before convert_instance_create_info — broke has_VK_KHR_win32_surface flag, removed translation
18. WAYLANDIE_HAS_AHARDWAREBUFFER never defined in CMake — bridge rejected all SHM buffers as "not-dmabuf-zero-copy"
19. Bridge looked for `vulkan.waylandie.a8xx.so` (doesn't exist) — fixed to probe for Turnip driver dynamically
20. NULL guards used fopen() which failed silently — replaced with ERR() macro
21. libadrenotools.so looked in /system/lib64 — fixed to use app's nativeLibraryDir

## ARCHITECTURE (Current Working Flow)

### Wayland Mode (desktop rendering)
```
Wine explorer.exe → winewayland.drv → wl_surface (SHM buffer)
  → Wayland bridge compositor (waylandie-wayland-bridge.c)
    → SHM→AHB conversion (WAYLANDIE_HAS_AHARDWAREBUFFER)
      → dmabuf fd exported
        → Java presenter (WaylandBridgeServer.java)
          → adrenotools + Turnip driver imports dmabuf as VkImage
            → ASurfaceControl presents to SurfaceFlinger
```

### X11 Mode (game rendering — currently the only path that works for games)
```
DXVK → winevulkan → win32u_vkCreateInstance → HOST vkCreateInstance (Turnip)
  → vkCreateWin32SurfaceKHR → winex11.drv → Xlib surface (ANativeWindow)
    → vkCreateSwapchainKHR → vkQueuePresentKHR
      → adrenotools → ANativeWindow → SurfaceFlinger (DIRECT, NOT through bridge)
```

### Intended Wayland Mode (game rendering — NOT YET WORKING)
```
DXVK → winevulkan → win32u_vkCreateInstance → HOST vkCreateInstance (Turnip)
  → vkCreateWin32SurfaceKHR → winewayland.drv → headless/Xlib surface
    → vkCreateSwapchainKHR (dmabuf layer intercepts)
      → vkQueuePresentKHR (dmabuf layer intercepts)
        → vkGetMemoryFdKHR exports dmabuf fd
          → zwp_linux_dmabuf_v1 sends fd to bridge
            → bridge imports dmabuf as AHB → SurfaceControl → SurfaceFlinger
```

## KEY FILES

| File | Purpose |
|------|---------|
| `app/src/main/cpp/vulkan_layer/waylandie_dmabuf_layer.c` | The Vulkan dmabuf layer (~1750 lines) |
| `app/src/main/cpp/vulkan_layer/waylandie_dmabuf_layer.json` | Layer manifest |
| `app/src/main/cpp/waylandie-wayland-bridge.c` | The Wayland compositor (5059 lines) |
| `app/src/main/cpp/waylandie_display_native.c` | JNI native code for AHB presentation |
| `app/src/main/cpp/winewayland-drv/vulkan.c` | winewayland.drv Vulkan surface creation |
| `app/src/main/runtime/display/environment/components/WaylandBridgeServer.java` | Java presenter for dmabuf |
| `app/src/main/runtime/display/environment/components/WaylandBridgeComponent.java` | Bridge process launcher |
| `app/src/main/runtime/display/XServerDisplayActivity.java` | Main activity, env vars (line ~6098 for launch cmd, ~6530 for Wayland setup) |
| `app/src/main/runtime/display/environment/components/GuestProgramLauncherComponent.java` | Sets LD_LIBRARY_PATH, VK_LAYER_PATH, env vars |
| `app/src/main/runtime/wine/WaylandDriverInstaller.java` | Installs driver + layer at runtime |
| `app/src/main/runtime/container/Container.java` | DEFAULT_DISPLAY_MODE = "wayland" (line 37) |
| `app/src/main/cpp/CMakeLists.txt` | Build config for bridge + layer (WAYLANDIE_HAS_AHARDWAREBUFFER at line 296) |
| `.github/scripts/build-winewayland-driver.sh` | Builds winevulkan, applies ALL patches |
| `.github/workflows/pr-ci.yml` | CI workflow (builds pubg variant) |

## winevulkan PATCHES (applied by build-winewayland-driver.sh)

1. **NULL-guard** for `wine_vkEnumeratePhysicalDeviceGroups` + KHR variant
2. **Chain construction** in `wine_vkCreateInstance` → `waylandie_wrapped_create_instance`
3. **`-ldl`** added to UNIX_LIBS in Makefile.in
4. **VK_USE_PLATFORM_WIN32_KHR** defined in config.h
5. **vkEnumeratePhysicalDevices NULL guards** — uses ERR() macro, checks instance and physical_devices
6. **init_physical_devices diagnostic** — uses ERR() macro, logs SUCCESS/FAILED with pointer values

## ENVIRONMENT VARIABLES

| Variable | Value | Purpose |
|----------|-------|---------|
| `WAYLANDIE_DMABUF_LAYER_ENABLE` | `1` | Enables the Vulkan layer |
| `WAYLANDIE_DMABUF_LAYER_PASSTHROUGH` | `1` | Passthrough mode (layer does nothing) |
| `WAYLANDIE_BRIDGE_SOCKET` | `waylandie.display.bridge.v1` | Bridge socket name |
| `WAYLANDIE_ANATIVE_WINDOW` | (pointer value) | ANativeWindow for surface creation |
| `WINEDEBUG` | `+waylanddrv` (Wayland) or `+warn,+err,+fixme,+module,+loaddll,+seh,+thread,+vulkan` (X11) | Wine subsystem traces |
| `WINEDLLOVERRIDES` | `winewayland.drv=b` (Wayland only) | Force builtin winewayland.drv |
| `DXVK_LOG_LEVEL` | `info` | DXVK extension probing |

## BUILD SYSTEM

### CI Workflow
- Single-target: Pubg variant — DO NOT CHANGE
- Build time: ~8-12 minutes
- NDK: 27.3.13750724 (or 26.1.10909125)
- Vulkan headers: KhronosGroup/Vulkan-Headers

### CI Monitoring
```bash
PAT=$(grep -oP "x-access-token:\K[^@]+" /home/z/.config/git/credentials)
REPO="Vower2993/WayLandIE"
curl -s -H "Authorization: token $PAT" \
  "https://api.github.com/repos/$REPO/actions/runs?per_page=5" \
  | python3 -c "import json,sys; [print(f\"#{r['run_number']} {r['status']}/{r['conclusion']} sha={r['head_sha'][:8]}\") for r in json.load(sys.stdin)['workflow_runs']]"
```

### Local Syntax Check
```bash
gcc -fsyntax-only -Wall -Wextra \
  -DVK_USE_PLATFORM_ANDROID_KHR -DVK_USE_PLATFORM_WIN32_KHR -DVK_USE_PLATFORM_XLIB_KHR \
  -I /tmp/vulkan-headers-install/include \
  -I WayLandIE/app/src/main/cpp/vulkan_layer/stub_includes \
  WayLandIE/app/src/main/cpp/vulkan_layer/waylandie_dmabuf_layer.c
```

## IMMEDIATE NEXT STEPS

### Priority 1: Fix Wayland mode DXVK vkCreateInstance -7 error
The wrapper no longer translates VK_KHR_win32_surface → VK_KHR_xlib_surface (commit 241bb8f). The winewayland driver's `wayland_map_instance_extensions` handles this. But DXVK still gets res=-7. The issue is likely that `convert_instance_create_info` in win32u/vulkan.c checks `has_VK_KHR_win32_surface` to enable `VK_EXT_surface_maintenance1`, and this flag may not be set correctly.

**Investigation**: Check if `wayland_map_instance_extensions` is actually called during DXVK's instance creation. Add ERR() logging to `convert_instance_create_info` to see which extensions are enabled.

### Priority 2: Fix X11 mode OOM crash
ROTTR memory grows until Android's OOM killer terminates the process. Possible causes:
- FEX JIT cache growing unboundedly
- DXVK shader/pipeline cache in memory
- Wine virtual memory leak
- GPU memory exhaustion

**Investigation**: Need `guest-process-exit.log` to confirm SIGKILL (signal 9). Check if FEX has a cache size limit option. Check DXVK environment variables for memory limits.

### Priority 3: Get dmabuf layer into dispatch chain
The dmabuf layer (`libvk_layer_waylandie_dmabuf.so`) is dlopened but NOT in the GIPA dispatch chain. Game frames go directly to SurfaceFlinger via adrenotools, bypassing the Wayland bridge.

**Options**:
- A) Wrapper .so loaded via LD_LIBRARY_PATH that intercepts vkGetInstanceProcAddr before adrenotools
- B) Patch adrenotools to allow VK_LAYER_PATH
- C) Patch winevulkan's Unix-side GIPA (generated code)

### Priority 4: Test CI #227 in Wayland mode
CI #227 (commit 4bae418c) fixes the libadrenotools.so path. The Wayland desktop should now be visible. Test and upload logs.

## LOG ANALYSIS COMMANDS

```bash
# Layer wrapper fired?
grep "WayLandIE wrapper" wine_*.txt

# vkCreateInstance succeeded?
grep "Created instance\|vkCreateInstance returned res=0" wine_*.txt

# Extension translation?
grep "translated.*win32.*xlib" wine_*.txt

# Physical device enumeration?
grep "init_physical_device\|EnumeratePhysicalDevices" wine_*.txt

# Crash/exception?
grep -E "Exception|SIGSEGV|C0000005|assert|tombstone|killed" wine_*.txt application.log fexcore_*.txt

# Bridge present results?
grep -E "status=fail|status=pass|reason=" wayland-bridge-output.log

# SHM→AHB conversion?
grep -E "shm-to-ahb|present-step" wayland-bridge-output.log

# Wayland driver init?
grep "waylanddrv:" wine_*.txt

# nodrv_CreateWindow (should be 0)?
grep -c "nodrv_CreateWindow" wine_*.txt

# guest process exit?
cat guest-process-exit.log
```

## IMPORTANT ARCHITECTURAL NOTES

1. **The bridge IS a real Wayland compositor** — `waylandie-wayland-bridge.c` (5059 lines) implements wl_compositor, wl_subcompositor, wl_seat, wl_shm, wl_output, xdg_wm_base, wp_presentation, wp_viewporter, zwp_relative_pointer_manager_v1, zwp_pointer_constraints_v1, zwp_linux_dmabuf_v1. Uses wayland-server-core.h.

2. **winewayland.drv connects to the bridge** — `wayland_process_init` calls `wl_display_connect(NULL)` which connects to `$WAYLAND_DISPLAY` (wayland-0) in `$XDG_RUNTIME_DIR`.

3. **explorer.exe is REQUIRED** — `load_graphics_driver()` in `programs/explorer/desktop.c:996` is the ONLY code that sets the GraphicsDriver registry value and creates the display device GUID. Without explorer, every process gets null_user_driver → nodrv_CreateWindow.

4. **The extension translation must NOT happen in the wrapper** — `wayland_map_instance_extensions` in `winewayland-drv/vulkan.c` handles win32_surface → xlib_surface mapping. If the wrapper translates first, it breaks `has_VK_KHR_win32_surface` flag tracking in `convert_instance_create_info`.

5. **WAYLANDIE_HAS_AHARDWAREBUFFER must be defined** — Without it, the bridge's SHM→AHB conversion path is compiled out, and all desktop SHM buffers are rejected as "not-dmabuf-zero-copy".

6. **libadrenotools.so is in the app's nativeLibraryDir** — NOT in /system/lib64. The Java presenter must pass `context.getApplicationInfo().nativeLibraryDir` as hookLibDir.

7. **The Turnip driver is at** `contents/adrenotools/Turnip Gen8 V27/libvulkan_freedreno.so` — The Java presenter probes for it dynamically.

8. **vkEnumeratePhysicalDevices NULL guards use ERR()** — NOT fopen(). fopen() fails silently if the path doesn't exist, bypassing the guards entirely.

## ITERATION HISTORY SUMMARY

### Phase 1: vkCreateInstance crashes (Root causes 1-11)
Fixed multiple issues with layer loading, extension translation, sType values, and dispatch table setup. vkCreateInstance finally succeeded for wineboot.

### Phase 2: init_physical_device crash (Root cause 12)
The `if (zero_bits && has_VK_EXT_map_memory_placed)` block crashed for FEX-emulated processes. Fixed by stripping map_placed structs in the layer.

### Phase 3: Driver loading (Root causes 13-14, 16)
nulldrv fallback created headless surfaces. explorer.exe was disabled. Fixed by re-enabling explorer and using `wine explorer /desktop=shell` in Wayland mode.

### Phase 4: Extension issues (Root cause 17)
Wrapper's extension translation broke has_VK_KHR_win32_surface flag. Fixed by removing translation from wrapper, letting winewayland.drv handle it.

### Phase 5: Bridge display (Root causes 18-21)
WAYLANDIE_HAS_AHARDWAREBUFFER wasn't defined. Turnip driver path was wrong. libadrenotools.so path was wrong. NULL guards used fopen() which failed silently. All fixed.

### Phase 6: Current — Game rendering
X11 mode: game loads but OOM crashes. Wayland mode: DXVK vkCreateInstance returns -7. dmabuf layer not in dispatch chain.

## FILE UPLOAD ISSUE

File uploads from the user are broken. The last successful upload was `winnative_logs_20260704_115453.zip`. Files uploaded after that do not appear in `/home/z/my-project/upload/`. 

**Workaround**: Ask the user to paste log contents directly in the chat, or upload to a file sharing service and provide a link.

## WORKLOG

All work is logged in `/home/z/my-project/worklog.md`. Read this file for detailed step-by-step history of all 12 task iterations.
