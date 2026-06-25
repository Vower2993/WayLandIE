#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# build-bionic-bridge.sh — Build bionic (NDK) dependencies for the WaylandIE
# bridge, eliminating glibc + seccomp SIGSYS issues.
#
# Builds (in order, all with Android NDK r26d → bionic libc):
#   1. libffi 3.4.4     → /tmp/bionic-libs/lib/libffi.a
#   2. libwayland 1.22  → /tmp/bionic-libs/lib/libwayland-server.a
#
# The bridge itself (libwaylandie_bridge.so) is compiled by the regular
# Gradle externalNativeBuildDebug task — it picks up these static libs via
# CMakeLists.txt. See app/src/main/cpp/CMakeLists.txt.
#
# Why this exists:
#   The bridge was previously compiled inside the Ubuntu Focal rootfs using
#   glibc 2.31 + libwayland-server.so.0. Test F diagnostic proved that
#   libwayland-server.so.0's library constructor triggers SIGSYS (exit 159)
#   from Android's seccomp filter. The LD_PRELOAD syscall shim (Option A)
#   is a workaround; this script + the CMake target is the proper long-term
#   fix — the bridge no longer depends on glibc at all.
#
# Usage (local dev):
#   bash tools/build-bionic-bridge.sh
#
# Usage (CI):
#   Called from .github/workflows/build-self-contained-apk.yml BEFORE the
#   Gradle build. Output is cached via actions/cache keyed on this script's
#   hash so subsequent CI runs skip the rebuild.
#
# Cache key: bionic-libs-${{ hashFiles('tools/build-bionic-bridge.sh') }}-v1
# ---------------------------------------------------------------------------
set -e
set -o pipefail
set -x  # verbose for CI logs

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORK_DIR="${WORK_DIR:-/tmp/bionic-bridge-build}"
OUTPUT_DIR="${OUTPUT_DIR:-/tmp/bionic-libs}"

# NDK r26d paths
NDK_ROOT="${NDK_ROOT:-$(ls -d ${ANDROID_HOME:-/usr/local/lib/android/sdk}/ndk/* 2>/dev/null | head -1)}"
NDK_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
SYSROOT="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
CC="$NDK_BIN/aarch64-linux-android33-clang"
CXX="$NDK_BIN/aarch64-linux-android33-clang++"
AR="$NDK_BIN/llvm-ar"
STRIP="$NDK_BIN/llvm-strip"

# Toolchain prefix for libffi's --host flag
HOST_TRIPLET="aarch64-linux-android"

# Versions
LIBFFI_VERSION="3.4.4"
WAYLAND_VERSION="1.22.0"

# ---------------------------------------------------------------------------
# Sanity checks
# ---------------------------------------------------------------------------
echo "=== build-bionic-bridge.sh ==="
echo "  REPO_ROOT : $REPO_ROOT"
echo "  WORK_DIR  : $WORK_DIR"
echo "  OUTPUT_DIR: $OUTPUT_DIR"
echo "  NDK_ROOT  : $NDK_ROOT"
echo ""

if [ ! -x "$CC" ]; then
    echo "FATAL: NDK clang not found at $CC"
    echo "  Set NDK_ROOT env var to point to your NDK r26d installation."
    exit 1
fi
if ! command -v meson >/dev/null 2>&1; then
    echo "FATAL: meson not found. Install with: pip install meson ninja"
    exit 1
fi
if ! command -v ninja >/dev/null 2>&1; then
    echo "FATAL: ninja not found. Install with: pip install meson ninja"
    exit 1
fi

mkdir -p "$WORK_DIR" "$OUTPUT_DIR"
cd "$WORK_DIR"

# ---------------------------------------------------------------------------
# 0. Build host wayland-scanner (needed as a BUILD tool by libwayland itself)
# ---------------------------------------------------------------------------
# libwayland's meson build invokes wayland-scanner to generate protocol
# marshalling code. wayland-scanner runs on the HOST (x86_64 CI machine) and
# produces C code that gets compiled for the TARGET (arm64). So we need a
# NATIVE (host) wayland-scanner binary, NOT a cross-compiled one.
#
# Strategy: if /tmp/host-wl/bin/wayland-scanner exists, use it (already built
# in a previous run). Otherwise build it from the same wayland source we'll
# cross-compile below.
echo ""
echo "=== [0/2] Ensuring host wayland-scanner is available ==="
HOST_WL_PREFIX="${HOST_WL_PREFIX:-/tmp/host-wl}"
HOST_SCANNER="$HOST_WL_PREFIX/bin/wayland-scanner"

if [ ! -x "$HOST_SCANNER" ]; then
    echo "  Building host wayland-scanner from source…"
    mkdir -p "$WORK_DIR"
    cd "$WORK_DIR"
    if [ ! -d "wayland-host-$WAYLAND_VERSION" ]; then
        git clone --depth 1 --branch "$WAYLAND_VERSION" \
            https://github.com/nicholasgasior/wayland.git \
            "wayland-host-$WAYLAND_VERSION" 2>&1 | tail -3
    fi
    cd "wayland-host-$WAYLAND_VERSION"
    # Native build — no cross-file. Build ONLY the scanner (libraries=false
    # skips libwayland-client/server which we don't need on the host).
    meson setup build-host \
        --prefix="$HOST_WL_PREFIX" \
        -Ddocumentation=false \
        -Dtests=false \
        -Dlibraries=false \
        -Dscanner=true \
        -Ddtd_validation=false 2>&1 | tail -5
    ninja -C build-host 2>&1 | tail -5
    meson install -C build-host 2>&1 | tail -5
    cd "$WORK_DIR"
fi

if [ ! -x "$HOST_SCANNER" ]; then
    echo "FATAL: host wayland-scanner not built at $HOST_SCANNER"
    exit 1
fi
echo "  ✓ Host wayland-scanner: $("$HOST_SCANNER" --version)"

# Set up pkg-config directory with wayland-scanner.pc so the cross-compile
# meson build can find it.
HOST_PKGCONFIG_DIR="$WORK_DIR/host-pkgconfig"
mkdir -p "$HOST_PKGCONFIG_DIR"
cp "$HOST_WL_PREFIX/lib/x86_64-linux-gnu/pkgconfig/wayland-scanner.pc" \
   "$HOST_PKGCONFIG_DIR/" 2>/dev/null || \
   find "$HOST_WL_PREFIX" -name "wayland-scanner.pc" -exec cp {} "$HOST_PKGCONFIG_DIR/" \;

# ---------------------------------------------------------------------------
# 1. Build libffi 3.4.4 (static, bionic)
# ---------------------------------------------------------------------------
echo ""
echo "=== [1/2] Building libffi $LIBFFI_VERSION (bionic, static) ==="

if [ ! -f "libffi-$LIBFFI_VERSION.tar.gz" ]; then
    echo "  Downloading libffi $LIBFFI_VERSION…"
    # Use GitHub release (more reliable than sourceware.org from CI)
    wget -q "https://github.com/libffi/libffi/releases/download/v$LIBFFI_VERSION/libffi-$LIBFFI_VERSION.tar.gz"
fi

if [ ! -d "libffi-$LIBFFI_VERSION" ]; then
    echo "  Extracting…"
    tar xf "libffi-$LIBFFI_VERSION.tar.gz"
fi

cd "libffi-$LIBFFI_VERSION"

# Configure for cross-compile to Android aarch64
# - --host tells autotools we're building for a different target
# - --disable-shared ensures we only get libffi.a (smaller, no dynamic linker issues)
# - --enable-static is the default but explicit is better
# - --disable-exec-static-tramp: avoids the tramp.c closure API which uses
#   memfd_create / executable mmap (problematic on Android's SELinux + the
#   tramp.c file has a bug where open_temp_exec_file() is called without
#   a prototype, breaking on NDK clang's C99+ strict mode). Wayland-server
#   does not need ffi_closure trampolines for its server-side dispatch.
echo "  Configuring…"
# Export the full NDK toolchain so autotools finds all tools.
# Without RANLIB/NM, configure may fail on tool detection.
CC="$CC" CXX="$NDK_BIN/aarch64-linux-android33-clang++" \
AR="$AR" STRIP="$STRIP" \
RANLIB="$NDK_BIN/llvm-ranlib" \
NM="$NDK_BIN/llvm-nm" \
OBJDUMP="$NDK_BIN/llvm-objdump" \
LD="$NDK_BIN/ld" \
    ./configure \
        --host="$HOST_TRIPLET" \
        --prefix="$OUTPUT_DIR" \
        --disable-shared \
        --enable-static \
        --disable-docs \
        --disable-dependency-tracking \
        --disable-exec-static-tramp 2>&1 | tail -20

echo "  Building…"
make -j"$(nproc)" 2>&1 | tail -10

echo "  Installing to $OUTPUT_DIR…"
make install 2>&1 | tail -10

# Verify
if [ ! -f "$OUTPUT_DIR/lib/libffi.a" ]; then
    echo "FATAL: libffi.a not produced"
    exit 1
fi
echo "  ✓ libffi.a: $(stat -c%s "$OUTPUT_DIR/lib/libffi.a") bytes"

cd "$WORK_DIR"

# ---------------------------------------------------------------------------
# 2. Build libwayland 1.22 (server only, static, bionic)
# ---------------------------------------------------------------------------
echo ""
echo "=== [2/2] Building libwayland $WAYLAND_VERSION (bionic, server-only, static) ==="

if [ ! -d "wayland-$WAYLAND_VERSION" ]; then
    echo "  Cloning wayland $WAYLAND_VERSION source…"
    # GitLab tarball download is unreliable from some CI networks; use git clone
    # which falls back through mirrors more gracefully.
    git clone --depth 1 --branch "$WAYLAND_VERSION" \
        https://github.com/nicholasgasior/wayland.git \
        "wayland-$WAYLAND_VERSION" 2>&1 | tail -5
fi

cd "wayland-$WAYLAND_VERSION"

# Meson cross-file — use the one we ship in tools/
CROSS_FILE="$REPO_ROOT/tools/android-aarch64-cross.txt"
if [ ! -f "$CROSS_FILE" ]; then
    echo "FATAL: meson cross-file not found at $CROSS_FILE"
    exit 1
fi

# Patch the cross-file's NDK paths — replace @NDK_ROOT@ placeholder
# with the actual NDK path. This makes the cross-file portable across
# different NDK install locations (CI, local dev, etc.)
TEMP_CROSS="$WORK_DIR/cross-file-active.txt"
sed "s|@NDK_ROOT@|$NDK_ROOT|g" \
    "$CROSS_FILE" > "$TEMP_CROSS"

echo "  Meson setup…"
# Notes on meson options:
# - -Ddocumentation=false : we don't need docs
# - -Dtests=false         : tests need glib + host toolchain, skip
# - -Dlibraries=true      : build libwayland-server + libwayland-client (we only
#                          link against server, but client doesn't hurt and is
#                          needed for some scanner internals)
# - -Dlibdir=lib          : install to $PREFIX/lib (not lib/aarch64-linux-gnu)
# - -Ddefault_library=static : produce .a files only
# - c_args: -fPIC (needed because the final bridge is a shared lib)
# - c_args: -I$OUTPUT_DIR/include (find our libffi)
# - c_link_args: -L$OUTPUT_DIR/lib -lffi (link our libffi)
# - PKG_CONFIG_PATH includes HOST_PKGCONFIG_DIR so meson finds the host
#   wayland-scanner (build-time tool, not linked into the output)
# - --native-file points to tools/build-machine-native.txt so meson can find
#   the host wayland-scanner binary for code generation (wayland's meson.build
#   uses dependency('wayland-scanner') which is resolved against the build
#   machine in cross-compile mode)
NATIVE_FILE="$REPO_ROOT/tools/build-machine-native.txt"
# Patch the native file: replace placeholders for host scanner binary AND
# the host pkg-config dir (the [built-in options] section needs the actual
# path so meson can find wayland-scanner.pc when resolving the build-machine
# dependency).
TEMP_NATIVE="$WORK_DIR/native-file-active.txt"
sed -e "s|/tmp/host-wl/bin/wayland-scanner|$HOST_SCANNER|g" \
    -e "s|HOST_PKGCONFIG_DIR|$HOST_PKGCONFIG_DIR|g" \
    "$NATIVE_FILE" > "$TEMP_NATIVE"

PKG_CONFIG_PATH="$HOST_PKGCONFIG_DIR:$OUTPUT_DIR/lib/pkgconfig:$OUTPUT_DIR/share/pkgconfig" \
    meson setup build \
        --prefix="$OUTPUT_DIR" \
        --cross-file="$TEMP_CROSS" \
        --native-file="$TEMP_NATIVE" \
        --libdir=lib \
        --default-library=static \
        -Ddocumentation=false \
        -Dtests=false \
        -Dlibraries=true \
        -Ddtd_validation=false \
        -Dscanner=false \
        -Dc_args="-fPIC -I$OUTPUT_DIR/include -D_WAYLAND_SERVER_NOPLUGINS" \
        -Dc_link_args="-L$OUTPUT_DIR/lib -lffi" 2>&1 | tail -30

echo "  Ninja build…"
ninja -C build 2>&1 | tail -10

echo "  Install to $OUTPUT_DIR…"
meson install -C build 2>&1 | tail -10

# Verify
if [ ! -f "$OUTPUT_DIR/lib/libwayland-server.a" ]; then
    echo "FATAL: libwayland-server.a not produced"
    exit 1
fi
echo "  ✓ libwayland-server.a: $(stat -c%s "$OUTPUT_DIR/lib/libwayland-server.a") bytes"

# Also need the wayland-server headers
if [ ! -f "$OUTPUT_DIR/include/wayland-server.h" ]; then
    echo "FATAL: wayland-server.h not installed"
    exit 1
fi
echo "  ✓ wayland-server.h installed"

# ---------------------------------------------------------------------------
# 3. Summary
# ---------------------------------------------------------------------------
echo ""
echo "=== Build summary ==="
echo "Output: $OUTPUT_DIR"
echo "  lib/libffi.a               : $(stat -c%s "$OUTPUT_DIR/lib/libffi.a" 2>/dev/null || echo MISSING) bytes"
echo "  lib/libwayland-server.a    : $(stat -c%s "$OUTPUT_DIR/lib/libwayland-server.a" 2>/dev/null || echo MISSING) bytes"
echo "  lib/libwayland-client.a    : $(stat -c%s "$OUTPUT_DIR/lib/libwayland-client.a" 2>/dev/null || echo MISSING) bytes"
echo "  include/wayland-server.h   : $([ -f "$OUTPUT_DIR/include/wayland-server.h" ] && echo present || echo MISSING)"
echo "  include/wayland-server-core.h: $([ -f "$OUTPUT_DIR/include/wayland-server-core.h" ] && echo present || echo MISSING)"
echo "  include/ffi.h              : $([ -f "$OUTPUT_DIR/include/ffi.h" ] && echo present || echo MISSING)"
echo ""
echo "=== ✓ Bionic bridge dependencies ready ==="
echo "The CMake build (externalNativeBuildDebug) will pick these up via the"
echo "waylandie_bridge target in app/src/main/cpp/CMakeLists.txt."
