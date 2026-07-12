// "System" / NULL driverName  -> /system/lib64/libvulkan.so.
// Any other name              -> adrenotools_open_libvulkan against the user-installed driver,
//                                falling back to the system loader if anything goes wrong.
// Caller owns the returned handle and must dlclose it.

#pragma once

#include <jni.h>

/* Forward declaration — VkRenderer is defined in vk_state.h as
 * typedef struct VkRenderer { ... } VkRenderer; */
struct VkRenderer;

#ifdef __cplusplus
extern "C" {
#endif

void *winlator_open_vulkan(JNIEnv *env, jobject context, const char *driver_name);
void *winlator_open_system_vulkan(void);

/* Used by vk_wayland_present.c */
void wait_inflight_frames(struct VkRenderer* r);

#ifdef __cplusplus
}
#endif
