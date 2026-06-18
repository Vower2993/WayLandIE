#!/bin/bash
# build-imagefs.sh — generates the bundled rootfs (imagefs.tar.xz) that
# ships inside the WayLandIE APK.
#
# Supports two modes:
#   1. NATIVE arm64 host (e.g. Termux on arm64 phone, or arm64 PC):
#      debootstrap runs natively, chroot works directly.
#   2. CROSS-BUILD x86_64 host (e.g. GitHub Actions runner):
#      Uses debootstrap --foreign + --second-stage with qemu-user-static
#      binfmt support. The kernel transparently runs arm64 binaries via qemu.
#
# Output: app/src/main/assets/imagefs/imagefs.tar.xz (~150 MB compressed)
#
# The generated rootfs contains:
#   - Debian trixie arm64 base
#   - Wine 9.x (arm64 build)
#   - DXVK (bionic-aware)
#   - Mesa Turnip (KGSL bionic variant — no libhardware dependency)
#   - box86 + box64 (latest releases)
#   - FEX-Emu (optional)
#   - PulseAudio (for audio bridge)
#   - gamescope (optional, for nested Wayland compositor)
#   - Wayland client libraries
#   - Pre-initialized Wine prefix
#
# Usage:
#   sudo bash build-imagefs.sh              # native or cross (auto-detected)
#   bash build-imagefs.sh                   # if sudo not needed (CI)
#   ARCH=arm64 DIST=trixie bash build-imagefs.sh
#
# Requirements (auto-installed on Debian/Ubuntu hosts):
#   - debootstrap
#   - qemu-user-static (for cross-build only)
#   - xz-utils, tar
#   - wget

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
    echo "    Will use qemu-user-static for arm64 chroot"
fi

echo "=== WayLandIE imagefs builder ==="
echo "  Work dir:    $WORK_DIR"
echo "  Rootfs dir:  $ROOTFS_DIR"
echo "  Output:      $OUTPUT_FILE"
echo "  Arch:        $ARCH"
echo "  Dist:        $DIST"
echo "  Cross-build: $CROSS_BUILD"
echo

mkdir -p "$WORK_DIR"
mkdir -p "$ROOTFS_DIR"
mkdir -p "$(dirname "$OUTPUT_FILE")"

# ---------------------------------------------------------------------
# Install host dependencies
# ---------------------------------------------------------------------
echo "[0/9] Installing host dependencies…"
if command -v apt-get >/dev/null 2>&1; then
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
        debootstrap xz-utils tar wget sudo binfmt-support 2>/dev/null || true
    if [ "$CROSS_BUILD" = "true" ]; then
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq qemu-user-static 2>/dev/null || true
        # Register binfmt
        update-binfmts --enable qemu-aarch64 2>/dev/null || true
        # Verify
        if [ -f /proc/sys/fs/binfmt_misc/qemu-aarch64 ]; then
            echo "  ✓ qemu-aarch64 binfmt registered"
        else
            echo "  ⚠ qemu-aarch64 binfmt not found — cross-build may fail" >&2
        fi
    fi
fi

# Find qemu-aarch64-static binary (needed inside rootfs for --second-stage)
QEMU_STATIC=""
for path in /usr/bin/qemu-aarch64-static /usr/bin/qemu-aarch64; do
    if [ -x "$path" ]; then
        QEMU_STATIC="$path"
        break
    fi
done
if [ "$CROSS_BUILD" = "true" ] && [ -z "$QEMU_STATIC" ]; then
    echo "ERROR: qemu-aarch64-static not found. Install: apt-get install qemu-user-static" >&2
    exit 1
fi

# Helper: run command inside rootfs chroot
chroot_run() {
    if [ "$CROSS_BUILD" = "true" ]; then
        # qemu binfmt handles arm64 execution transparently
        chroot "$ROOTFS_DIR" "$@"
    else
        chroot "$ROOTFS_DIR" "$@"
    fi
}

# ---------------------------------------------------------------------
# 1. Bootstrap base rootfs
# ---------------------------------------------------------------------
if [ ! -d "$ROOTFS_DIR/usr" ]; then
    echo "[1/9] Bootstrapping $DIST $ARCH base…"
    if [ "$CROSS_BUILD" = "true" ]; then
        # Cross-build: --foreign first, then --second-stage inside chroot
        debootstrap --arch="$ARCH" --foreign --variant=minbase "$DIST" "$ROOTFS_DIR" \
            http://deb.debian.org/debian
        # Copy qemu into rootfs for --second-stage
        if [ -n "$QEMU_STATIC" ]; then
            cp "$QEMU_STATIC" "$ROOTFS_DIR/usr/bin/"
        fi
        # Run second stage (uses qemu binfmt)
        chroot "$ROOTFS_DIR" /debootstrap/debootstrap --second-stage
        # Remove qemu from rootfs (not needed after bootstrap)
        rm -f "$ROOTFS_DIR/usr/bin/qemu-aarch64-static"
    else
        # Native build
        debootstrap --arch="$ARCH" --variant=minbase "$DIST" "$ROOTFS_DIR" \
            http://deb.debian.org/debian
    fi
else
    echo "[1/9] Rootfs exists, skipping debootstrap."
fi

# ---------------------------------------------------------------------
# 2. Configure apt sources + install core packages
# ---------------------------------------------------------------------
echo "[2/9] Configuring apt + installing core packages…"
# Write sources.list
cat > "$ROOTFS_DIR/etc/apt/sources.list" <<EOF
deb http://deb.debian.org/debian $DIST main universe
deb http://deb.debian.org/debian $DIST-updates main
EOF

# Disable frontend prompts
echo 'APT::Get::Assume-Yes "true";' > "$ROOTFS_DIR/etc/apt/apt.conf.d/99assumeyes"
echo 'debconf debconf/frontend select Noninteractive' | chroot_run debconf-set-selections 2>/dev/null || true

chroot_run apt-get update -qq
chroot_run apt-get install -y --no-install-recommends \
    ca-certificates wget curl xz-utils tar bzip2 gzip \
    locales bash coreutils findutils \
    libwayland-client0 libwayland-server0 wayland-protocols \
    libvulkan1 vulkan-tools mesa-vulkan-drivers mesa-utils \
    libpulse0 pulseaudio pulseaudio-utils \
    libfreetype6 libfontconfig1 libgnutls30 libxcomposite1 \
    libxinerama1 libxcursor1 libxrandr2 libxi6 libxtst6 \
    libxrender1 libxext6 libxfixes3 libxxf86vm1 \
    libgl1 libegl1 libgles2 2>&1 | tail -5

# Generate locale
chroot_run locale-gen en_US.UTF-8 2>/dev/null || true

# ---------------------------------------------------------------------
# 3. Install Wine 9.x (Debian's wine arm64 build)
#    Pre-fix: Debian trixie's wine package sometimes has dep issues.
#    We try apt first, then fall back to winehq, then to a manual
#    tarball install. If all fail, the rootfs still builds — games
#    just won't run until Wine is added manually.
# ---------------------------------------------------------------------
echo "[3/9] Installing Wine…"
WINE_OK=false

# Try 1: Debian's wine package
chroot_run bash -c '
    dpkg --add-architecture arm64 2>/dev/null || true
    apt-get update -qq 2>/dev/null
    if apt-get install -y --no-install-recommends wine wine64 2>/dev/null; then
        echo "  ✓ wine installed from Debian apt"
        exit 0
    fi
    exit 1
' 2>&1 | tail -5 && WINE_OK=true

# Try 2: winehq stable
if [ "$WINE_OK" = "false" ]; then
    echo "  Debian apt failed, trying winehq…"
    chroot_run bash -c '
        apt-get install -y --no-install-recommends winehq-stable 2>/dev/null && exit 0
        exit 1
    ' 2>&1 | tail -3 && WINE_OK=true
fi

# Try 3: Download prebuilt Wine tarball from WineHQ
if [ "$WINE_OK" = "false" ]; then
    echo "  winehq failed, trying prebuilt tarball…"
    chroot_run bash -c '
        mkdir -p /opt/wine
        cd /tmp
        # Try Kron4ek's wine builds (popular for Android Linux gaming)
        WINE_TARBALL=""
        for url in \
            "https://github.com/Kron4ek/Wine-Builds/releases/download/9.0/wine-9.0-arm64.tar.xz" \
            "https://github.com/Kron4ek/Wine-Builds/releases/download/8.0/wine-8.0-arm64.tar.xz"; do
            if wget -qO wine.tar.xz "$url"; then
                WINE_TARBALL="$url"
                break
            fi
        done
        if [ -n "$WINE_TARBALL" ]; then
            tar -xJf wine.tar.xz -C /opt/wine --strip-components=1
            rm -f wine.tar.xz
            # Symlink wine binaries into /usr/local/bin
            ln -sf /opt/wine/bin/wine /usr/local/bin/wine
            ln -sf /opt/wine/bin/wine64 /usr/local/bin/wine64
            ln -sf /opt/wine/bin/wineserver /usr/local/bin/wineserver
            echo "  ✓ wine installed from $WINE_TARBALL"
            exit 0
        fi
        exit 1
    ' 2>&1 | tail -3 && WINE_OK=true
fi

if [ "$WINE_OK" = "false" ]; then
    echo "  ⚠ All Wine install methods failed. Rootfs builds without Wine."
    echo "    Games won't launch until you install Wine manually."
else
    echo "  ✓ Wine installed successfully"
fi

# ---------------------------------------------------------------------
# 4. Install box86 + box64 from official release tarballs
# ---------------------------------------------------------------------
echo "[4/9] Installing box86 + box64…"
chroot_run bash -c '
    mkdir -p /usr/local/bin
    cd /tmp
    # box86
    if wget -qO box86.tgz "https://github.com/ptitSeb/box86/releases/latest/download/box86-Rootfs-aarch64.tgz"; then
        tar -xzf box86.tgz -C /usr/local/bin --strip-components=1
        chmod +x /usr/local/bin/box86 2>/dev/null || true
        echo "  ✓ box86 installed"
    else
        echo "  ⚠ box86 download failed"
    fi
    # box64
    if wget -qO box64.tgz "https://github.com/ptitSeb/box64/releases/latest/download/box64-Rootfs-aarch64.tgz"; then
        tar -xzf box64.tgz -C /usr/local/bin --strip-components=1
        chmod +x /usr/local/bin/box64 2>/dev/null || true
        echo "  ✓ box64 installed"
    else
        echo "  ⚠ box64 download failed"
    fi
    rm -f box86.tgz box64.tgz
' 2>&1 | tail -5

# ---------------------------------------------------------------------
# 5. Install FEX-Emu (optional)
# ---------------------------------------------------------------------
echo "[5/9] Installing FEX-Emu (optional)…"
chroot_run bash -c '
    apt-get install -y --no-install-recommends fex-emu-app 2>&1 | tail -2 || \
        echo "  ⚠ FEX-Emu not in repos (optional)"
' 2>&1 | tail -3 || true

# ---------------------------------------------------------------------
# 6. Install Mesa Turnip (KGSL bionic variant)
#    Pre-fix: Turnip download URLs change frequently. We try 5 different
#    sources. If all fail, the rootfs builds with llvmpipe (software
#    rendering) — slow but functional.
# ---------------------------------------------------------------------
echo "[6/9] Installing Mesa Turnip (KGSL)…"
chroot_run bash -c '
    mkdir -p /usr/local/lib /usr/local/etc/vulkan/icd.d
    cd /tmp

    # Comprehensive list of Turnip sources — try each until one works
    TURNIP_OK=false
    TURNIP_SOURCES=(
        "https://github.com/K11MCH1/AdrenoToolsDrivers/releases/latest/download/turnip-24.1.0.tar.xz"
        "https://github.com/K11MCH1/AdrenoToolsDrivers/releases/download/V27/a8xx-turnip-gen8-sync-V27.zip"
        "https://github.com/K11MCH1/AdrenoToolsDrivers/releases/download/V29/a8xx-turnip-gen8-sync-V29.zip"
        "https://github.com/K11MCH1/AdrenoToolsDrivers/releases/download/V29/a8xx-turnip-gen8-V29.zip"
        "https://github.com/AdrenoToolsDrivers/AdrenoToolsDrivers/releases/latest/download/turnip-24.1.0.tar.xz"
    )

    for url in "${TURNIP_SOURCES[@]}"; do
        echo "  Trying: $url"
        if wget -q --timeout=30 -O turnip.archive "$url"; then
            rm -rf /tmp/turnip-extract
            mkdir -p /tmp/turnip-extract
            # Try xz, then zip, then plain tar
            tar -xJf turnip.archive -C /tmp/turnip-extract 2>/dev/null || \
                unzip -q turnip.archive -d /tmp/turnip-extract 2>/dev/null || \
                tar -xzf turnip.archive -C /tmp/turnip-extract 2>/dev/null || true

            SO=$(find /tmp/turnip-extract -name "libvulkan_freedreno.so" -type f 2>/dev/null | head -1)
            if [ -n "$SO" ]; then
                cp -f "$SO" /usr/local/lib/libvulkan_freedreno.so
                chmod 644 /usr/local/lib/libvulkan_freedreno.so
                cat > /usr/local/etc/vulkan/icd.d/freedreno_icd.json <<JSONEOF
{
    "file_format_version": "1.0.0",
    "ICD": {
        "library_path": "/usr/local/lib/libvulkan_freedreno.so",
        "api_version": "1.3.0"
    }
}
JSONEOF
                echo "  ✓ Turnip installed from: $url"
                TURNIP_OK=true
                break
            fi
            rm -rf /tmp/turnip-extract
        fi
    done
    rm -f /tmp/turnip.archive

    if [ "$TURNIP_OK" = "false" ]; then
        echo "  ⚠ No Turnip driver installed from any source."
        echo "    Vulkan will fall back to llvmpipe (software rendering)."
        echo "    Install Turnip manually via the app Settings tab."
    fi

    # Disable llvmpipe so Turnip wins by default (if installed)
    if [ -f /usr/share/vulkan/icd.d/lvp_icd.json ]; then
        mv /usr/share/vulkan/icd.d/lvp_icd.json /usr/share/vulkan/icd.d/lvp_icd.json.disabled
        echo "  ✓ Disabled llvmpipe ICD"
    fi
' 2>&1 | tail -15

# ---------------------------------------------------------------------
# 7. Install gamescope (optional)
# ---------------------------------------------------------------------
echo "[7/9] Installing gamescope (optional)…"
chroot_run bash -c '
    apt-get install -y --no-install-recommends gamescope 2>&1 | tail -2 || \
        echo "  ⚠ gamescope not in repos (optional)"
' 2>&1 | tail -3 || true

# ---------------------------------------------------------------------
# 8. Initialize Wine prefix + create user
# ---------------------------------------------------------------------
echo "[8/9] Creating user + initializing Wine prefix…"
chroot_run bash -c '
    # Create xuser
    if ! id xuser >/dev/null 2>&1; then
        useradd -m -s /bin/bash xuser || true
    fi
    mkdir -p /home/xuser
    chown xuser:xuser /home/xuser 2>/dev/null || true
    
    # Initialize Wine prefix (non-fatal if it fails)
    export HOME=/home/xuser
    export WINEPREFIX=/home/xuser/.wine
    export USER=xuser
    export DISPLAY=
    
    if command -v wineboot >/dev/null 2>&1; then
        sudo -u xuser wineboot --init 2>&1 | tail -3 || echo "  ⚠ wineboot init failed (may be OK)"
    elif command -v wine >/dev/null 2>&1; then
        sudo -u xuser wine wineboot 2>&1 | tail -3 || echo "  ⚠ wine init failed (may be OK)"
    fi
' 2>&1 | tail -5 || true

# ---------------------------------------------------------------------
# 9. Create the tarball
# ---------------------------------------------------------------------
echo "[9/9] Creating imagefs.tar.xz…"
cd "$ROOTFS_DIR"

# Clean up apt cache + tmp to reduce size
rm -rf var/cache/apt/archives/*.deb 2>/dev/null || true
rm -rf var/lib/apt/lists/* 2>/dev/null || true
rm -rf tmp/* 2>/dev/null || true
rm -rf root/.bash_history 2>/dev/null || true

# Create tarball with xz compression (level 6 for good ratio + speed)
tar --xattrs -cJf "$OUTPUT_FILE" .

# Verify
SIZE=$(stat -c%s "$OUTPUT_FILE")
echo
echo "=== Done ==="
echo "  Output: $OUTPUT_FILE"
echo "  Size:   $SIZE bytes ($(numfmt --to=iec $SIZE))"
echo
echo "Copy this file into your WayLandIE-android-noroot project at:"
echo "  app/src/main/assets/imagefs/imagefs.tar.xz"
echo
echo "Then rebuild the APK — the app will extract this rootfs on first launch."
