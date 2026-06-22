# WayLandIE native presenter — dmabuf / AHardwareBuffer / Vulkan fast path.
#
# Build with NDK r25+. Default ABI list is arm64-v8a + x86_64 (declared in
# app/build.gradle.kts). The C code is identical for both ABIs because it
# uses AHardwareBuffer + Vulkan external memory, not raw GPU commands.

cmake_minimum_required(VERSION 3.22.1)

project(waylandie_display_native
        LANGUAGES C CXX
        VERSION 0.2.0)

# The NDK r26 surface_control.h has C++-only syntax (default arguments
# like `int acquire_fence_fd = -1`, and C++ references like `const ARect&`
# in ASurfaceTransaction_setCrop). The waylandie_display_native.c source
# uses C-style JNI (*env)->Method(env, ...) which is invalid C++, so we
# cannot simply switch to C++ compilation. Instead, we wrap the small
# number of C++-only surface_control.h APIs the code uses in a thin C++
# shim file (surface_control_cpp_shim.cpp) that exposes C-linkage
# wrappers, then call those wrappers from the main C file.

# Aggressive release defaults. The presenter is on the hot per-frame path
# and every µs counts. These flags are also forced from app/build.gradle.kts
# but we re-assert them here so command-line builds behave the same.
add_compile_options(
    $<$<CONFIG:Release>:-O3>
    $<$<CONFIG:Release>:-fvisibility=hidden>
    $<$<CONFIG:Release>:-ffunction-sections>
    $<$<CONFIG:Release>:-fdata-sections>
    $<$<CONFIG:Release>:-fno-unwind-tables>
    $<$<CONFIG:Release>:-fno-asynchronous-unwind-tables>
    -Wall
    -Wextra
    -Wno-unused-parameter
)

# LTO at link time — measurably helps the per-frame Vulkan queue submit path.
add_link_options(
    $<$<CONFIG:Release>:-flto>
    $<$<CONFIG:Release>:-Wl,--gc-sections>
    $<$<CONFIG:Release>:-Wl,--exclude-libs,ALL>
    $<$<CONFIG:Release>:-Wl,-z,now>
    $<$<CONFIG:Release>:-Wl,-z,relro>
)

add_library(waylandie_display_native SHARED
    waylandie_display_native.c
    surface_control_cpp_shim.cpp
    crash_handler.c
)

# Android platform libraries that the native presenter links against.
# - libandroid: AHardwareBuffer, SurfaceControl, NativeWindow
# - libvulkan:  VK_ANDROID_external_memory_android_hardware_buffer
# - liblog:     __android_log_print
# - libnativewindow: ANativeWindow_fromSurface
# - libdl:      dlsym/dladdr for crash_handler.c backtrace_symbols_fd
#               (also already pulled in transitively, but explicit > implicit)
target_link_libraries(waylandie_display_native
    android
    vulkan
    log
    nativewindow
    EGL
    dl
)

# Make sure the Vulkan header from NDK picks up the Android platform extension.
target_compile_definitions(waylandie_display_native PRIVATE
    VK_USE_PLATFORM_ANDROID_KHR=1
    VK_NO_PROTOTYPES
)

# Strip the final .so in release — saves ~60% binary size, which matters
# because the .so ships inside the APK and is mmap'd at app launch.
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET waylandie_display_native POST_BUILD
        COMMAND ${CMAKE_STRIP} --strip-unneeded
                $<TARGET_FILE:waylandie_display_native>
        COMMENT "Stripping waylandie_display_native.so")
endif()

# ----------------------------------------------------------------------
# Bionic Wayland bridge — the long-term fix for bridge SIGSYS.
#
# This is the same ~3900-line Wayland compositor (waylandie-wayland-bridge.c)
# that's also compiled into the glibc rootfs, but here it's compiled with
# NDK clang against BIONIC libc (no glibc). This eliminates libwayland-server's
# glibc constructor entirely — the root cause of Test F's SIGSYS finding.
#
# Dependencies (all built by tools/build-bionic-bridge.sh, all bionic-static):
#   - libwayland-server.a  (wayland 1.22, cross-compiled with NDK)
#   - libffi.a             (libffi 3.4.4, cross-compiled with NDK)
#   - liblog               (Android platform — for __android_log_print)
#
# Compile flags:
#   - -DWAYLANDIE_NO_XTEST : disables X11/Xtst input injection (bionic has no
#     X11 libs). The bridge has #ifdef WAYLANDIE_NO_XTEST guards that turn
#     all xtest_* functions into no-op stubs. The Wayland compositor +
#     zero-copy dmabuf pipeline is unchanged.
#   - -fPIC                : needed because the final binary is a shared lib
#     (well, PIE executable named .so for jniLibs packaging)
#
# Launch: WineRunner detects libwaylandie_bridge.so in nativeLibraryDir and
# launches it directly via ProcessBuilder — no glibc linker, no LD_PRELOAD
# shim, no rootfs dependency for the bridge itself. (Wine still uses glibc,
# that's expected — this only fixes the bridge.)
#
# The bionic libs at /tmp/bionic-libs/ are populated by:
#   - Local dev: bash tools/build-bionic-bridge.sh
#   - CI: the "Build bionic bridge deps" step in build-self-contained-apk.yml
# If the directory is missing, this target is SKIPPED with a warning, and
# WineRunner falls back to the glibc bridge.
#
# ARM64-ONLY: The bionic static libs are cross-compiled for aarch64 only.
# x86_64 builds skip this target (the bridge is only needed on the actual
# target device which is arm64). x86_64 APKs will fall back to the glibc
# bridge — that's acceptable because x86_64 is only used for emulator
# testing where SIGSYS doesn't occur (no real seccomp filter).
# ----------------------------------------------------------------------
set(BIONIC_LIBS_DIR "${CMAKE_SOURCE_DIR}/bionic-libs"
    CACHE PATH "Path to bionic-compiled libffi + libwayland-server static libs")

if(EXISTS "${BIONIC_LIBS_DIR}/lib/libwayland-server.a"
   AND EXISTS "${BIONIC_LIBS_DIR}/lib/libffi.a"
   AND EXISTS "${BIONIC_LIBS_DIR}/include/wayland-server.h"
   AND ANDROID_ABI STREQUAL "arm64-v8a")
    message(STATUS "waylandie_bridge: bionic libs found at ${BIONIC_LIBS_DIR} (ABI=${ANDROID_ABI})")

    # CMAKE_SOURCE_DIR is app/src/main/cpp/. Bridge source lives in
    # app/src/main/assets/linux-runtime/bridge/ — one .. up to app/src/main/.
    set(BRIDGE_SRC "${CMAKE_SOURCE_DIR}/../assets/linux-runtime/bridge/waylandie-wayland-bridge.c")
    set(GENERATED_DIR "${CMAKE_SOURCE_DIR}/generated")

    add_executable(waylandie_bridge
        ${BRIDGE_SRC}
        ${GENERATED_DIR}/xdg-shell-protocol.c
        ${GENERATED_DIR}/linux-dmabuf-unstable-v1-protocol.c
        ${GENERATED_DIR}/presentation-time-protocol.c
        ${GENERATED_DIR}/viewporter-protocol.c
        ${GENERATED_DIR}/relative-pointer-unstable-v1-protocol.c
        ${GENERATED_DIR}/pointer-constraints-unstable-v1-protocol.c
    )

    # Android requires .so naming for jniLibs packaging
    set_target_properties(waylandie_bridge PROPERTIES
        OUTPUT_NAME "waylandie_bridge"
        PREFIX "lib"
        SUFFIX ".so"
    )

    # Include paths:
    # - generated/        : protocol headers (xdg-shell-server-protocol.h etc.)
    # - bionic-libs/include: wayland-server.h, wayland-server-core.h, ffi.h
    target_include_directories(waylandie_bridge PRIVATE
        ${GENERATED_DIR}
        ${BIONIC_LIBS_DIR}/include
    )

    # Compile flags:
    # - -DWAYLANDIE_NO_XTEST: disable X11/Xtst (bionic has no X11)
    # - -D_WAYLAND_SERVER_NOPLUGINS: avoid dlopen of server plugins
    # - -fPIC: needed because we link into a PIE executable (jniLibs .so)
    # - -O2: optimization (matches the glibc bridge's cc -Wall -Wextra default)
    # - -Wno-unused-parameter: matches existing bridge compile flags
    # NOTE: _GNU_SOURCE and _POSIX_C_SOURCE are already defined at the top of
    # waylandie-wayland-bridge.c — don't redefine them here.
    target_compile_definitions(waylandie_bridge PRIVATE
        WAYLANDIE_NO_XTEST
        _WAYLAND_SERVER_NOPLUGINS
    )
    target_compile_options(waylandie_bridge PRIVATE
        -fPIC
        -O2
        -Wall
        -Wextra
        -Wno-unused-parameter
    )

    # Link against bionic static libs + Android platform liblog.
    # The static libs are linked with --whole-archive to ensure all
    # wayland-server symbols are pulled in (some are referenced via
    # function pointers and may be GC'd by --gc-sections otherwise).
    target_link_options(waylandie_bridge PRIVATE
        "-Wl,--whole-archive"
        ${BIONIC_LIBS_DIR}/lib/libwayland-server.a
        ${BIONIC_LIBS_DIR}/lib/libffi.a
        "-Wl,--no-whole-archive"
    )
    target_link_libraries(waylandie_bridge
        log
        m
        dl
        # NOTE: bionic libc has pthread built-in (no -lpthread needed).
        # Android does not ship libpthread.so / libpthread.a.
    )

    # Strip in release for size (matches waylandie_display_native)
    if(CMAKE_BUILD_TYPE STREQUAL "Release")
        add_custom_command(TARGET waylandie_bridge POST_BUILD
            COMMAND ${CMAKE_STRIP} --strip-unneeded
                    $<TARGET_FILE:waylandie_bridge>
            COMMENT "Stripping libwaylandie_bridge.so")
    endif()

    message(STATUS "waylandie_bridge: target enabled (libwaylandie_bridge.so)")
else()
    message(WARNING "waylandie_bridge: bionic libs not found at ${BIONIC_LIBS_DIR} — "
                    "SKIPPING bionic bridge target. Run tools/build-bionic-bridge.sh "
                    "or wait for CI to populate the directory.")
endif()


# ----------------------------------------------------------------------
# wine_launcher — tiny native launcher stub for bionic Wine.
#
# WHY: Android 10+ (targetSdk >= 29) blocks execve() of binaries in
# getFilesDir() (W^X enforcement). This launcher is packaged as
# libwine_launcher.so in jniLibs, so Android extracts it to
# nativeLibraryDir where execve is allowed. The launcher then execve()'s
# the actual wine binary from getFilesDir() — works because the launcher
# process already has the right SELinux context.
#
# This eliminates PRoot entirely. PRoot was adding 2-5x overhead to every
# syscall (ptrace traps), which is brutal for games.
#
# The launcher itself is a single C file (~100 lines). It reads env vars
# set by Java (LD_LIBRARY_PATH, HOME, WINEPREFIX, etc.) and calls
# execve() on the wine binary path passed as argv[1].
#
# Launch sequence (Java side, in WineRunner.java):
#   ProcessBuilder pb = new ProcessBuilder(
#       nativeLibDir + "/libwine_launcher.so",
#       wineBin.getAbsolutePath(),    // argv[1] — wine binary
#       exePath,                       // argv[2] — .exe to run
#       extraArgs...                   // argv[3..] — extra wine args
#   );
#   pb.environment().put("LD_LIBRARY_PATH", ...);
#   pb.environment().put("HOME", ...);
#   pb.environment().put("WINEPREFIX", ...);
#   Process p = pb.start();
# ----------------------------------------------------------------------
add_executable(wine_launcher wine_launcher.c)

# Android requires .so naming for jniLibs packaging (same trick as
# libld_glibc.so, libproot.so, libwaylandie_bridge.so, etc.)
set_target_properties(wine_launcher PROPERTIES
    OUTPUT_NAME "wine_launcher"
    PREFIX "lib"
    SUFFIX ".so"
)

# Link against bionic libc + liblog for __android_log_print
target_link_libraries(wine_launcher log)

# Strip in release for size
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET wine_launcher POST_BUILD
        COMMAND ${CMAKE_STRIP} --strip-unneeded
                $<TARGET_FILE:wine_launcher>
        COMMENT "Stripping libwine_launcher.so")
endif()

message(STATUS "wine_launcher: target enabled (libwine_launcher.so)")

# ----------------------------------------------------------------------
# Native Content IO — fast native C++ extraction for XZ and ZSTD archives.
# Provides 5-10x speedup over Java extraction for large rootfs/driver tarballs.
# Based on WinNative's native_content_io.cpp (MIT licensed).
# ----------------------------------------------------------------------
include(FetchContent)

# ZSTD — needed for .tar.zst decompression
FetchContent_Declare(
    zstd
    GIT_REPOSITORY https://github.com/facebook/zstd.git
    GIT_TAG v1.5.6
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(zstd)

add_library(waylandie_native_io SHARED
    winlator/native_content_io.cpp
    xz/xz_embedded/xz_crc32.c
    xz/xz_embedded/xz_crc64.c
    xz/xz_embedded/xz_dec_bcj.c
    xz/xz_embedded/xz_dec_lzma2.c
    xz/xz_embedded/xz_dec_stream.c
)

target_include_directories(waylandie_native_io PRIVATE
    xz/xz_embedded
    ${zstd_SOURCE_DIR}/lib
    ${zstd_SOURCE_DIR}/lib/common
)

target_link_libraries(waylandie_native_io
    android
    log
    libzstd_static
)

# Strip in release
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET waylandie_native_io POST_BUILD
        COMMAND ${CMAKE_STRIP} --strip-unneeded
                $<TARGET_FILE:waylandie_native_io>
        COMMENT "Stripping libwaylandie_native_io.so")
endif()

message(STATUS "waylandie_native_io: target enabled (libwaylandie_native_io.so)")
