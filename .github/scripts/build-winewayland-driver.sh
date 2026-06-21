#!/usr/bin/env bash
# Builds winewayland driver (PE32+ ARM64) + winewayland.so (ELF aarch64 bionic)
# Output: $GITHUB_WORKSPACE/app/src/main/assets/winewayland-driver.zip
set -euo pipefail

WORKSPACE="${GITHUB_WORKSPACE:-$(pwd)}"
OUTDIR="/tmp/winewayland-build"
PROTON_OUT="$OUTDIR/proton"
ROOTFS_OUT="$OUTDIR/rootfs"

rm -rf "$OUTDIR"
mkdir -p "$PROTON_OUT/lib/wine/aarch64-windows" \
         "$PROTON_OUT/lib/wine/aarch64-unix" \
         "$ROOTFS_OUT/usr/local/lib"

echo "=== [1/6] Locate NDK ==="
# Android SDK step already installed NDK 26.1.10909125
NDK_DIR="$ANDROID_HOME/ndk/26.1.10909125"
if [ ! -d "$NDK_DIR" ]; then
  echo "NDK not at $NDK_DIR — searching..."
  NDK_DIR=$(ls -d $ANDROID_HOME/ndk/* 2>/dev/null | head -1)
fi
echo "Using NDK: $NDK_DIR"
[ -d "$NDK_DIR" ] || { echo "FATAL: no NDK found"; exit 1; }

TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
API=28
export CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
export CXX="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export SYSROOT="$TOOLCHAIN/sysroot"
export CFLAGS="-fPIC --sysroot=$SYSROOT -I$SYSROOT/usr/include"
export CXXFLAGS="$CFLAGS"
export LDFLAGS="--sysroot=$SYSROOT"

# PE toolchain (for winewayland.drv) — use clang with mingw target
PE_CC="$TOOLCHAIN/bin/clang"
PE_CXX="$TOOLCHAIN/bin/clang++"

echo "=== [2/6] Clone proton-wine ==="
cd /tmp
rm -rf proton-wine
git clone --depth=1 https://github.com/GameNative/proton-wine.git
cd proton-wine

echo "=== [3/6] Locate bionic wayland/xkbcommon from bridge cache ==="
# The "Build bionic bridge deps" step produced static libs in:
#   app/src/main/cpp/bionic-libs/
BIONIC_LIBS="$WORKSPACE/app/src/main/cpp/bionic-libs"
if [ -d "$BIONIC_LIBS" ]; then
  echo "Found bionic-libs:"
  ls -la "$BIONIC_LIBS/lib/" || true
  ls "$BIONIC_LIBS/include/" 2>/dev/null | head || true
else
  echo "WARNING: bionic-libs not found at $BIONIC_LIBS"
  echo "Will attempt to build winewayland.drv without bionic wayland support"
fi

echo "=== [4/6] Configure proton-wine ==="
# Write a real cross-file (NOT /dev/stdin)
cat > /tmp/wine-android-cross.txt << EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkgconfig = '$TOOLCHAIN/bin/llvm-pkg-config'

[built-in options]
c_args = ['-fPIC', '--sysroot=$SYSROOT', '-I$SYSROOT/usr/include', '-I$BIONIC_LIBS/include']
cpp_args = ['-fPIC', '--sysroot=$SYSROOT', '-I$SYSROOT/usr/include', '-I$BIONIC_LIBS/include']
c_link_args = ['--sysroot=$SYSROOT', '-L$BIONIC_LIBS/lib']
cpp_link_args = ['--sysroot=$SYSROOT', '-L$BIONIC_LIBS/lib']

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

# Skip configure entirely — go straight to the specific DLL targets
# proton-wine's Makefile uses winegcc wrappers, but we can invoke them directly
echo "=== [5/6] Build winewayland.drv (PE) + winewayland.so (ELF) ==="
cd /tmp/proton-wine

# Try Wine's configure first (it sets up winegcc, tools, etc.)
./configure \
  --host=aarch64-linux-android \
  --prefix=/usr/local \
  --enable-winewayland \
  --without-x \
  --without-opengl \
  --without-vulkan \
  --disable-tests \
  --disable-docs \
  2>&1 | tail -80

# Build the winewayland targets specifically
make -j$(nproc) dlls/winewayland.drv 2>&1 | tail -50 || \
  echo "make dlls/winewayland.drv failed, will try alternative"

# Wine 7+ produces both .so (unix) and .drv (PE) in the same target
# The exact output paths depend on Wine version
echo "Searching for built artifacts..."
find . -name "winewayland*" -type f -newer configure 2>/dev/null | head -20

echo "=== [6/6] Collect + zip ==="
# Try various known output paths
for f in \
  "dlls/winewayland.drv/winewayland.drv.so" \
  "dlls/winewayland.drv/winewayland.drv" \
  "build-aarch64-linux-android/dlls/winewayland.drv/winewayland.drv"; do
  if [ -f "$f" ]; then
    echo "Found PE driver: $f ($(stat -c%s "$f") bytes)"
    cp "$f" "$PROTON_OUT/lib/wine/aarch64-windows/winewayland.drv"
    break
  fi
done

for f in \
  "dlls/winewayland.drv/winewayland.so" \
  "dlls/winewayland.drv/winewayland.dll.so" \
  "build-aarch64-linux-android/dlls/winewayland.drv/winewayland.so"; do
  if [ -f "$f" ]; then
    echo "Found ELF helper: $f ($(stat -c%s "$f") bytes)"
    cp "$f" "$PROTON_OUT/lib/wine/aarch64-unix/winewayland.so"
    break
  fi
done

# Copy bionic shared libs (if any were built as .so) into rootfs
if [ -d "$BIONIC_LIBS" ]; then
  find "$BIONIC_LIBS/lib" -name "*.so*" -exec cp -P {} "$ROOTFS_OUT/usr/local/lib/" \; 2>/dev/null || true
fi

echo "--- proton/ contents ---"
find "$PROTON_OUT" -type f -exec ls -la {} \;
echo "--- rootfs/ contents ---"
find "$ROOTFS_OUT" -type f -exec ls -la {} \;

# Sanity check
DRV_SIZE=$(stat -c%s "$PROTON_OUT/lib/wine/aarch64-windows/winewayland.drv" 2>/dev/null || echo 0)
SO_SIZE=$(stat -c%s "$PROTON_OUT/lib/wine/aarch64-unix/winewayland.so" 2>/dev/null || echo 0)
if [ "$DRV_SIZE" -lt 5000 ]; then
  echo "✗ FATAL: winewayland.drv missing or too small ($DRV_SIZE bytes)"
  echo "Dumping configure + build log tail for diagnosis:"
  ls -la /tmp/proton-wine/*.log 2>/dev/null || true
  exit 1
fi

cd "$OUTDIR"
mkdir -p "$WORKSPACE/app/src/main/assets"
rm -f "$WORKSPACE/app/src/main/assets/winewayland-driver.zip"
zip -r "$WORKSPACE/app/src/main/assets/winewayland-driver.zip" proton/ rootfs/
ls -la "$WORKSPACE/app/src/main/assets/winewayland-driver.zip"
echo "✓ winewayland-driver.zip built"
