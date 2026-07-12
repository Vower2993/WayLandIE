/*
 * vulkan-1.dll PE Proxy — Direct Android Compositing (DAC) Zero-Copy Layer
 *
 * This is a PE DLL that replaces Wine's built-in vulkan-1.dll. DXVK loads
 * vulkan-1.dll to access the Vulkan API — our proxy intercepts the calls
 * BEFORE they reach winevulkan.so, completely bypassing adrenotools'
 * isolated linker namespace.
 *
 * Interception flow:
 *   DXVK → vulkan-1.dll (our proxy) → winevulkan.dll → winevulkan.so → Turnip
 *
 * The proxy intercepts:
 *   vkCreateInstance         — translate VK_KHR_win32_surface → VK_KHR_xlib_surface
 *   vkCreateWin32SurfaceKHR  — create Xlib surface using ANativeWindow
 *   vkCreateSwapchainKHR     — create exportable staging images (DXVK renders into these)
 *   vkGetSwapchainImagesKHR  — return staging images (zero-blit)
 *   vkQueuePresentKHR        — send dmabuf fd to bridge, no GPU blit
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
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define LOG_TAG "WayLandIE/Proxy"
#define LOGI(...) fprintf(stderr, LOG_TAG ": " __VA_ARGS__)
#define LOGE(...) fprintf(stderr, LOG_TAG " ERROR: " __VA_ARGS__)

#define MAX_SWAPCHAIN_IMAGES 8
#define BRIDGE_SOCKET "waylandie.display.bridge.v1"

/* ========================================================================
 * Real winevulkan function pointers (resolved on first use)
 * ======================================================================== */
static HMODULE g_winevulkan_mod = NULL;
static PFN_vkGetInstanceProcAddr g_real_gipa = NULL;
static PFN_vkGetDeviceProcAddr g_real_gdpa = NULL;

/* Resolved device-level functions (for staging image creation) */
static PFN_vkCreateImage g_real_create_image = NULL;
static PFN_vkDestroyImage g_real_destroy_image = NULL;
static PFN_vkAllocateMemory g_real_alloc_mem = NULL;
static PFN_vkFreeMemory g_real_free_mem = NULL;
static PFN_vkBindImageMemory g_real_bind_img_mem = NULL;
static PFN_vkGetImageMemoryRequirements2 g_real_get_img_mem_reqs2 = NULL;
static PFN_vkGetImageSubresourceLayout g_real_get_subres_layout = NULL;
static PFN_vkGetMemoryFdKHR g_real_get_memory_fd = NULL;
static PFN_vkGetPhysicalDeviceMemoryProperties g_real_get_phys_mem_props = NULL;

/* Tracked state */
static VkInstance g_instance = NULL;
static VkPhysicalDevice g_physical_device = NULL;
static VkDevice g_device = NULL;
static uint32_t g_queue_family = 0;
static void* g_anative_window = NULL; /* from WAYLANDIE_ANATIVE_WINDOW env var */
static int g_initialized = 0;

/* ========================================================================
 * Per-swapchain staging image (exportable as dmabuf)
 * ======================================================================== */
typedef struct {
    VkImage image;
    VkDeviceMemory mem;
    int dmabuf_fd;       /* cached from vkGetMemoryFdKHR */
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    uint64_t size;
    uint32_t drm_format;
} staging_image;

typedef struct {
    VkSwapchainKHR real_swapchain;   /* real hardware swapchain (for acquire/present cycling) */
    uint32_t image_count;
    staging_image images[MAX_SWAPCHAIN_IMAGES];
    int bridge_sock;
    uint64_t present_count;
} proxy_swapchain;

static proxy_swapchain* g_swapchains[16]; /* simple array, keyed by real_swapchain pointer */
static int g_swapchain_count = 0;

/* ========================================================================
 * Bridge socket communication
 * ======================================================================== */
static int bridge_connect(const char* name) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    size_t len = strlen(name);
    if (len + 1 > sizeof(addr.sun_path)) { close(fd); return -1; }
    addr.sun_path[0] = '\0'; /* abstract socket */
    memcpy(addr.sun_path + 1, name, len);
    if (connect(fd, (struct sockaddr*)&addr,
                (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + len)) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int bridge_send_dmabuf(int sock, int fd, uint32_t w, uint32_t h,
                              uint32_t fmt, uint32_t stride, uint64_t size) {
    char cmd[512];
    int n = snprintf(cmd, sizeof(cmd),
        "dmabuf-present fast=1 window=fullscreen width=%u height=%u "
        "format=%u modifier=0 planes=1 stride0=%u offset0=0 "
        "size=%llu driver=turnip\n",
        w, h, fmt, stride, (unsigned long long)size);
    if (n <= 0 || (size_t)n >= sizeof(cmd)) return -1;

    char ctrl[CMSG_SPACE(sizeof(int))];
    struct iovec iov = { .iov_base = cmd, .iov_len = (size_t)n };
    struct msghdr msg = {};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = ctrl;
    msg.msg_controllen = sizeof(ctrl);
    struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
    if (!cmsg) return -1;
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &fd, sizeof(int));
    msg.msg_controllen = cmsg->cmsg_len;

    if (sendmsg(sock, &msg, MSG_NOSIGNAL) < 0) return -1;

    /* Read response */
    char resp[256];
    ssize_t r = recv(sock, resp, sizeof(resp) - 1, 0);
    if (r > 0) resp[r] = '\0';
    return 0;
}

/* ========================================================================
 * Initialization — load winevulkan.dll and resolve real functions
 * ======================================================================== */
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

static void resolve_device_functions(VkDevice device) {
    if (!g_real_gdpa || !device) return;
    if (g_real_create_image) return; /* already resolved */

    g_real_create_image = (PFN_vkCreateImage)g_real_gdpa(device, "vkCreateImage");
    g_real_destroy_image = (PFN_vkDestroyImage)g_real_gdpa(device, "vkDestroyImage");
    g_real_alloc_mem = (PFN_vkAllocateMemory)g_real_gdpa(device, "vkAllocateMemory");
    g_real_free_mem = (PFN_vkFreeMemory)g_real_gdpa(device, "vkFreeMemory");
    g_real_bind_img_mem = (PFN_vkBindImageMemory)g_real_gdpa(device, "vkBindImageMemory");
    g_real_get_img_mem_reqs2 = (PFN_vkGetImageMemoryRequirements2)g_real_gdpa(device, "vkGetImageMemoryRequirements2");
    g_real_get_subres_layout = (PFN_vkGetImageSubresourceLayout)g_real_gdpa(device, "vkGetImageSubresourceLayout");
    g_real_get_memory_fd = (PFN_vkGetMemoryFdKHR)g_real_gdpa(device, "vkGetMemoryFdKHR");
    if (g_instance)
        g_real_get_phys_mem_props = (PFN_vkGetPhysicalDeviceMemoryProperties)g_real_gipa(g_instance, "vkGetPhysicalDeviceMemoryProperties");

    LOGI("device functions resolved: create_image=%p get_mem_fd=%p\n",
         (void*)g_real_create_image, (void*)g_real_get_memory_fd);
}

/* ========================================================================
 * Hook: vkCreateInstance
 * Translate VK_KHR_win32_surface → VK_KHR_xlib_surface
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
                LOGI("translate VK_KHR_win32_surface → VK_KHR_xlib_surface\n");
            } else {
                new_exts[i] = pCreateInfo->ppEnabledExtensionNames[i];
            }
        }
        if (found_win32) {
            modified.ppEnabledExtensionNames = new_exts;
        }
    }

    /* Strip VkLayerInstanceCreateInfo from pNext chain (we're not a real layer) */
    if (modified.pNext) {
        const VkBaseInStructure* pnext = (const VkBaseInStructure*)modified.pNext;
        /* Skip VkLayerInstanceCreateInfo (sType = 47 = VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO) */
        if (pnext->sType == (VkStructureType)47) {
            modified.pNext = pnext->pNext;
            LOGI("stripped VkLayerInstanceCreateInfo from pNext chain\n");
        }
    }

    PFN_vkCreateInstance real_create = (PFN_vkCreateInstance)g_real_gipa(NULL, "vkCreateInstance");
    VkResult res = real_create(&modified, pAllocator, pInstance);
    if (new_exts) free(new_exts);

    if (res == VK_SUCCESS) {
        g_instance = *pInstance;
        LOGI("vkCreateInstance success instance=%p\n", (void*)g_instance);
    } else {
        LOGE("vkCreateInstance failed res=%d\n", res);
    }
    return res;
}

/* ========================================================================
 * Hook: vkCreateWin32SurfaceKHR
 * Create Xlib surface using ANativeWindow from WAYLANDIE_ANATIVE_WINDOW
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

    /* Create Xlib surface using ANativeWindow as the Window handle.
     * The adrenotools wrapper ignores dpy and uses the ANativeWindow directly. */
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
    LOGI("CreateWin32SurfaceKHR → CreateXlibSurfaceKHR res=%d surface=%p\n",
         res, (void*)*pSurface);
    return res;
}

/* ========================================================================
 * DRM format conversion
 * ======================================================================== */
static uint32_t vk_format_to_drm(VkFormat fmt) {
    switch (fmt) {
        case VK_FORMAT_B8G8R8A8_UNORM:
        case VK_FORMAT_B8G8R8A8_SRGB:
            return 0x34325241; /* DRM_FORMAT_ARGB8888 */
        case VK_FORMAT_R8G8B8A8_UNORM:
        case VK_FORMAT_R8G8B8A8_SRGB:
            return 0x34324241; /* DRM_FORMAT_ABGR8888 */
        default:
            return 0x34324241; /* default ABGR8888 */
    }
}

/* ========================================================================
 * Create exportable staging image (DXVK renders into this directly)
 * ======================================================================== */
static VkResult create_staging_image(VkDevice device, VkFormat fmt, VkExtent2D extent,
                                     staging_image* out) {
    memset(out, 0, sizeof(*out));
    out->dmabuf_fd = -1;
    out->width = extent.width;
    out->height = extent.height;
    out->drm_format = vk_format_to_drm(fmt);

    resolve_device_functions(device);

    /* Create image with external memory handle type */
    VkExternalMemoryImageCreateInfo ext_info = {};
    ext_info.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    ext_info.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;

    VkImageCreateInfo img_info = {};
    img_info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    img_info.pNext = &ext_info;
    img_info.imageType = VK_IMAGE_TYPE_2D;
    img_info.format = fmt;
    img_info.extent.width = extent.width;
    img_info.extent.height = extent.height;
    img_info.extent.depth = 1;
    img_info.mipLevels = 1;
    img_info.arrayLayers = 1;
    img_info.samples = VK_SAMPLE_COUNT_1_BIT;
    img_info.tiling = VK_IMAGE_TILING_LINEAR;
    img_info.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                     VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    img_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VkResult res = g_real_create_image(device, &img_info, NULL, &out->image);
    if (res != VK_SUCCESS) {
        LOGE("vkCreateImage failed res=%d\n", res);
        return res;
    }

    /* Get memory requirements */
    VkImageMemoryRequirementsInfo2 req_info = {};
    req_info.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_REQUIREMENTS_INFO_2;
    req_info.image = out->image;
    VkMemoryRequirements2 reqs = {};
    reqs.sType = VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2;
    g_real_get_img_mem_reqs2(device, &req_info, &reqs);

    /* Get stride from linear layout */
    VkImageSubresource subres = {};
    subres.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    VkSubresourceLayout layout = {};
    g_real_get_subres_layout(device, out->image, &subres, &layout);
    out->stride = (uint32_t)layout.rowPitch;
    out->size = layout.size;

    /* Find memory type */
    VkPhysicalDeviceMemoryProperties mem_props;
    g_real_get_phys_mem_props(g_physical_device, &mem_props);
    uint32_t mem_type = 0;
    int found = 0;
    for (uint32_t i = 0; i < mem_props.memoryTypeCount; i++) {
        if ((reqs.memoryRequirements.memoryTypeBits & (1u << i)) &&
            (mem_props.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) &&
            (mem_props.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
            mem_type = i; found = 1; break;
        }
    }
    if (!found) {
        for (uint32_t i = 0; i < mem_props.memoryTypeCount; i++) {
            if (reqs.memoryRequirements.memoryTypeBits & (1u << i)) {
                mem_type = i; found = 1; break;
            }
        }
    }
    if (!found) {
        LOGE("no suitable memory type\n");
        g_real_destroy_image(device, out->image, NULL);
        return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    }

    /* Allocate exportable memory */
    VkExportMemoryAllocateInfo export_info = {};
    export_info.sType = VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO;
    export_info.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;

    VkMemoryAllocateInfo alloc_info = {};
    alloc_info.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc_info.pNext = &export_info;
    alloc_info.allocationSize = reqs.memoryRequirements.size;
    alloc_info.memoryTypeIndex = mem_type;

    res = g_real_alloc_mem(device, &alloc_info, NULL, &out->mem);
    if (res != VK_SUCCESS) {
        LOGE("vkAllocateMemory failed res=%d\n", res);
        g_real_destroy_image(device, out->image, NULL);
        return res;
    }
    res = g_real_bind_img_mem(device, out->image, out->mem, 0);
    if (res != VK_SUCCESS) {
        LOGE("vkBindImageMemory failed res=%d\n", res);
        g_real_free_mem(device, out->mem, NULL);
        g_real_destroy_image(device, out->image, NULL);
        return res;
    }

    /* Export dmabuf fd (cached for lifetime of image) */
    if (g_real_get_memory_fd) {
        VkMemoryGetFdInfoKHR fd_info = {};
        fd_info.sType = VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR;
        fd_info.memory = out->mem;
        fd_info.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
        int fd = -1;
        res = g_real_get_memory_fd(device, &fd_info, &fd);
        if (res == VK_SUCCESS && fd >= 0) {
            out->dmabuf_fd = fd;
            LOGI("staging image %ux%u stride=%u fd=%d (DAC zero-blit)\n",
                 extent.width, extent.height, out->stride, fd);
        } else {
            LOGE("vkGetMemoryFdKHR failed res=%d\n", res);
        }
    }

    return VK_SUCCESS;
}

static void destroy_staging_image(VkDevice device, staging_image* img) {
    if (img->dmabuf_fd >= 0) { close(img->dmabuf_fd); img->dmabuf_fd = -1; }
    if (img->mem) g_real_free_mem(device, img->mem, NULL);
    if (img->image) g_real_destroy_image(device, img->image, NULL);
    memset(img, 0, sizeof(*img));
    img->dmabuf_fd = -1;
}

/* ========================================================================
 * Hook: vkCreateSwapchainKHR
 * Create real swapchain (for acquire/present cycling) + staging images
 * ======================================================================== */
static VKAPI_ATTR VkResult VKAPI_CALL hook_CreateSwapchainKHR(
    VkDevice device,
    const VkSwapchainCreateInfoKHR* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkSwapchainKHR* pSwapchain)
{
    init();
    if (!g_real_gdpa) return VK_ERROR_INITIALIZATION_FAILED;

    g_device = device;
    resolve_device_functions(device);

    /* Create real swapchain (for vkAcquireNextImageKHR semaphore cycling) */
    PFN_vkCreateSwapchainKHR real_create =
        (PFN_vkCreateSwapchainKHR)g_real_gdpa(device, "vkCreateSwapchainKHR");
    if (!real_create) return VK_ERROR_INITIALIZATION_FAILED;

    VkResult res = real_create(device, pCreateInfo, pAllocator, pSwapchain);
    if (res != VK_SUCCESS) {
        LOGE("real vkCreateSwapchainKHR failed res=%d\n", res);
        return res;
    }

    /* Create staging images — DXVK will render directly into these */
    uint32_t count = pCreateInfo->minImageCount;
    if (count > MAX_SWAPCHAIN_IMAGES) count = MAX_SWAPCHAIN_IMAGES;

    proxy_swapchain* sw = (proxy_swapchain*)calloc(1, sizeof(*sw));
    if (!sw) return VK_ERROR_OUT_OF_HOST_MEMORY;
    sw->real_swapchain = *pSwapchain;
    sw->image_count = count;
    sw->bridge_sock = -1;

    for (uint32_t i = 0; i < count; i++) {
        res = create_staging_image(device, pCreateInfo->imageFormat,
                                   pCreateInfo->imageExtent, &sw->images[i]);
        if (res != VK_SUCCESS) {
            LOGE("staging image %u failed — falling back to real swapchain images\n", i);
            for (uint32_t j = 0; j < i; j++) destroy_staging_image(device, &sw->images[j]);
            free(sw);
            return res; /* DXVK will use real swapchain images (PATH A fallback) */
        }
    }

    /* Register swapchain */
    if (g_swapchain_count < 16) {
        g_swapchains[g_swapchain_count++] = sw;
    }

    LOGI("CreateSwapchainKHR: %ux%u fmt=%d count=%u — %u staging images created (DAC)\n",
         pCreateInfo->imageExtent.width, pCreateInfo->imageExtent.height,
         pCreateInfo->imageFormat, count, count);

    return VK_SUCCESS;
}

/* ========================================================================
 * Hook: vkGetSwapchainImagesKHR
 * Return STAGING images — DXVK renders directly into these (zero-blit)
 * ======================================================================== */
static VKAPI_ATTR VkResult VKAPI_CALL hook_GetSwapchainImagesKHR(
    VkDevice device,
    VkSwapchainKHR swapchain,
    uint32_t* pSwapchainImageCount,
    VkImage* pSwapchainImages)
{
    /* Find our proxy swapchain */
    proxy_swapchain* sw = NULL;
    for (int i = 0; i < g_swapchain_count; i++) {
        if (g_swapchains[i] && g_swapchains[i]->real_swapchain == swapchain) {
            sw = g_swapchains[i];
            break;
        }
    }

    if (!sw) {
        /* Not our swapchain — delegate to real */
        PFN_vkGetSwapchainImagesKHR real =
            (PFN_vkGetSwapchainImagesKHR)g_real_gdpa(device, "vkGetSwapchainImagesKHR");
        return real ? real(device, swapchain, pSwapchainImageCount, pSwapchainImages)
                    : VK_ERROR_INITIALIZATION_FAILED;
    }

    /* Return staging images — DXVK renders into these directly */
    if (!pSwapchainImages || *pSwapchainImageCount < sw->image_count) {
        *pSwapchainImageCount = sw->image_count;
        return pSwapchainImages ? VK_INCOMPLETE : VK_SUCCESS;
    }
    *pSwapchainImageCount = sw->image_count;
    for (uint32_t i = 0; i < sw->image_count; i++)
        pSwapchainImages[i] = sw->images[i].image;

    LOGI("GetSwapchainImagesKHR: returned %u STAGING images (DAC zero-blit)\n",
         sw->image_count);
    return VK_SUCCESS;
}

/* ========================================================================
 * Hook: vkQueuePresentKHR
 * Send dmabuf to bridge (no GPU blit), then call real present
 * ======================================================================== */
static VKAPI_ATTR VkResult VKAPI_CALL hook_QueuePresentKHR(
    VkQueue queue,
    const VkPresentInfoKHR* pPresentInfo)
{
    /* For each swapchain, find our proxy and send dmabuf to bridge */
    for (uint32_t i = 0; i < pPresentInfo->swapchainCount; i++) {
        proxy_swapchain* sw = NULL;
        for (int j = 0; j < g_swapchain_count; j++) {
            if (g_swapchains[j] &&
                g_swapchains[j]->real_swapchain == pPresentInfo->pSwapchains[i]) {
                sw = g_swapchains[j];
                break;
            }
        }
        if (!sw) continue;

        uint32_t idx = pPresentInfo->pImageIndices[i];
        if (idx >= sw->image_count) continue;

        staging_image* img = &sw->images[idx];

        /* Send dmabuf to bridge — NO BLIT, DXVK already rendered into the image */
        if (img->dmabuf_fd >= 0) {
            if (sw->bridge_sock < 0) {
                sw->bridge_sock = bridge_connect(BRIDGE_SOCKET);
                if (sw->bridge_sock >= 0)
                    LOGI("bridge connected sock=%d (DAC zero-blit)\n", sw->bridge_sock);
            }
            if (sw->bridge_sock >= 0) {
                bridge_send_dmabuf(sw->bridge_sock, img->dmabuf_fd,
                                   img->width, img->height, img->drm_format,
                                   img->stride, img->size);
            }
        }

        sw->present_count++;
        if (sw->present_count <= 3 || (sw->present_count % 60) == 0)
            LOGI("DAC present #%llu: %ux%u fd=%d (zero-blit)\n",
                 (unsigned long long)sw->present_count,
                 img->width, img->height, img->dmabuf_fd);
    }

    /* Call real vkQueuePresentKHR to cycle the real swapchain */
    PFN_vkQueuePresentKHR real_present =
        (PFN_vkQueuePresentKHR)g_real_gdpa(g_device, "vkQueuePresentKHR");
    if (real_present) return real_present(queue, pPresentInfo);
    return VK_SUCCESS;
}

/* ========================================================================
 * Exported entry points — DXVK calls these via GetProcAddress
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
    if (strcmp(pName, "vkEnumeratePhysicalDevices") == 0) {
        /* Track physical device for memory type queries */
        PFN_vkEnumeratePhysicalDevices real =
            (PFN_vkEnumeratePhysicalDevices)g_real_gipa(instance, pName);
        return real ? (PFN_vkVoidFunction)real : NULL;
    }

    return g_real_gipa(instance, pName);
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(
    VkDevice device, const char* pName)
{
    init();
    if (!g_real_gdpa) return NULL;

    if (!pName) return NULL;

    if (strcmp(pName, "vkCreateSwapchainKHR") == 0)
        return (PFN_vkVoidFunction)hook_CreateSwapchainKHR;
    if (strcmp(pName, "vkGetSwapchainImagesKHR") == 0)
        return (PFN_vkVoidFunction)hook_GetSwapchainImagesKHR;
    if (strcmp(pName, "vkQueuePresentKHR") == 0)
        return (PFN_vkVoidFunction)hook_QueuePresentKHR;

    return g_real_gdpa(device, pName);
}

/* PE DLL entry point */
BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    switch (fdwReason) {
        case DLL_PROCESS_ATTACH:
            LOGI("vulkan-1.dll PE proxy loaded (DAC zero-blit)\n");
            init();
            break;
        case DLL_PROCESS_DETACH:
            break;
    }
    return TRUE;
}
