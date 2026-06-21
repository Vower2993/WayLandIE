#!/usr/bin/env bash
# Builds winewayland driver (PE32+ ARM64) + winewayland.so (ELF aarch64 bionic)
# proton-wine uses meson (not autotools configure)
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
echo "bionic-libs at: $BIONIC_LIBS"
ls -la "$BIONIC_LIBS/lib/" 2>/dev/null | head -20

echo "=== [2/6] Clone proton-wine ==="
cd /tmp
rm -rf proton-wine
git clone --depth=1 https://github.com/GameNative/proton-wine.git
cd proton-wine

# Verify meson is the build system
ls meson.build configure.ac autogen.sh 2>/dev/null || true

echo "=== [3/6] Write meson cross file ==="
cat > /tmp/wine-android-cross.txt << EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
windres = '/usr/bin/aarch64-w64-mingw32-windres'
exe_wrapper = 'qemu-aarch64'

[built-in options]
c_args = ['-fPIC', '--sysroot=$SYSROOT', '-I$SYSROOT/usr/include', '-I$BIONIC_LIBS/include', '-D__ANDROID_API__=$API']
cpp_args = ['-fPIC', '--sysroot=$SYSROOT', '-I$SYSROOT/usr/include', '-I$BIONIC_LIBS/include', '-D__ANDROID_API__=$API']
c_link_args = ['--sysroot=$SYSROOT', '-L$BIONIC_LIBS/lib']
cpp_link_args = ['--sysroot=$SYSROOT', '-L$BIONIC_LIBS/lib']

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

# Also need a PE (Windows) cross-file for the .drv
cat > /tmp/wine-pe-cross.txt << EOF
[binaries]
c = '/usr/bin/clang'
cpp = '/usr/bin/clang++'
ar = '$AR'
strip = '$STRIP'
windres = '/usr/bin/aarch64-w64-mingw32-windres'

[built-in options]
c_args = ['--target=aarch64-windows-gnu', '-I$BIONIC_LIBS/include']
cpp_args = ['--target=aarch64-windows-gnu', '-I$BIONIC_LIBS/include']
c_link_args = ['--target=aarch64-windows-gnu', '-fuse-ld=lld']
cpp_link_args = ['--target=aarch64-windows-gnu', '-fuse-ld=lld']

[host_machine]
system = 'windows'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

echo "=== [4/6] Meson configure ==="
cd /tmp/proton-wine

# Wine's meson.build has a --cross-file option for the PE build
# On proton-wine, you typically configure once and it builds both ELF and PE
meson setup build \
  --cross-file=/tmp/wine-android-cross.txt \
  --buildtype=release \
  -Dwayland=enabled \
  -Dx11=disabled \
  -Dopengl=disabled \
  -Dvulkan=disabled \
  -Dtests=disabled \
  -Ddocs=disabled \
  2>&1 | tail -100

echo "=== [5/6] Build winewayland targets only ==="
cd /tmp/proton-wine/build

# Build just the winewayland driver targets
ninja -j$(nproc) dlls/winewayland.drv/winewayland.drv 2>&1 | tail -30 || \
  echo "PE build failed, trying .so target..."
ninja -j$(nproc) dlls/winewayland.drv/winewayland.so 2>&1 | tail -30 || \
  echo "ELF build failed"

echo "=== Searching build tree for winewayland artifacts ==="
find /tmp/proton-wine/build -name "winewayland*" -type f 2>/dev/null
find /tmp/proton-wine/build -path "*winewayland*" -name "*.so*" 2>/dev/null

echo "=== [6/6] Collect + zip ==="
# Look in standard meson output paths
for f in \
  "/tmp/proton-wine/build/dlls/winewayland.drv/winewayland.drv" \
  "/tmp/proton-wine/build/dlls/winewayland.drv/winewayland.drv.so" \
  "/tmp/proton-wine/build/dlls/winewayland.drv/winewayland.dll.so"; do
  if [ -f "$f" ]; then
    echo "Found: $f ($(stat -c%s "$f") bytes)"
    cp "$f" "$PROTON_OUT/lib/wine/aarch64-windows/winewayland.drv"
    break
  fi
done

for f in \
  "/tmp/proton-wine/build/dlls/winewayland.drv/winewayland.so" \
  "/tmp/proton-wine/build/dlls/winewayland.drv/libwinewayland.so"; do
  if [ -f "$f" ]; then
    echo "Found: $f ($(stat -c%s "$f") bytes)"
    cp "$f" "$PROTON_OUT/lib/wine/aarch64-unix/winewayland.so"
    break
  fi
done

# Verify
DRV_SIZE=$(stat -c%s "$PROTON_OUT/lib/wine/aarch64-windows/winewayland.drv" 2>/dev/null || echo 0)
SO_SIZE=$(stat -c%s "$PROTON_OUT/lib/wine/aarch64-unix/winewayland.so" 2>/dev/null || echo 0)
echo "winewayland.drv: $DRV_SIZE bytes"
echo "winewayland.so: $SO_SIZE bytes"

if [ "$DRV_SIZE" -lt 5000 ]; then
  echo "✗ FATAL: winewayland.drv missing or too small"
  echo "=== Build tree contents ==="
  find /tmp/proton-wine/build -name "*.drv*" -o -name "winewayland*" 2>/dev/null | head -20
  echo "=== Meson log tail ==="
  tail -100 /tmp/proton-wine/build/meson-logs/meson-log.txt 2>/dev/null || true
  exit 1
fi

cd "$OUTDIR"
mkdir -p "$WORKSPACE/app/src/main/assets"
rm -f "$WORKSPACE/app/src/main/assets/winewayland-driver.zip"
zip -r "$WORKSPACE/app/src/main/assets/winewayland-driver.zip" proton/ rootfs/
ls -la "$WORKSPACE/app/src/main/assets/winewayland-driver.zip"
echo "✓ winewayland-driver.zip built"
