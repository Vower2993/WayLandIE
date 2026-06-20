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
# Bionic test binary — proves bionic works without SIGSYS on Android 16.
# Compiled as a PIE executable, but named lib*.so so Android packages it
# in jniLibs. Android extracts it to nativeLibraryDir where we can execve
# it directly (same trick as libld_glibc.so and libproot.so).
# ----------------------------------------------------------------------
add_executable(waylandie_bionic_test bionic_test.c)
# Android requires .so naming for jniLibs packaging
set_target_properties(waylandie_bionic_test PROPERTIES
    OUTPUT_NAME "waylandie_bionic_test"
    PREFIX "lib"
    SUFFIX ".so"
)
# Link against bionic libc + liblog for __android_log_print (if needed)
target_link_libraries(waylandie_bionic_test log)

# ----------------------------------------------------------------------
# Syscall scanner — identifies EXACT blocked syscalls on this device.
# Tests each syscall number in a forked child. If child exits with
# SIGSYS (159), that syscall is blocked by seccomp.
# Same jniLibs packaging trick: libwaylandie_syscall_scan.so
# ----------------------------------------------------------------------
add_executable(waylandie_syscall_scan syscall_scan.c)
set_target_properties(waylandie_syscall_scan PROPERTIES
    OUTPUT_NAME "waylandie_syscall_scan"
    PREFIX "lib"
    SUFFIX ".so"
)
target_link_libraries(waylandie_syscall_scan log)

