#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# build-gamescope-stack.sh — Cross-compile wlroots 0.19 + gamescope for
# Android aarch64 (bionic libc, NDK r26d, API 33).
#
# This script is a sibling to build-bionic-bridge.sh. It assumes the
# bionic-libs/ tree (libffi, libwayland-server, libwayland-client) is
# already produced by that script. It then builds:
#
#   3. libdrm (headers + libdrm.a)        — needed for DRM_FORMAT_*
#   4. pixman                              — wlroots dep for software cursors
#   5. libxkbcommon                        — Xwayland keymap dep
#   6. wlroots 0.19.x (vendored in gamescope/subprojects/wlroots)
#        - headless backend only
#        - vulkan renderer
#        - no gbm, no session, no libinput, no x11 backend, no drm
#   7. gamescope
#        - wayland nested backend (presents to WaylandIE bridge)
#        - no DRM backend, no SDL2, no openvr, no pipewire, no input_emulation
#        - output: libgamescope.so (renamed for AGP packaging trick)
#
# The gamescope binary is named `libgamescope.so` (despite being an ELF
# executable) so that AGP picks it up and packages it into lib/arm64-v8a/,
# matching the pattern used for libwaylandie_bridge_exe.so.
#
# Usage (local dev):
#   bash tools/build-gamescope-stack.sh
#
# Environment variables:
#   NDK_ROOT          Path to Android NDK r26d
#   BIONIC_LIBS_DIR   Path to /tmp/bionic-libs (output of build-bionic-bridge.sh)
#   GAMESCOPE_STACK_DIR  Path to /tmp/gamescope-stack (output of this script)
#   GAMESCOPE_SRC_DIR    Path to cloned gamescope repo (default: ./build/gamescope)
# ---------------------------------------------------------------------------
set -e
set -o pipefail
set -x

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORK_DIR="${WORK_DIR:-/tmp/gamescope-stack-build}"
OUTPUT_DIR="${GAMESCOPE_STACK_DIR:-/tmp/gamescope-stack}"
GAMESCOPE_SRC_DIR="${GAMESCOPE_SRC_DIR:-$REPO_ROOT/build/gamescope}"

# NDK r26d paths (fall back to env or sane defaults)
NDK_ROOT="${NDK_ROOT:-/home/z/my-project/ndk/android-ndk-r26d}"
NDK_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
SYSROOT="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
CC="$NDK_BIN/aarch64-linux-android33-clang"
CXX="$NDK_BIN/aarch64-linux-android33-clang++"
AR="$NDK_BIN/llvm-ar"
STRIP="$NDK_BIN/llvm-strip"

HOST_TRIPLET="aarch64-linux-android"
BIONIC_LIBS_DIR="${BIONIC_LIBS_DIR:-/tmp/bionic-libs}"

# Versions
PIXMAN_VERSION="0.43.0"
LIBDRM_VERSION="2.4.122"
LIBXKBCOMMON_VERSION="1.6.0"
WLROOTS_GIT_REF="88a869855742281c98c22cab9641b317b8d065ef"  # gamescope vendored

echo "=== build-gamescope-stack.sh ==="
echo "  REPO_ROOT        : $REPO_ROOT"
echo "  WORK_DIR         : $WORK_DIR"
echo "  OUTPUT_DIR       : $OUTPUT_DIR"
echo "  GAMESCOPE_SRC_DIR: $GAMESCOPE_SRC_DIR"
echo "  NDK_ROOT         : $NDK_ROOT"
echo "  BIONIC_LIBS_DIR  : $BIONIC_LIBS_DIR"
echo ""

# Sanity checks
if [ ! -x "$CC" ]; then
    echo "FATAL: NDK clang not found at $CC"
    exit 1
fi
if [ ! -d "$BIONIC_LIBS_DIR" ]; then
    echo "FATAL: BIONIC_LIBS_DIR ($BIONIC_LIBS_DIR) not found."
    echo "  Run tools/build-bionic-bridge.sh first."
    exit 1
fi
if [ ! -d "$GAMESCOPE_SRC_DIR" ]; then
    echo "FATAL: gamescope source not found at $GAMESCOPE_SRC_DIR"
    echo "  git clone https://github.com/ValveSoftware/gamescope.git"
    exit 1
fi
if ! command -v meson >/dev/null 2>&1; then
    echo "FATAL: meson not found. Install: pip install meson ninja"
    exit 1
fi
if ! command -v ninja >/dev/null 2>&1; then
    echo "FATAL: ninja not found. Install: pip install meson ninja"
    exit 1
fi

# Bison is required by libxkbcommon. The repo's dev environment may not have
# it installed system-wide; we extract portable copies if needed.
if ! command -v bison >/dev/null 2>&1; then
    if [ -x /tmp/bison-extract/usr/bin/bison ]; then
        export PATH="/tmp/m4-extract/usr/bin:/tmp/bison-extract/usr/bin:/tmp/flex-extract/usr/bin:$PATH"
        export BISON_PKGDATADIR=/tmp/bison-extract/usr/share/bison
    fi
fi
# glslang is required by wlroots' Vulkan renderer for compiling shaders.
if ! command -v glslang >/dev/null 2>&1; then
    if [ -x /tmp/glslang-extract/usr/bin/glslang ]; then
        export PATH="/tmp/glslang-extract/usr/bin:$PATH"
    fi
fi

mkdir -p "$WORK_DIR" "$OUTPUT_DIR" "$OUTPUT_DIR/lib" "$OUTPUT_DIR/include"

# ---------------------------------------------------------------------------
# Set up cross-file pointing at our NDK + bionic-libs
# We use a custom cross-file WITHOUT sys_root, because meson's PKG_CONFIG_SYSROOT_DIR
# mechanism mangles absolute paths in .pc files (e.g. wayland-scanner.pc's
# `wayland_scanner=${bindir}/wayland-scanner` gets prefixed with the sysroot,
# breaking find_program lookups).
# ---------------------------------------------------------------------------
CROSS_FILE="$WORK_DIR/cross-file-gamescope.txt"
python3 - "$CROSS_FILE" "$NDK_ROOT" << 'PYEOF'
import sys
path, ndk_root = sys.argv[1], sys.argv[2]
content = f"""[binaries]
c         = '{ndk_root}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang'
cpp       = '{ndk_root}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang++'
ar        = '{ndk_root}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar'
strip     = '{ndk_root}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'
nm        = '{ndk_root}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm'
ranlib    = '{ndk_root}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ranlib'
ld        = '{ndk_root}/toolchains/llvm/prebuilt/linux-x86_64/bin/ld'
pkgconfig = '/usr/bin/pkg-config'

[host_machine]
system     = 'android'
cpu_family = 'aarch64'
cpu        = 'aarch64'
endian     = 'little'

[properties]
needs_exe_wrapper = false
"""
with open(path, 'w') as f:
    f.write(content)
PYEOF
echo "  ✓ cross-file: $CROSS_FILE"

# Native file pointing at host tools (wayland-scanner is already in
# BIONIC_LIBS_DIR/host/bin from build-bionic-bridge.sh, or system-installed)
HOST_SCANNER="$(command -v wayland-scanner || echo $BIONIC_LIBS_DIR/host/bin/wayland-scanner)"
NATIVE_FILE="$WORK_DIR/native-file-gamescope.txt"
cat > "$NATIVE_FILE" <<EOF
[binaries]
wayland-scanner = '$HOST_SCANNER'

[built-in options]
pkg_config_path = '$BIONIC_LIBS_DIR/lib/pkgconfig:$BIONIC_LIBS_DIR/share/pkgconfig'
EOF
echo "  ✓ native-file: $NATIVE_FILE"

# Common env for all builds
export PKG_CONFIG_PATH="$BIONIC_LIBS_DIR/lib/pkgconfig:$BIONIC_LIBS_DIR/share/pkgconfig:$OUTPUT_DIR/lib/pkgconfig"
# DO NOT set PKG_CONFIG_SYSROOT_DIR — it mangles absolute paths in .pc files
# (e.g. wayland-scanner.pc's `wayland_scanner=${bindir}/wayland-scanner` gets
# prefixed with the sysroot, breaking find_program). We provide sysroot via
# --sysroot in CC/CXX instead.
unset PKG_CONFIG_SYSROOT_DIR
export PKG_CONFIG_LIBDIR="$BIONIC_LIBS_DIR/lib/pkgconfig:$BIONIC_LIBS_DIR/share/pkgconfig:$OUTPUT_DIR/lib/pkgconfig"
export CFLAGS="-fPIC -I$BIONIC_LIBS_DIR/include -I$OUTPUT_DIR/include -I$SYSROOT/usr/include"
export CXXFLAGS="$CFLAGS"
export LDFLAGS="-L$BIONIC_LIBS_DIR/lib -L$OUTPUT_DIR/lib -fPIC"

# ---------------------------------------------------------------------------
# 3. libdrm (headers + static lib)
# ---------------------------------------------------------------------------
echo ""
echo "=== Building libdrm $LIBDRM_VERSION ==="
LIBDRM_SRC="$WORK_DIR/libdrm-$LIBDRM_VERSION"
if [ ! -d "$LIBDRM_SRC" ]; then
    wget -q "https://dri.freedesktop.org/libdrm/libdrm-$LIBDRM_VERSION.tar.xz" -O "$WORK_DIR/libdrm.tar.xz"
    tar -xf "$WORK_DIR/libdrm.tar.xz" -C "$WORK_DIR"
fi
cd "$LIBDRM_SRC"
meson setup _build \
    --prefix="$OUTPUT_DIR" \
    --cross-file="$CROSS_FILE" \
    --libdir=lib \
    --default-library=static \
    -Dintel=disabled \
    -Dradeon=disabled \
    -Damdgpu=disabled \
    -Dnouveau=disabled \
    -Dvmwgfx=disabled \
    -Domap=disabled \
    -Dexynos=disabled \
    -Dfreedreno=disabled \
    -Dtegra=disabled \
    -Dvc4=disabled \
    -Detnaviv=disabled \
    -Dtests=false \
    -Dcairo-tests=disabled \
    -Dman-pages=disabled \
    -Dvalgrind=disabled 2>&1 | tail -15
ninja -C _build 2>&1 | tail -5
meson install -C _build 2>&1 | tail -5
echo "  ✓ libdrm: $(stat -c%s "$OUTPUT_DIR/lib/libdrm.a" 2>/dev/null || echo MISSING) bytes"

# ---------------------------------------------------------------------------
# 4. pixman
# ---------------------------------------------------------------------------
echo ""
echo "=== Building pixman $PIXMAN_VERSION ==="
PIXMAN_SRC="$WORK_DIR/pixman-$PIXMAN_VERSION"
if [ ! -d "$PIXMAN_SRC" ]; then
    wget -q "https://cairographics.org/releases/pixman-$PIXMAN_VERSION.tar.gz" -O "$WORK_DIR/pixman.tar.gz"
    tar -xzf "$WORK_DIR/pixman.tar.gz" -C "$WORK_DIR"
fi
cd "$PIXMAN_SRC"
meson setup _build \
    --prefix="$OUTPUT_DIR" \
    --cross-file="$CROSS_FILE" \
    --libdir=lib \
    --default-library=static \
    -Dgtk=disabled \
    -Dtests=disabled \
    -Dlibpng=disabled \
    -Da64-neon=disabled \
    -Darm-simd=disabled 2>&1 | tail -10
ninja -C _build 2>&1 | tail -5
meson install -C _build 2>&1 | tail -5
echo "  ✓ pixman: $(stat -c%s "$OUTPUT_DIR/lib/libpixman-1.a" 2>/dev/null || echo MISSING) bytes"

# ---------------------------------------------------------------------------
# 5. libxkbcommon (needs libxml2 — vendored via meson wrap)
# ---------------------------------------------------------------------------
echo ""
echo "=== Building libxkbcommon $LIBXKBCOMMON_VERSION ==="
XKBCOMMON_SRC="$WORK_DIR/libxkbcommon-$LIBXKBCOMMON_VERSION"
if [ ! -d "$XKBCOMMON_SRC" ]; then
    wget -q "https://xkbcommon.org/download/libxkbcommon-$LIBXKBCOMMON_VERSION.tar.xz" -O "$WORK_DIR/xkbcommon.tar.xz"
    tar -xf "$WORK_DIR/xkbcommon.tar.xz" -C "$WORK_DIR"
fi
cd "$XKBCOMMON_SRC"
# libxkbcommon needs wayland-scanner only if -Denable-wayland=true; we disable it
meson setup _build \
    --prefix="$OUTPUT_DIR" \
    --cross-file="$CROSS_FILE" \
    --libdir=lib \
    --default-library=static \
    -Denable-wayland=false \
    -Denable-docs=false \
    -Denable-tools=false \
    -Denable-x11=false \
    -Denable-xkbregistry=false \
    -Denable-bash-completion=false \
    -Dx-locale-root=/usr/share/X11/locale 2>&1 | tail -10
ninja -C _build 2>&1 | tail -5
meson install -C _build 2>&1 | tail -5
echo "  ✓ libxkbcommon: $(stat -c%s "$OUTPUT_DIR/lib/libxkbcommon.a" 2>/dev/null || echo MISSING) bytes"

# ---------------------------------------------------------------------------
# 6. wlroots 0.19.x (use gamescope's vendored copy)
# ---------------------------------------------------------------------------
echo ""
echo "=== Building wlroots (vendored in gamescope/subprojects/wlroots) ==="
WLROOTS_SRC="$GAMESCOPE_SRC_DIR/subprojects/wlroots"
if [ ! -d "$WLROOTS_SRC" ] || [ ! -f "$WLROOTS_SRC/meson.build" ]; then
    echo "FATAL: wlroots not initialized at $WLROOTS_SRC"
    echo "  cd $GAMESCOPE_SRC_DIR && git submodule update --init subprojects/wlroots"
    exit 1
fi

cd "$WLROOTS_SRC"
# Bionic compatibility patches:
#  1. wlroots/util/shm.c uses shm_open() — works because we LD_PRELOAD
#     our android_sysvshm shim that provides shm_open.
#  2. Force-disable features that require glibc-specific deps.

meson setup _build \
    --prefix="$OUTPUT_DIR" \
    --cross-file="$CROSS_FILE" \
    --native-file="$NATIVE_FILE" \
    --libdir=lib \
    --default-library=static \
    -Drenderers=vulkan \
    -Dbackends= \
    -Dallocators= \
    -Dsession=disabled \
    -Dxwayland=disabled \
    -Dxcb-errors=disabled \
    -Dcolor-management=disabled \
    -Dlibliftoff=disabled \
    -Dexamples=false \
    -Dauto_features=disabled 2>&1 | tail -25
ninja -C _build 2>&1 | tail -10
meson install -C _build 2>&1 | tail -10
echo "  ✓ wlroots: $(stat -c%s "$OUTPUT_DIR/lib/libwlroots-0.19.a" 2>/dev/null || stat -c%s "$OUTPUT_DIR/lib/libwlroots.a" 2>/dev/null || echo MISSING) bytes"

# ---------------------------------------------------------------------------
# 7. gamescope itself
# ---------------------------------------------------------------------------
echo ""
echo "=== Building gamescope ==="
cd "$GAMESCOPE_SRC_DIR"

# Patch out C++20 features that NDK r26d clang 17 doesn't fully support.
# Specifically, std::format is missing — gamescope uses fmt::format instead,
# so this is usually fine. But verify by adding -DFMT_* if needed.

# Disable problematic subprojects: openvr, pipewire, input_emulation.
meson setup _build_android \
    --prefix="$OUTPUT_DIR" \
    --cross-file="$CROSS_FILE" \
    --native-file="$NATIVE_FILE" \
    --libdir=lib \
    --default-library=static \
    -Ddrm_backend=disabled \
    -Dsdl2_backend=disabled \
    -Dpipewire=disabled \
    -Dinput_emulation=disabled \
    -Davif_screenshots=disabled \
    -Dbenchmark=disabled \
    -Denable_openvr_support=false \
    -Denable_tests=false \
    -Denable_gamescope_wsi_layer=false \
    -Drt_cap=disabled \
    -Dc_args="$CFLAGS -DWLR_USE_UNSTABLE -D_GNU_SOURCE" \
    -Dcpp_args="$CXXFLAGS -DWLR_USE_UNSTABLE -D_GNU_SOURCE -DVK_USE_PLATFORM_ANDROID_KHR" \
    -Dc_link_args="$LDFLAGS" \
    -Dcpp_link_args="$LDFLAGS" 2>&1 | tail -40
ninja -C _build_android 2>&1 | tail -20
echo "  ✓ gamescope build complete"

# Copy android_stubs.cpp + x11_stubs.c into the gamescope source tree
# (they're added as sources in src/meson.build via the `sources:` argument)
cp "$REPO_ROOT/tools/android_stubs.cpp" "$GAMESCOPE_SRC_DIR/src/android_stubs.cpp"
mkdir -p "$GAMESCOPE_SRC_DIR/tools"
cp "$REPO_ROOT/tools/x11_stubs.c" "$GAMESCOPE_SRC_DIR/tools/x11_stubs.c"

# Find the gamescope binary and rename it to libgamescope.so for AGP packaging
GAMESCOPE_BIN="$GAMESCOPE_SRC_DIR/_build_android/src/gamescope"
if [ ! -f "$GAMESCOPE_BIN" ]; then
    # Try alternate location
    GAMESCOPE_BIN="$(find $GAMESCOPE_SRC_DIR/_build_android -name 'gamescope' -type f -executable | head -1)"
fi
if [ ! -f "$GAMESCOPE_BIN" ]; then
    echo "FATAL: gamescope binary not found"
    exit 1
fi

# Copy to repo's jniLibs directory with the .so naming trick
JNILIBS_DIR="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNILIBS_DIR"
"$STRIP" "$GAMESCOPE_BIN" -o "$JNILIBS_DIR/libgamescope.so"
echo "  ✓ Installed: $JNILIBS_DIR/libgamescope.so ($(stat -c%s "$JNILIBS_DIR/libgamescope.so") bytes)"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo "=== Build summary ==="
echo "Output: $OUTPUT_DIR"
echo "  lib/libdrm.a                : $(stat -c%s "$OUTPUT_DIR/lib/libdrm.a" 2>/dev/null || echo MISSING) bytes"
echo "  lib/libpixman-1.a           : $(stat -c%s "$OUTPUT_DIR/lib/libpixman-1.a" 2>/dev/null || echo MISSING) bytes"
echo "  lib/libxkbcommon.a          : $(stat -c%s "$OUTPUT_DIR/lib/libxkbcommon.a" 2>/dev/null || echo MISSING) bytes"
echo "  lib/libwlroots.a            : $(stat -c%s "$OUTPUT_DIR/lib/libwlroots.a" 2>/dev/null || echo MISSING) bytes"
echo "  APK jniLibs:"
echo "    arm64-v8a/libgamescope.so : $(stat -c%s "$JNILIBS_DIR/libgamescope.so" 2>/dev/null || echo MISSING) bytes"
echo ""
echo "=== ✓ gamescope stack ready ==="
echo "Next: ./gradlew :app:assembleLudashiDebug to package into APK"
