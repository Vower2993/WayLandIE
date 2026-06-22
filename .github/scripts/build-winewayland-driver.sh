#!/usr/bin/env bash
# Two-stage Wine cross-build with GameNative android patches
set -eu
set +o pipefail  # ls|head can SIGPIPE; we handle errors explicitly

WORKSPACE="${GITHUB_WORKSPACE:-$(pwd)}"
OUTDIR="/tmp/winewayland-build"
PROTON_OUT="$OUTDIR/proton"
ROOTFS_OUT="$OUTDIR/rootfs"

rm -rf "$OUTDIR"
mkdir -p "$PROTON_OUT/lib/wine/aarch64-windows" \
         "$PROTON_OUT/lib/wine/aarch64-unix" \
         "$ROOTFS_OUT/usr/local/lib"

echo "=== [1/9] Install build deps ==="
sudo apt-get install -y -qq \
  autoconf automake libtool bison flex gettext \
  pkg-config python3 python3-pip libffi-dev libexpat1-dev \
  libxml2-dev libxml2 libxkbcommon-dev wayland-protocols libwayland-bin libxkbregistry-dev
pip3 install --user meson ninja 2>&1 | tail -3
export PATH="$HOME/.local/bin:$PATH"

echo "=== [1b/9] Download llvm-mingw toolchain (arm64ec PE support) ==="
LLVM_MINGW_DIR="$HOME/llvm-mingw"
if [ ! -d "$LLVM_MINGW_DIR" ]; then
  wget -q "https://github.com/mstorsjo/llvm-mingw/releases/download/20260616/llvm-mingw-20260616-ucrt-ubuntu-22.04-x86_64.tar.xz" -O /tmp/llvm-mingw.tar.xz
  mkdir -p "$LLVM_MINGW_DIR"
  tar -xf /tmp/llvm-mingw.tar.xz -C "$LLVM_MINGW_DIR" --strip-components=1
  echo "llvm-mingw installed at $LLVM_MINGW_DIR"
  ls "$LLVM_MINGW_DIR/bin/" 2>/dev/null | grep -E "^(aarch64-w64-mingw32-(clang|gcc)|x86_64-w64-mingw32-)" | head -5 || true
fi
export PATH="$LLVM_MINGW_DIR/bin:$PATH"

echo "=== [2/9] Locate NDK ==="
NDK_DIR="$ANDROID_HOME/ndk/26.1.10909125"
[ -d "$NDK_DIR" ] || NDK_DIR=$(ls -d $ANDROID_HOME/ndk/* 2>/dev/null | head -1)
echo "Using NDK: $NDK_DIR"
[ -d "$NDK_DIR" ] || { echo "FATAL: no NDK found"; exit 1; }

TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
# Use API 33 to match the bionic-libs cache (built with android33-clang).
# This ensures symbols like memfd_create (API 30+) are available at link time.
# The APK's targetSdk=28 (for W^X bypass) is separate from this compile API.
# Runtime: S24 runs Android 14 (API 34+), so all API 33 functions are available.
API=33
export CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
export CXX="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export SYSROOT="$TOOLCHAIN/sysroot"

BIONIC_LIBS="$WORKSPACE/app/src/main/cpp/bionic-libs"
ls "$BIONIC_LIBS/lib/" 2>/dev/null | head -20 || true

echo "=== [3/9] Clone proton-wine (proton_11.0 branch for GDI v108) ==="
cd /tmp
rm -rf proton-wine
git clone --depth=1 --branch proton_11.0 https://github.com/GameNative/proton-wine.git
cd proton-wine
chmod +x autogen.sh
./autogen.sh 2>&1 | tail -5

echo "=== [4/9] Build libandroid-sysvshm.so ==="
cd /tmp/proton-wine/android/android_sysvshm
"$CC" -Wall -std=gnu99 -shared -fPIC \
  -I"$(pwd)" \
  -o libandroid-sysvshm.so \
  android_sysvshm.c
echo "Built: $(ls -la libandroid-sysvshm.so)"
cp libandroid-sysvshm.so "$BIONIC_LIBS/lib/"
mkdir -p "$BIONIC_LIBS/include/sys"
cp -r sys/* "$BIONIC_LIBS/include/sys/" 2>/dev/null || true
cp libandroid-sysvshm.so "$ROOTFS_OUT/usr/local/lib/"

cp /tmp/proton-wine/android/shm_utils/shm_utils.h "$BIONIC_LIBS/include/shm_utils.h"
cp /tmp/proton-wine/android/shm_utils/shm_utils.h /tmp/proton-wine/include/

echo "=== [4b/9] Write pkg-config files ==="
mkdir -p "$BIONIC_LIBS/lib/pkgconfig"

PC_WAYLAND_CLIENT='prefix=BIONIC_LIBS_PLACEHOLDER
exec_prefix=${prefix}
libdir=${exec_prefix}/lib
includedir=${prefix}/include

Name: wayland-client
Description: Wayland client bionic lib (for Android cross-build)
Version: 1.20.0
Libs: -L${libdir} -lwayland-client -lffi -landroid-sysvshm
Cflags: -I${includedir} -D__ANDROID__ -DHAVE_SHM_UTILS
'
PC_WAYLAND_CLIENT="${PC_WAYLAND_CLIENT//BIONIC_LIBS_PLACEHOLDER/$BIONIC_LIBS}"
printf '%s' "$PC_WAYLAND_CLIENT" > "$BIONIC_LIBS/lib/pkgconfig/wayland-client.pc"

PC_WAYLAND_SERVER='prefix=BIONIC_LIBS_PLACEHOLDER
exec_prefix=${prefix}
libdir=${exec_prefix}/lib
includedir=${prefix}/include

Name: wayland-server
Description: Wayland server bionic lib (for Android cross-build)
Version: 1.20.0
Libs: -L${libdir} -lwayland-server -lffi -landroid-sysvshm
Cflags: -I${includedir} -D__ANDROID__ -DHAVE_SHM_UTILS
'
PC_WAYLAND_SERVER="${PC_WAYLAND_SERVER//BIONIC_LIBS_PLACEHOLDER/$BIONIC_LIBS}"
printf '%s' "$PC_WAYLAND_SERVER" > "$BIONIC_LIBS/lib/pkgconfig/wayland-server.pc"

PC_SCANNER='prefix=/usr
exec_prefix=${prefix}
bindir=${exec_prefix}/bin

Name: wayland-scanner
Description: Wayland scanner (system binary)
Version: 1.20.0
wayland_scanner=${bindir}/wayland-scanner
'
printf '%s' "$PC_SCANNER" > "$BIONIC_LIBS/lib/pkgconfig/wayland-scanner.pc"

PC_XKBCOMMON='prefix=BIONIC_LIBS_PLACEHOLDER
exec_prefix=${prefix}
libdir=${exec_prefix}/lib
includedir=${prefix}/include

Name: xkbcommon
Description: XKB common bionic lib (for Android cross-build)
Version: 1.4.0
Libs: -L${libdir} -lxkbcommon
Cflags: -I${includedir}
'
PC_XKBCOMMON="${PC_XKBCOMMON//BIONIC_LIBS_PLACEHOLDER/$BIONIC_LIBS}"
printf '%s' "$PC_XKBCOMMON" > "$BIONIC_LIBS/lib/pkgconfig/xkbcommon.pc"

echo "=== written .pc files ==="
ls -la "$BIONIC_LIBS/lib/pkgconfig/"

export PKG_CONFIG_PATH="$BIONIC_LIBS/lib/pkgconfig"
export PKG_CONFIG_LIBDIR="$BIONIC_LIBS/lib/pkgconfig"
unset PKG_CONFIG_SYSROOT_DIR

echo "=== pkg-config check ==="
pkg-config --cflags --libs wayland-client
pkg-config --cflags --libs xkbcommon
pkg-config --variable=wayland_scanner wayland-scanner

echo "=== [4c/9] Build xkbcommon + xkbregistry for bionic ==="
# Build static xkbcommon + xkbregistry for aarch64 Android.
# These are needed because winewayland.so links against them.
mkdir -p /tmp/xkb-build
cd /tmp/xkb-build

# Write meson cross-file for aarch64 bionic
cat > /tmp/xkb-cross.txt << XEOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'

[built-in options]
c_args = ['-fPIC', '--sysroot=$SYSROOT', '-I$SYSROOT/usr/include', '-I$BIONIC_LIBS/include']
c_link_args = ['--sysroot=$SYSROOT', '-L$BIONIC_LIBS/lib']

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
XEOF

# Write native-file for build machine (uses system gcc + pkg-config)
cat > /tmp/xkb-native.txt << XEOF
[binaries]
c = '/usr/bin/gcc'
cpp = '/usr/bin/g++'
ar = '/usr/bin/ar'
strip = '/usr/bin/strip'
pkgconfig = '/usr/bin/pkg-config'

[build_machine]
system = 'linux'
cpu_family = 'x86_64'
cpu = 'x86_64'
endian = 'little'
XEOF

# Clone and build xkbcommon (includes xkbregistry when enable-xkbregistry=true)
if [ ! -d libxkbcommon ]; then
  git clone --depth=1 https://github.com/xkbcommon/libxkbcommon.git
fi
cd libxkbcommon
rm -rf build
meson setup build \
  --native-file=/tmp/xkb-native.txt \
  --cross-file=/tmp/xkb-cross.txt \
  --prefix=/usr/local \
  --default-library=static \
  --libdir=lib \
  --includedir=include \
  -Denable-wayland=false \
  -Denable-docs=false \
  -Denable-x11=false \
  -Denable-tools=false \
  -Denable-xkbregistry=false \
  2>&1 | tail -20
ninja -C build 2>&1 | tail -10
# Install into bionic-libs
DESTDIR="$BIONIC_LIBS" ninja -C build install 2>&1 | tail -5

# Meson installs to $BIONIC_LIBS/usr/local/{lib,include} because of --prefix=/usr/local
# But Wine expects libs in $BIONIC_LIBS/lib and headers in $BIONIC_LIBS/include
# Copy them to the expected locations
cp -r "$BIONIC_LIBS/usr/local/include/xkbcommon" "$BIONIC_LIBS/include/xkbcommon" 2>/dev/null || true
cp "$BIONIC_LIBS/usr/local/lib/libxkbcommon.a" "$BIONIC_LIBS/lib/" 2>/dev/null || true
cp "$BIONIC_LIBS/usr/local/lib/pkgconfig/xkbcommon.pc" "$BIONIC_LIBS/lib/pkgconfig/" 2>/dev/null || true

# Create a stub libxkbregistry.a so Wine's link test passes.
# We disabled xkbregistry because it requires libxml2 (not available for bionic).
# Wine only uses xkbregistry for keyboard layout enumeration — not needed for
# the wayland driver to function.
echo "=== Creating stub xkbregistry header + library ==="

# Use forward declarations of struct tags (NOT typedefs) — matches what
# Wine's wayland_keyboard.c expects. Using typedef would cause
# "redefinition as different kind of symbol" errors.
mkdir -p "$BIONIC_LIBS/include/xkbcommon"
cat > "$BIONIC_LIBS/include/xkbcommon/xkbregistry.h" << 'HDR'
#ifndef _XKBREGISTRY_H_
#define _XKBREGISTRY_H_

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Forward declarations of opaque struct types */
struct rxkb_context;
struct rxkb_layout;
struct rxkb_model;
struct rxkb_option_group;
struct rxkb_option;
struct rxkb_iso639_code;
struct rxkb_iso3166_code;

enum rxkb_context_flags {
    RXKB_CONTEXT_NO_FLAGS = 0,
    RXKB_CONTEXT_NO_DEFAULT_INCLUDES = (1 << 0),
    RXKB_CONTEXT_NO_ENVIRONMENT = (1 << 1)
};

enum rxkb_log_level {
    RXKB_LOG_LEVEL_CRITICAL = 10,
    RXKB_LOG_LEVEL_ERROR = 20,
    RXKB_LOG_LEVEL_WARNING = 30,
    RXKB_LOG_LEVEL_INFO = 40,
    RXKB_LOG_LEVEL_DEBUG = 50
};

struct rxkb_context *rxkb_context_new(enum rxkb_context_flags flags);
void rxkb_context_unref(struct rxkb_context *ctx);
int rxkb_context_parse_default_ruleset(struct rxkb_context *ctx);

struct rxkb_layout *rxkb_layout_first(struct rxkb_context *ctx);
struct rxkb_layout *rxkb_layout_next(struct rxkb_layout *layout);
const char *rxkb_layout_get_name(struct rxkb_layout *layout);
const char *rxkb_layout_get_description(struct rxkb_layout *layout);
const char *rxkb_layout_get_variant(struct rxkb_layout *layout);

#ifdef __cplusplus
}
#endif

#endif /* _XKBREGISTRY_H_ */
HDR

# Stub library — returns NULL/0 from all functions (keyboard layout enum won't work)
cat > /tmp/xkbregistry_stub.c << 'STUBEOF'
#include <stddef.h>

struct rxkb_context;
struct rxkb_layout;

enum rxkb_context_flags {
    RXKB_CONTEXT_NO_FLAGS = 0,
    RXKB_CONTEXT_NO_DEFAULT_INCLUDES = 1,
    RXKB_CONTEXT_NO_ENVIRONMENT = 2
};

struct rxkb_context *rxkb_context_new(enum rxkb_context_flags flags) { return NULL; }
void rxkb_context_unref(struct rxkb_context *ctx) {}
int rxkb_context_parse_default_ruleset(struct rxkb_context *ctx) { return 0; }
struct rxkb_layout *rxkb_layout_first(struct rxkb_context *ctx) { return NULL; }
struct rxkb_layout *rxkb_layout_next(struct rxkb_layout *layout) { return NULL; }
const char *rxkb_layout_get_name(struct rxkb_layout *layout) { return NULL; }
const char *rxkb_layout_get_description(struct rxkb_layout *layout) { return NULL; }
const char *rxkb_layout_get_variant(struct rxkb_layout *layout) { return NULL; }
STUBEOF
$CC -c -fPIC --sysroot=$SYSROOT -I$BIONIC_LIBS/include /tmp/xkbregistry_stub.c -o /tmp/xkbregistry_stub.o 2>&1
$AR rcs "$BIONIC_LIBS/lib/libxkbregistry.a" /tmp/xkbregistry_stub.o
echo "Created header: $(ls -la $BIONIC_LIBS/include/xkbcommon/xkbregistry.h)"
echo "Created lib: $(ls -la $BIONIC_LIBS/lib/libxkbregistry.a)"

echo "=== bionic-libs after xkbcommon build ==="
ls -la "$BIONIC_LIBS/lib/" | grep xkb
ls "$BIONIC_LIBS/include/xkbcommon/" 2>/dev/null | head -10

echo "=== [5/9] Apply GameNative patches (proton_11.0 — single patches dir) ==="
cd /tmp/proton-wine

# proton_11.0 has ALL patches in a single android/patches/ directory.
# GameNative's build-step-arm64ec.sh applies them in a specific order
# with some patches skipped. We follow their exact approach.
apply_one() {
  local p="$1"
  if [ ! -f "$p" ]; then
    echo "  SKIP (not found): $p"
    return 0
  fi
  if git apply --check "$p" 2>/dev/null; then
    if git apply "$p" 2>/dev/null; then
      return 0
    fi
  fi
  # Fallback: try with --3way
  if git apply --3way --whitespace=nowarn "$p" 2>/dev/null; then
    return 0
  fi
  echo "  WARN failed: $(basename "$p")"
  return 1
}

PATCHES=(
  "dlls_advapi32_advapi.c.patch"
  "dlls_amd_ags_x64_unixlib.c.patch"
  "dlls_dnsapi_libresolv.c.patch"
  "dlls_dnsapi_record.c.patch"
  "dlls_midimap_Makefile.in.patch"
  "dlls_midimap_midimap.c.patch"
  "dlls_nsiproxy.sys_nsi_common.h.patch"
  "dlls_nsiproxy.sys_ip.c.patch"
  "dlls_nsiproxy.sys_ndis.c.patch"
  "dlls_ntdll_Makefile.in.patch"
  "dlls_ntdll_unix_fsync.c.patch"
  "dlls_ntdll_unix_loader.c.patch"
  "dlls_ntdll_unix_server.c.patch"
  "dlls_ntdll_unix_sync.c.patch"
  "dlls_ntdll_unix_virtual.c.patch"
  "dlls_ntdll_unix_signal_x86_64.c.patch"
  "dlls_opengl32_unix_wgl.c.patch"
  "dlls_user32_Makefile.in.patch"
  "dlls_win32u_clipboard.c.patch"
  "dlls_winebus.sys_bus_sdl.c.patch"
  "dlls_winepulse.drv_pulse.c.patch"
  "dlls_winex11.drv_bitblt.c.patch"
  "dlls_winex11.drv_keyboard.c.patch"
  "dlls_winex11.drv_mouse.c.patch"
  "dlls_winex11.drv_opengl.c.patch"
  "dlls_winex11.drv_window.c.patch"
  "dlls_winex11.drv_x11drv.h.patch"
  "dlls_winex11.drv_x11drv_main.c.patch"
  "dlls_wow64_syscall.c.patch"
  "loader_preloader.c.patch"
  "programs_explorer_desktop.c.patch"
  "programs_wineboot_wineboot.c.patch"
  "programs_winebrowser_Makefile.in.patch"
  "programs_winebrowser_main.c.patch"
  "programs_winemenubuilder_winemenubuilder.c.patch"
  "server_Makefile.in.patch"
  "server_fsync.c.patch"
  "server_inproc_sync.c.patch"
  "server_main.c.patch"
  "server_thread.c.patch"
  "server_unicode.c.patch"
  "dlls_ntdll_unix_esync.c.patch"
  "dlls_ntdll_unix_esync.h.patch"
  "server_esync.c.patch"
  "server_esync.h.patch"
)

PATCH_DIR="/tmp/proton-wine/android/patches"
count=0
failed=0
for patch in "${PATCHES[@]}"; do
  if apply_one "$PATCH_DIR/$patch"; then
    count=$((count+1))
  else
    failed=$((failed+1))
  fi
done
echo "  Applied $count, failed $failed patches"

echo "=== [6/9] Stage A: Native x86_64 tools build ==="
mkdir -p /tmp/proton-wine-tools-build
cd /tmp/proton-wine-tools-build
unset CC CXX AR STRIP LDFLAGS PKG_CONFIG_PATH PKG_CONFIG_LIBDIR
export CFLAGS="-O2"
export CXXFLAGS="-O2"
/tmp/proton-wine/configure \
  --without-x --without-opengl --without-vulkan \
  --without-alsa --without-oss --without-pulse --without-cups \
  --without-sane --without-usb --without-sdl --without-gstreamer \
  --without-freetype --without-fontconfig --without-v4l2 \
  --enable-win64 \
  --disable-tests \
  2>&1 | tail -10
# Build makedep first, then each tool subdir individually
make -j$(nproc) -C tools makedep 2>&1 | tail -10
# Each tool subdir has its own Makefile (generated by configure)
for tool in winebuild wmc wrc widl winedump winegcc; do
  echo "--- Building tools/$tool ---"
  make -j$(nproc) -C tools/$tool 2>&1 | tail -10 || \
    { echo "FATAL: tools/$tool failed"; exit 1; }
done
echo "=== built tools ==="
ls -la tools/winebuild/winebuild tools/wrc/wrc tools/wmc/wmc tools/widl/widl 2>&1 || \
  { echo "FATAL: tools not built"; find tools -maxdepth 2 -type f -executable | head -20; exit 1; }

echo "=== [7/9] Stage B: Cross-compile configure ==="
cd /tmp/proton-wine
export CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
export CXX="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export CFLAGS="-fPIC --sysroot=$SYSROOT -I$SYSROOT/usr/include -I$BIONIC_LIBS/include -I/tmp/proton-wine/include -D__ANDROID_API__=$API -D__ANDROID__"
export CXXFLAGS="$CFLAGS"
export LDFLAGS="--sysroot=$SYSROOT -L$BIONIC_LIBS/lib -landroid-sysvshm -lffi"
export PKG_CONFIG_PATH="$BIONIC_LIBS/lib/pkgconfig"
export PKG_CONFIG_LIBDIR="$BIONIC_LIBS/lib/pkgconfig"
unset PKG_CONFIG_SYSROOT_DIR

# Pre-seed all the link-test cache vars (avoid the broken pkg-config path in configure.ac)
export ac_cv_lib_wayland_client_wl_display_connect=yes
export ac_cv_lib_wayland_server_wl_display_init_shm=yes
export ac_cv_lib_wayland_egl_wl_egl_window_create=yes
export ac_cv_lib_xkbcommon_xkb_context_new=yes
export ac_cv_lib_xkbregistry_rxkb_context_new=yes
export ac_cv_header_wayland_client_h=yes
export ac_cv_header_wayland_egl_h=yes
export ac_cv_header_xkbcommon_xkbcommon_h=yes
export ac_cv_header_xkbcommon_xkbregistry_h=yes

# Direct env-var settings (configure.ac honors these via WINE_PACKAGE_FLAGS)
# Point at bionic-libs where we just copied the system xkbcommon headers
export XKBCOMMON_CFLAGS="-I$BIONIC_LIBS/include"
export XKBCOMMON_LIBS="-L$BIONIC_LIBS/lib -lxkbcommon"
export XKBREGISTRY_CFLAGS="-I$BIONIC_LIBS/include"
export XKBREGISTRY_LIBS="-L$BIONIC_LIBS/lib -lxkbregistry"
export ac_cv_header_linux_input_h=yes
export ac_cv_prog_wayland_scanner=$(which wayland-scanner)
export ac_cv_func_shm_open=yes
export ac_cv_search_shm_open="none required"
# Bionic has pthread built into libc — no separate libpthread
export ac_cv_func_pthread_create=yes
export ac_cv_lib_pthread_pthread_create=yes

# Direct env-var settings (configure.ac honors these via WINE_PACKAGE_FLAGS)
export WAYLAND_CLIENT_CFLAGS="-I$BIONIC_LIBS/include -D__ANDROID__ -DHAVE_SHM_UTILS"
export WAYLAND_CLIENT_LIBS="-L$BIONIC_LIBS/lib -lwayland-client -lffi -landroid-sysvshm"
export WAYLAND_SERVER_CFLAGS="-I$BIONIC_LIBS/include -D__ANDROID__ -DHAVE_SHM_UTILS"
export WAYLAND_SERVER_LIBS="-L$BIONIC_LIBS/lib -lwayland-server -lffi -landroid-sysvshm"
export WAYLAND_EGL_CFLAGS="-I$BIONIC_LIBS/include"
export WAYLAND_EGL_LIBS="-L$BIONIC_LIBS/lib -lwayland-egl"

export WAYLAND_SCANNER="$(which wayland-scanner)"

# Pre-seed Vulkan soname cache so vulkan_update_surfaces gets compiled
# (win32u/window.c calls it unconditionally; if SONAME_LIBVULKAN is undefined,
# the function is #ifdef'd out in vulkan.c, causing a link error)
# NDK provides libvulkan.so at sysroot/usr/lib/aarch64-linux-android/28/
export ac_cv_lib_soname_vulkan=libvulkan.so
export ac_cv_lib_vulkan_vkGetInstanceProcAddr=yes

./configure \
  --host=aarch64-linux-android \
  --prefix=/usr/local \
  --with-wine-tools=/tmp/proton-wine-tools-build \
  --without-x --without-opengl \
  --without-alsa --without-oss --without-pulse --without-cups \
  --without-sane --without-usb --without-sdl --without-gstreamer \
  --without-freetype --without-fontconfig --without-v4l2 \
  --enable-win64 \
  --enable-archs=arm64ec,aarch64 \
  --with-mingw=$LLVM_MINGW_DIR/bin/clang \
  --with-pthread \
  --disable-tests \
  2>&1 | tail -40

echo "=== configure Wayland vars ==="
grep -E "^(WAYLAND|XKB)" /tmp/proton-wine/config.status | head -20 || true

echo "=== [8/9] Build winewayland targets ==="
# Build the specific output files (not the directory target which is a no-op)
# -k keeps going past errors so we see ALL failures, not just the first
make -j$(nproc) -k \
  dlls/winewayland.drv/aarch64-windows/winewayland.drv \
  dlls/winewayland.drv/winewayland.so \
  dlls/winewayland.drv/arm64ec-windows/winewayland.drv \
  2>&1 | tail -300 || true

echo "=== Searching for built artifacts ==="
find /tmp/proton-wine -name "winewayland*" -type f -newer /tmp/proton-wine/configure 2>/dev/null | head -20
find /tmp/proton-wine -name "*.drv" -type f -newer /tmp/proton-wine/configure 2>/dev/null | head -10
ls -la /tmp/proton-wine/dlls/winewayland.drv/ 2>/dev/null

echo "=== [9/9] Collect + zip ==="
for f in \
  "/tmp/proton-wine/dlls/winewayland.drv/aarch64-windows/winewayland.drv" \
  "/tmp/proton-wine/dlls/winewayland.drv/arm64ec-windows/winewayland.drv" \
  "/tmp/proton-wine/dlls/winewayland.drv/winewayland.drv.so" \
  "/tmp/proton-wine/dlls/winewayland.drv/winewayland.drv" \
  "/tmp/proton-wine/dlls/winewayland.drv/winewayland.dll.so"; do
  if [ -f "$f" ] && [ "$(stat -c%s "$f")" -gt 1000 ]; then
    echo "Found PE: $f ($(stat -c%s "$f") bytes)"
    cp "$f" "$PROTON_OUT/lib/wine/aarch64-windows/winewayland.drv"
    break
  fi
done
for f in \
  "/tmp/proton-wine/dlls/winewayland.drv/winewayland.so" \
  "/tmp/proton-wine/dlls/winewayland.drv/winewayland.dll.so"; do
  if [ -f "$f" ] && [ "$(stat -c%s "$f")" -gt 1000 ]; then
    echo "Found ELF: $f ($(stat -c%s "$f") bytes)"
    cp "$f" "$PROTON_OUT/lib/wine/aarch64-unix/winewayland.so"
    break
  fi
done

DRV_SIZE=$(stat -c%s "$PROTON_OUT/lib/wine/aarch64-windows/winewayland.drv" 2>/dev/null || echo 0)
SO_SIZE=$(stat -c%s "$PROTON_OUT/lib/wine/aarch64-unix/winewayland.so" 2>/dev/null || echo 0)
echo "winewayland.drv: $DRV_SIZE bytes"
echo "winewayland.so: $SO_SIZE bytes"

if [ "$DRV_SIZE" -lt 1000 ]; then
  echo "FATAL: winewayland.drv missing or too small"
  echo "=== dlls/winewayland.drv/ contents ==="
  ls -la /tmp/proton-wine/dlls/winewayland.drv/ 2>/dev/null || true
  echo "=== Full Wayland detection from config.log ==="
  grep -B2 -A10 "checking for wayland" /tmp/proton-wine/config.log 2>/dev/null | head -50 || true
  echo "=== Makefile for winewayland.drv ==="
  cat /tmp/proton-wine/dlls/winewayland.drv/Makefile 2>/dev/null | head -50 || true
  exit 1
fi

if [ "$SO_SIZE" -lt 1000 ]; then
  echo "FATAL: winewayland.so missing or too small"
  echo "=== winewayland.so build output (last 80 lines) ==="
  # The make output was truncated earlier; re-run with verbose to see errors
  make -j1 dlls/winewayland.drv/winewayland.so V=1 2>&1 | tail -80 || true
  exit 1
fi

cd "$OUTDIR"
mkdir -p "$WORKSPACE/app/src/main/assets"
rm -f "$WORKSPACE/app/src/main/assets/winewayland-driver.zip"

# Create zip with FLAT structure (no proton/ or rootfs/ prefix).
# The installer extracts into protonDir, so entries must be relative
# to that dir. Both .drv and .so go into protonDir/lib/ which is in
# LD_LIBRARY_PATH (proton/active/lib is in the Wine process's search path).
cp "$ROOTFS_OUT/usr/local/lib/libandroid-sysvshm.so" "$PROTON_OUT/lib/"
( cd "$PROTON_OUT" && zip -r "$WORKSPACE/app/src/main/assets/winewayland-driver.zip" lib/ )

echo "=== zip contents ==="
unzip -l "$WORKSPACE/app/src/main/assets/winewayland-driver.zip"
ls -la "$WORKSPACE/app/src/main/assets/winewayland-driver.zip"
echo "winewayland-driver.zip built"
