# WayLandIE — no-root edition

> An Android app that runs **Windows games** on Android via Wayland +
> gamescope, **without rooting the phone**. Works with any `.exe` you
> download — Steam, GOG, itch.io, or your own builds. Steam is supported
> but optional.

This fork turns the original WayLandIE engineering handoff into an
installable Android Studio project with a built-in no-root setup wizard.
The original dmabuf/Vulkan zero-copy presenter is preserved unchanged —
this fork only adds:

- a proper Gradle / Android Studio build (replaces the Windows-only
  PowerShell `build-apk.ps1`),
- a Material-dark **Home activity** with bridge status, Start/Stop
  display, Launch a game, Launch Steam, Install driver, Open Terminal,
  and a live bridge log,
- a **7-step no-root Setup wizard** that triggers Termux `RUN_COMMAND`
  intents so the user never types a shell command,
- a **Game launcher** that lets the user pick any `.exe` from their
  Downloads folder and run it through Wine inside the Debian proot,
  with optional gamescope + driver slot,
- a **Driver installer** with a file picker for DXVK / FEX / Turnip /
  Qualcomm Adreno driver packages — install into named slots without
  touching the command line,
- the previously-missing `linux-runtime/` shell scripts (`install.sh`,
  `waylandie-start-display`, `waylandie-doctor`, `waylandie-run`,
  `waylandie-run-game`, `waylandie-steam-session`,
  `waylandie-game-profile`, `waylandie-install-driver`, …) bundled as
  app assets and auto-extracted into the public Downloads folder so
  Termux can see them.

---

## Does this preserve the zero-copy path?

**Yes.** The hot path is unchanged from the original:

```
Linux Wayland client (game / gamescope)
   → dmabuf fd
   → waylandie.display.bridge.v1 abstract socket
   → Android app imports dmabuf as AHardwareBuffer via
     VK_ANDROID_external_memory_android_hardware_buffer
   → presented through SurfaceControl with USAGE_COMPOSITOR_OVERLAY
   → phone display
```

No CPU copy on the hot path. Putting the Linux side inside a `proot`
**does not break this** because:

- dmabuf fds are kernel-level objects; proot's syscall translation does
  not affect `SCM_RIGHTS` fd passing.
- The bridge socket uses the Linux abstract namespace, which is not
  filesystem-based, so it works the same inside and outside proot.
- `AHardwareBuffer` is an Android framework API exposed to every app;
  no root or special permission is needed.
- `SurfaceControl.Transaction` with `USAGE_COMPOSITOR_OVERLAY` lets the
  display HWC composer scan out the buffer directly — no GPU blit.

If `WAYLANDIE_FINAL_COPY=forbidden` is set in the environment (the
default set by `install.sh`), the bridge will refuse to fall back to a
CPU blit path and will report an error instead of silently degrading.

---

## CPU / GPU optimization flags

The native presenter is compiled with:

- `-O3` — maximum optimization
- `-flto` — link-time optimization across translation units
- `-ffunction-sections -fdata-sections` + `-Wl,--gc-sections` — dead
  code elimination
- `-fvisibility=hidden` — only JNI entry points are exported
- `-fno-unwind-tables -fno-asynchronous-unwind-tables` — smaller .so,
  no runtime unwind overhead

At runtime the presenter uses:

- `AHardwareBuffer` with `AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
  AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER |
  AHARDWAREBUFFER_USAGE_COMPOSITOR_OVERLAY` — direct overlay plane
  eligibility, no compositor blit
- `VK_ANDROID_external_memory_android_hardware_buffer` — import dmabuf
  as Vulkan memory, zero copy
- `SyncFence` for explicit async fences — CPU never waits on the GPU
- `SurfaceControl.Transaction.setBufferCrop` + `setBufferSize` —
  hardware crop, no CPU scaling

`MESA_VK_WSI_PRESENT_MODE=immediate` is exported by `waylandie-run` so
the Wayland client side does not queue frames internally.

---

## Build (in Android Studio)

Requirements:

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17 (bundled with Android Studio)
- Android SDK with `platform-34` + `build-tools;34.0.0`
- Android NDK r25+ (Android Studio will offer to install it on first
  project open)

Steps:

```sh
cd WayLandIE-android-noroot
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Install on a connected device (USB debugging on):

```sh
./gradlew installDebug
```

Or sideload manually:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> The `gradlew` shell script will auto-download `gradle-wrapper.jar`
> from the official Gradle GitHub release if it's missing from the
> archive (some git hosts strip binary files).

---

## Build (from cmdline-tools without Android Studio)

If you don't want to install Android Studio, you can build with the
Android cmdline-tools + NDK only:

```sh
# 1. Install cmdline-tools + sdkmanager
#    https://developer.android.com/tools
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" "ndk;26.3.11579264"

# 2. Set env
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.3.11579264

# 3. Build
cd WayLandIE-android-noroot
./gradlew assembleDebug
```

---

## No-root setup walkthrough

The first time you open WayLandIE on your phone, the Home screen shows
"Bridge status: Off". Tap **Run setup wizard**.

The wizard walks you through 7 steps. Each step either fires a Termux
`RUN_COMMAND` intent (preferred — runs the command for you in Termux) or
copies the command to the clipboard and opens Termux so you can paste
it manually (fallback if Termux:API is missing).

### Step 1 — Install Termux

Opens the F-Droid page for Termux. **Do not install Termux from the Play
Store** — that version is unmaintained and the `RUN_COMMAND` service
does not work in it. Install from F-Droid or directly from
<https://termux.dev>.

### Step 2 — Grant Termux storage access

Runs `termux-setup-storage` inside Termux. You'll see a permission
dialog on the phone — accept it. This lets Termux read the shared
`/sdcard/Download/` folder.

### Step 3 — Install proot-distro + Debian

Runs:

```
pkg install -y proot-distro
proot-distro install debian
```

This pulls ~80 MB. No root needed — proot runs as a normal user under
Termux's UID.

### Step 4 — Push WayLandIE scripts

WayLandIE already extracted the bundled `linux-runtime/` scripts into
`/sdcard/Download/WayLandIE/linux-runtime/` during step 2 (via
`AssetInstaller`). This step just verifies they're there:

```
ls -la ~/storage/downloads/WayLandIE/linux-runtime/install.sh
```

### Step 5 — Install WayLandIE inside Debian

Runs the installer inside the Debian proot:

```
proot-distro login debian --shared-tmp -- bash -lc \
  'cd /sdcard/Download/WayLandIE/linux-runtime && \
   sh install.sh --backend proot --prefix /usr/local --install-packages'
```

This installs: Wayland dev headers, mesa, Vulkan loader, vulkan-tools,
mesa-utils, weston, and (if available) gamescope. It also installs the
`waylandie-*` helper scripts into `/usr/local/bin/`.

### Step 6 — Start the WayLandIE display

Taps the Android-side **Start display** button for you. The display
activity opens and starts listening on the abstract socket
`waylandie.display.bridge.v1`.

### Step 7 — Run waylandie-doctor

Verifies the bridge end-to-end inside Debian:

```
proot-distro login debian --shared-tmp -- bash -lc 'waylandie-doctor'
```

You should see:

```
[1] Bridge socket
  ✓ abstract socket @waylandie.display.bridge.v1 is listening
[2] Vulkan driver
  ✓ Vulkan device: Adreno (TM) 750
[3] Test render
  ✓ vkcube ran (timed out as expected)
=== doctor: 3 pass / 0 fail ===
```

If doctor passes, you're ready to play.

---

## Playing a Windows game

1. Open WayLandIE → tap **Start display**.
2. Tap **Open Termux**.
3. Inside Termux:

   ```sh
   proot-distro login debian --shared-tmp
   waylandie-start-display
   waylandie-steam-session start
   ```

   This launches Steam Big Picture inside the Debian proot, rendered
   into the WayLandIE display activity on the Android side.

4. Sign into Steam, install an ARM64-compatible Windows game (e.g.
   *Metro: Last Light Redux* — see `examples/steam-metro-last-light/`
   in the original repo).

5. To apply DXVK / Turnip / Qualcomm driver slot overrides:

   ```sh
   waylandie-steam-profile bootstrap
   waylandie-steam-profile list-profiles 287390
   waylandie-steam-profile set 287390 turnip-current
   waylandie-steam-profile hook 287390
   ```

   The `hook` command prints the exact Steam Launch Options string to
   paste into Steam → game → Properties → Launch Options.

---

## Repository layout

```
WayLandIE-android-noroot/
├── build.gradle.kts                  # root build script
├── settings.gradle.kts
├── gradle.properties
├── gradlew, gradlew.bat              # wrapper bootstrap
├── gradle/wrapper/                   # wrapper jar + properties
├── app/
│   ├── build.gradle.kts              # app build script + NDK CMake flags
│   ├── proguard-rules.pro
│   ├── keystore/debug.keystore       # debug signing key (from original repo)
│   └── src/main/
│       ├── AndroidManifest.xml       # HomeActivity is launcher; MainActivity presenter
│       ├── java/io/waylandie/display/
│       │   ├── MainActivity.java          (original — 6000-line bridge)
│       │   ├── LinuxWindowActivity.java   (original — multi-window presenter)
│       │   ├── BridgeKeepAliveService.java (original — foreground service)
│       │   ├── WindowBridgeRegistry.java  (original — window registry)
│       │   ├── HomeActivity.java          (NEW — launcher + status + actions)
│       │   ├── SetupWizardActivity.java   (NEW — 7-step no-root wizard)
│       │   ├── TermuxBridge.java          (NEW — RUN_COMMAND intent sender)
│       │   ├── AssetInstaller.java        (NEW — extracts bundled scripts)
│       ├── cpp/
│       │   ├── CMakeLists.txt             (NEW — aggressive optimization flags)
│       │   ├── waylandie_display_native.c (original — dmabuf/Vulkan presenter)
│       │   └── ahb_vk_3d_shaders.h        (original — Vulkan shaders)
│       ├── res/
│       │   ├── drawable/                  (ic_bridge, ic_launcher_foreground)
│       │   ├── layout/                    (activity_home, activity_setup, item_setup_step)
│       │   ├── values/                    (colors, strings, styles)
│       │   ├── xml/file_paths.xml         (FileProvider paths)
│       │   ├── mipmap-anydpi-v26/         (adaptive launcher icon)
│       └── assets/linux-runtime/
│           ├── install.sh                 (NEW — backend-aware installer)
│           └── bin/
│               ├── waylandie-start-display
│               ├── waylandie-status
│               ├── waylandie-doctor
│               ├── waylandie-run
│               ├── waylandie-steam-session
│               ├── waylandie-steam-profile
│               ├── waylandie-steam-install-dxvk-slot
│               ├── waylandie-steam-install-turnip-slot
│               └── waylandie-import-qcom-adreno-driver
├── LICENSE                           (from original repo)
└── THIRD_PARTY_NOTICES.md            (from original repo)
```

---

## What about Steam / Qualcomm drivers / game data?

WayLandIE does not redistribute any of:

- Steam (closed source, Valve),
- Proton ( redistributable but large; install inside Debian yourself),
- Qualcomm proprietary Adreno driver packages (licensable from Qualcomm),
- Game files (you own these via your Steam account).

The `waylandie-steam-*` and `waylandie-import-qcom-adreno-driver`
scripts help you import legally-obtained copies into named slots. See
`THIRD_PARTY_NOTICES.md` for full details.

---

## Troubleshooting

**"Bridge status: Off"** — the Android display app isn't running. Tap
*Start display* on the Home screen.

**"Bridge status: Listening" but `waylandie-doctor` says socket is off**
— you're inside the Debian proot. Run `waylandie-start-display` first
to re-source the bridge env.

**vkcube fails** — Vulkan driver missing. On Adreno, install Turnip:

```sh
waylandie-steam-install-turnip-slot \
  --appid 0 --slot turnip-current \
  --activate ~/Downloads/turnip.tar.gz
```

**Steam doesn't render** — make sure `waylandie-steam-session start`
ran, then `waylandie-steam-session status` to see the pid + log path.
Tail the log: `tail -f $XDG_RUNTIME_DIR/waylandie/steam-session.log`.

**Termux RUN_COMMAND doesn't work** — Termux:API is missing. Install
`Termux:API` from F-Droid, then in Termux run `pkg install termux-api`.
Also edit `~/.termux/termux.properties` and add `allow-external-apps=true`.

---

## Credits

This is a fork of the original WayLandIE engineering handoff. The
dmabuf / Vulkan / SurfaceControl presenter (`MainActivity.java` +
`waylandie_display_native.c`) is **unchanged** from the original repo.
The no-root Gradle build, Home activity, Setup wizard, Termux bridge,
and Linux-runtime scripts are new work in this fork.

See `LICENSE` and `THIRD_PARTY_NOTICES.md` for the original license and
attribution.
