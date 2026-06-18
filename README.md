# WayLandIE — no-root edition (bionic / Termux-native)

> An Android app that runs **Windows games** on Android via Wayland +
> gamescope, **without rooting the phone**. Bionic throughout — no proot,
> no glibc. Works with any `.exe` you download. Supports Armec DXVK,
> Armec Proton, and KGSL-bionic Turnip drivers. Steam is supported but
> optional.

This is the bionic / Termux-native edition of WayLandIE. The
architecture pivots away from proot-distro Debian (glibc) in favor of
running everything directly in Termux (bionic). This eliminates the
glibc/bionic linker conflicts that caused Turnip crashes with the
"Trace/breakpoint trap" error.

The original dmabuf/Vulkan zero-copy presenter is preserved unchanged.

## What's bundled

- a proper Gradle / Android Studio build (replaces the Windows-only
  PowerShell `build-apk.ps1`),
- a Material-dark **Home activity** (GameNative-inspired) with hero
  "Select .exe to launch" CTA, gamescope toggle, prominent Steam
  button, and live status dots for Bridge / Controller / Audio,
- a **7-step no-root Setup wizard** that triggers Termux `RUN_COMMAND`
  intents so the user never types a shell command,
- a **Game launcher** that picks any `.exe` and auto-launches through
  Wine + Armec DXVK + (optional) Armec Proton,
- a **Settings tab** with file pickers for installing:
  - **Turnip** (KGSL bionic variant — standalone variant is rejected
    with a clear error explaining why)
  - **Armec DXVK** (bionic)
  - **Armec Proton** (bionic)
  - **FEX-Emu** (optional x86/x64 translator)
  - **Qualcomm Adreno** proprietary driver (.deb)
  - **box86 / box64** alternative x86 emulators
- **Controller support**: Android `InputDevice.SOURCE_GAMEPAD` is
  detected and forwarded through the bridge as Wayland input events
- **Audio output**: PulseAudio runs in Termux with a TCP sink on port
  57392; the WayLandIE app pulls audio and plays via Android AudioTrack
- the `linux-runtime/` shell scripts (`install.sh`, `waylandie-start-display`,
  `waylandie-doctor`, `waylandie-run`, `waylandie-run-game`,
  `waylandie-steam-session`, `waylandie-install-driver`,
  `waylandie-audio`, `waylandie-termux`, …) bundled as app assets and
  auto-extracted into the public Downloads folder.

---

## Architecture (bionic, no proot)

```
[Android app]
  └─ MainActivity listens on:
       ├─ Abstract socket @waylandie.display.bridge.v1
       └─ TCP 127.0.0.1:57391

[Termux (bionic)]
  └─ Wine (bionic) → game.exe
       ├─ DXVK → Vulkan (your Turnip, KGSL bionic)
       ├─ (optional) gamescope Wayland compositor
       └─ Renders to dmabuf
            → WaylandIE bridge (abstract socket)
            → Android app imports dmabuf as AHardwareBuffer
            → SurfaceControl w/ USAGE_COMPOSITOR_OVERLAY
            → display HWC scanout (zero copy)

[Audio]
  └─ Wine → PulseAudio (Termux, bionic)
       → TCP 127.0.0.1:57392
       → WayLandIE app pulls PCM
       → Android AudioTrack (OpenSL ES)

[Input]
  └─ Bluetooth controller → Android InputDevice
       → WayLandIE MainActivity captures input events
       → Bridge socket forwards as Wayland input events
       → Wine receives via Wayland seat
```

### Why bionic instead of glibc (proot Debian)

The standalone Turnip driver from K11MCH1/AdrenoToolsDrivers links
against Android's `libhardware.so`, `libsync.so`, `libcutils.so`. In a
proot Debian (glibc) environment, binding `/system/lib64` to provide
those libraries triggers a Bionic/glibc ABI conflict — both define
`pthread_atfork`, `__cxa_atexit`, etc. with different signatures. The
linker detects this and aborts with SIGTRAP "Trace/breakpoint trap".

The fix is to use the **KGSL bionic variant** of Turnip
(`mesa-vulkan-kgsl`) which links against Termux's own bionic and talks
directly to `/dev/kgsl-3d0` via ioctl. No Android framework libraries
needed. This works perfectly in Termux-native because Termux IS bionic
— there's no ABI conflict.

Armec DXVK and Armec Proton are also built for bionic, so they slot in
naturally.

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

No CPU copy on the hot path. Termux-native doesn't break this because
Termux runs under the same kernel namespace as WayLandIE — abstract
sockets are visible directly (no proot namespace isolation).

`WAYLANDIE_FINAL_COPY=forbidden` is set by default. If the bridge can't
get a zero-copy path, it errors out rather than silently degrading to
CPU blit.

---

## Build (in Android Studio)

Requirements: Android Studio Hedgehog+, JDK 17, Android SDK platform-34,
build-tools 34.0.0, NDK 26.1.10909125, CMake 3.22.1.

```sh
cd WayLandIE-android-noroot
./gradlew assembleDebug
```

APK at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Build (in the cloud — recommended if you only have a phone)

You cannot build this APK inside Termux — NDK ships x86_64 host toolchains.
Use GitHub Actions instead (workflow at `.github/workflows/build-apk.yml`):

1. Push this repo to GitHub
2. Actions tab → Build APK → Run workflow
3. Download artifact `waylandie-debug-apk`
4. Unzip on phone, sideload APK

---

## No-root setup walkthrough

Open WayLandIE → tap **Run setup wizard**. 7 steps:

1. **Install Termux** (F-Droid link — do NOT use Play Store version)
2. **Grant Termux storage** (`termux-setup-storage`)
3. **Install Termux packages** — enables x11-repo + game-repo, then `pkg install wayland wayland-protocols vulkan-tools mesa-demos wine-stable box86 box64 pulseaudio gamescope`
4. **Push WayLandIE scripts** (auto-extracted by app)
5. **Install WayLandIE helpers** — `sh install.sh --backend termux-native --prefix $PREFIX --install-packages`
6. **Start the WayLandIE display** (Android activity)
7. **Run waylandie-doctor** — verifies bridge + audio + (if installed) Turnip

After the wizard:

1. Open Settings → Install Turnip (KGSL bionic variant)
2. Open Settings → Install Armec DXVK
3. Open Settings → Install Armec Proton (for Steam games)
4. Home → Select .exe to launch → game auto-runs
5. Or Home → Launch Steam → Big Picture boots on display

---

## Driver install — important notes

### Turnip

The Settings tab **rejects the standalone Turnip variant** (links to
`libhardware.so`). Install the **KGSL bionic variant** instead. Look for:

- File names containing `kgsl` or `mesa-vulkan-kgsl`
- Builds from termux-packages mesa-vulkan-icd-freedreno
- Builds labeled "bionic" from Armec or similar

If you only have the standalone variant, the install-driver script
will print a clear error explaining why it was rejected.

### Armec DXVK

Standard Armec DXVK layout: `x32/`, `x64/`, optional `dxvk.conf`.
The install script auto-detects and flattens if needed.

### Armec Proton

Standard Proton tarball layout: `proton` script + `files/` + `dist/`.
Activated via `STEAM_COMPAT_CLIENT_INSTALL_PATH` + `STEAM_COMPAT_DATA_PATH`
in `env-proton.sh`. `STEAM_RUNTIME=0` is forced because pressure-vessel
can't create user namespaces in Termux.

---

## Troubleshooting

**Bridge status dot grey**: tap **Start display** first. If still grey
after display activity is visible, the bridge server thread may have
failed to bind — check that port 57391 isn't already in use.

**Turnip install fails with "standalone variant" error**: you have the
wrong Turnip build. Get the KGSL bionic variant.

**vulkaninfo still shows llvmpipe**: llvmpipe ICD wasn't disabled. Run
`mv $PREFIX/etc/vulkan/icd.d/lvp_icd.json $PREFIX/etc/vulkan/icd.d/lvp_icd.json.disabled`
in Termux.

**Steam game fails with `bwrap: No permissions to create new namespace`**:
expected — pressure-vessel doesn't work in Termux. `waylandie-steam-session`
defaults to `STEAM_RUNTIME=0` to avoid this.

**No audio**: run `waylandie-audio start` in Termux to start PulseAudio.
Check the audio status dot on Home — green = PulseAudio listening on
57392.

**Controller not detected**: open Android Settings → Connected devices
→ make sure your controller is paired. The Home screen Controller dot
will turn green when InputDevice detects a SOURCE_GAMEPAD.

---

## Credits

This is a fork of the original WayLandIE engineering handoff. The
dmabuf / Vulkan / SurfaceControl presenter (`MainActivity.java` +
`waylandie_display_native.c`) is unchanged from the original repo. The
bionic/Termux-native build, Home activity, Setup wizard, Settings tab,
controller detection, audio bridge, and Linux-runtime scripts are new
work in this fork.

See `LICENSE` and `THIRD_PARTY_NOTICES.md` for the original license and
attribution.

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

## Build (in the cloud — recommended if you only have a phone)

**You cannot build this APK inside Termux on a phone.** The official
Android NDK only ships x86_64-linux *host* toolchains — the
`aarch64-linux-android-clang` binary inside the NDK is an x86_64 ELF
and won't run on arm64 Termux. Don't waste time on sdkmanager
workarounds; they all hit this wall.

Use GitHub Actions instead. The repo ships a workflow at
`.github/workflows/build-apk.yml` that builds a debug APK on every
push and on manual dispatch.

Steps:

1. Create a free GitHub account if you don't have one.
2. Fork or push this repo to GitHub:
   ```sh
   cd WayLandIE-android-noroot
   git init
   git add .
   git commit -m "init"
   git branch -M main
   git remote add origin https://github.com/YOUR_USER/WayLandIE.git
   git push -u origin main
   ```
3. Open the repo on GitHub → **Actions** tab → **Build APK** →
   **Run workflow** button (top right).
4. Wait ~5 minutes. The build runs on a free GitHub-hosted Ubuntu
   runner.
5. When the run finishes, scroll down to the **Artifacts** section of
   the run → download `waylandie-debug-apk`. You'll get a zip with
   `app-debug.apk` inside.
6. Unzip on your phone and sideload the APK.

This is the only reliable path if you don't have access to a PC. It's
free for personal use (2,000 actions minutes/month).

---

## Build (from cmdline-tools on a PC)

If you have a Linux/Mac/Windows PC and don't want to install Android
Studio:

```sh
# 1. Install cmdline-tools + sdkmanager
#    https://developer.android.com/tools
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" "ndk;26.1.10909125" "cmake;3.22.1"

# 2. Set env
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125

# 3. Build
cd WayLandIE-android-noroot
./gradlew assembleDebug
```

---

## Can I build inside Termux anyway?

Short answer: **no**, not with the official NDK.

Longer answer: you can technically run the x86_64 NDK toolchains under
FEX-Emu inside Termux, but it's slow (~5× slower compiles), fragile,
and you'd need to patch CMake to invoke FEX as a prefix. Not worth it
when GitHub Actions gives you a free Ubuntu runner that builds this
project in 5 minutes.

If you really want to try it anyway:

```sh
pkg install fex-emu
# Then wrap every NDK toolchain call with `FEXLoader --`
# You're on your own for the CMake plumbing.
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

**Steam game fails with `bwrap: No permissions to create new namespace`
or `pressure-vessel: Failed to create container`** — this is expected
inside proot. proot cannot create real unprivileged user namespaces,
which bubblewrap (used by Steam's pressure-vessel container runtime)
requires.

`waylandie-steam-session` defaults to `STEAM_RUNTIME=0` (host runtime,
no container) which avoids this. Do NOT try `--runtime=sniper` or
`--runtime=medic` — those use pressure-vessel and will fail. Use
`--runtime=0` (default) or `--runtime=scout` (legacy non-container
runtime) only.

The trade-off: per-game library isolation is disabled. Games run
directly in Debian with FEX. Install any missing game libraries via
`apt-get install` as needed.

**Snap-based Steam does NOT work in proot.** Steam's Snap package
requires `snapd` → systemd → cgroups v2 + real mount namespaces, none
of which work in proot. Use the official Steam .deb from
`repo.steampowered.com` instead — `waylandie install.sh` installs it
automatically during Debian setup.

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
