// surface_control_c_compat.h — C-compatible subset of NDK r26's
// <android/surface_control.h>.
//
// NDK r26's <android/surface_control.h> uses C++-only syntax (default
// arguments and C++ references) that fails to compile as plain C.
// waylandie_display_native.c uses C-style JNI calls and can't be ported
// to C++ without rewriting thousands of call sites.
//
// This header provides just the type declarations and function
// declarations the .c file actually uses, written in plain C. The
// function declarations match the runtime ABI in libandroid.so — the
// C++ default args and reference params are just syntactic sugar over
// the same C calling convention, so we declare them as plain C
// functions and call them directly. The .cpp shim handles the two
// cases that actually need C++ syntax (setCrop with `const ARect&` and
// setBuffer with default arg).
#ifndef WAYLANDIE_SURFACE_CONTROL_C_COMPAT_H
#define WAYLANDIE_SURFACE_CONTROL_C_COMPAT_H

#include <android/hardware_buffer.h>
#include <android/rect.h>  // ARect

#ifdef __cplusplus
extern "C" {
#endif

// Opaque types — match NDK definitions.
typedef struct ASurfaceControl ASurfaceControl;
typedef struct ASurfaceTransaction ASurfaceTransaction;
typedef struct ASurfaceTransactionStats ASurfaceTransactionStats;

// Visibility enum.
enum ASurfaceControlVisible {
    ASURFACE_TRANSACTION_VISIBILITY_HIDE = 0,
    ASURFACE_TRANSACTION_VISIBILITY_SHOW = 1,
};

// Transparency enum.
enum ASurfaceControlTransparency {
    ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE = 0,
    ASURFACE_TRANSACTION_TRANSPARENCY_TRANSLUCENT = 1,
};

// Stats callback type (used by setOnComplete, not actually used by the
// waylandie .c file but declared here for completeness).
typedef void (*ASurfaceTransaction_OnComplete)(void *context, ASurfaceTransactionStats *stats);

// Function declarations — these are loaded from libandroid.so at link time
// (the libandroid.so symbol exports match these C signatures).
ASurfaceTransaction *ASurfaceTransaction_create(void);
void ASurfaceTransaction_delete(ASurfaceTransaction *transaction);
void ASurfaceTransaction_apply(ASurfaceTransaction *transaction);

void ASurfaceControl_release(ASurfaceControl *surface_control);

void ASurfaceTransaction_setVisibility(
        ASurfaceTransaction *transaction,
        ASurfaceControl *surface_control,
        int visible);
void ASurfaceTransaction_setZOrder(
        ASurfaceTransaction *transaction,
        ASurfaceControl *surface_control,
        int32_t z_order);
void ASurfaceTransaction_setBufferTransparency(
        ASurfaceTransaction *transaction,
        ASurfaceControl *surface_control,
        int transparency);
void ASurfaceTransaction_setBufferAlpha(
        ASurfaceTransaction *transaction,
        ASurfaceControl *surface_control,
        float alpha);
void ASurfaceTransaction_setPosition(
        ASurfaceTransaction *transaction,
        ASurfaceControl *surface_control,
        int32_t x,
        int32_t y);
void ASurfaceTransaction_setDamageRegion(
        ASurfaceTransaction *transaction,
        ASurfaceControl *surface_control,
        const ARect rects[],
        uint32_t count);

// setCrop and setBuffer are wrapped because NDK r26 declares them with
// C++-only syntax (default arg + reference). The shim .cpp file provides
// the actual implementations that call through to libandroid.so.
void waylandie_surface_transaction_set_crop(
        ASurfaceTransaction *transaction,
        ASurfaceControl *surface_control,
        const ARect *crop);
void waylandie_surface_transaction_set_buffer(
        ASurfaceTransaction *transaction,
        ASurfaceControl *surface_control,
        AHardwareBuffer *buffer,
        int acquire_fence_fd);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // WAYLANDIE_SURFACE_CONTROL_C_COMPAT_H