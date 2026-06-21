#!/usr/bin/env bash
# Two-stage Wine cross-build:
#   Stage A: build Wine tools natively (x86_64)
#   Stage B: cross-compile winewayland.drv + winewayland.so for aarch64 Android
set -euo pipefail

WORKSPACE="${GITHUB_WORKSPACE:-$(pwd)}"
OUTDIR="/tmp/winewayland-build"
PROTON_OUT="$OUTDIR/proton"
ROOTFS_OUT="$OUTDIR/rootfs"

rm -rf "$OUTDIR"
mkdir -p "$PROTON_OUT/lib/wine/aarch64-windows" \
         "$PROTON_OUT/lib/wine/aarch64-unix" \
         "$ROOTFS_OUT/usr/local/lib"

echo "=== [1/8] Install build deps ==="
sudo apt-get install -y -qq \
  autoconf automake libtool bison flex gettext \
  pkg-config python3 python3-pip libffi-dev libexpat1-dev \
  libxml2-dev libxkbcommon-dev wayland-protocols
pip3 install --user meson ninja 2>&1 | tail -3
export PATH="$HOME/.local/bin:$PATH"

echo "=== [2/8] Locate NDK ==="
NDK_DIR="$ANDROID_HOME/ndk/26.1.10909125"
[ -d "$NDK_DIR" ] || NDK_DIR=$(ls -d $ANDROID_HOME/ndk/* 2>/dev/null | head -1)
echo "Using NDK: $NDK_DIR"
[ -d "$NDK_DIR" ] || { echo "FATAL: no NDK found"; exit 1; }

TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
API=28
export CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
export CXX="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export SYSROOT="$TOOLCHAIN/sysroot"

BIONIC_LIBS="$WORKSPACE/app/src/main/cpp/bionic-libs"
ls "$BIONIC_LIBS/lib/" 2>/dev/null | head

echo "=== [3/8] Clone proton-wine ==="
cd /tmp
rm -rf proton-wine
git clone --depth=1 https://github.com/GameNative/proton-wine.git
cd proton-wine
chmod +x autogen.sh
./autogen.sh 2>&1 | tail -5

echo "=== [4/8] Stage A: Native x86_64 tools build ==="
mkdir -p /tmp/proton-wine-tools-build
cd /tmp/proton-wine-tools-build
# Native build, minimal — just produces tools/winebuild, tools/wrc, tools/wmc, etc.
unset CC CXX AR STRIP LDFLAGS
export CFLAGS="-O2"
export CXXFLAGS="-O2"
/tmp/proton-wine/configure \
  --without-x \
  --without-opengl \
  --without-vulkan \
  --without-alsa \
  --without-oss \
  --without-pulse \
  --without-cups \
  --without-sane \
  --without-usb \
  --without-sdl \
  --without-gstreamer \
  --without-freetype \
  --without-fontconfig \
  --without-v4l2 \
  --disable-tests \
  2>&1 | tail -20

# Build only the tools directory (much faster than full Wine)
make -j$(nproc) tools 2>&1 | tail -10
echo "Native tools built:"
ls tools/winebuild tools/wrc tools/wmc 2>&1

echo "=== [5/8] Stage B: Cross-compile configure ==="
cd /tmp/proton-wine
export CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
export CXX="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export CFLAGS="-fPIC --sysroot=$SYSROOT -I$SYSROOT/usr/include -I$BIONIC_LIBS/include -D__ANDROID_API__=$API"
export CXXFLAGS="$CFLAGS"
export LDFLAGS="--sysroot=$SYSROOT -L$BIONIC_LIBS/lib"
export WAYLAND_CFLAGS="-I$BIONIC_LIBS/include"
export WAYLAND_LIBS="-L$BIONIC_LIBS/lib -lwayland-client"
export XKB_COMMON_CFLAGS="-I$BIONIC_LIBS/include"
export XKB_COMMON_LIBS="-L$BIONIC_LIBS/lib -lxkbcommon"

./configure \
  --host=aarch64-linux-android \
  --prefix=/usr/local \
  --with-wine-tools=/tmp/proton-wine-tools-build \
  --without-x \
  --without-opengl \
  --without-vulkan \
  --without-alsa \
  --without-oss \
  --without-pulse \
  --without-cups \
  --without-sane \
  --without-usb \
  --without-sdl \
  --without-gstreamer \
  --without-freetype \
  --without-fontconfig \
  --without-v4l2 \
  --disable-tests \
  2>&1 | tail -60

echo "=== [6/8] Build winewayland targets ==="
make -j$(nproc) dlls/winewayland.drv 2>&1 | tail -50

echo "=== Searching for built artifacts ==="
find /tmp/proton-wine -name "winewayland*" -type f -newer /tmp/proton-wine/configure 2>/dev/null | head -20

echo "=== [7/8] Collect ==="
for f in \
  "/tmp/proton-wine/dlls/winewayland.drv/winewayland.drv.so" \
  "/tmp/proton-wine/dlls/winewayland.drv/winewayland.drv" \
  "/tmp/proton-wine/build-aarch64-linux-android/dlls/winewayland.drv/winewayland.drv"; do
  if [ -f "$f" ]; then
    echo "Found PE: $f ($(stat -c%s "$f") bytes)"
    cp "$f" "$PROTON_OUT/lib/wine/aarch64-windows/winewayland.drv"
    break
  fi
done

for f in \
  "/tmp/proton-wine/dlls/winewayland.drv/winewayland.so" \
  "/tmp/proton-wine/dlls/winewayland.drv/winewayland.dll.so"; do
  if [ -f "$f" ]; then
    echo "Found ELF: $f ($(stat -c%s "$f") bytes)"
    cp "$f" "$PROTON_OUT/lib/wine/aarch64-unix/winewayland.so"
    break
  fi
done

DRV_SIZE=$(stat -c%s "$PROTON_OUT/lib/wine/aarch64-windows/winewayland.drv" 2>/dev/null || echo 0)
SO_SIZE=$(stat -c%s "$PROTON_OUT/lib/wine/aarch64-unix/winewayland.so" 2>/dev/null || echo 0)
echo "winewayland.drv: $DRV_SIZE bytes"
echo "winewayland.so: $SO_SIZE bytes"

if [ "$DRV_SIZE" -lt 5000 ]; then
  echo "✗ FATAL: winewayland.drv missing or too small"
  echo "=== dlls/winewayland.drv/ contents ==="
  ls -la /tmp/proton-wine/dlls/winewayland.drv/ 2>/dev/null || true
  echo "=== Config.log tail ==="
  tail -80 /tmp/proton-wine/config.log 2>/dev/null || true
  exit 1
fi

echo "=== [8/8] Zip ==="
cd "$OUTDIR"
mkdir -p "$WORKSPACE/app/src/main/assets"
rm -f "$WORKSPACE/app/src/main/assets/winewayland-driver.zip"
zip -r "$WORKSPACE/app/src/main/assets/winewayland-driver.zip" proton/ rootfs/
ls -la "$WORKSPACE/app/src/main/assets/winewayland-driver.zip"
echo "✓ winewayland-driver.zip built"
