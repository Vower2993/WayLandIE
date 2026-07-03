/* WayLandIE dmabuf zero-copy Vulkan layer.
 *
 * ARCHITECTURE (definitive):
 *
 * 1. STANDARD LAYER INTERCEPTION — no dispatch table patching.
 *    layer_get_device_proc_addr / layer_get_instance_proc_addr return our hooks
 *    for vkCreateSwapchainKHR, vkQueuePresentKHR, etc.
 *
 * 2. PASSTHROUGH MODE (WAYLANDIE_DMABUF_LAYER_PASSTHROUGH=1):
 *    Skip watcher, don't touch dispatch table, all 5 hooks delegate to
 *    next layer via fp_gdpa. Use this to isolate whether the layer causes
 *    deadlocks.
 *
 * 3. ACTIVE MODE (default when WAYLANDIE_DMABUF_LAYER_ENABLE=1):
 *    - layer_create_swapchain calls real vkCreateSwapchainKHR to get real swapchain
 *    - DXVK renders to REAL swapchain images (returned via layer_get_swapchain_images)
 *    - For each real image, allocate exportable Image B (LINEAR, TRANSFER_DST,
 *      VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, fallback OPAQUE_FD_BIT)
 *    - layer_acquire_next_image calls real acquire
 *    - layer_queue_present: blit real_image -> Image B, send cached dmabuf fd
 *      to bridge, THEN call real vkQueuePresentKHR so hardware cycles the frame
 *
 * 4. TRUE ZERO-COPY: No memfd_create, no CPU memcpy, no vkMapMemory.
 *    Raw dmabuf fd obtained via vkGetMemoryFdKHR at image creation time,
 *    cached for the lifetime of the image, sent to bridge via SCM_RIGHTS.
 *
 * Copyright 2024 WayLandIE Project */

#define _GNU_SOURCE

/* Platform type stubs (HINSTANCE, HWND, Display, Window) are provided by
 * stub_includes/windows.h and stub_includes/X11/Xlib.h, included by the
 * Khronos vulkan.h when VK_USE_PLATFORM_WIN32_KHR / VK_USE_PLATFORM_XLIB_KHR
 * are defined. See build-waylandie-dmabuf-layer.sh for -I path. */

#include <vulkan/vk_layer.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#ifndef VK_LAYER_EXPORT
#define VK_LAYER_EXPORT __attribute__((visibility("default")))
#endif

#ifndef AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM
#define AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM 5
#endif

/* The official Khronos Vulkan headers are used (installed via libvulkan-dev
 * or cloned from KhronosGroup/Vulkan-Headers in CI). With
 * -DVK_USE_PLATFORM_WIN32_KHR and -DVK_USE_PLATFORM_XLIB_KHR defined at
 * compile time, the standard types VkWin32SurfaceCreateInfoKHR,
 * VkXlibSurfaceCreateInfoKHR, and PFN_vkCreateXlibSurfaceKHR are available
 * directly from <vulkan/vulkan.h>. The HINSTANCE/HWND/Display/Window
 * types are stubbed above so the struct definitions compile. */

#define LOG_TAG "WayLandIE/Layer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define WAYLANDIE_MAX_IMAGES 8
#define WAYLANDIE_BRIDGE_SOCKET "waylandie.display.bridge.v1"

#define DRM_FORMAT_ARGB8888 0x34325241U
#define DRM_FORMAT_ABGR8888 0x34324241U

/* ------------------------------------------------------------------ */
/* Dispatch tables                                                    */
/* ------------------------------------------------------------------ */

typedef struct {
    PFN_vkGetInstanceProcAddr get_instance_proc_addr;
    PFN_vkGetDeviceProcAddr get_device_proc_addr;
    PFN_vkCreateInstance create_instance;
    PFN_vkDestroyInstance destroy_instance;
    PFN_vkCreateDevice create_device;
    PFN_vkDestroyDevice destroy_device;
    PFN_vkGetPhysicalDeviceMemoryProperties get_phys_mem_props;
} instance_dispatch;

typedef struct {
    PFN_vkGetDeviceProcAddr get_device_proc_addr;
    PFN_vkDestroyDevice destroy_device;
    PFN_vkGetDeviceQueue get_device_queue;
    PFN_vkCreateCommandPool create_cmd_pool;
    PFN_vkDestroyCommandPool destroy_cmd_pool;
    PFN_vkAllocateCommandBuffers alloc_cmd_bufs;
    PFN_vkFreeCommandBuffers free_cmd_bufs;
    PFN_vkBeginCommandBuffer begin_cmd;
    PFN_vkEndCommandBuffer end_cmd;
    PFN_vkQueueSubmit queue_submit;
    PFN_vkQueueWaitIdle queue_wait_idle;
    PFN_vkCreateFence create_fence;
    PFN_vkDestroyFence destroy_fence;
    PFN_vkWaitForFences wait_fences;
    PFN_vkResetFences reset_fences;
    PFN_vkCreateImage create_image;
    PFN_vkDestroyImage destroy_image;
    PFN_vkAllocateMemory alloc_mem;
    PFN_vkFreeMemory free_mem;
    PFN_vkBindImageMemory bind_img_mem;
    PFN_vkGetImageMemoryRequirements2 get_img_mem_reqs2;
    PFN_vkGetImageSubresourceLayout get_subres_layout;
    PFN_vkCmdCopyImage cmd_copy_image;
    PFN_vkCmdPipelineBarrier cmd_pipeline_barrier;
    /* External memory fd export (VK_KHR_external_memory_fd) */
    PFN_vkGetMemoryFdKHR get_memory_fd;
    /* Real (down-chain) swapchain functions, resolved lazily via fp_gdpa */
    PFN_vkCreateSwapchainKHR real_create_swapchain;
    PFN_vkDestroySwapchainKHR real_destroy_swapchain;
    PFN_vkGetSwapchainImagesKHR real_get_images;
    PFN_vkAcquireNextImageKHR real_acquire;
    PFN_vkQueuePresentKHR real_present;
} device_dispatch;

typedef struct instance_data {
    instance_dispatch vtable;
    VkInstance instance;
    VkPhysicalDevice physical_device;
    struct instance_data *next;
} instance_data;

typedef struct device_data {
    device_dispatch vtable;
    VkDevice device;
    VkPhysicalDevice physical_device;
    VkQueue graphics_queue;
    uint32_t queue_family;
    instance_data *inst_data;
    struct device_data *next;
} device_data;

/* Per-real-swapchain-image staging resource (Image B — exportable).
 * The REAL swapchain image serves as Image A (DXVK renders to it). */
typedef struct {
    VkImage staging_img;       /* Image B — LINEAR, TRANSFER_DST, exportable */
    VkDeviceMemory staging_mem;
    uint32_t stride;
    uint64_t staging_size;
    uint32_t width, height;
    uint32_t drm_format;
    int dmabuf_fd;             /* Cached fd from vkGetMemoryFdKHR */
    VkExternalMemoryHandleTypeFlagBits handle_type;
    bool in_use;
} swapchain_image;

typedef struct swapchain_data {
    device_data *dev_data;
    VkSwapchainKHR real_swapchain;     /* The actual hardware swapchain */
    uint32_t image_count;
    VkImage real_images[WAYLANDIE_MAX_IMAGES];  /* Real swapchain images (Image A) */
    swapchain_image images[WAYLANDIE_MAX_IMAGES]; /* Per-image Image B staging */
    VkFormat format;
    VkExtent2D extent;
    VkCommandPool cmd_pool;
    VkCommandBuffer blit_cmd;
    VkFence blit_fence;
    int bridge_sock;
    uint64_t present_count;
    struct swapchain_data *next;
} swapchain_data;

/* Forward declarations */
static VkResult layer_create_swapchain(VkDevice, const VkSwapchainCreateInfoKHR *,
                                       const VkAllocationCallbacks *, VkSwapchainKHR *);
static void layer_destroy_swapchain(VkDevice, VkSwapchainKHR, const VkAllocationCallbacks *);
static VkResult layer_get_swapchain_images(VkDevice, VkSwapchainKHR, uint32_t *, VkImage *);
static VkResult layer_acquire_next_image(VkDevice, VkSwapchainKHR, uint64_t,
                                         VkSemaphore, VkFence, uint32_t *);
static VkResult layer_queue_present(VkQueue, const VkPresentInfoKHR *);
static void ensure_device_vtable(device_data *data);

/* ------------------------------------------------------------------ */
/* Globals                                                            */
/* ------------------------------------------------------------------ */

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static instance_data *g_instances = NULL;
static device_data *g_devices = NULL;
static swapchain_data *g_swapchains = NULL;
static atomic_int g_enabled = 0;

/* ------------------------------------------------------------------ */
/* Helpers                                                            */
/* ------------------------------------------------------------------ */

static int is_enabled(void) {
    if (atomic_load(&g_enabled) == 0) {
        const char *env = getenv("WAYLANDIE_DMABUF_LAYER_ENABLE");
        if (env && env[0] == '1') {
            atomic_store(&g_enabled, 1);
            LOGI("WayLandIE dmabuf layer ENABLED");
        } else {
            atomic_store(&g_enabled, -1);
        }
    }
    return atomic_load(&g_enabled) == 1;
}

static int is_passthrough(void) {
    const char *env = getenv("WAYLANDIE_DMABUF_LAYER_PASSTHROUGH");
    return env && env[0] == '1';
}

static uint32_t vk_format_to_drm(VkFormat fmt) {
    switch (fmt) {
        case VK_FORMAT_B8G8R8A8_UNORM:
        case VK_FORMAT_B8G8R8A8_SRGB:
            return DRM_FORMAT_ARGB8888;
        case VK_FORMAT_R8G8B8A8_UNORM:
        case VK_FORMAT_R8G8B8A8_SRGB:
            return DRM_FORMAT_ABGR8888;
        default:
            return DRM_FORMAT_ARGB8888;
    }
}

static instance_data *find_instance(VkInstance inst) {
    pthread_mutex_lock(&g_lock);
    for (instance_data *d = g_instances; d; d = d->next)
        if (d->instance == inst) { pthread_mutex_unlock(&g_lock); return d; }
    pthread_mutex_unlock(&g_lock);
    return NULL;
}

static device_data *find_device(VkDevice dev) {
    pthread_mutex_lock(&g_lock);
    for (device_data *d = g_devices; d; d = d->next)
        if (d->device == dev) { pthread_mutex_unlock(&g_lock); return d; }
    pthread_mutex_unlock(&g_lock);
    return NULL;
}

static swapchain_data *find_swapchain(VkSwapchainKHR sw) {
    pthread_mutex_lock(&g_lock);
    for (swapchain_data *s = g_swapchains; s; s = s->next)
        if ((VkSwapchainKHR)(uintptr_t)s == sw) {
            pthread_mutex_unlock(&g_lock);
            return s;
        }
    pthread_mutex_unlock(&g_lock);
    return NULL;
}

/* Resolve a real (down-chain) function pointer via the next layer's GDPA. */
static PFN_vkVoidFunction resolve_real(VkDevice device, device_data *dd, const char *name) {
    if (!dd || !dd->vtable.get_device_proc_addr || !device) return NULL;
    return dd->vtable.get_device_proc_addr(device, name);
}

/* Lazily resolve the real swapchain + external-memory function pointers.
 * Called from layer_create_swapchain and layer_queue_present. */
static void ensure_swapchain_vtable(VkDevice device, device_data *dd) {
    if (!dd) return;
    if (!dd->vtable.real_create_swapchain)
        dd->vtable.real_create_swapchain = (PFN_vkCreateSwapchainKHR)resolve_real(device, dd, "vkCreateSwapchainKHR");
    if (!dd->vtable.real_destroy_swapchain)
        dd->vtable.real_destroy_swapchain = (PFN_vkDestroySwapchainKHR)resolve_real(device, dd, "vkDestroySwapchainKHR");
    if (!dd->vtable.real_get_images)
        dd->vtable.real_get_images = (PFN_vkGetSwapchainImagesKHR)resolve_real(device, dd, "vkGetSwapchainImagesKHR");
    if (!dd->vtable.real_acquire)
        dd->vtable.real_acquire = (PFN_vkAcquireNextImageKHR)resolve_real(device, dd, "vkAcquireNextImageKHR");
    if (!dd->vtable.real_present)
        dd->vtable.real_present = (PFN_vkQueuePresentKHR)resolve_real(device, dd, "vkQueuePresentKHR");
    if (!dd->vtable.get_memory_fd)
        dd->vtable.get_memory_fd = (PFN_vkGetMemoryFdKHR)resolve_real(device, dd, "vkGetMemoryFdKHR");
}

/* ------------------------------------------------------------------ */
/* Bridge socket                                                      */
/* ------------------------------------------------------------------ */

static int bridge_connect(const char *name) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    size_t len = strlen(name);
    if (len + 1 > sizeof(addr.sun_path)) { close(fd); return -1; }
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, name, len);
    if (connect(fd, (struct sockaddr *)&addr,
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
        "format=%u modifier=0x0000000000000000 planes=1 stride0=%u offset0=0 "
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
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    if (!cmsg) return -1;
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &fd, sizeof(int));
    msg.msg_controllen = cmsg->cmsg_len;

    if (sendmsg(sock, &msg, MSG_NOSIGNAL) < 0) return -1;

    char resp[256];
    struct timeval tv = { .tv_sec = 1, .tv_usec = 0 };
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    recv(sock, resp, sizeof(resp) - 1, 0);
    return 0;
}

/* ------------------------------------------------------------------ */
/* Exportable Image B creation (LINEAR, TRANSFER_DST, dmabuf-exportable) */
/* ------------------------------------------------------------------ */

static VkResult create_exportable_staging(device_data *dd, VkFormat fmt,
                                          VkExtent2D extent,
                                          VkExternalMemoryHandleTypeFlagBits handle_type,
                                          swapchain_image *out) {
    memset(out, 0, sizeof(*out));
    out->dmabuf_fd = -1;
    out->handle_type = handle_type;
    VkResult res;

    /* Image B: LINEAR, TRANSFER_DST, with external memory handle type */
    VkExternalMemoryImageCreateInfo ext_img_info = {};
    ext_img_info.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    ext_img_info.handleTypes = handle_type;

    VkImageCreateInfo b_info = {};
    b_info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    b_info.pNext = &ext_img_info;
    b_info.imageType = VK_IMAGE_TYPE_2D;
    b_info.format = fmt;
    b_info.extent.width = extent.width;
    b_info.extent.height = extent.height;
    b_info.extent.depth = 1;
    b_info.mipLevels = 1;
    b_info.arrayLayers = 1;
    b_info.samples = VK_SAMPLE_COUNT_1_BIT;
    b_info.tiling = VK_IMAGE_TILING_LINEAR;
    b_info.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    b_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    res = dd->vtable.create_image(dd->device, &b_info, NULL, &out->staging_img);
    if (res != VK_SUCCESS) {
        LOGE("vkCreateImage B failed res=%d handleType=0x%x", res, handle_type);
        return res;
    }

    /* Get memory requirements */
    VkMemoryRequirements2 b_reqs = {};
    b_reqs.sType = VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2;
    VkImageMemoryRequirementsInfo2 req_info = {};
    req_info.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_REQUIREMENTS_INFO_2;
    req_info.image = out->staging_img;
    dd->vtable.get_img_mem_reqs2(dd->device, &req_info, &b_reqs);

    /* Query stride from staging image (LINEAR only) */
    VkImageSubresource subres = {};
    subres.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    VkSubresourceLayout layout = {};
    dd->vtable.get_subres_layout(dd->device, out->staging_img, &subres, &layout);
    out->stride = (uint32_t)layout.rowPitch;
    out->staging_size = layout.size;
    out->width = extent.width;
    out->height = extent.height;
    out->drm_format = vk_format_to_drm(fmt);

    LOGI("staging layout: stride=%u size=%llu handleType=0x%x",
         out->stride, (unsigned long long)layout.size, handle_type);

    /* Get memory properties */
    VkPhysicalDeviceMemoryProperties mem_props;
    dd->inst_data->vtable.get_phys_mem_props(dd->physical_device, &mem_props);

    /* Allocate exportable memory — prefer HOST_VISIBLE | HOST_COHERENT (Turnip
     * requires this for LINEAR images), fall back to any matching type. */
    uint32_t b_type = 0;
    bool b_found = false;
    for (uint32_t i = 0; i < mem_props.memoryTypeCount; i++) {
        if ((b_reqs.memoryRequirements.memoryTypeBits & (1u << i)) &&
            (mem_props.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) &&
            (mem_props.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
            b_type = i; b_found = true; break;
        }
    }
    if (!b_found) {
        for (uint32_t i = 0; i < mem_props.memoryTypeCount; i++) {
            if (b_reqs.memoryRequirements.memoryTypeBits & (1u << i)) {
                b_type = i; b_found = true; break;
            }
        }
    }
    if (!b_found) {
        LOGE("no suitable memory type for staging image");
        res = VK_ERROR_OUT_OF_DEVICE_MEMORY;
        goto err_img;
    }
    LOGI("staging memory type %u: flags=0x%x", b_type,
         mem_props.memoryTypes[b_type].propertyFlags);

    VkExportMemoryAllocateInfo export_mem_info = {};
    export_mem_info.sType = VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO;
    export_mem_info.handleTypes = handle_type;

    VkMemoryAllocateInfo b_alloc = {};
    b_alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    b_alloc.pNext = &export_mem_info;
    b_alloc.allocationSize = b_reqs.memoryRequirements.size;
    b_alloc.memoryTypeIndex = b_type;
    res = dd->vtable.alloc_mem(dd->device, &b_alloc, NULL, &out->staging_mem);
    if (res != VK_SUCCESS) {
        LOGE("alloc B failed res=%d handleType=0x%x", res, handle_type);
        goto err_img;
    }
    res = dd->vtable.bind_img_mem(dd->device, out->staging_img, out->staging_mem, 0);
    if (res != VK_SUCCESS) {
        LOGE("bind B failed res=%d", res);
        goto err_mem;
    }

    /* Export dmabuf fd via vkGetMemoryFdKHR — cached for the lifetime of the
     * image. The kernel does NOT consume the sender's fd on sendmsg(SCM_RIGHTS),
     * so we can reuse the same fd for every frame. */
    if (dd->vtable.get_memory_fd) {
        VkMemoryGetFdInfoKHR fd_info = {};
        fd_info.sType = VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR;
        fd_info.memory = out->staging_mem;
        fd_info.handleType = handle_type;
        int fd = -1;
        res = dd->vtable.get_memory_fd(dd->device, &fd_info, &fd);
        if (res != VK_SUCCESS || fd < 0) {
            LOGE("vkGetMemoryFdKHR failed res=%d handleType=0x%x", res, handle_type);
            out->dmabuf_fd = -1;
            /* Non-fatal: bridge won't receive frames, but real present still works */
        } else {
            out->dmabuf_fd = fd;
            LOGI("exported dmabuf fd=%d handleType=0x%x", fd, handle_type);
        }
    } else {
        LOGE("vkGetMemoryFdKHR not available — bridge will not receive frames");
        out->dmabuf_fd = -1;
    }

    out->in_use = false;
    LOGI("created exportable staging %ux%u drm=0x%08x stride=%u fd=%d",
         extent.width, extent.height, out->drm_format, out->stride, out->dmabuf_fd);
    return VK_SUCCESS;

err_mem:
    dd->vtable.free_mem(dd->device, out->staging_mem, NULL);
    out->staging_mem = VK_NULL_HANDLE;
err_img:
    dd->vtable.destroy_image(dd->device, out->staging_img, NULL);
    out->staging_img = VK_NULL_HANDLE;
    return res;
}

static void destroy_swapchain_image(device_data *dd, swapchain_image *img) {
    if (img->dmabuf_fd >= 0) { close(img->dmabuf_fd); img->dmabuf_fd = -1; }
    if (img->staging_mem) dd->vtable.free_mem(dd->device, img->staging_mem, NULL);
    if (img->staging_img) dd->vtable.destroy_image(dd->device, img->staging_img, NULL);
    memset(img, 0, sizeof(*img));
    img->dmabuf_fd = -1;
}

/* ------------------------------------------------------------------ */
/* Layer hooks                                                        */
/* ------------------------------------------------------------------ */

VkResult layer_create_swapchain(VkDevice device, const VkSwapchainCreateInfoKHR *info,
                                const VkAllocationCallbacks *alloc, VkSwapchainKHR *ret) {
    device_data *dd = find_device(device);
    if (dd) ensure_device_vtable(dd);
    if (dd) ensure_swapchain_vtable(device, dd);

    /* Passthrough or disabled: delegate to real create_swapchain.
     * Return the REAL swapchain handle directly (no wrapping). */
    if (!dd || !is_enabled() || is_passthrough()) {
        if (dd && dd->vtable.real_create_swapchain)
            return dd->vtable.real_create_swapchain(device, info, alloc, ret);
        LOGE("create_swapchain: no real_create_swapchain (passthrough/degraded)");
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    LOGI("create_swapchain: %ux%u fmt=%d count=%u",
         info->imageExtent.width, info->imageExtent.height,
         info->imageFormat, info->minImageCount);

    /* Step 1: Call real vkCreateSwapchainKHR to create the real hardware swapchain */
    VkSwapchainKHR real_swapchain = VK_NULL_HANDLE;
    VkResult res = dd->vtable.real_create_swapchain(device, info, alloc, &real_swapchain);
    if (res != VK_SUCCESS) {
        LOGE("real_create_swapchain failed res=%d", res);
        return res;
    }

    /* Step 2: Get real swapchain images (DXVK will render to these) */
    uint32_t count = 0;
    res = dd->vtable.real_get_images(device, real_swapchain, &count, NULL);
    if (res != VK_SUCCESS || count == 0) {
        LOGE("real_get_images (count) failed res=%d count=%u", res, count);
        dd->vtable.real_destroy_swapchain(device, real_swapchain, alloc);
        return res != VK_SUCCESS ? res : VK_ERROR_INITIALIZATION_FAILED;
    }
    if (count > WAYLANDIE_MAX_IMAGES) count = WAYLANDIE_MAX_IMAGES;

    VkImage real_imgs[WAYLANDIE_MAX_IMAGES];
    for (uint32_t i = 0; i < WAYLANDIE_MAX_IMAGES; i++) real_imgs[i] = VK_NULL_HANDLE;
    res = dd->vtable.real_get_images(device, real_swapchain, &count, real_imgs);
    if (res != VK_SUCCESS) {
        LOGE("real_get_images failed res=%d", res);
        dd->vtable.real_destroy_swapchain(device, real_swapchain, alloc);
        return res;
    }

    LOGI("real swapchain created: %u images handle=%p", count, (void *)real_swapchain);

    /* Step 3: Allocate swapchain_data */
    swapchain_data *sw = (swapchain_data *)calloc(1, sizeof(*sw));
    if (!sw) {
        dd->vtable.real_destroy_swapchain(device, real_swapchain, alloc);
        return VK_ERROR_OUT_OF_HOST_MEMORY;
    }
    sw->dev_data = dd;
    sw->real_swapchain = real_swapchain;
    sw->image_count = count;
    sw->format = info->imageFormat;
    sw->extent = info->imageExtent;
    sw->bridge_sock = -1;
    for (uint32_t i = 0; i < count; i++) sw->real_images[i] = real_imgs[i];

    /* Step 4: Create exportable Image B per real image.
     * Try VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT first.
     * If that fails, fall back to VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT. */
    for (uint32_t i = 0; i < count; i++) {
        res = create_exportable_staging(dd, sw->format, sw->extent,
                                         VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
                                         &sw->images[i]);
        if (res != VK_SUCCESS) {
            LOGW("DMA_BUF_BIT_EXT failed (image %u, res=%d), trying OPAQUE_FD_BIT", i, res);
            res = create_exportable_staging(dd, sw->format, sw->extent,
                                             VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT,
                                             &sw->images[i]);
        }
        if (res != VK_SUCCESS) {
            LOGE("staging image %u creation failed (both handle types)", i);
            goto fail;
        }
    }

    /* Step 5: Create command pool + blit command buffer + fence */
    VkCommandPoolCreateInfo pool_info = {};
    pool_info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pool_info.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pool_info.queueFamilyIndex = dd->queue_family;
    res = dd->vtable.create_cmd_pool(dd->device, &pool_info, NULL, &sw->cmd_pool);
    if (res != VK_SUCCESS) { LOGE("create_cmd_pool res=%d", res); goto fail; }

    VkCommandBufferAllocateInfo cmd_info = {};
    cmd_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmd_info.commandPool = sw->cmd_pool;
    cmd_info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmd_info.commandBufferCount = 1;
    res = dd->vtable.alloc_cmd_bufs(dd->device, &cmd_info, &sw->blit_cmd);
    if (res != VK_SUCCESS) { LOGE("alloc_cmd res=%d", res); goto fail; }

    VkFenceCreateInfo fence_info = {};
    fence_info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    res = dd->vtable.create_fence(dd->device, &fence_info, NULL, &sw->blit_fence);
    if (res != VK_SUCCESS) { LOGE("create_fence res=%d", res); goto fail; }

    /* Step 6: Register in global list */
    pthread_mutex_lock(&g_lock);
    sw->next = g_swapchains;
    g_swapchains = sw;
    pthread_mutex_unlock(&g_lock);

    *ret = (VkSwapchainKHR)(uintptr_t)sw;
    LOGI("create_swapchain: success real=%p staging=%u", (void *)real_swapchain, count);
    return VK_SUCCESS;

fail:
    for (uint32_t i = 0; i < count; i++) destroy_swapchain_image(dd, &sw->images[i]);
    if (sw->blit_fence) dd->vtable.destroy_fence(dd->device, sw->blit_fence, NULL);
    if (sw->blit_cmd) dd->vtable.free_cmd_bufs(dd->device, sw->cmd_pool, 1, &sw->blit_cmd);
    if (sw->cmd_pool) dd->vtable.destroy_cmd_pool(dd->device, sw->cmd_pool, NULL);
    dd->vtable.real_destroy_swapchain(device, real_swapchain, alloc);
    free(sw);
    return res;
}

void layer_destroy_swapchain(VkDevice device, VkSwapchainKHR sw, const VkAllocationCallbacks *alloc) {
    if (!sw) return;
    swapchain_data *s = find_swapchain(sw);
    if (!s) {
        /* Not our swapchain — delegate to real */
        device_data *dd = find_device(device);
        if (dd) {
            ensure_swapchain_vtable(device, dd);
            if (dd->vtable.real_destroy_swapchain)
                dd->vtable.real_destroy_swapchain(device, sw, alloc);
        }
        return;
    }

    device_data *dd = s->dev_data;
    LOGI("destroy_swapchain: presents=%llu", (unsigned long long)s->present_count);

    pthread_mutex_lock(&g_lock);
    swapchain_data **pp = &g_swapchains;
    while (*pp) { if (*pp == s) { *pp = s->next; break; } pp = &(*pp)->next; }
    pthread_mutex_unlock(&g_lock);

    for (uint32_t i = 0; i < s->image_count; i++) destroy_swapchain_image(dd, &s->images[i]);
    if (s->blit_fence) dd->vtable.destroy_fence(dd->device, s->blit_fence, NULL);
    if (s->blit_cmd) dd->vtable.free_cmd_bufs(dd->device, s->cmd_pool, 1, &s->blit_cmd);
    if (s->cmd_pool) dd->vtable.destroy_cmd_pool(dd->device, s->cmd_pool, NULL);
    if (s->bridge_sock >= 0) close(s->bridge_sock);

    /* Destroy real swapchain */
    if (dd->vtable.real_destroy_swapchain)
        dd->vtable.real_destroy_swapchain(device, s->real_swapchain, alloc);

    free(s);
}

VkResult layer_get_swapchain_images(VkDevice device, VkSwapchainKHR sw,
                                    uint32_t *count, VkImage *images) {
    swapchain_data *s = find_swapchain(sw);
    if (!s) {
        device_data *dd = find_device(device);
        if (dd) {
            ensure_swapchain_vtable(device, dd);
            if (dd->vtable.real_get_images)
                return dd->vtable.real_get_images(device, sw, count, images);
        }
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    /* Return REAL swapchain images — DXVK renders to these (Image A = real image) */
    if (!images || *count < s->image_count) {
        *count = s->image_count;
        return images ? VK_INCOMPLETE : VK_SUCCESS;
    }
    *count = s->image_count;
    for (uint32_t i = 0; i < s->image_count; i++)
        images[i] = s->real_images[i];
    return VK_SUCCESS;
}

VkResult layer_acquire_next_image(VkDevice device, VkSwapchainKHR sw,
                                  uint64_t timeout, VkSemaphore sem,
                                  VkFence fence, uint32_t *idx) {
    swapchain_data *s = find_swapchain(sw);
    if (!s) {
        device_data *dd = find_device(device);
        if (dd) {
            ensure_swapchain_vtable(device, dd);
            if (dd->vtable.real_acquire)
                return dd->vtable.real_acquire(device, sw, timeout, sem, fence, idx);
        }
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    /* Delegate to real acquire — real image index == our index */
    return s->dev_data->vtable.real_acquire(device, s->real_swapchain, timeout, sem, fence, idx);
}

VkResult layer_queue_present(VkQueue queue, const VkPresentInfoKHR *info) {
    pthread_mutex_lock(&g_lock);
    device_data *dd = g_devices;
    pthread_mutex_unlock(&g_lock);

    /* Passthrough or disabled: resolve real present and delegate */
    if (!dd || !is_enabled() || is_passthrough()) {
        if (dd) {
            ensure_swapchain_vtable(dd->device, dd);
            if (dd->vtable.real_present)
                return dd->vtable.real_present(queue, info);
        }
        LOGE("present: no real_present available (passthrough/degraded)");
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    ensure_swapchain_vtable(dd->device, dd);
    if (!dd->vtable.real_present) {
        LOGE("present: real_present not resolvable");
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    /* Active mode: for each layer swapchain, do the blit + bridge send.
     * Then call real present with real swapchain handles. */
    bool any_layer_swapchain = false;
    bool semaphores_consumed = false;

    for (uint32_t i = 0; i < info->swapchainCount; i++) {
        swapchain_data *s = find_swapchain(info->pSwapchains[i]);
        if (!s) continue;
        any_layer_swapchain = true;
        uint32_t idx = info->pImageIndices[i];
        if (idx >= s->image_count) continue;

        swapchain_image *img = &s->images[idx];
        VkImage real_img = s->real_images[idx];

        /* Wait for DXVK's rendering semaphores (only once per present call,
         * since semaphores are global to the present info, not per-swapchain).
         * We consume them here via a no-op submit that waits on them. */
        if (info->waitSemaphoreCount > 0 && !semaphores_consumed) {
            VkPipelineStageFlags wait = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            VkSubmitInfo wsi = {};
            wsi.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            wsi.waitSemaphoreCount = info->waitSemaphoreCount;
            wsi.pWaitSemaphores = info->pWaitSemaphores;
            wsi.pWaitDstStageMask = &wait;
            dd->vtable.reset_fences(dd->device, 1, &s->blit_fence);
            dd->vtable.queue_submit(queue, 1, &wsi, s->blit_fence);
            dd->vtable.wait_fences(dd->device, 1, &s->blit_fence, VK_TRUE, 5000000000ULL);
            semaphores_consumed = true;
        }

        /* Blit real_image (Image A) -> Image B (OPTIMAL -> LINEAR) */
        VkCommandBufferBeginInfo bi = {};
        bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        dd->vtable.begin_cmd(s->blit_cmd, &bi);

        /* Transition real image: PRESENT_SRC -> TRANSFER_SRC_OPTIMAL */
        VkImageMemoryBarrier bar_a = {};
        bar_a.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        bar_a.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
        bar_a.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        bar_a.oldLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        bar_a.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        bar_a.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bar_a.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bar_a.image = real_img;
        bar_a.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        bar_a.subresourceRange.levelCount = 1;
        bar_a.subresourceRange.layerCount = 1;

        /* Transition staging image: UNDEFINED -> TRANSFER_DST_OPTIMAL */
        VkImageMemoryBarrier bar_b = {};
        bar_b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        bar_b.srcAccessMask = 0;
        bar_b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        bar_b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        bar_b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        bar_b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bar_b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bar_b.image = img->staging_img;
        bar_b.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        bar_b.subresourceRange.levelCount = 1;
        bar_b.subresourceRange.layerCount = 1;

        VkImageMemoryBarrier bars[2];
        bars[0] = bar_a;
        bars[1] = bar_b;
        dd->vtable.cmd_pipeline_barrier(s->blit_cmd,
                                        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                                        VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                                        0, NULL, 0, NULL, 2, bars);

        /* Copy real_image -> Image B */
        VkImageCopy copy = {};
        copy.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copy.srcSubresource.layerCount = 1;
        copy.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copy.dstSubresource.layerCount = 1;
        copy.extent.width = img->width;
        copy.extent.height = img->height;
        copy.extent.depth = 1;
        dd->vtable.cmd_copy_image(s->blit_cmd, real_img,
                                  VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                  img->staging_img,
                                  VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);

        /* Transition real image back: TRANSFER_SRC -> PRESENT_SRC (for real present) */
        VkImageMemoryBarrier bar_a_back = {};
        bar_a_back.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        bar_a_back.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        bar_a_back.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT;
        bar_a_back.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        bar_a_back.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        bar_a_back.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bar_a_back.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bar_a_back.image = real_img;
        bar_a_back.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        bar_a_back.subresourceRange.levelCount = 1;
        bar_a_back.subresourceRange.layerCount = 1;

        dd->vtable.cmd_pipeline_barrier(s->blit_cmd,
                                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
                                        0, NULL, 0, NULL, 1, &bar_a_back);

        dd->vtable.end_cmd(s->blit_cmd);

        VkSubmitInfo si = {};
        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.commandBufferCount = 1;
        si.pCommandBuffers = &s->blit_cmd;
        dd->vtable.reset_fences(dd->device, 1, &s->blit_fence);
        dd->vtable.queue_submit(queue, 1, &si, s->blit_fence);
        dd->vtable.wait_fences(dd->device, 1, &s->blit_fence, VK_TRUE, 5000000000ULL);

        /* Send cached dmabuf fd to bridge */
        if (img->dmabuf_fd >= 0) {
            if (s->bridge_sock < 0) {
                s->bridge_sock = bridge_connect(WAYLANDIE_BRIDGE_SOCKET);
                if (s->bridge_sock >= 0)
                    LOGI("bridge connected sock=%d", s->bridge_sock);
            }
            if (s->bridge_sock >= 0) {
                bridge_send_dmabuf(s->bridge_sock, img->dmabuf_fd,
                                   img->width, img->height, img->drm_format,
                                   img->stride, img->staging_size);
            }
        }

        s->present_count++;
        if (s->present_count <= 3 || (s->present_count % 60) == 0)
            LOGI("present #%llu: %ux%u stride=%u fd=%d",
                 (unsigned long long)s->present_count,
                 img->width, img->height, img->stride, img->dmabuf_fd);
    }

    /* Call real vkQueuePresentKHR with real swapchain handles.
     * If we consumed the wait semaphores above, pass 0 to real present
     * (our blit fence already ensured GPU ordering on this queue). */
    if (any_layer_swapchain) {
        VkSwapchainKHR real_swapchains[WAYLANDIE_MAX_IMAGES];
        for (uint32_t i = 0; i < info->swapchainCount && i < WAYLANDIE_MAX_IMAGES; i++) {
            swapchain_data *s = find_swapchain(info->pSwapchains[i]);
            real_swapchains[i] = s ? s->real_swapchain : info->pSwapchains[i];
        }
        VkPresentInfoKHR real_info = *info;
        real_info.pSwapchains = real_swapchains;
        /* pImageIndices stays the same — real index == our index */
        if (semaphores_consumed) {
            real_info.waitSemaphoreCount = 0;
            real_info.pWaitSemaphores = NULL;
        }
        return dd->vtable.real_present(queue, &real_info);
    }

    /* No layer swapchains — just delegate to real present as-is */
    return dd->vtable.real_present(queue, info);
}

/* ------------------------------------------------------------------ */
/* Device vtable resolution                                          */
/* ------------------------------------------------------------------ */

static void ensure_device_vtable(device_data *data) {
    if (data->vtable.destroy_device) return;
    PFN_vkGetDeviceProcAddr fp = data->vtable.get_device_proc_addr;
    if (!fp || !data->device) return;

    data->vtable.destroy_device = (PFN_vkDestroyDevice)fp(data->device, "vkDestroyDevice");
    data->vtable.get_device_queue = (PFN_vkGetDeviceQueue)fp(data->device, "vkGetDeviceQueue");
    data->vtable.create_cmd_pool = (PFN_vkCreateCommandPool)fp(data->device, "vkCreateCommandPool");
    data->vtable.destroy_cmd_pool = (PFN_vkDestroyCommandPool)fp(data->device, "vkDestroyCommandPool");
    data->vtable.alloc_cmd_bufs = (PFN_vkAllocateCommandBuffers)fp(data->device, "vkAllocateCommandBuffers");
    data->vtable.free_cmd_bufs = (PFN_vkFreeCommandBuffers)fp(data->device, "vkFreeCommandBuffers");
    data->vtable.begin_cmd = (PFN_vkBeginCommandBuffer)fp(data->device, "vkBeginCommandBuffer");
    data->vtable.end_cmd = (PFN_vkEndCommandBuffer)fp(data->device, "vkEndCommandBuffer");
    data->vtable.queue_submit = (PFN_vkQueueSubmit)fp(data->device, "vkQueueSubmit");
    data->vtable.queue_wait_idle = (PFN_vkQueueWaitIdle)fp(data->device, "vkQueueWaitIdle");
    data->vtable.create_fence = (PFN_vkCreateFence)fp(data->device, "vkCreateFence");
    data->vtable.destroy_fence = (PFN_vkDestroyFence)fp(data->device, "vkDestroyFence");
    data->vtable.wait_fences = (PFN_vkWaitForFences)fp(data->device, "vkWaitForFences");
    data->vtable.reset_fences = (PFN_vkResetFences)fp(data->device, "vkResetFences");
    data->vtable.create_image = (PFN_vkCreateImage)fp(data->device, "vkCreateImage");
    data->vtable.destroy_image = (PFN_vkDestroyImage)fp(data->device, "vkDestroyImage");
    data->vtable.alloc_mem = (PFN_vkAllocateMemory)fp(data->device, "vkAllocateMemory");
    data->vtable.free_mem = (PFN_vkFreeMemory)fp(data->device, "vkFreeMemory");
    data->vtable.bind_img_mem = (PFN_vkBindImageMemory)fp(data->device, "vkBindImageMemory");
    data->vtable.get_img_mem_reqs2 = (PFN_vkGetImageMemoryRequirements2)fp(data->device, "vkGetImageMemoryRequirements2");
    data->vtable.get_subres_layout = (PFN_vkGetImageSubresourceLayout)fp(data->device, "vkGetImageSubresourceLayout");
    data->vtable.cmd_copy_image = (PFN_vkCmdCopyImage)fp(data->device, "vkCmdCopyImage");
    data->vtable.cmd_pipeline_barrier = (PFN_vkCmdPipelineBarrier)fp(data->device, "vkCmdPipelineBarrier");

    if (data->graphics_queue == VK_NULL_HANDLE && data->queue_family < UINT32_MAX)
        data->vtable.get_device_queue(data->device, data->queue_family, 0, &data->graphics_queue);

    LOGI("ensure_device_vtable: device=%p queue=%p", (void *)data->device, (void *)data->graphics_queue);
}

/* ------------------------------------------------------------------ */
/* Layer create/destroy instance & device                             */
/* ------------------------------------------------------------------ */

static VkResult layer_create_instance(const VkInstanceCreateInfo *ci,
                                      const VkAllocationCallbacks *alloc, VkInstance *inst) {
    is_enabled();

    PFN_vkGetInstanceProcAddr fp_gipa = NULL;
    PFN_vkCreateInstance fp_create = NULL;
    const VkLayerInstanceCreateInfo *li = (const VkLayerInstanceCreateInfo *)ci->pNext;
    while (li) {
        if (li->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO &&
            li->function == VK_LAYER_LINK_INFO) {
            fp_gipa = li->u.pLayerInfo->pfnNextGetInstanceProcAddr;
            fp_create = (PFN_vkCreateInstance)fp_gipa(VK_NULL_HANDLE, "vkCreateInstance");
            break;
        }
        li = (const VkLayerInstanceCreateInfo *)li->pNext;
    }
    if (!fp_create || !fp_gipa) return VK_ERROR_INITIALIZATION_FAILED;

    VkResult res = fp_create(ci, alloc, inst);
    if (res != VK_SUCCESS) return res;

    instance_data *data = (instance_data *)calloc(1, sizeof(*data));
    data->instance = *inst;
    data->vtable.get_instance_proc_addr = fp_gipa;
    data->vtable.create_instance = fp_create;
    data->vtable.destroy_instance = (PFN_vkDestroyInstance)fp_gipa(*inst, "vkDestroyInstance");
    data->vtable.get_phys_mem_props = (PFN_vkGetPhysicalDeviceMemoryProperties)
        fp_gipa(*inst, "vkGetPhysicalDeviceMemoryProperties");

    pthread_mutex_lock(&g_lock);
    data->next = g_instances;
    g_instances = data;
    pthread_mutex_unlock(&g_lock);

    return VK_SUCCESS;
}

static void layer_destroy_instance(VkInstance inst, const VkAllocationCallbacks *alloc) {
    instance_data *data = find_instance(inst);
    if (!data) return;
    PFN_vkDestroyInstance fp = data->vtable.destroy_instance;
    pthread_mutex_lock(&g_lock);
    instance_data **pp = &g_instances;
    while (*pp) { if (*pp == data) { *pp = data->next; break; } pp = &(*pp)->next; }
    pthread_mutex_unlock(&g_lock);
    free(data);
    if (fp) fp(inst, alloc);
}

static VkResult layer_create_device(VkPhysicalDevice phys, const VkDeviceCreateInfo *ci,
                                    const VkAllocationCallbacks *alloc, VkDevice *dev) {
    pthread_mutex_lock(&g_lock);
    instance_data *inst = g_instances;
    pthread_mutex_unlock(&g_lock);
    if (!inst) return VK_ERROR_INITIALIZATION_FAILED;

    PFN_vkGetDeviceProcAddr fp_gdpa = NULL;
    PFN_vkCreateDevice fp_create = NULL;
    const VkLayerDeviceCreateInfo *li = (const VkLayerDeviceCreateInfo *)ci->pNext;
    while (li) {
        if (li->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO &&
            li->function == VK_LAYER_LINK_INFO) {
            fp_gdpa = li->u.pLayerInfo->pfnNextGetDeviceProcAddr;
            fp_create = (PFN_vkCreateDevice)inst->vtable.get_instance_proc_addr(VK_NULL_HANDLE, "vkCreateDevice");
            break;
        }
        li = (const VkLayerDeviceCreateInfo *)li->pNext;
    }
    if (!fp_create || !fp_gdpa) return VK_ERROR_INITIALIZATION_FAILED;

    /* Do NOT inject extensions — winevulkan rejects unexposed ones. */
    VkResult res = fp_create(phys, ci, alloc, dev);
    if (res != VK_SUCCESS) return res;

    device_data *data = (device_data *)calloc(1, sizeof(*data));
    data->device = *dev;
    data->physical_device = phys;
    data->inst_data = inst;
    data->vtable.get_device_proc_addr = fp_gdpa;
    data->queue_family = UINT32_MAX;
    data->graphics_queue = VK_NULL_HANDLE;
    if (ci->queueCreateInfoCount > 0)
        data->queue_family = ci->pQueueCreateInfos[0].queueFamilyIndex;

    pthread_mutex_lock(&g_lock);
    data->next = g_devices;
    g_devices = data;
    pthread_mutex_unlock(&g_lock);

    /* No watcher thread. No dispatch table patching.
     * Rely entirely on standard Vulkan layer interception via
     * layer_get_device_proc_addr / layer_get_instance_proc_addr. */

    LOGI("create_device: device=%p family=%u passthrough=%d",
         (void *)*dev, data->queue_family, is_passthrough());
    return VK_SUCCESS;
}

static void layer_destroy_device(VkDevice dev, const VkAllocationCallbacks *alloc) {
    device_data *data = find_device(dev);
    if (!data) return;
    PFN_vkDestroyDevice fp = data->vtable.destroy_device;
    pthread_mutex_lock(&g_lock);
    device_data **pp = &g_devices;
    while (*pp) { if (*pp == data) { *pp = data->next; break; } pp = &(*pp)->next; }
    pthread_mutex_unlock(&g_lock);
    free(data);
    if (fp) fp(dev, alloc);
}

/* ------------------------------------------------------------------ */
/* Layer entry points                                                 */
/* ------------------------------------------------------------------ */

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
layer_get_device_proc_addr(VkDevice dev, const char *name);

/* ------------------------------------------------------------------ */
/* RUNTIME SURFACE OVERRIDE                                           */
/*                                                                    */
/* winevulkan's Unix side doesn't have vkCreateWin32SurfaceKHR in its */
/* dispatch table (VK_USE_PLATFORM_WIN32_KHR not defined at build     */
/* time). When winevulkan can't find it, it falls through to the HOST */
/* driver's vkGetInstanceProcAddr — which goes through OUR layer.     */
/*                                                                    */
/* We intercept the query and return a custom implementation that     */
/* calls vkCreateXlibSurfaceKHR with the ANativeWindow from the env   */
/* var WAYLANDIE_ANATIVE_WINDOW. The adrenotools wrapper translates   */
/* xlib_surface → android_surface internally.                         */
/* ------------------------------------------------------------------ */

static VkResult VKAPI_CALL layer_create_win32_surface(
    VkInstance instance,
    const VkWin32SurfaceCreateInfoKHR *pCreateInfo,
    const VkAllocationCallbacks *pAllocator,
    VkSurfaceKHR *pSurface)
{
    /* Get ANativeWindow from env var (set by Java side) */
    const char *anw_env = getenv("WAYLANDIE_ANATIVE_WINDOW");
    LOGI("layer_create_win32_surface: called — instance=%p hwnd=%p WAYLANDIE_ANATIVE_WINDOW=%s",
         (void *)instance, pCreateInfo ? (void *)pCreateInfo->hwnd : NULL,
         anw_env ? anw_env : "(null)");

    if (!anw_env || !anw_env[0]) {
        LOGE("layer_create_win32_surface: WAYLANDIE_ANATIVE_WINDOW not set — cannot create surface");
        return VK_ERROR_NATIVE_WINDOW_IN_USE_KHR;
    }
    uint64_t anw_val = strtoull(anw_env, NULL, 0);
    if (!anw_val) {
        LOGE("layer_create_win32_surface: invalid ANativeWindow value: %s", anw_env);
        return VK_ERROR_NATIVE_WINDOW_IN_USE_KHR;
    }
    LOGI("layer_create_win32_surface: resolved ANativeWindow pointer=0x%llx",
         (unsigned long long)anw_val);

    /* Find instance_data to get the next layer's GIPA */
    instance_data *id = find_instance(instance);
    if (!id || !id->vtable.get_instance_proc_addr) {
        LOGE("layer_create_win32_surface: no instance_data for instance=%p", (void *)instance);
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    /* Resolve vkCreateXlibSurfaceKHR from the HOST driver (next layer) */
    PFN_vkCreateXlibSurfaceKHR fp_create_xlib =
        (PFN_vkCreateXlibSurfaceKHR)id->vtable.get_instance_proc_addr(instance, "vkCreateXlibSurfaceKHR");
    if (!fp_create_xlib) {
        LOGE("layer_create_win32_surface: vkCreateXlibSurfaceKHR not available on HOST");
        return VK_ERROR_EXTENSION_NOT_PRESENT;
    }
    LOGI("layer_create_win32_surface: resolved vkCreateXlibSurfaceKHR=%p", (void *)fp_create_xlib);

    /* Create Xlib surface using the ANativeWindow as the Xlib Window.
     * The adrenotools wrapper ignores dpy and uses the window directly. */
    VkXlibSurfaceCreateInfoKHR xlib_info;
    xlib_info.sType = VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR;
    xlib_info.pNext = NULL;
    xlib_info.flags = 0;
    xlib_info.dpy = NULL;
    xlib_info.window = (Window)(uintptr_t)anw_val;

    VkResult res = fp_create_xlib(instance, &xlib_info, pAllocator, pSurface);
    LOGI("layer_create_win32_surface: vkCreateXlibSurfaceKHR returned res=%d surface=%p",
         res, pSurface ? (void *)*pSurface : NULL);
    return res;
}

static VkBool32 VKAPI_CALL layer_get_win32_presentation_support(
    VkPhysicalDevice physical_device,
    uint32_t queue_family_index)
{
    /* The HOST driver supports presentation on all queue families via
     * VK_KHR_xlib_surface. Return VK_TRUE so DXVK proceeds with surface creation. */
    LOGI("layer_get_win32_presentation_support: phys_dev=%p family=%u → TRUE",
         (void *)physical_device, queue_family_index);
    return VK_TRUE;
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
layer_get_instance_proc_addr(VkInstance inst, const char *name) {
    if (!name) return NULL;
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (PFN_vkVoidFunction)layer_get_instance_proc_addr;
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)layer_get_device_proc_addr;
    if (!strcmp(name, "vkCreateInstance")) return (PFN_vkVoidFunction)layer_create_instance;
    if (!strcmp(name, "vkDestroyInstance")) return (PFN_vkVoidFunction)layer_destroy_instance;
    if (!strcmp(name, "vkCreateDevice")) return (PFN_vkVoidFunction)layer_create_device;
    if (!strcmp(name, "vkDestroyDevice")) return (PFN_vkVoidFunction)layer_destroy_device;
    /* RUNTIME SURFACE OVERRIDE: inject Win32 surface functions that winevulkan
     * doesn't have (VK_USE_PLATFORM_WIN32_KHR not defined on Unix side).
     * When winevulkan falls through to HOST vkGetInstanceProcAddr, our layer
     * returns these custom implementations. */
    if (!strcmp(name, "vkCreateWin32SurfaceKHR")) return (PFN_vkVoidFunction)layer_create_win32_surface;
    if (!strcmp(name, "vkGetPhysicalDeviceWin32PresentationSupportKHR")) return (PFN_vkVoidFunction)layer_get_win32_presentation_support;
    if (!strcmp(name, "vkCreateSwapchainKHR")) return (PFN_vkVoidFunction)layer_create_swapchain;
    if (!strcmp(name, "vkDestroySwapchainKHR")) return (PFN_vkVoidFunction)layer_destroy_swapchain;
    if (!strcmp(name, "vkGetSwapchainImagesKHR")) return (PFN_vkVoidFunction)layer_get_swapchain_images;
    if (!strcmp(name, "vkAcquireNextImageKHR")) return (PFN_vkVoidFunction)layer_acquire_next_image;
    if (!strcmp(name, "vkQueuePresentKHR")) return (PFN_vkVoidFunction)layer_queue_present;

    if (inst) {
        instance_data *data = find_instance(inst);
        if (data && data->vtable.get_instance_proc_addr)
            return data->vtable.get_instance_proc_addr(inst, name);
    }
    return NULL;
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
layer_get_device_proc_addr(VkDevice dev, const char *name) {
    if (!name) return NULL;
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)layer_get_device_proc_addr;
    if (!strcmp(name, "vkCreateSwapchainKHR")) return (PFN_vkVoidFunction)layer_create_swapchain;
    if (!strcmp(name, "vkDestroySwapchainKHR")) return (PFN_vkVoidFunction)layer_destroy_swapchain;
    if (!strcmp(name, "vkGetSwapchainImagesKHR")) return (PFN_vkVoidFunction)layer_get_swapchain_images;
    if (!strcmp(name, "vkAcquireNextImageKHR")) return (PFN_vkVoidFunction)layer_acquire_next_image;
    if (!strcmp(name, "vkQueuePresentKHR")) return (PFN_vkVoidFunction)layer_queue_present;
    if (!strcmp(name, "vkDestroyDevice")) return (PFN_vkVoidFunction)layer_destroy_device;

    if (dev) {
        device_data *data = find_device(dev);
        if (data && data->vtable.get_device_proc_addr)
            return data->vtable.get_device_proc_addr(dev, name);
    }
    return NULL;
}

/* ------------------------------------------------------------------ */
/* Layer enumeration                                                  */
/* ------------------------------------------------------------------ */

VK_LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL
vkEnumerateInstanceLayerProperties(uint32_t *count, VkLayerProperties *props) {
    if (!props) { *count = 1; return VK_SUCCESS; }
    if (*count < 1) { *count = 0; return VK_INCOMPLETE; }
    memset(props, 0, sizeof(VkLayerProperties));
    strncpy(props[0].layerName, "VK_LAYER_waylandie_dmabuf", 255);
    strncpy(props[0].description, "WayLandIE dmabuf present layer", 255);
    props[0].implementationVersion = 1;
    props[0].specVersion = VK_MAKE_VERSION(1, 3, 0);
    *count = 1;
    return VK_SUCCESS;
}

VK_LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL
vkEnumerateInstanceExtensionProperties(const char *layer, uint32_t *count,
                                       VkExtensionProperties *props) {
    /* When layer != NULL, the caller is asking for THIS layer's extensions.
     * We expose none, so return 0/empty.
     *
     * When layer == NULL, the caller is asking for the INSTANCE's extensions
     * (which includes the HOST driver's extensions). We must FORWARD this to
     * the HOST driver via dlsym, otherwise winevulkan's loader.c asserts
     * because it expects vkEnumerateInstanceExtensionProperties(NULL, ...) to
     * return the HOST's actual extensions.
     *
     * winevulkan loader.c line 466: assert(!status && "vkEnumerateInstanceExtensionProperties")
     * This fires when the call returns non-VK_SUCCESS. Our layer was returning 0
     * extensions but winevulkan expected the HOST's full extension list. */
    if (layer != NULL) {
        *count = 0;
        return VK_SUCCESS;
    }

    /* Forward to the HOST driver's vkEnumerateInstanceExtensionProperties.
     * We use dlsym(RTLD_NEXT, ...) to get the next symbol in the chain,
     * which is the real Android Vulkan loader (libvulkan.so). */
    static PFN_vkEnumerateInstanceExtensionProperties fp_host = NULL;
    if (!fp_host) {
        /* RTLD_NEXT skips our layer and finds the next symbol in the chain.
         * This is the standard way for layers to forward calls. */
        fp_host = (PFN_vkEnumerateInstanceExtensionProperties)
            dlsym(RTLD_NEXT, "vkEnumerateInstanceExtensionProperties");
    }
    if (fp_host) {
        return fp_host(NULL, count, props);
    }

    /* Fallback: no HOST driver found */
    *count = 0;
    return VK_SUCCESS;
}

VK_LAYER_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
vkGetInstanceProcAddr(VkInstance inst, const char *name) {
    return layer_get_instance_proc_addr(inst, name);
}

VK_LAYER_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
vkGetDeviceProcAddr(VkDevice dev, const char *name) {
    return layer_get_device_proc_addr(dev, name);
}
