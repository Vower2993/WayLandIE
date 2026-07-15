/* WAYLANDDRV Vulkan implementation — Android xlib_surface zero-copy edition
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

/* On Android, the adrenotools Vulkan wrapper (Turnip/Adreno) exposes
 * VK_KHR_xlib_surface — NOT VK_KHR_android_surface. The wrapper translates
 * VK_KHR_xlib_surface internally to VK_KHR_android_surface for the real
 * driver. We must use VK_KHR_xlib_surface for surface creation.
 *
 * The ANativeWindow is passed from Java via WAYLANDIE_ANATIVE_WINDOW env var.
 * vkCreateXlibSurfaceKHR accepts a Display* + Window, but the adrenotools
 * wrapper ignores those and uses its internal ANativeWindow. We pass the
 * ANativeWindow as the "window" parameter (casted to Window which is unsigned long).
 *
 * === GAME PRESENTATION PATHS (Android) ===
 *
 * There are two paths for game (Vulkan/DXVK) frame presentation on Android:
 *
 * PATH A — DIRECT RENDERING (default, WAYLANDIE_GAME_VIA_BRIDGE unset or 0):
 *   Game → vkCreateXlibSurfaceKHR(ANativeWindow) → adrenotools →
 *   vkCreateAndroidSurfaceKHR → SurfaceFlinger.
 *
 *   Game frames go DIRECTLY to SurfaceFlinger via the SurfaceView's
 *   ANativeWindow. The Wayland bridge is completely bypassed for game
 *   frames. The bridge ONLY handles desktop SHM frames (explorer.exe).
 *
 *   This is the current working path. It works because adrenotools'
 *   xlib_surface → android_surface translation gives the game a real
 *   present path to SurfaceFlinger.
 *
 * PATH B — VIA BRIDGE (WAYLANDIE_GAME_VIA_BRIDGE=1):
 *   Game → vkCreateWaylandSurfaceKHR(wl_surface) → Turnip swapchain →
 *   vkQueuePresentKHR → wl_surface_commit(dmabuf) → bridge's
 *   linux-dmabuf handler → present_buffer_to_android() → SurfaceControl
 *   → SurfaceFlinger.
 *
 *   Game frames flow through the Wayland bridge as zero-copy dmabuf
 *   buffers. No CPU memcpy, no AHB conversion. The bridge's
 *   linux-dmabuf-v1 implementation (waylandie-wayland-bridge.c) receives
 *   the dmabuf and forwards it to SurfaceFlinger via SurfaceControl,
 *   exactly like it does for desktop SHM→AHB frames.
 *
 *   REQUIRES: The HOST driver (Turnip via adrenotools) must support
 *   VK_KHR_wayland_surface. If it doesn't, vkCreateWaylandSurfaceKHR
 *   fails and we fall back to PATH A (direct rendering).
 *
 *   REQUIRES: process_wayland.wl_display must be connected to the
 *   bridge (set WAYLAND_DISPLAY=wayland-0, which GuestProgramLauncherComponent
 *   does). The bridge's wl_compositor creates the wl_surface that
 *   vkCreateWaylandSurfaceKHR binds to the swapchain.
 *
 *   This is the "zero-copy buffer" path for games. Once the desktop
 *   freeze issue is resolved, this flag can be enabled to test game
 *   presentation through the Wayland display compositor.
 */
static ANativeWindow *g_anative_window = NULL;

static const struct vulkan_driver_funcs wayland_vulkan_driver_funcs;

static VkResult wayland_vulkan_surface_create(HWND hwnd, BOOL raw, const struct vulkan_instance *instance,
                                              VkSurfaceKHR *handle, struct client_surface **client)
{
    VkResult res;
    struct wayland_client_surface *surface;

    TRACE("%p %p %p %p\n", hwnd, instance, handle, client);

    if (!(surface = wayland_client_surface_create(hwnd))) return VK_ERROR_OUT_OF_HOST_MEMORY;

#ifdef __ANDROID__
    /* On Android, choose between direct rendering (Xlib) and bridge routing
     * (Wayland) based on the WAYLANDIE_GAME_VIA_BRIDGE env var.
     *
     * See the long comment above g_anative_window for the full architecture
     * description of both paths.
     *
     * Bridge path is attempted FIRST when the flag is set. If it fails (e.g.
     * HOST driver doesn't support VK_KHR_wayland_surface, or wl_display is
     * not connected), we fall back to the Xlib direct-rendering path so the
     * game can still render. */
    {
        const char *bridge_env = getenv("WAYLANDIE_GAME_VIA_BRIDGE");
        int game_via_bridge = (bridge_env && bridge_env[0] == '1');

        if (game_via_bridge)
        {
            /* PATH B: Route game frames through the Wayland bridge via
             * zero-copy dmabuf. The bridge's linux-dmabuf-v1 handler
             * (waylandie-wayland-bridge.c) receives the dmabuf from
             * vkQueuePresentKHR and forwards it to SurfaceFlinger via
             * SurfaceControl — same path as desktop SHM→AHB frames. */
            if (process_wayland.wl_display && surface->wl_surface &&
                instance->p_vkCreateWaylandSurfaceKHR)
            {
                VkWaylandSurfaceCreateInfoKHR create_info_host;
                create_info_host.sType = VK_STRUCTURE_TYPE_WAYLAND_SURFACE_CREATE_INFO_KHR;
                create_info_host.pNext = NULL;
                create_info_host.flags = 0;
                create_info_host.display = process_wayland.wl_display;
                create_info_host.surface = surface->wl_surface;

                res = instance->p_vkCreateWaylandSurfaceKHR(
                    instance->host.instance, &create_info_host, NULL, handle);
                if (res == VK_SUCCESS)
                {
                    set_client_surface(hwnd, surface);
                    *client = &surface->client;
                    ERR("Bridge path: created Wayland surface=0x%s for hwnd=%p "
                        "(game frames via bridge dmabuf)\n",
                        wine_dbgstr_longlong(*handle), hwnd);
                    return VK_SUCCESS;
                }
                ERR("Bridge path failed (vkCreateWaylandSurfaceKHR res=%d), "
                    "falling back to Xlib direct rendering\n", res);
                /* Fall through to Xlib path — surface is still valid,
                 * we just didn't use the wl_surface. */
            }
            else
            {
                ERR("Bridge path requested but prerequisites not met "
                    "(wl_display=%p wl_surface=%p vkCreateWaylandSurfaceKHR=%p), "
                    "falling back to Xlib direct rendering\n",
                    (void *)process_wayland.wl_display,
                    (void *)(surface ? surface->wl_surface : NULL),
                    (void *)(instance ? instance->p_vkCreateWaylandSurfaceKHR : NULL));
                /* Fall through to Xlib path */
            }
        }
    }

    /* PATH A (default + fallback): Direct rendering via Xlib surface →
     * adrenotools → SurfaceFlinger. Game frames render directly to the SurfaceView.
     *
     * Turnip via adrenotools supports VK_KHR_xlib_surface. Wine's
     * wayland_map_instance_extensions maps win32_surface → xlib_surface,
     * and convert_instance_create_info translates the extension LIST
     * before passing to the HOST driver.
     *
     * The ANativeWindow pointer is passed via WAYLANDIE_ANATIVE_WINDOW
     * env var (set by surfaceCreated → waitForSurfaceCreated on background
     * thread) or via file fallback (Option B). */
    {
        const char *anw_env = getenv("WAYLANDIE_ANATIVE_WINDOW");
        if (!g_anative_window && anw_env)
            g_anative_window = (ANativeWindow *)(uintptr_t)strtoull(anw_env, NULL, 0);

        /* Option B: file-based fallback */
        if (!g_anative_window) {
            const char *anw_file = getenv("WAYLANDIE_ANATIVE_WINDOW_FILE");
            if (!anw_file || !anw_file[0])
                anw_file = "/data/user/0/com.tencent.ig/files/anative_window_ptr";
            FILE *f = fopen(anw_file, "r");
            if (f) {
                char buf[64];
                if (fgets(buf, sizeof(buf), f)) {
                    unsigned long long val = strtoull(buf, NULL, 0);
                    if (val != 0) {
                        g_anative_window = (ANativeWindow *)(uintptr_t)val;
                        fprintf(stderr, "WayLandIE: read ANativeWindow=%p from %s\n",
                                (void*)g_anative_window, anw_file);
                    }
                }
                fclose(f);
            }
        }

        if (g_anative_window)
        {
            VkXlibSurfaceCreateInfoKHR create_info;
            create_info.sType = VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR;
            create_info.pNext = NULL;
            create_info.flags = 0;
            create_info.dpy = NULL;  /* adrenotools wrapper ignores this */
            create_info.window = (Window)(uintptr_t)g_anative_window;

            res = instance->p_vkCreateXlibSurfaceKHR(instance->host.instance, &create_info, NULL, handle);
            if (res != VK_SUCCESS)
            {
                ERR("Failed to create Xlib Vulkan surface, res=%d\n", res);
                client_surface_release(&surface->client);
                return res;
            }

            set_client_surface(hwnd, surface);
            *client = &surface->client;
            TRACE("Created Xlib surface=0x%s for hwnd=%p (direct rendering)\n",
                  wine_dbgstr_longlong(*handle), hwnd);
            return VK_SUCCESS;
        }
    }
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
    /* Map win32_surface → xlib_surface so DXVK's request for
     * VK_KHR_win32_surface is satisfied by VK_KHR_xlib_surface.
     * The adrenotools wrapper (Turnip) exposes xlib_surface, not android_surface.
     * Wine's convert_instance_create_info translates the extension LIST
     * (removes win32_surface, adds xlib_surface) before passing to HOST driver. */
    if (extensions->has_VK_KHR_win32_surface) extensions->has_VK_KHR_xlib_surface = 1;
    if (extensions->has_VK_KHR_xlib_surface) extensions->has_VK_KHR_win32_surface = 1;
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
