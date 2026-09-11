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

// Sets source and destination together, atomically.
//
// WHY THIS EXISTS: the caller used to set the crop and then call
// waylandie_surface_transaction_set_destination_frame(). That wrapper is gated on
// __ANDROID_API__ >= 34, but android_api_override.h force-defines __ANDROID_API__ 31 for this
// whole target (it is -include'd into every translation unit, this shim included). So the #else
// branch always won and the "destination frame" was never set at all - it called setCrop a
// SECOND time with the same rect, making the second call a no-op. Meanwhile setPosition(0,0)
// with no parent left the layer with no meaningful destination geometry.
//
// AOSP documents the correct primitive (include/android/surface_control.h):
//   void ASurfaceTransaction_setGeometry(ASurfaceTransaction*, ASurfaceControl*,
//                                        const ARect& source, const ARect& destination,
//                                        int32_t transform);
// with "the source rect's width and height must be > 0" and "the destination rect's width and
// height must be > 0", and the destination being "the rect in the parent's space where this
// surface will be drawn ... clipped by the bounds of its parent".
//
// setGeometry is available from API 29, so the API-31 target can use it unconditionally. The
// NDK declares it with C++-only syntax (const ARect&), which is why it needs this C++ shim.
void waylandie_surface_transaction_set_geometry(
        ASurfaceTransaction *transaction,
        ASurfaceControl *surface_control,
        int32_t src_left, int32_t src_top, int32_t src_right, int32_t src_bottom,
        int32_t dst_left, int32_t dst_top, int32_t dst_right, int32_t dst_bottom) {
    if (transaction == nullptr || surface_control == nullptr) {
        return;
    }
    // AOSP requires strictly positive extents; skip rather than feed SurfaceFlinger a
    // degenerate rect, which it would silently ignore.
    if (src_right <= src_left || src_bottom <= src_top) {
        return;
    }
    if (dst_right <= dst_left || dst_bottom <= dst_top) {
        return;
    }
    ARect source = {src_left, src_top, src_right, src_bottom};
    ARect destination = {dst_left, dst_top, dst_right, dst_bottom};
    ASurfaceTransaction_setGeometry(transaction, surface_control, source, destination, 0);
}

}  // extern "C"
