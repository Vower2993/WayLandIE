/*
 * vulkan-1.dll PE Proxy — Extension Translation Layer
 *
 * This is a PE DLL that replaces Wine's built-in vulkan-1.dll. DXVK loads
 * vulkan-1.dll to access the Vulkan API — our proxy intercepts the calls
 * BEFORE they reach winevulkan.so.
 *
 * The PE proxy's PRIMARY job: translate VK_KHR_win32_surface → VK_KHR_xlib_surface
 * in vkCreateInstance. Without this, DXVK's vkCreateInstance fails because
 * Turnip doesn't support VK_KHR_win32_surface.
 *
 * The PE proxy also intercepts vkCreateWin32SurfaceKHR to create an Xlib
 * surface using the ANativeWindow from WAYLANDIE_ANATIVE_WINDOW.
 *
 * Everything else passes through to winevulkan.dll unchanged. The game
 * renders via PATH A (direct to SurfaceFlinger via real swapchain).
 *
 * NOTE: This is a Windows PE DLL — it CANNOT use POSIX sockets (sys/socket.h),
 * Unix domain sockets, or SCM_RIGHTS (fd passing). Those are Linux-specific.
 * The dmabuf fd sending to the bridge must be done from the ELF side
 * (waylandie_dmabuf_layer.c or winevulkan_dmabuf.c).
 *
 * Compile: aarch64-w64-mingw32-clang -shared -o vulkan-1.dll vulkan-1-proxy.c
 *           -lvulkan -lkernel32
 *
 * License: MIT
 */

#define _GNU_SOURCE
#include <windows.h>
#include <vulkan/vulkan.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdint.h>

#define LOG_TAG "WayLandIE/Proxy"
#define LOGI(...) fprintf(stderr, LOG_TAG ": " __VA_ARGS__)
#define LOGE(...) fprintf(stderr, LOG_TAG " ERROR: " __VA_ARGS__)

/* ========================================================================
 * Real winevulkan function pointers (resolved on first use)
 * ======================================================================== */
static HMODULE g_winevulkan_mod = NULL;
static PFN_vkGetInstanceProcAddr g_real_gipa = NULL;
static PFN_vkGetDeviceProcAddr g_real_gdpa = NULL;
static void* g_anative_window = NULL; /* from WAYLANDIE_ANATIVE_WINDOW env var */
static int g_initialized = 0;

static void init(void) {
    if (g_initialized) return;
    g_initialized = 1;

    g_winevulkan_mod = LoadLibraryA("winevulkan.dll");
    if (!g_winevulkan_mod) {
        LOGE("LoadLibraryA(winevulkan.dll) failed\n");
        return;
    }
    g_real_gipa = (PFN_vkGetInstanceProcAddr)GetProcAddress(g_winevulkan_mod, "vkGetInstanceProcAddr");
    g_real_gdpa = (PFN_vkGetDeviceProcAddr)GetProcAddress(g_winevulkan_mod, "vkGetDeviceProcAddr");
    if (!g_real_gipa || !g_real_gdpa) {
        LOGE("GetProcAddress for GIPA/GDPA failed\n");
        return;
    }
    LOGI("loaded winevulkan.dll gipa=%p gdpa=%p\n", (void*)g_real_gipa, (void*)g_real_gdpa);

    /* Read ANativeWindow from env var */
    const char* anw_env = getenv("WAYLANDIE_ANATIVE_WINDOW");
    if (anw_env && anw_env[0] != '0') {
        g_anative_window = (void*)(uintptr_t)strtoull(anw_env, NULL, 0);
        LOGI("ANativeWindow=%p\n", g_anative_window);
    }
}

/* ========================================================================
 * Hook: vkCreateInstance
 * Translate VK_KHR_win32_surface → VK_KHR_xlib_surface
 *
 * This is THE critical fix. DXVK requests VK_KHR_win32_surface, which Turnip
 * doesn't support. We translate it to VK_KHR_xlib_surface (Turnip supports
 * this via adrenotools). Without this translation, vkCreateInstance fails:
 *   err: DxvkInstance::createInstance: Failed to create Vulkan instance
 * ======================================================================== */
static VKAPI_ATTR VkResult VKAPI_CALL hook_CreateInstance(
    const VkInstanceCreateInfo* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkInstance* pInstance)
{
    init();
    if (!g_real_gipa) return VK_ERROR_INITIALIZATION_FAILED;

    /* Translate extension list: replace VK_KHR_win32_surface with VK_KHR_xlib_surface */
    VkInstanceCreateInfo modified = *pCreateInfo;
    const char** new_exts = NULL;

    if (pCreateInfo->enabledExtensionCount > 0 && pCreateInfo->ppEnabledExtensionNames) {
        new_exts = (const char**)calloc(pCreateInfo->enabledExtensionCount, sizeof(char*));
        if (!new_exts) return VK_ERROR_OUT_OF_HOST_MEMORY;

        int found_win32 = 0;
        for (uint32_t i = 0; i < pCreateInfo->enabledExtensionCount; i++) {
            if (strcmp(pCreateInfo->ppEnabledExtensionNames[i], "VK_KHR_win32_surface") == 0) {
                new_exts[i] = "VK_KHR_xlib_surface";
                found_win32 = 1;
                LOGI("translate VK_KHR_win32_surface -> VK_KHR_xlib_surface\n");
            } else {
                new_exts[i] = pCreateInfo->ppEnabledExtensionNames[i];
            }
        }
        if (found_win32) {
            modified.ppEnabledExtensionNames = new_exts;
        }
    }

    /* Strip VkLayerInstanceCreateInfo from pNext chain (sType=47)
     * The HOST driver doesn't understand loader-internal types. */
    if (modified.pNext) {
        const VkBaseInStructure* pnext = (const VkBaseInStructure*)modified.pNext;
        if (pnext->sType == (VkStructureType)47) {
            modified.pNext = pnext->pNext;
            LOGI("stripped VkLayerInstanceCreateInfo from pNext chain\n");
        }
    }

    PFN_vkCreateInstance real_create = (PFN_vkCreateInstance)g_real_gipa(NULL, "vkCreateInstance");
    VkResult res = real_create(&modified, pAllocator, pInstance);
    if (new_exts) free(new_exts);

    if (res == VK_SUCCESS) {
        LOGI("vkCreateInstance success instance=%p\n", (void*)*pInstance);
    } else {
        LOGE("vkCreateInstance failed res=%d\n", res);
    }
    return res;
}

/* ========================================================================
 * Hook: vkCreateWin32SurfaceKHR
 * Create Xlib surface using ANativeWindow from WAYLANDIE_ANATIVE_WINDOW
 *
 * DXVK calls vkCreateWin32SurfaceKHR with an HWND. We translate this to
 * vkCreateXlibSurfaceKHR using the ANativeWindow pointer. The adrenotools
 * wrapper ignores dpy and uses the ANativeWindow directly.
 * ======================================================================== */
static VKAPI_ATTR VkResult VKAPI_CALL hook_CreateWin32SurfaceKHR(
    VkInstance instance,
    const VkWin32SurfaceCreateInfoKHR* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkSurfaceKHR* pSurface)
{
    init();
    if (!g_real_gipa || !g_anative_window) {
        LOGE("CreateWin32SurfaceKHR: no ANativeWindow — falling back to real\n");
        PFN_vkCreateWin32SurfaceKHR real =
            (PFN_vkCreateWin32SurfaceKHR)g_real_gipa(instance, "vkCreateWin32SurfaceKHR");
        return real ? real(instance, pCreateInfo, pAllocator, pSurface) : VK_ERROR_INITIALIZATION_FAILED;
    }

    /* Create Xlib surface using ANativeWindow as the Window handle */
    PFN_vkCreateXlibSurfaceKHR real_create_xlib =
        (PFN_vkCreateXlibSurfaceKHR)g_real_gipa(instance, "vkCreateXlibSurfaceKHR");
    if (!real_create_xlib) {
        LOGE("vkCreateXlibSurfaceKHR not available\n");
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    VkXlibSurfaceCreateInfoKHR xlib_info = {};
    xlib_info.sType = VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR;
    xlib_info.pNext = NULL;
    xlib_info.flags = 0;
    xlib_info.dpy = NULL; /* adrenotools wrapper ignores this */
    xlib_info.window = (void*)(uintptr_t)g_anative_window; /* ANativeWindow* as Window */

    VkResult res = real_create_xlib(instance, &xlib_info, pAllocator, pSurface);
    LOGI("CreateWin32SurfaceKHR -> CreateXlibSurfaceKHR res=%d surface=%p\n",
         res, (void*)*pSurface);
    return res;
}

/* ========================================================================
 * Exported entry points — DXVK calls these via GetProcAddress
 *
 * We intercept ONLY:
 *   vkCreateInstance         — extension translation
 *   vkCreateWin32SurfaceKHR  — surface translation
 *
 * Everything else passes through to winevulkan.dll unchanged.
 * The game renders via PATH A (direct to SurfaceFlinger via real swapchain).
 * ======================================================================== */
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(
    VkInstance instance, const char* pName)
{
    init();
    if (!g_real_gipa) return NULL;
    if (!pName) return NULL;

    if (strcmp(pName, "vkCreateInstance") == 0)
        return (PFN_vkVoidFunction)hook_CreateInstance;
    if (strcmp(pName, "vkCreateWin32SurfaceKHR") == 0)
        return (PFN_vkVoidFunction)hook_CreateWin32SurfaceKHR;

    return g_real_gipa(instance, pName);
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(
    VkDevice device, const char* pName)
{
    init();
    if (!g_real_gdpa) return NULL;
    if (!pName) return NULL;

    /* All device-level functions pass through unchanged.
     * DXVK creates a real swapchain, renders into real swapchain images,
     * and presents via real vkQueuePresentKHR (PATH A). */

    return g_real_gdpa(device, pName);
}

/* PE DLL entry point */
BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    switch (fdwReason) {
        case DLL_PROCESS_ATTACH:
            LOGI("vulkan-1.dll PE proxy loaded (extension translation)\n");
            init();
            break;
        case DLL_PROCESS_DETACH:
            break;
    }
    return TRUE;
}
