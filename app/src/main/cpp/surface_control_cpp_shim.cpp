// surface_control_cpp_shim.cpp — see header for explanation.
//
// This file is compiled as C++ so it can include NDK r26's
// <android/surface_control.h> which uses C++-only syntax. It exposes
// plain-C extern "C" wrappers that waylandie_display_native.c (compiled
// as C) can call.
#include <android/surface_control.h>
#include <android/hardware_buffer.h>

extern "C" {

void waylandie_surface_transaction_set_crop(
        ASurfaceTransaction *transaction,
        ASurfaceControl *surface_control,
        const ARect *crop) {
    if (transaction == nullptr || surface_control == nullptr || crop == nullptr) {
        return;
    }
    // NDK r26 signature: ASurfaceTransaction_setCrop(ASurfaceTransaction*,
    // ASurfaceControl*, const ARect&). Pass *crop as the reference.
    ASurfaceTransaction_setCrop(transaction, surface_control, *crop);
}

void waylandie_surface_transaction_set_buffer(
        ASurfaceTransaction *transaction,
        ASurfaceControl *surface_control,
        AHardwareBuffer *buffer,
        int acquire_fence_fd) {
    if (transaction == nullptr || surface_control == nullptr) {
        return;
    }
    // NDK r26 signature uses a default argument for acquire_fence_fd.
    // We pass it explicitly.
    ASurfaceTransaction_setBuffer(transaction, surface_control, buffer, acquire_fence_fd);
}

void waylandie_surface_transaction_set_destination_frame(
        ASurfaceTransaction *transaction,
        ASurfaceControl *surface_control,
        int32_t left, int32_t top, int32_t right, int32_t bottom) {
    if (transaction == nullptr || surface_control == nullptr) {
        return;
    }
    // NDK r34+ has ASurfaceTransaction_setDestinationFrame.
    // On older NDK (r26), this is a no-op — the crop + buffer size is used.
#if __ANDROID_API__ >= 34
    ASurfaceTransaction_setDestinationFrame(transaction, surface_control, left, top, right, bottom);
#else
    // Fallback: set position + scale via crop on older NDK
    ARect dest = {left, top, right, bottom};
    ASurfaceTransaction_setCrop(transaction, surface_control, dest);
#endif
}

}  // extern "C"
