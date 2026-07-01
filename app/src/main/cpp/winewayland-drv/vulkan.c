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
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#endif

WINE_DEFAULT_DEBUG_CHANNEL(vulkan);

/* ANativeWindow handle for Android Vulkan surface creation.
 * Set by the Java side via WAYLANDIE_ANATIVE_WINDOW env var.
 * On Android, vkCreateWaylandSurfaceKHR is not supported — we use
 * vkCreateAndroidSurfaceKHR instead, which requires an ANativeWindow. */
static ANativeWindow *g_anative_window = NULL;

static const struct vulkan_driver_funcs wayland_vulkan_driver_funcs;

#ifdef __ANDROID__

/* Wine's vulkan.h doesn't define the Android surface extension types.
 * Define them locally so we can call vkCreateAndroidSurfaceKHR. */
#define VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR 1000008000

typedef struct VkAndroidSurfaceCreateInfoKHR {
    VkStructureType sType;
    const void *pNext;
    VkAndroidSurfaceCreateFlagsKHR flags;
    struct ANativeWindow *window;
} VkAndroidSurfaceCreateInfoKHR_Local;

typedef VkResult (VKAPI_PTR *PFN_vkCreateAndroidSurfaceKHR_local)(
    VkInstance, const VkAndroidSurfaceCreateInfoKHR_Local *,
    const VkAllocationCallbacks *, VkSurfaceKHR *);

/* Load vkCreateAndroidSurfaceKHR at runtime via vkGetInstanceProcAddr.
 * Wine's vulkan_instance struct doesn't have p_vkCreateAndroidSurfaceKHR. */
static PFN_vkCreateAndroidSurfaceKHR_local load_vkCreateAndroidSurfaceKHR(VkInstance host_instance)
{
    static PFN_vkCreateAndroidSurfaceKHR_local fn = NULL;
    if (!fn && host_instance)
    {
        /* Wine's vulkan_instance doesn't expose p_vkGetInstanceProcAddr.
         * Use dlopen/dlsym to get it from libvulkan.so directly. */
        static PFN_vkGetInstanceProcAddr gipa = NULL;
        if (!gipa)
        {
            void *lib = dlopen(SONAME_LIBVULKAN, RTLD_NOW);
            if (lib)
            {
                gipa = (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr");
                if (!gipa)
                    ERR("dlsym(vkGetInstanceProcAddr) failed\n");
            }
            else
                ERR("dlopen(%s) failed\n", SONAME_LIBVULKAN);
        }
        if (gipa)
        {
            fn = (PFN_vkCreateAndroidSurfaceKHR_local)gipa(host_instance, "vkCreateAndroidSurfaceKHR");
            if (fn)
                ERR("Loaded vkCreateAndroidSurfaceKHR at %p\n", fn);
            else
                ERR("vkCreateAndroidSurfaceKHR not available\n");
        }
    }
    return fn;
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
     * ANativeWindow provided by the Java side (set via env var). */
    {
        const char *anw_env = getenv("WAYLANDIE_ANATIVE_WINDOW");
        if (!g_anative_window && anw_env)
            g_anative_window = (ANativeWindow *)(uintptr_t)strtoull(anw_env, NULL, 0);
    }

    if (g_anative_window)
    {
        VkAndroidSurfaceCreateInfoKHR_Local create_info;
        PFN_vkCreateAndroidSurfaceKHR_local pfn;

        pfn = load_vkCreateAndroidSurfaceKHR(instance->host.instance);
        if (!pfn)
        {
            ERR("Cannot create Android surface — vkCreateAndroidSurfaceKHR not loaded\n");
            client_surface_release(&surface->client);
            return VK_ERROR_EXTENSION_NOT_PRESENT;
        }

        create_info.sType = (VkStructureType)VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        create_info.pNext = NULL;
        create_info.flags = 0;
        create_info.window = g_anative_window;

        res = pfn(instance->host.instance, &create_info, NULL, handle);
        if (res != VK_SUCCESS)
        {
            ERR("Failed to create Android Vulkan surface, res=%d\n", res);
            client_surface_release(&surface->client);
            return res;
        }

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
    /* On Android, always claim presentation support */
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
}

static void wayland_map_device_extensions(struct vulkan_device_extensions *extensions)
{
    if (extensions->has_VK_KHR_external_memory_win32) extensions->has_VK_KHR_external_memory_fd = 1;
    if (extensions->has_VK_KHR_external_memory_fd) extensions->has_VK_KHR_external_memory_win32 = 1;
    if (extensions->has_VK_KHR_external_semaphore_win32) extensions->has_VK_KHR_external_semaphore_fd = 1;
    if (extensions->has_VK_KHR_external_semaphore_fd) extensions->has_VK_KHR_external_semaphore_win32 = 1;
    if (extensions->has_VK_KHR_external_fence_win32) extensions->has_VK_KHR_external_fence_fd = 1;
    if (extensions->has_VK_KHR_external_fence_fd) extensions->has_VK_KHR_external_fence_win32 = 1;
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
