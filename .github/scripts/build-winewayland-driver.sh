#!/usr/bin/env bash
# Builds winewayland driver (PE32+ ARM64) + supporting bionic ELF libs
# for the WayLandIE Android app. Output: $WORKSPACE/app/src/main/assets/winewayland-driver.zip
set -euo pipefail

WORKSPACE="${GITHUB_WORKSPACE:-$(pwd)}"
OUTDIR="/tmp/winewayland-build"
PROTON_OUT="$OUTDIR/proton"
ROOTFS_OUT="$OUTDIR/rootfs"

rm -rf "$OUTDIR"
mkdir -p "$PROTON_OUT/lib/wine/aarch64-windows" \
         "$PROTON_OUT/lib/wine/aarch64-unix" \
         "$ROOTFS_OUT/usr/local/lib"

echo "=== [1/7] Install build deps ==="
sudo apt-get update -qq
sudo apt-get install -y -qq \
  build-essential clang lld meson ninja-build pkg-config \
  flex bison gettext python3 python3-pip \
  libwayland-dev libxkbcommon-dev \
  mingw-w64

# Android NDK
NDK_VERSION="r26d"
NDK_ZIP="/tmp/ndk.zip"
if [ ! -d "$HOME/android-ndk-$NDK_VERSION" ]; then
  echo "Downloading NDK $NDK_VERSION..."
  wget -q "https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux.zip" -O "$NDK_ZIP"
  unzip -q "$NDK_ZIP" -d "$HOME"
fi
export NDK="$HOME/android-ndk-$NDK_VERSION"
export TOOLCHAIN_PREFIX="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
export API=28
export CC_BIONIC="$TOOLCHAIN_PREFIX/bin/aarch64-linux-android${API}-clang"
export CXX_BIONIC="$TOOLCHAIN_PREFIX/bin/aarch64-linux-android${API}-clang++"
export AR_BIONIC="$TOOLCHAIN_PREFIX/bin/llvm-ar"
export SYSROOT="$TOOLCHAIN_PREFIX/sysroot"

# PE toolchain for winewayland.drv (PE32+ ARM64)
export PE_CC="$TOOLCHAIN_PREFIX/bin/clang"
export PE_CXX="$TOOLCHAIN_PREFIX/bin/clang++"

echo "=== [2/7] Clone proton-wine ==="
cd /tmp
rm -rf proton-wine
git clone --depth=1 https://github.com/GameNative/proton-wine.git
cd proton-wine

echo "=== [3/7] Apply android_sysvshm patch ==="
# This patch is shipped in proton-wine repo under patches/
if [ -f patches/android_sysvshm.patch ]; then
  git apply patches/android_sysvshm.patch || git apply --3way patches/android_sysvshm.patch
else
  echo "WARNING: android_sysvshm.patch not found in repo, skipping"
fi

# ELF byte-order fix (little-endian read helper) — already merged in GameNative tree
# as of latest, but apply defensively
if [ -f patches/elf-le-read-fix.patch ]; then
  git apply patches/elf-le-read-fix.patch || true
fi

echo "=== [4/7] Build bionic ELF libs (wayland-client, xkbcommon, xkbregistry, sysvshm) ==="
mkdir -p /tmp/elf-build && cd /tmp/elf-build

# libwayland-client (bionic)
if [ ! -d wayland ]; then
  git clone --depth=1 https://gitlab.freedesktop.org/wayland/wayland.git
fi
mkdir -p wayland/build && cd wayland/build
meson setup .. \
  --prefix=/usr/local \
  --cross-file=/dev/stdin <<'EOF' >/dev/null
[binaries]
c = '/PATH/toolchain/bin/aarch64-linux-android28-clang'
ar = '/PATH/toolchain/bin/llvm-ar'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF
ninja
DESTDIR="$ROOTFS_OUT" ninja install
cd /tmp/elf-build

# libxkbcommon + libxkbregistry (bionic)
git clone --depth=1 https://github.com/xkbcommon/libxkbcommon.git
mkdir -p libxkbcommon/build && cd libxkbcommon/build
meson setup .. --prefix=/usr/local -Denable-wayland=false -Denable-docs=false
ninja
DESTDIR="$ROOTFS_OUT" ninja install
cd /tmp/elf-build

echo "=== [5/7] Build winewayland.drv + winewayland.so ==="
cd /tmp/proton-wine

# Wine configure with bionic toolchain + Wayland support
export CC="$CC_BIONIC"
export CXX="$CXX_BIONIC"
export CFLAGS="-fPIC --sysroot=$SYSROOT -I$SYSROOT/usr/include"
export CXXFLAGS="$CFLAGS"
export LDFLAGS="--sysroot=$SYSROOT"

./configure \
  --host=aarch64-linux-android \
  --prefix=/usr/local \
  --enable-winewayland \
  --with-wayland \
  --with-xkbcommon \
  --without-x \
  --without-opengl \
  --without-vulkan \
  --disable-tests \
  --disable-docs \
  2>&1 | tee /tmp/wine-configure.log

# Build only the winewayland targets
make -j$(nproc) dlls/winewayland.drv
make -j$(nproc) dlls/winewayland.drv.so

echo "=== [6/7] Collect artifacts ==="
# PE32+ ARM64 driver
cp build-aarch64-linux-android/dlls/winewayland.drv/winewayland.drv.so \
   "$PROTON_OUT/lib/wine/aarch64-windows/winewayland.drv" 2>/dev/null || \
cp dlls/winewayland.drv/winewayland.drv.so \
   "$PROTON_OUT/lib/wine/aarch64-windows/winewayland.drv"

# ELF aarch64 helper
find . -name "winewayland.so" -type f -exec cp {} "$PROTON_OUT/lib/wine/aarch64-unix/" \;

# Bionic deps already installed into $ROOTFS_OUT by meson

echo "=== [7/7] Verify + zip ==="
echo "--- proton/ ---"
find "$PROTON_OUT" -type f -exec ls -la {} \;
echo "--- rootfs/ ---"
find "$ROOTFS_OUT" -name "*.so*" -type f -exec ls -la {} \;

# Sanity checks
DRV_SIZE=$(stat -c%s "$PROTON_OUT/lib/wine/aarch64-windows/winewayland.drv" 2>/dev/null || echo 0)
SO_SIZE=$(stat -c%s "$PROTON_OUT/lib/wine/aarch64-unix/winewayland.so" 2>/dev/null || echo 0)
if [ "$DRV_SIZE" -lt 10000 ] || [ "$SO_SIZE" -lt 10000 ]; then
  echo "✗ FATAL: driver artifacts missing or too small"
  echo "  winewayland.drv = $DRV_SIZE bytes (expect >10000)"
  echo "  winewayland.so = $SO_SIZE bytes (expect >100000)"
  exit 1
fi

cd "$OUTDIR"
rm -f "$WORKSPACE/app/src/main/assets/winewayland-driver.zip"
zip -r "$WORKSPACE/app/src/main/assets/winewayland-driver.zip" proton/ rootfs/
ls -la "$WORKSPACE/app/src/main/assets/winewayland-driver.zip"
echo "✓ winewayland-driver.zip built"

