// surface_control_cpp_shim.h — C-linkage wrappers around the C++-only
// surface_control.h APIs in NDK r26.
//
// NDK r26's <android/surface_control.h> uses C++-only syntax (default
// arguments and C++ references) that fails to compile as plain C. The
// waylandie_display_native.c source uses C-style JNI calls and cannot be
// ported to C++ without rewriting thousands of (*env)->Method(env, ...)
// call sites. This shim is compiled as C++ and exposes C-linkage
// wrappers for the few APIs the main C file actually calls directly.
//
// The rest of the ASurfaceTransaction_* APIs are loaded via dlsym in
// the .c file, so they don't need wrappers here.
#ifndef WAYLANDIE_SURFACE_CONTROL_CPP_SHIM_H
#define WAYLANDIE_SURFACE_CONTROL_CPP_SHIM_H

// This header is included from BOTH the .c file (compiled as C) and the
// .cpp shim (compiled as C++). Only the .cpp shim needs the real NDK
// surface_control.h. The .c file uses surface_control_c_compat.h instead.
#ifdef __cplusplus
#include <android/surface_control.h>
#include <android/hardware_buffer.h>
#endif

#ifdef __cplusplus
extern "C" {
#endif

// Opaque type forward declarations for C callers. In C++ these come from
// the real NDK header above.
#ifndef __cplusplus
struct ASurfaceTransaction;
struct ASurfaceControl;
typedef struct AHardwareBuffer AHardwareBuffer;
typedef struct ARect ARect;
#endif

// Wraps ASurfaceTransaction_setCrop(transaction, surface_control, crop)
// — the NDK r26 signature takes `const ARect&` (C++ reference), but we
// expose a C-friendly `const ARect*` pointer.
void waylandie_surface_transaction_set_crop(
        ASurfaceTransaction *transaction,
        ASurfaceControl *surface_control,
        const ARect *crop);

// Wraps ASurfaceTransaction_setBuffer(transaction, surface_control, buffer,
// acquire_fence_fd) — the NDK r26 signature uses a default argument
// (`int acquire_fence_fd = -1`) which is C++-only.
void waylandie_surface_transaction_set_buffer(
        ASurfaceTransaction *transaction,
        ASurfaceControl *surface_control,
        AHardwareBuffer *buffer,
        int acquire_fence_fd);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // WAYLANDIE_SURFACE_CONTROL_CPP_SHIM_H
