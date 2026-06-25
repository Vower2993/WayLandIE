#!/usr/bin/env bash
# Build FEX-Emu's libarm64ecfex.dll — the ARM64EC x86_64 emulator DLL for Wine.
#
# This DLL has a LIGHTWEIGHT DllMain (no-op) — all heavy JIT initialization
# happens in ProcessInit() which Wine calls AFTER the loader lock is released.
# This prevents the stack overflow that occurs with the proton-armec's version
# which does heavy init during DllMain PROCESS_ATTACH.
#
# Output: /tmp/fex-build/Bin/libarm64ecfex.dll
set -eu

LLVM_MINGW_DIR="${LLVM_MINGW_DIR:-$HOME/llvm-mingw}"
FEX_SRC="${FEX_SRC:-/tmp/fex-src}"
FEX_BUILD="${FEX_BUILD:-/tmp/fex-build}"

# CRITICAL: Clear ALL NDK/Android environment variables that were set by the
# parent build script. FEX's cmake toolchain file uses arm64ec-w64-mingw32-clang
# from llvm-mingw, but the NDK's CC/CXX/CXXFLAGS/etc. override it, causing cmake
# to use the NDK sysroot (bionic) instead of MinGW sysroot (Windows).
# This was the root cause of "unable to find library -luser32 -lkernel32".
unset CC CXX AR STRIP SYSROOT CFLAGS CXXFLAGS LDFLAGS
unset PKG_CONFIG_PATH PKG_CONFIG_LIBDIR PKG_CONFIG_SYSROOT_DIR
unset ANDROID_HOME ANDROID_NDK_HOME ANDROID_NDK_ROOT
unset CROSS_COMPILE

export PATH="$LLVM_MINGW_DIR/bin:$PATH"

# Verify the MinGW toolchain is available
echo "=== Verifying llvm-mingw toolchain ==="
which arm64ec-w64-mingw32-clang || {
    echo "FATAL: arm64ec-w64-mingw32-clang not found in PATH"
    echo "PATH=$PATH"
    exit 1
}
echo "  ✓ arm64ec-w64-mingw32-clang found"

echo "=== Cloning FEX-Emu (shallow) ==="
rm -rf "$FEX_SRC"
git clone --depth=1 https://github.com/FEX-Emu/FEX.git "$FEX_SRC"
cd "$FEX_SRC"
git submodule update --init --recursive 2>&1 | tail -3

echo "=== Configuring FEX arm64ecfex build ==="
rm -rf "$FEX_BUILD"
mkdir -p "$FEX_BUILD"
cd "$FEX_BUILD"

cmake -DCMAKE_BUILD_TYPE=RelWithDebInfo \
      -DCMAKE_TOOLCHAIN_FILE="$FEX_SRC/Data/CMake/toolchain_mingw.cmake" \
      -DENABLE_LTO=False \
      -DMINGW_TRIPLE=arm64ec-w64-mingw32 \
      -DENABLE_ASSERTIONS=False \
      -DENABLE_JEMALLOC_GLIBC_ALLOC=False \
      -DTUNE_ARCH=generic \
      -DTUNE_CPU=none \
      -DBUILD_TESTS=False \
      -DBUILD_TESTING=False \
      -DBUILD_FEX_LINUX_TESTS=False \
      "$FEX_SRC" 2>&1 | tail -30

echo "=== Building arm64ecfex target ==="
make -j$(nproc) arm64ecfex 2>&1 | tail -30

echo "=== Verifying output ==="
DLL="$FEX_BUILD/Bin/libarm64ecfex.dll"
if [ -f "$DLL" ]; then
    SIZE=$(stat -c%s "$DLL")
    echo "Built: $DLL ($SIZE bytes)"
    if [ "$SIZE" -lt 100000 ]; then
        echo "WARNING: DLL is suspiciously small — build may have failed"
        exit 1
    fi
    echo "=== Verifying exports ==="
    "$LLVM_MINGW_DIR/bin/llvm-objdump" -p "$DLL" 2>/dev/null | grep -E "BTCpu64|ProcessInit|ThreadInit|BeginSimulation" | head -10 || true
    echo "=== FEX build complete ==="
else
    echo "FATAL: libarm64ecfex.dll not built"
    echo "=== CMake cache (for debugging) ==="
    cat "$FEX_BUILD/CMakeCache.txt" 2>/dev/null | grep -i "mingw\|toolchain\|compiler\|CMAKE_C_COMPILER\|CMAKE_CXX_COMPILER" | head -20 || true
    exit 1
fi
