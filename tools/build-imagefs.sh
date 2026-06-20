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
#   - Debian trixie arm64 base (minimal)
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

WORK_DIR="${WORK_DIR:-/tmp/waylandie-imagefs-build}"
ROOTFS_DIR="$WORK_DIR/rootfs"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_FILE="${OUTPUT_FILE:-$SCRIPT_DIR/../app/src/main/assets/imagefs/imagefs.tar.xz}"
ARCH="${ARCH:-arm64}"
DIST="${DIST:-trixie}"

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
        debootstrap --arch="$ARCH" --foreign --variant=minbase "$DIST" "$ROOTFS_DIR" \
            http://deb.debian.org/debian
        [ -n "$QEMU_STATIC" ] && cp "$QEMU_STATIC" "$ROOTFS_DIR/usr/bin/"
        chroot "$ROOTFS_DIR" /debootstrap/debootstrap --second-stage
        rm -f "$ROOTFS_DIR/usr/bin/qemu-aarch64-static"
    else
        debootstrap --arch="$ARCH" --variant=minbase "$DIST" "$ROOTFS_DIR" \
            http://deb.debian.org/debian
    fi
else
    echo "[1/7] Rootfs exists, skipping debootstrap."
fi

# ---------------------------------------------------------------------
# 2. Configure apt + install core runtime libraries
#    NO WINE — Wine/Proton is installed separately via Settings tab
# ---------------------------------------------------------------------
echo "[2/7] Configuring apt + installing core libraries (NO WINE)…"
cat > "$ROOTFS_DIR/etc/apt/sources.list" <<EOF
deb http://deb.debian.org/debian $DIST main
deb http://deb.debian.org/debian $DIST-updates main
EOF

echo 'APT::Get::Assume-Yes "true";' > "$ROOTFS_DIR/etc/apt/apt.conf.d/99assumeyes"
echo 'debconf debconf/frontend select Noninteractive' | chroot_run debconf-set-selections 2>/dev/null || true

chroot_run apt-get update -qq

# Group 1: Essential tools — must succeed first so later steps can use them
echo "  Installing essential tools…"
chroot_run apt-get install -y --no-install-recommends \
    ca-certificates wget curl xz-utils tar bzip2 gzip unzip binutils \
    locales bash coreutils findutils gcc pkg-config 2>&1 | tail -3

# Group 2: Wayland + Vulkan + display libraries
# NOTE: 'wayland-scanner' is not a package in Debian trixie — the binary
# is provided by 'libwayland-bin'. Using the wrong name causes the entire
# apt-get to abort, which is why we split into separate groups.
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
    wget -qO box64.tzst "https://github.com/StevenMXZ/Winlator-Ludashi/raw/main/app/src/main/assets/box64/box64-0.4.1.tzst" && \
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
    chroot_run bash -c '
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
        echo "  protocol headers generated"
    ' 2>&1 | tail -2

    # Compile the bridge
    chroot_run bash -c '
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
        echo "  bridge compiled → /usr/local/bin/waylandie-wayland-bridge"
    ' 2>&1 | tail -5

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
echo "[7/7] Creating imagefs.tar.xz…"
# Clean up to reduce size
rm -rf "$ROOTFS_DIR/var/cache/apt/archives/"*.deb 2>/dev/null || true
rm -rf "$ROOTFS_DIR/var/lib/apt/lists/"* 2>/dev/null || true
rm -rf "$ROOTFS_DIR/tmp/"* 2>/dev/null || true

cd "$ROOTFS_DIR"
tar --xattrs -cJf "$OUTPUT_FILE" .

SIZE=$(stat -c%s "$OUTPUT_FILE")
echo
echo "=== Done ==="
echo "  Output: $OUTPUT_FILE"
echo "  Size:   $SIZE bytes ($(numfmt --to=iec $SIZE))"
echo
echo "  NOTE: This rootfs does NOT contain Wine. Users install Proton"
echo "  via the Settings tab. Proton is bind-mounted into /opt/proton"
echo "  at runtime by ProotRunner."
