#!/usr/bin/env bash
# Builds libvk_layer_waylandie_dmabuf.so — the WaylandIE zero-copy Vulkan layer.
#
# The layer intercepts the swapchain lifecycle to create AHardwareBuffer-backed
# images, and on vkQueuePresentKHR exports each AHB's dmabuf fd and forwards it
# to the WaylandIE bridge socket (waylandie.display.bridge.v1).
set -eu

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SRC="$REPO_DIR/app/src/main/cpp/vulkan_layer/waylandie_dmabuf_layer.c"
OUT_DIR="$REPO_DIR/app/src/main/jniLibs/arm64-v8a"
OUT="$OUT_DIR/libvk_layer_waylandie_dmabuf.so"

mkdir -p "$OUT_DIR"

# Locate NDK.
NDK_DIR="${ANDROID_NDK:-${ANDROID_NDK_HOME:-${ANDROID_HOME:-}/ndk/27.2.12479018}}"
if [ ! -d "$NDK_DIR" ]; then
    NDK_DIR="$(ls -d ${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/* 2>/dev/null | head -1 || true)"
fi
if [ ! -d "$NDK_DIR" ]; then
    # Fall back to common locations.
    for candidate in /home/z/android-ndk/android-ndk-r27c /opt/android-ndk "$HOME/android-ndk"; do
        if [ -d "$candidate" ]; then NDK_DIR="$candidate"; break; fi
    done
fi
if [ ! -d "$NDK_DIR" ]; then
    echo "FATAL: NDK not found. Set ANDROID_NDK or ANDROID_HOME." >&2
    exit 1
fi
echo "Using NDK: $NDK_DIR"

TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
API=26  # AHardwareBuffer requires API 26+
CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
SYSROOT="$TOOLCHAIN/sysroot"

if [ ! -x "$CC" ]; then
    echo "FATAL: clang not found at $CC" >&2
    exit 1
fi

echo "=== Compiling waylandie_dmabuf_layer.c ==="
"$CC" \
    -Wall -Wextra -Wno-unused-parameter \
    -O2 \
    -fPIC \
    -fvisibility=hidden \
    --sysroot="$SYSROOT" \
    -I"$SYSROOT/usr/include" \
    -DVK_USE_PLATFORM_ANDROID_KHR \
    -D__ANDROID_API__=$API \
    -c "$SRC" \
    -o /tmp/waylandie_dmabuf_layer.o

echo "=== Linking libvk_layer_waylandie_dmabuf.so ==="
"$CC" \
    -shared \
    -fPIC \
    --sysroot="$SYSROOT" \
    -o "$OUT" \
    /tmp/waylandie_dmabuf_layer.o \
    -Wl,--no-undefined \
    -landroid \
    -llog \
    -ldl \
    -lc

echo "=== Verifying output ==="
ls -la "$OUT"
echo "Dynamic symbols (undefined):"
"$TOOLCHAIN/bin/llvm-readelf" --dyn-syms "$OUT" 2>/dev/null | grep 'UND' | head -30
echo "Exported entry points:"
"$TOOLCHAIN/bin/llvm-readelf" --dyn-syms "$OUT" 2>/dev/null | grep -E 'vkGetInstanceProcAddr|vkGetDeviceProcAddr|vkEnumerateInstanceLayer' || true
echo "NEEDED libs:"
"$TOOLCHAIN/bin/llvm-readelf" -d "$OUT" 2>/dev/null | grep NEEDED || true

# Verify no undefined symbols that would cause dlopen failure.
UNDEFINED=$("$TOOLCHAIN/bin/llvm-readelf" --dyn-syms "$OUT" 2>/dev/null | grep 'UND' | grep -v -E '__|abort|strlen|memcpy|memset|strcmp|strncpy|snprintf|strstr|calloc|free|close|socket|connect|sendmsg|recv|setsockopt|dlopen|dlsym|dlerror|dup|getenv|__android_log_print|AHardwareBuffer' || true)
if [ -n "$UNDEFINED" ]; then
    echo "WARNING: unexpected undefined symbols:" >&2
    echo "$UNDEFINED" >&2
fi

echo "=== Build complete: $OUT ==="
