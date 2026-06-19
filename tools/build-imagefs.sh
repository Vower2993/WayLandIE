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
chroot_run apt-get install -y --no-install-recommends \
    ca-certificates wget curl xz-utils tar bzip2 gzip unzip binutils \
    locales bash coreutils findutils \
    libwayland-client0 libwayland-server0 wayland-protocols \
    libvulkan1 vulkan-tools mesa-vulkan-drivers mesa-utils \
    libpulse0 pulseaudio pulseaudio-utils \
    libfreetype6 libfontconfig1 libgnutls30 libxcomposite1 \
    libxinerama1 libxcursor1 libxrandr2 libxi6 libxtst6 \
    libxrender1 libxext6 libxfixes3 libxxf86vm1 \
    libgl1 libegl1 libgles2 \
    libgstreamer1.0-0 libgstreamer-plugins-base1.0-0 \
    desktop-file-utils 2>&1 | tail -5

chroot_run locale-gen en_US.UTF-8 2>/dev/null || true

# ---------------------------------------------------------------------
# 3. Install box86 + box64 (x86/x64 emulators — needed for Proton)
# ---------------------------------------------------------------------
echo "[3/7] Installing box86 + box64…"
chroot_run bash -c 'mkdir -p /usr/local/bin && cd /tmp && wget -qO box86.tgz "https://github.com/ptitSeb/box86/releases/latest/download/box86-Rootfs-aarch64.tgz" && tar -xzf box86.tgz -C /usr/local/bin --strip-components=1 && chmod +x /usr/local/bin/box86 2>/dev/null && echo "  box86 installed" || echo "  box86 skipped"; wget -qO box64.tgz "https://github.com/ptitSeb/box64/releases/latest/download/box64-Rootfs-aarch64.tgz" && tar -xzf box64.tgz -C /usr/local/bin --strip-components=1 && chmod +x /usr/local/bin/box64 2>/dev/null && echo "  box64 installed" || echo "  box64 skipped"; rm -f box86.tgz box64.tgz' 2>&1 | tail -5

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
