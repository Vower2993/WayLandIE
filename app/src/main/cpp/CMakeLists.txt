# WayLandIE native presenter — dmabuf / AHardwareBuffer / Vulkan fast path.
#
# Build with NDK r25+. Default ABI list is arm64-v8a + x86_64 (declared in
# app/build.gradle.kts). The C code is identical for both ABIs because it
# uses AHardwareBuffer + Vulkan external memory, not raw GPU commands.

cmake_minimum_required(VERSION 3.22.1)

project(waylandie_display_native
        LANGUAGES C
        VERSION 0.2.0)

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
)

# Android platform libraries that the native presenter links against.
# - libandroid: AHardwareBuffer, SurfaceControl, NativeWindow
# - libvulkan:  VK_ANDROID_external_memory_android_hardware_buffer
# - liblog:     __android_log_print
# - libnativewindow: ANativeWindow_fromSurface
target_link_libraries(waylandie_display_native
    android
    vulkan
    log
    nativewindow
    EGL
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
