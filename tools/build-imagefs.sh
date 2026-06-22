#!/bin/bash
# build-imagefs.sh — generates the BASE rootfs (imagefs.tar.xz) that
# ships inside the WayLandIE APK.
#
# IMPORTANT: This rootfs does NOT include Wine. Wine/Proton/DXVK/Turnip
# are installed by the user via the Settings tab and bind-mounted into
# this rootfs at runtime by ProotRunner. This mirrors WinNative's
# architecture where the imagefs is minimal and Proton is a separate
# downloadable asset.
#
# Supports two modes:
#   1. NATIVE arm64 host (e.g. Termux on arm64 phone, or arm64 PC):
#      debootstrap runs natively, chroot works directly.
#   2. CROSS-BUILD x86_64 host (e.g. GitHub Actions runner):
#      Uses debootstrap --foreign + --second-stage with qemu-user-static
#      binfmt support.
#
# Output: app/src/main/assets/imagefs/imagefs.tar.xz (~80-100 MB compressed)
#
# The rootfs contains ONLY:
#   - Ubuntu 20.04 Focal arm64 base (minimal) — glibc 2.31
#     CRITICAL: glibc 2.31 is the LATEST version that avoids Android's seccomp
#     SIGSYS issue. glibc 2.34+ calls clone3() and glibc 2.35+ calls rseq()
#     during __libc_start_main() — both are blocked by Android's seccomp filter
#     → SIGSYS → exit code 159. Ubuntu 20.04 Focal (glibc 2.31) predates both
#     and runs natively on Android 16 without seccomp issues.
#     Reference: Winlator-Ludashi-Plus uses Ubuntu 18.04 Bionic (glibc 2.27).
#     We use 20.04 Focal for newer Wayland protocol packages (1.20+).
#   - Wayland client libraries
#   - Vulkan loader + mesa-vulkan-drivers (llvmpipe fallback only —
#     Turnip is installed separately via Settings tab)
#   - PulseAudio (for audio bridge)
#   - box86 + box64 (x86/x64 emulators)
#   - Core X11/Wayland libs that Wine/Proton need at runtime
#
# What the rootfs does NOT contain:
#   - Wine (user installs via Settings → Proton)
#   - Proton (user installs via Settings → Proton)
#   - DXVK (user installs via Settings → DXVK, or Proton bundles it)
#   - Turnip (user installs via Settings → Turnip, or rootfs uses llvmpipe)
#   - FEX-Emu (user installs via Settings → FEX, optional)
#
# Usage:
#   sudo bash build-imagefs.sh              # native or cross (auto-detected)
#   bash build-imagefs.sh                   # if sudo not needed (CI)
#   ARCH=arm64 DIST=trixie bash build-imagefs.sh

set -e
set -o pipefail  # CRITICAL: without this, 'cmd | tail' masks cmd failures

WORK_DIR="${WORK_DIR:-/tmp/waylandie-imagefs-build}"
ROOTFS_DIR="$WORK_DIR/rootfs"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_FILE="${OUTPUT_FILE:-$SCRIPT_DIR/../app/src/main/assets/imagefs/imagefs.tar.zst}"
ARCH="${ARCH:-arm64}"
DIST="${DIST:-focal}"

# Detect host architecture
HOST_ARCH=$(uname -m)
CROSS_BUILD=false
if [ "$HOST_ARCH" != "aarch64" ] && [ "$HOST_ARCH" != "arm64" ]; then
    CROSS_BUILD=true
    echo "=== Cross-build detected (host: $HOST_ARCH, target: $ARCH) ==="
fi

echo "=== WayLandIE imagefs builder (NO WINE — Proton installed separately) ==="
echo "  Work dir:    $WORK_DIR"
echo "  Rootfs dir:  $ROOTFS_DIR"
echo "  Output:      $OUTPUT_FILE"
echo "  Arch:        $ARCH"
echo "  Dist:        $DIST"
echo "  Cross-build: $CROSS_BUILD"
echo

mkdir -p "$WORK_DIR" "$ROOTFS_DIR" "$(dirname "$OUTPUT_FILE")"

# ---------------------------------------------------------------------
# 0. Install host dependencies
# ---------------------------------------------------------------------
echo "[0/7] Installing host dependencies…"
if command -v apt-get >/dev/null 2>&1; then
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
        debootstrap xz-utils tar wget sudo binfmt-support 2>/dev/null || true
    if [ "$CROSS_BUILD" = "true" ]; then
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq qemu-user-static 2>/dev/null || true
        update-binfmts --enable qemu-aarch64 2>/dev/null || true
        [ -f /proc/sys/fs/binfmt_misc/qemu-aarch64 ] && echo "  ✓ qemu-aarch64 binfmt registered"
    fi
fi

QEMU_STATIC=""
for path in /usr/bin/qemu-aarch64-static /usr/bin/qemu-aarch64; do
    [ -x "$path" ] && QEMU_STATIC="$path" && break
done

chroot_run() { chroot "$ROOTFS_DIR" "$@"; }

# ---------------------------------------------------------------------
# 1. Bootstrap base rootfs
# ---------------------------------------------------------------------
if [ ! -d "$ROOTFS_DIR/usr" ]; then
    echo "[1/7] Bootstrapping $DIST $ARCH base…"
    if [ "$CROSS_BUILD" = "true" ]; then
        # Ubuntu arm64 uses ports.ubuntu.com (not archive.ubuntu.com)
        debootstrap --arch="$ARCH" --foreign --variant=minbase "$DIST" "$ROOTFS_DIR" \
            http://ports.ubuntu.com/ubuntu-ports
        [ -n "$QEMU_STATIC" ] && cp "$QEMU_STATIC" "$ROOTFS_DIR/usr/bin/"
        chroot "$ROOTFS_DIR" /debootstrap/debootstrap --second-stage
        rm -f "$ROOTFS_DIR/usr/bin/qemu-aarch64-static"
    else
        debootstrap --arch="$ARCH" --variant=minbase "$DIST" "$ROOTFS_DIR" \
            http://ports.ubuntu.com/ubuntu-ports
    fi
else
    echo "[1/7] Rootfs exists, skipping debootstrap."
fi

# ---------------------------------------------------------------------
# 2. Configure apt + install core runtime libraries
#    NO WINE — Wine/Proton is installed separately via Settings tab
# ---------------------------------------------------------------------
echo "[2/7] Configuring apt + installing core libraries (NO WINE)…"
# Ubuntu arm64 uses ports.ubuntu.com. Include universe for wayland packages.
cat > "$ROOTFS_DIR/etc/apt/sources.list" <<EOF
deb http://ports.ubuntu.com/ubuntu-ports $DIST main universe
deb http://ports.ubuntu.com/ubuntu-ports $DIST-updates main universe
deb http://ports.ubuntu.com/ubuntu-ports $DIST-security main universe
EOF

echo 'APT::Get::Assume-Yes "true";' > "$ROOTFS_DIR/etc/apt/apt.conf.d/99assumeyes"
echo 'debconf debconf/frontend select Noninteractive' | chroot_run debconf-set-selections 2>/dev/null || true

chroot_run apt-get update -qq

# Group 1: Essential tools — must succeed first so later steps can use them
echo "  Installing essential tools…"
chroot_run apt-get install -y --no-install-recommends \
    ca-certificates wget curl xz-utils tar bzip2 gzip unzip binutils \
    locales bash coreutils findutils gcc pkg-config libc6-dev 2>&1 | tail -3

# Group 2: Wayland + Vulkan + display libraries
# Ubuntu 20.04 Focal has wayland-protocols 1.20+ which includes all
# the protocol XMLs we need (xdg-shell, linux-dmabuf, presentation-time,
# viewporter, relative-pointer, pointer-constraints).
echo "  Installing Wayland + Vulkan + display libraries…"
chroot_run apt-get install -y --no-install-recommends \
    libwayland-client0 libwayland-server0 wayland-protocols \
    libwayland-bin libwayland-dev \
    libvulkan1 vulkan-tools mesa-vulkan-drivers mesa-utils \
    libpulse0 pulseaudio pulseaudio-utils \
    libfreetype6 libfontconfig1 libgnutls30 libxcomposite1 \
    libxinerama1 libxcursor1 libxrandr2 libxi6 libxtst6 \
    libxrender1 libxext6 libxfixes3 libxxf86vm1 \
    libx11-dev libxtst-dev \
    libgl1 libegl1 libgles2 \
    libgstreamer1.0-0 libgstreamer-plugins-base1.0-0 \
    desktop-file-utils 2>&1 | tail -3 || echo "  WARNING: some display libs failed"

chroot_run locale-gen en_US.UTF-8 2>/dev/null || true

# ---------------------------------------------------------------------
# 2.5. Build libwayland 1.22 from source + vendor wayland-protocols 1.27
# ---------------------------------------------------------------------
# CRITICAL: The Wayland bridge translator uses features from:
#   - wl_seat v8 (wl_seat_send_name — added in wayland 1.21)
#   - wl_output v4 (done event semantics — added in wayland 1.20)
#   - zwp_linux_dmabuf_v1 v4 (dmabuf feedback — added in wayland-protocols 1.23)
#
# Ubuntu 20.04 Focal ships libwayland 1.18 + wayland-protocols 1.20, which
# are too old. We MUST build libwayland 1.22 from source and vendor newer
# wayland-protocols XMLs. The bridge won't compile or run without these.
#
# We keep Focal's glibc 2.31 (safe for Android seccomp) but override wayland.
echo "[2.5/7] Building libwayland 1.22 from source + vendoring wayland-protocols 1.27…"

# Install build dependencies for wayland
# CRITICAL: Focal's meson 0.53.2 is too old for wayland 1.22 (needs >= 0.56.0).
# Install python3-pip + upgrade meson via pip to get a newer version.
echo "  Installing wayland build deps + upgrading meson via pip…"
chroot_run apt-get install -y -qq python3-pip ninja-build libffi-dev libexpat1-dev libxml2-dev 2>&1 | tail -2
chroot_run bash -c 'pip3 install "meson>=0.56.0" 2>&1 | tail -2 || echo "  pip install meson failed"'
chroot_run bash -c 'meson --version 2>&1'

# Download + build wayland 1.22 (provides wl_seat v8, wl_output v4)
# CRITICAL: Do NOT pipe through tail — it masks the exit code and causes
# silent failures. If this build fails, the bridge won't compile.
echo "  Building libwayland 1.22.0 from source…"
if ! chroot_run bash -c 'set -e; export PATH=/usr/local/bin:$PATH; cd /tmp && \
    wget -q https://gitlab.freedesktop.org/wayland/wayland/-/archive/1.22.0/wayland-1.22.0.tar.gz && \
    tar xf wayland-1.22.0.tar.gz && \
    cd wayland-1.22.0 && \
    meson setup build --prefix=/usr --libdir=lib/aarch64-linux-gnu -Ddocumentation=false -Dtests=false -Dlibraries=true && \
    ninja -C build install && \
    ldconfig && \
    echo "  ✓ libwayland 1.22.0 built + installed" && \
    rm -rf /tmp/wayland-1.22.0*'; then
    echo "  ✗ FATAL: libwayland 1.22 build FAILED — bridge won't compile"
    echo "    Check: meson, libffi-dev, libexpat1-dev, libxml2-dev installed?"
    exit 1
fi

# Download + install wayland-protocols 1.27 (provides linux-dmabuf v4, xdg_wm_base v6)
echo "  Installing wayland-protocols 1.27…"
if ! chroot_run bash -c 'set -e; export PATH=/usr/local/bin:$PATH; cd /tmp && \
    wget -q https://gitlab.freedesktop.org/wayland/wayland-protocols/-/archive/1.27/wayland-protocols-1.27.tar.gz && \
    tar xf wayland-protocols-1.27.tar.gz && \
    cd wayland-protocols-1.27 && \
    meson setup build --prefix=/usr && \
    ninja -C build install && \
    echo "  ✓ wayland-protocols 1.27 installed" && \
    rm -rf /tmp/wayland-protocols-1.27*'; then
    echo "  ✗ FATAL: wayland-protocols 1.27 install FAILED — bridge won't compile"
    exit 1
fi

# Verify wayland version (must be >= 1.20 for wl_output v4)
WL_VER=$(chroot_run pkg-config --modversion wayland-server 2>/dev/null || echo "0.0.0")
echo "  libwayland-server version: $WL_VER"
WP_VER=$(chroot_run pkg-config --modversion wayland-protocols 2>/dev/null || echo "0.0.0")
echo "  wayland-protocols version: $WP_VER"

# ---------------------------------------------------------------------
# 3. Install box64 (BIONIC build — Android-native, no glibc needed)
# ---------------------------------------------------------------------
# We use the BIONIC box64 from StevenMXZ/Winlator-Ludashi (MIT license).
# This runs directly on Android without a glibc linker wrapper — same
# architecture as winlator. box64 then loads glibc libraries from this
# rootfs for the emulated Wine process.
# NOTE: box64 is only needed for x86_64 Wine. arm64ec Wine runs natively
# and uses FEXCore (libarm64ecfex.dll) for x86_64 game code instead.
echo "[3/7] Installing bionic box64 (Android-native, for x86_64 Wine fallback)…"
chroot_run bash -c 'mkdir -p /usr/bin && cd /tmp && \
    wget -qO box64.tzst "https://github.com/StevenMXZ/Winlator-Ludashi/raw/ludashi-3.0/app/src/main/assets/box64/box64-0.4.1.tzst" && \
    if command -v zstd >/dev/null 2>&1; then \
        zstd -d box64.tzst -o box64.tar && tar -xf box64.tar -C / && chmod +x /usr/bin/box64 && echo "  bionic box64 installed (zstd)"; \
    else \
        apt-get install -y -qq zstd && zstd -d box64.tzst -o box64.tar && tar -xf box64.tar -C / && chmod +x /usr/bin/box64 && echo "  bionic box64 installed (zstd apt)"; \
    fi || echo "  box64 install failed — arm64ec Wine will still work without it"; \
    rm -f box64.tzst box64.tar' 2>&1 | tail -5

# Verify box64 is bionic (interpreter should be /system/bin/linker64)
if [ -f "$ROOTFS_DIR/usr/bin/box64" ]; then
    BOX64_INTERP=$(file "$ROOTFS_DIR/usr/bin/box64" 2>/dev/null || echo "unknown")
    echo "  box64: $BOX64_INTERP"
    if echo "$BOX64_INTERP" | grep -q "linker64"; then
        echo "  ✓ bionic box64 confirmed"
    else
        echo "  ⚠ WARNING: box64 is NOT bionic — may not work without glibc linker"
    fi
fi

# ---------------------------------------------------------------------
# 3.5. Compile the Wayland-to-Android bridge translator
# ---------------------------------------------------------------------
# The bridge is a ~3900-line C program that implements a minimal Wayland
# compositor (wl_compositor, xdg_wm_base, linux-dmabuf, presentation-time,
# viewporter, seat, relative-pointer, pointer-constraints). It accepts
# Wayland client connections from Wine, extracts dmabuf fds, and forwards
# them to the Android bridge (abstract socket waylandie.display.bridge.v1)
# via the custom "waylandie-bridge" text protocol. This is the zero-copy
# path — dmabuf fds pass through directly, no CPU copy.
#
# Source: AstroCODEsky/WayLandIE (MIT license), adapted for our build.
echo "[3.5/7] Compiling Wayland bridge translator…"
BRIDGE_SRC="$SCRIPT_DIR/../app/src/main/assets/linux-runtime/bridge/waylandie-wayland-bridge.c"
if [ -f "$BRIDGE_SRC" ]; then
    cp "$BRIDGE_SRC" "$ROOTFS_DIR/tmp/waylandie-wayland-bridge.c"

    # Generate Wayland protocol headers + source files
    if ! chroot_run bash -c 'set -e; export PATH=/usr/local/bin:$PATH; \
        cd /tmp && \
        XML_DIR=/usr/share/wayland-protocols && \
        wayland-scanner server-header $XML_DIR/stable/xdg-shell/xdg-shell.xml /tmp/xdg-shell-server-protocol.h && \
        wayland-scanner private-code $XML_DIR/stable/xdg-shell/xdg-shell.xml /tmp/xdg-shell-protocol.c && \
        wayland-scanner server-header $XML_DIR/unstable/linux-dmabuf/linux-dmabuf-unstable-v1.xml /tmp/linux-dmabuf-unstable-v1-server-protocol.h && \
        wayland-scanner private-code $XML_DIR/unstable/linux-dmabuf/linux-dmabuf-unstable-v1.xml /tmp/linux-dmabuf-unstable-v1-protocol.c && \
        wayland-scanner server-header $XML_DIR/stable/presentation-time/presentation-time.xml /tmp/presentation-time-server-protocol.h && \
        wayland-scanner private-code $XML_DIR/stable/presentation-time/presentation-time.xml /tmp/presentation-time-protocol.c && \
        wayland-scanner server-header $XML_DIR/stable/viewporter/viewporter.xml /tmp/viewporter-server-protocol.h && \
        wayland-scanner private-code $XML_DIR/stable/viewporter/viewporter.xml /tmp/viewporter-protocol.c && \
        wayland-scanner server-header $XML_DIR/unstable/relative-pointer/relative-pointer-unstable-v1.xml /tmp/relative-pointer-unstable-v1-server-protocol.h && \
        wayland-scanner private-code $XML_DIR/unstable/relative-pointer/relative-pointer-unstable-v1.xml /tmp/relative-pointer-unstable-v1-protocol.c && \
        wayland-scanner server-header $XML_DIR/unstable/pointer-constraints/pointer-constraints-unstable-v1.xml /tmp/pointer-constraints-unstable-v1-server-protocol.h && \
        wayland-scanner private-code $XML_DIR/unstable/pointer-constraints/pointer-constraints-unstable-v1.xml /tmp/pointer-constraints-unstable-v1-protocol.c && \
        echo "  protocol headers generated"'; then
        echo "  ✗ FATAL: wayland protocol header generation FAILED"
        exit 1
    fi

    # Compile the bridge
    # CRITICAL: Do NOT pipe through tail — masks exit code.
    if ! chroot_run bash -c 'set -e; export PATH=/usr/local/bin:$PATH; \
        cc -Wall -Wextra -o /usr/local/bin/waylandie-wayland-bridge \
            /tmp/waylandie-wayland-bridge.c \
            /tmp/xdg-shell-protocol.c \
            /tmp/linux-dmabuf-unstable-v1-protocol.c \
            /tmp/presentation-time-protocol.c \
            /tmp/viewporter-protocol.c \
            /tmp/relative-pointer-unstable-v1-protocol.c \
            /tmp/pointer-constraints-unstable-v1-protocol.c \
            $(pkg-config --cflags --libs wayland-server x11 xtst) && \
        chmod +x /usr/local/bin/waylandie-wayland-bridge && \
        echo "  bridge compiled → /usr/local/bin/waylandie-wayland-bridge"'; then
        echo "  ✗ FATAL: bridge compilation FAILED"
        exit 1
    fi

    # Clean up temp files
    rm -f "$ROOTFS_DIR/tmp/waylandie-wayland-bridge.c" "$ROOTFS_DIR/tmp/"*-protocol.{c,h}

    # Verify
    if [ -f "$ROOTFS_DIR/usr/local/bin/waylandie-wayland-bridge" ]; then
        echo "  ✓ Wayland bridge translator installed"
    else
        echo "  ⚠ WARNING: bridge compilation failed — Wine won't be able to render"
    fi
else
    echo "  ⚠ bridge source not found at $BRIDGE_SRC — skipping"
fi

# ---------------------------------------------------------------------
# 3.6. Verify glibc version is < 2.34 (seccomp safety check)
# ---------------------------------------------------------------------
# glibc 2.34+ calls clone3() and glibc 2.35+ calls rseq() during
# __libc_start_main(). Both are blocked by Android's seccomp filter.
# Ubuntu 20.04 Focal ships glibc 2.31 which is SAFE. Verify this.
echo "[3.6/7] Verifying glibc version (must be < 2.34 for seccomp safety)…"
GLIBC_VERSION=$(chroot_run ldd --version 2>&1 | head -1 | grep -oP '\d+\.\d+' | head -1)
echo "  glibc version: $GLIBC_VERSION"
if [ -n "$GLIBC_VERSION" ]; then
    GLIBC_MAJOR=$(echo "$GLIBC_VERSION" | cut -d. -f1)
    GLIBC_MINOR=$(echo "$GLIBC_VERSION" | cut -d. -f2)
    if [ "$GLIBC_MAJOR" -gt 2 ] || ([ "$GLIBC_MAJOR" -eq 2 ] && [ "$GLIBC_MINOR" -ge 34 ]); then
        echo "  ✗ FATAL: glibc $GLIBC_VERSION is too new — will trigger seccomp SIGSYS on Android"
        echo "    Need glibc < 2.34. Ubuntu 20.04 Focal has 2.31."
        exit 1
    else
        echo "  ✓ glibc $GLIBC_VERSION is safe (< 2.34) — no clone3/rseq during startup"
    fi
fi

# ---------------------------------------------------------------------
# 4. Disable llvmpipe ICD so user-installed Turnip wins by default
#    (Turnip is installed separately via Settings tab, bind-mounted in)
# ---------------------------------------------------------------------
echo "[4/7] Disabling llvmpipe so user-installed Turnip wins…"
chroot_run bash -c 'if [ -f /usr/share/vulkan/icd.d/lvp_icd.json ]; then mv /usr/share/vulkan/icd.d/lvp_icd.json /usr/share/vulkan/icd.d/lvp_icd.json.disabled && echo "  llvmpipe disabled"; else echo "  llvmpipe not present"; fi' 2>&1 | tail -2

# ---------------------------------------------------------------------
# 5. Create user + directories for bind-mounting Proton/Turnip/DXVK
# ---------------------------------------------------------------------
echo "[5/7] Creating user + bind-mount directories…"
chroot_run bash -c 'if ! id xuser >/dev/null 2>&1; then useradd -m -s /bin/bash xuser; fi; mkdir -p /home/xuser /opt/proton /opt/dxvk /opt/turnip /opt/fex /usr/local/etc/vulkan/icd.d; chown xuser:xuser /home/xuser 2>/dev/null; echo "  ✓ user + dirs created"' 2>&1 | tail -2

# ---------------------------------------------------------------------
# 6. Install gamescope (optional)
# ---------------------------------------------------------------------
echo "[6/7] Installing gamescope (optional)…"
chroot_run bash -c 'apt-get install -y --no-install-recommends gamescope 2>&1 | tail -2 || echo "  gamescope not in repos"' 2>&1 | tail -3 || true

# ---------------------------------------------------------------------
# 6.5. Copy glibc dynamic linker to jniLibs for native execution
# ---------------------------------------------------------------------
# Android SELinux blocks execve() from app data directories but ALLOWS it
# from nativeLibraryDir. We bundle the glibc linker as libld_glibc.so so
# Android extracts it to nativeLibraryDir at install time. WineRunner then
# launches it directly: nativeLibraryDir/libld_glibc.so --library-path ... wine
# This gives us native-speed glibc execution without proot or root.
echo "[6.5/7] Copying glibc linker to jniLibs…"
JNI_DIR="$SCRIPT_DIR/../app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI_DIR"
LINKER_SRC=""
for candidate in \
    "$ROOTFS_DIR/usr/lib/ld-linux-aarch64.so.1" \
    "$ROOTFS_DIR/lib/ld-linux-aarch64.so.1" \
    "$ROOTFS_DIR/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" \
    "$ROOTFS_DIR/lib64/ld-linux-aarch64.so.1"; do
    if [ -f "$candidate" ]; then
        LINKER_SRC="$candidate"
        break
    fi
done
if [ -n "$LINKER_SRC" ]; then
    cp -L "$LINKER_SRC" "$JNI_DIR/libld_glibc.so"
    chmod 755 "$JNI_DIR/libld_glibc.so"
    echo "  ✓ glibc linker → $JNI_DIR/libld_glibc.so"
    echo "  Source: $LINKER_SRC"
    file "$JNI_DIR/libld_glibc.so"
else
    echo "  ⚠ WARNING: glibc linker not found — native launch will fall back to proot"
fi

# ---------------------------------------------------------------------
# 7. Create the tarball
# ---------------------------------------------------------------------
echo "[7/7] Creating imagefs.tar.zst…"

# Purge dev packages — only needed for bridge compilation, bloat rootfs 3x
# Ubuntu 20.04 Focal ships gcc-9 (not gcc-14 like Trixie)
# Also purge wayland build deps (meson, ninja, libffi-dev, etc.) — only needed
# to build libwayland 1.22 from source, not at runtime.
# NOTE: Do NOT purge libwayland-dev/libwayland-bin — we built libwayland 1.22
# from source over the Focal packages. Purging the packages could remove the
# runtime library (libwayland-server0) that the bridge links against.
echo "  Purging dev packages…"
chroot_run apt-get purge -y --auto-remove \
    gcc gcc-9 libgcc-9-dev cpp cpp-9 \
    libc6-dev linux-libc-dev \
    libx11-dev libxtst-dev \
    meson ninja-build libffi-dev libexpat1-dev libxml2-dev \
    python3-pip \
    pkg-config binutils 2>&1 | tail -3 || echo "  (some purge failures OK)"
# Mark libwayland-server0 as manually installed so it survives auto-remove
chroot_run apt-mark manual libwayland-server0 libwayland-client0 2>/dev/null || true

# Recreate unversioned libdl.so symlink (removed when libc6-dev is purged).
# Some binaries reference libdl.so (unversioned) at link time. glibc 2.31
# has a real libdl.so.2, but the unversioned symlink is provided by libc6-dev.
# Create it manually so the diagnostic doesn't report it as missing.
chroot_run bash -c '
    LIBDIR=/usr/lib/aarch64-linux-gnu
    if [ ! -e "$LIBDIR/libdl.so" ] && [ -e "$LIBDIR/libdl.so.2" ]; then
        ln -s libdl.so.2 "$LIBDIR/libdl.so"
        echo "  ✓ Recreated libdl.so → libdl.so.2 symlink"
    fi
' 2>&1 | tail -2

# Clean up apt cache + temp + docs
rm -rf "$ROOTFS_DIR/var/cache/apt/archives/"*.deb 2>/dev/null || true
rm -rf "$ROOTFS_DIR/var/lib/apt/lists/"* 2>/dev/null || true
rm -rf "$ROOTFS_DIR/tmp/"* 2>/dev/null || true
rm -rf "$ROOTFS_DIR/usr/share/doc" 2>/dev/null || true
rm -rf "$ROOTFS_DIR/usr/share/man" 2>/dev/null || true
rm -rf "$ROOTFS_DIR/usr/share/locale" 2>/dev/null || true
rm -rf "$ROOTFS_DIR/usr/share/info" 2>/dev/null || true

# Aggressive size reduction — remove build-time-only artifacts left over
# from the libwayland 1.22 + wayland-protocols 1.27 source builds. These
# are not needed at runtime and bloat the tarball significantly.
echo "  Aggressive rootfs trimming…"
# Remove Python packages (leftover from meson install — meson was purged
# above but its site-packages remain). ~20-40 MB on Focal.
rm -rf "$ROOTFS_DIR/usr/local/lib/python3.8" 2>/dev/null || true
rm -rf "$ROOTFS_DIR/usr/local/lib/python3."* 2>/dev/null || true
# Remove static libraries (not needed at runtime — only for linking)
find "$ROOTFS_DIR/usr/lib" -name "*.a" -delete 2>/dev/null || true
find "$ROOTFS_DIR/usr/local/lib" -name "*.a" -delete 2>/dev/null || true
# Remove libtool .la files (only needed at link time)
find "$ROOTFS_DIR/usr/lib" -name "*.la" -delete 2>/dev/null || true
find "$ROOTFS_DIR/usr/local/lib" -name "*.la" -delete 2>/dev/null || true
# Remove wayland-protocols XML (only needed at build time for code generation;
# the bridge already compiled the protocol C sources into the binary)
rm -rf "$ROOTFS_DIR/usr/share/wayland-protocols" 2>/dev/null || true
# Remove pkgconfig files (only needed at build time)
find "$ROOTFS_DIR/usr/lib" -name "*.pc" -delete 2>/dev/null || true
find "$ROOTFS_DIR/usr/local/lib" -name "*.pc" -delete 2>/dev/null || true
find "$ROOTFS_DIR/usr/share/pkgconfig" -name "*.pc" -delete 2>/dev/null || true
# Remove wayland-scanner binary (not needed at runtime — only for codegen)
rm -f "$ROOTFS_DIR/usr/bin/wayland-scanner" 2>/dev/null || true
# Strip debug symbols from shared libraries (saves ~30-50% on .so size).
# --strip-unneeded removes debug symbols but keeps dynamic symbols.
find "$ROOTFS_DIR/usr/lib" -name "*.so*" -exec strip --strip-unneeded {} \; 2>/dev/null || true
find "$ROOTFS_DIR/usr/local/lib" -name "*.so*" -exec strip --strip-unneeded {} \; 2>/dev/null || true
# Strip the bridge binary too
strip --strip-unneeded "$ROOTFS_DIR/usr/local/bin/waylandie-wayland-bridge" 2>/dev/null || true
echo "  ✓ Aggressive trimming complete"

echo "  ✓ Cleanup complete"

cd "$ROOTFS_DIR"
# Use zstd compression. ZSTD decompresses 3-5x faster than XZ, reducing
# first-launch extraction time from ~5 minutes to ~1 minute.
# The app's TarCompressorUtils.java already supports ZSTD via zstd-jni.
tar --xattrs -cf - . | zstd -19 -T0 -o "$OUTPUT_FILE"

SIZE=$(stat -c%s "$OUTPUT_FILE")
echo
echo "=== Done ==="
echo "  Output: $OUTPUT_FILE"
echo "  Size:   $SIZE bytes ($(numfmt --to=iec $SIZE))"
echo
echo "  NOTE: This rootfs does NOT contain Wine. Users install Proton"
echo "  via the Settings tab. Proton is bind-mounted into /opt/proton"
echo "  at runtime by ProotRunner."
