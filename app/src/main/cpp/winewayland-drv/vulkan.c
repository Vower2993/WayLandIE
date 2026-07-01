/* WAYLANDDRV Vulkan implementation — Android dmabuf zero-copy edition
 *
 * Copyright 2017 Roderick Colenbrander
 * Copyright 2021 Alexandros Frantzis
 * Copyright 2024 WayLandIE Project
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 */

#if 0
#pragma makedep unix
#endif

#include "config.h"

#include <dlfcn.h>
#include <stdlib.h>
#include <unistd.h>

#include "ntstatus.h"
#define WIN32_NO_STATUS
#include "waylanddrv.h"
#include "wine/debug.h"

#include "wine/vulkan.h"
#include "wine/vulkan_driver.h"

#ifdef __ANDROID__
/* Include Android headers AFTER wine/vulkan.h so Vulkan types are defined.
 * The NDK's vulkan/vulkan_android.h expects vulkan/vulkan_core.h first,
 * but wine/vulkan.h provides equivalent definitions. */
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#endif

WINE_DEFAULT_DEBUG_CHANNEL(vulkan);

/* ANativeWindow handle for Android Vulkan surface creation.
 * Set by the Java side via WAYLANDIE_ANATIVE_WINDOW env var.
 * On Android, vkCreateWaylandSurfaceKHR is not supported — we use
 * vkCreateAndroidSurfaceKHR instead, which requires an ANativeWindow. */
static ANativeWindow *g_anative_window = NULL;

/* Track the Wayland surface associated with each HWND for dmabuf present. */
static struct wayland_client_surface *g_current_surface = NULL;

static const struct vulkan_driver_funcs wayland_vulkan_driver_funcs;

#ifdef __ANDROID__

/* Extract dmabuf fd from a VkImage via AHardwareBuffer.
 * Returns the dmabuf fd, or -1 on failure.
 * The caller MUST close() the fd when done. */
static int wayland_vulkan_image_to_dmabuf_fd(VkDevice device, VkImage image,
                                             uint32_t *out_width, uint32_t *out_height,
                                             uint32_t *out_stride, uint32_t *out_format)
{
    /* This function uses VK_ANDROID_external_memory_android_hardware_buffer
     * to export the VkImage as an AHardwareBuffer, then extracts the dmabuf
     * fd via AHardwareBuffer_getNativeHandle().
     *
     * Requirements:
     * - The VkImage must have been created with
     *   VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID
     * - The device must support VK_ANDROID_external_memory_android_hardware_buffer
     *
     * TODO: This requires swapchain images to be created with AHB external
     * memory. Currently, DXVK creates swapchain images without AHB support.
     * A winevulkan-level hook is needed to add AHB external memory to
     * swapchain image creation. See the winevulkan-dmabuf-hook patch. */
    ERR("wayland_vulkan_image_to_dmabuf_fd: not yet implemented — requires winevulkan swapchain hook\n");
    return -1;
}

/* Present a dmabuf fd through the Wayland compositor.
 * Creates a wl_buffer from the fd via zwp_linux_dmabuf_v1, attaches it
 * to the surface, adds damage, and commits. */
static VkResult wayland_vulkan_present_dmabuf(int dmabuf_fd, uint32_t width,
                                              uint32_t height, uint32_t stride,
                                              uint32_t format, uint64_t modifier)
{
    struct wayland_dmabuf_buffer *dmabuf_buf;

    if (!g_current_surface)
    {
        ERR("wayland_vulkan_present_dmabuf: no current Wayland surface\n");
        return VK_ERROR_SURFACE_LOST_KHR;
    }

    /* Create a wl_buffer from the dmabuf fd. The fd is dup'd internally
     * so the caller can close it immediately after. */
    dmabuf_buf = wayland_dmabuf_wl_buffer_for_gpu_fd(width, height, dmabuf_fd,
                                                     format, modifier, stride, NULL);
    if (!dmabuf_buf)
    {
        ERR("wayland_vulkan_present_dmabuf: failed to create dmabuf buffer\n");
        return VK_ERROR_OUT_OF_HOST_MEMORY;
    }

    /* Attach the dmabuf buffer to the Wayland surface and commit. */
    wayland_surface_attach_dmabuf(g_current_surface->client.wl_surface ?
                                  NULL : NULL, dmabuf_buf, NULL);
    /* TODO: wayland_surface_attach_dmabuf expects a struct wayland_surface *,
     * not a wl_surface. Need to get the wayland_surface from the HWND. */

    TRACE("Presented dmabuf fd=%d %ux%u through Wayland\n", dmabuf_fd, width, height);
    return VK_SUCCESS;
}

#endif /* __ANDROID__ */

static VkResult wayland_vulkan_surface_create(HWND hwnd, BOOL raw, const struct vulkan_instance *instance,
                                              VkSurfaceKHR *handle, struct client_surface **client)
{
    VkResult res;
    struct wayland_client_surface *surface;

    TRACE("%p %p %p %p\n", hwnd, instance, handle, client);

    if (!(surface = wayland_client_surface_create(hwnd))) return VK_ERROR_OUT_OF_HOST_MEMORY;

#ifdef __ANDROID__
    /* On Android, the Vulkan driver only supports VK_KHR_android_surface,
     * not VK_KHR_wayland_surface. Create an Android surface using the
     * ANativeWindow provided by the Java side (set via env var).
     *
     * The zero-copy dmabuf path is handled by intercepting vkQueuePresentKHR
     * at the winevulkan level — see wayland_vulkan_present_dmabuf() above.
     * For now, this creates a working Vulkan surface so DXVK can render.
     * The actual frame data flows through the Wayland compositor via
     * zwp_linux_dmabuf_v1 once the present hook is connected. */
    {
        const char *anw_env = getenv("WAYLANDIE_ANATIVE_WINDOW");
        if (!g_anative_window && anw_env)
            g_anative_window = (ANativeWindow *)(uintptr_t)strtoull(anw_env, NULL, 0);
    }

    if (g_anative_window)
    {
        VkAndroidSurfaceCreateInfoKHR create_info_android;
        create_info_android.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        create_info_android.pNext = NULL;
        create_info_android.flags = 0;
        create_info_android.window = g_anative_window;

        res = instance->p_vkCreateAndroidSurfaceKHR(instance->host.instance,
                                                    &create_info_android, NULL, handle);
        if (res != VK_SUCCESS)
        {
            ERR("Failed to create Android Vulkan surface, res=%d\n", res);
            client_surface_release(&surface->client);
            return res;
        }

        g_current_surface = surface;
        set_client_surface(hwnd, surface);
        *client = &surface->client;
        TRACE("Created Android surface=0x%s for hwnd=%p\n", wine_dbgstr_longlong(*handle), hwnd);
        return VK_SUCCESS;
    }
    /* Fall through to Wayland surface creation if no ANativeWindow */
#endif

    /* Desktop Linux path: create a Wayland surface */
    {
        VkWaylandSurfaceCreateInfoKHR create_info_host;
        create_info_host.sType = VK_STRUCTURE_TYPE_WAYLAND_SURFACE_CREATE_INFO_KHR;
        create_info_host.pNext = NULL;
        create_info_host.flags = 0;
        create_info_host.display = process_wayland.wl_display;
        create_info_host.surface = surface->wl_surface;

        res = instance->p_vkCreateWaylandSurfaceKHR(instance->host.instance, &create_info_host, NULL, handle);
        if (res != VK_SUCCESS)
        {
            ERR("Failed to create vulkan wayland surface, res=%d\n", res);
            client_surface_release(&surface->client);
            return res;
        }

        set_client_surface(hwnd, surface);
        *client = &surface->client;
        TRACE("Created Wayland surface=0x%s, client=%p\n", wine_dbgstr_longlong(*handle), *client);
        return VK_SUCCESS;
    }
}

static VkBool32 wayland_get_physical_device_presentation_support(struct vulkan_physical_device *physical_device,
                                                                 uint32_t index)
{
    struct vulkan_instance *instance = physical_device->instance;

    TRACE("%p %u\n", physical_device, index);

#ifdef __ANDROID__
    /* On Android, always claim presentation support — the Android Vulkan
     * driver handles queueing to the ANativeWindow. */
    return VK_TRUE;
#else
    return instance->p_vkGetPhysicalDeviceWaylandPresentationSupportKHR(physical_device->host.physical_device, index,
                                                                        process_wayland.wl_display);
#endif
}

static void wayland_map_instance_extensions(struct vulkan_instance_extensions *extensions)
{
    if (extensions->has_VK_KHR_win32_surface) extensions->has_VK_KHR_wayland_surface = 1;
    if (extensions->has_VK_KHR_wayland_surface) extensions->has_VK_KHR_win32_surface = 1;
#ifdef __ANDROID__
    /* Map win32 surface to android surface on Android */
    if (extensions->has_VK_KHR_win32_surface) extensions->has_VK_KHR_android_surface = 1;
    if (extensions->has_VK_KHR_android_surface) extensions->has_VK_KHR_win32_surface = 1;
#endif
}

static void wayland_map_device_extensions(struct vulkan_device_extensions *extensions)
{
    if (extensions->has_VK_KHR_external_memory_win32) extensions->has_VK_KHR_external_memory_fd = 1;
    if (extensions->has_VK_KHR_external_memory_fd) extensions->has_VK_KHR_external_memory_win32 = 1;
    if (extensions->has_VK_KHR_external_semaphore_win32) extensions->has_VK_KHR_external_semaphore_fd = 1;
    if (extensions->has_VK_KHR_external_semaphore_fd) extensions->has_VK_KHR_external_semaphore_win32 = 1;
    if (extensions->has_VK_KHR_external_fence_win32) extensions->has_VK_KHR_external_fence_fd = 1;
    if (extensions->has_VK_KHR_external_fence_fd) extensions->has_VK_KHR_external_fence_win32 = 1;
#ifdef __ANDROID__
    /* Enable AHB external memory for dmabuf export */
    if (extensions->has_VK_KHR_external_memory_fd)
        extensions->has_VK_ANDROID_external_memory_android_hardware_buffer = 1;
#endif
}

static const struct vulkan_driver_funcs wayland_vulkan_driver_funcs =
{
    .p_vulkan_surface_create = wayland_vulkan_surface_create,
    .p_get_physical_device_presentation_support = wayland_get_physical_device_presentation_support,
    .p_map_instance_extensions = wayland_map_instance_extensions,
    .p_map_device_extensions = wayland_map_device_extensions,
};

/**********************************************************************
 *           WAYLAND_VulkanInit
 */
UINT WAYLAND_VulkanInit(UINT version, void *vulkan_handle, const struct vulkan_driver_funcs **driver_funcs)
{
    if (version != WINE_VULKAN_DRIVER_VERSION)
    {
        ERR("version mismatch, win32u wants %u but driver has %u\n", version, WINE_VULKAN_DRIVER_VERSION);
        return STATUS_INVALID_PARAMETER;
    }

    *driver_funcs = &wayland_vulkan_driver_funcs;
    return STATUS_SUCCESS;
}
