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
    /* External memory fd (VK_KHR_external_memory_fd) */
    PFN_vkGetMemoryFdKHR get_memory_fd;
    PFN_vkGetMemoryFdPropertiesKHR get_mem_fd_props;
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
/* Constructor — confirms .so load independently of any hook firing.   */
/*                                                                      */
/* Analysis of Impact (Protocol #3):                                   */
/*   This is the ONLY reliable signal that the layer .so has been      */
/*   mapped into the process address space. Without it, we cannot      */
/*   distinguish "layer .so failed to load" from "layer .so loaded     */
/*   but never invoked" — exactly the ambiguity that masked the        */
/*   previous agent's misdiagnosis (Task ID 1, Finding 1: zero         */
/*   WayLandIE/Layer log lines despite successful install).            */
/*                                                                      */
/*   The constructor runs at dlopen()/LD_PRELOAD time, before any      */
/*   Vulkan function is called. It only calls getpid() and             */
/*   __android_log_print() — both async-signal-safe and allocation-    */
/*   free. No Vulkan state is touched.                                 */
/* ------------------------------------------------------------------ */
__attribute__((constructor))
static void waylandie_layer_ctor(void) {
    LOGI("Shared object pre-loaded via constructor, PID=%d", (int)getpid());
}

/* ------------------------------------------------------------------ */
/* Host Vulkan loader handle — cached dlopen("libvulkan.so").           *
 *                                                                      *
 * Analysis of Impact (Protocol #1):                                   *
 *   Used as the fat-layer fallback when the Khronos pNext chain walk   *
 *   fails (LD_PRELOAD scenario). Also used by vkEnumerateInstance-     *
 *   ExtensionProperties to forward to the HOST driver. Lazy-init'd    *
 *   via pthread_once for thread safety. dlopen itself is thread-safe   *
 *   per POSIX and caches the handle (no actual re-mapping).            *
 * ------------------------------------------------------------------ */
static void *g_host_vulkan_handle = NULL;
static pthread_once_t g_host_vulkan_once = PTHREAD_ONCE_INIT;

static void init_host_vulkan_handle(void) {
    /* Attempt 1: dlopen(NULL, ...) — searches the global symbol scope. */
    void *main_handle = dlopen(NULL, RTLD_NOW);
    if (main_handle) {
        void *sym = dlsym(main_handle, "vkGetInstanceProcAddr");
        if (sym) {
            g_host_vulkan_handle = main_handle;
            LOGI("init_host_vulkan_handle: using main-program handle=%p (vkGetInstanceProcAddr=%p)",
                 main_handle, sym);
            return;
        }
    }

    /* Attempt 2: explicit dlopen("libvulkan.so", ...). */
    void *vulkan_handle = dlopen("libvulkan.so", RTLD_NOW);
    if (vulkan_handle) {
        g_host_vulkan_handle = vulkan_handle;
        LOGI("init_host_vulkan_handle: using libvulkan.so handle=%p", vulkan_handle);
        return;
    }

    LOGE("init_host_vulkan_handle: FAILED to obtain any Vulkan loader handle: %s", dlerror());
}

static void *get_host_vulkan_handle(void) {
    pthread_once(&g_host_vulkan_once, init_host_vulkan_handle);
    return g_host_vulkan_handle;
}

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
    /* Handle-transparent lookup: compare the raw host VkSwapchainKHR
     * stored in real_swapchain, NOT a pointer cast. The layer returns
     * the raw host handle to the caller, so sw IS real_swapchain. */
    pthread_mutex_lock(&g_lock);
    for (swapchain_data *s = g_swapchains; s; s = s->next)
        if (s->real_swapchain == sw) {
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
    if (!dd->vtable.get_mem_fd_props)
        dd->vtable.get_mem_fd_props = (PFN_vkGetMemoryFdPropertiesKHR)resolve_real(device, dd, "vkGetMemoryFdPropertiesKHR");
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
    b_info.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
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

    /* Handle-transparent: return the RAW host VkSwapchainKHR, not a
     * pointer to our swapchain_data. The caller (winevulkan/loader)
     * sees the real driver handle. Our internal find_swapchain() looks
     * up swapchain_data by comparing real_swapchain == handle. */
    *ret = real_swapchain;
    LOGI("create_swapchain: success real=%p staging=%u (handle-transparent)", (void *)real_swapchain, count);
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
    /* DAC: Return STAGING images — DXVK renders directly into these exportable
     * images. No blit needed — on present, the staging dmabuf is sent to the
     * bridge directly. The real swapchain images are never used for rendering. */
    if (!images || *count < s->image_count) {
        *count = s->image_count;
        return images ? VK_INCOMPLETE : VK_SUCCESS;
    }
    *count = s->image_count;
    for (uint32_t i = 0; i < s->image_count; i++)
        images[i] = s->images[i].staging_img;
    LOGI("get_swapchain_images: returned %u STAGING images (DAC zero-blit)", s->image_count);
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

    /* DAC mode: DXVK rendered directly into the staging images (returned by
     * layer_get_swapchain_images). No blit needed — just wait for rendering
     * to complete, then send the dmabuf to the bridge. */
    bool any_layer_swapchain = false;
    bool semaphores_consumed = false;

    for (uint32_t i = 0; i < info->swapchainCount; i++) {
        swapchain_data *s = find_swapchain(info->pSwapchains[i]);
        if (!s) continue;
        any_layer_swapchain = true;
        uint32_t idx = info->pImageIndices[i];
        if (idx >= s->image_count) continue;

        swapchain_image *img = &s->images[idx];

        /* Wait for DXVK's rendering semaphores — ensures the GPU has finished
         * writing to the staging image before we send its dmabuf to the bridge.
         * We consume them via a no-op submit that waits on them. */
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

        /* NO BLIT — DXVK already rendered directly into img->staging_img.
         * The dmabuf fd is cached from image creation time. Send it to bridge. */
        if (img->dmabuf_fd >= 0) {
            if (s->bridge_sock < 0) {
                s->bridge_sock = bridge_connect(WAYLANDIE_BRIDGE_SOCKET);
                if (s->bridge_sock >= 0)
                    LOGI("bridge connected sock=%d (DAC zero-blit)", s->bridge_sock);
            }
            if (s->bridge_sock >= 0) {
                bridge_send_dmabuf(s->bridge_sock, img->dmabuf_fd,
                                   img->width, img->height, img->drm_format,
                                   img->stride, img->staging_size);
            }
        }

        s->present_count++;
        if (s->present_count <= 3 || (s->present_count % 60) == 0)
            LOGI("DAC present #%llu: %ux%u stride=%u fd=%d (zero-blit)",
                 (unsigned long long)s->present_count,
                 img->width, img->height, img->stride, img->dmabuf_fd);
    }

    /* Call real vkQueuePresentKHR.
     * Handle-transparent: info->pSwapchains already contains the raw host
     * VkSwapchainKHR handles (we returned them from layer_create_swapchain).
     * No handle rewriting needed — pass info straight through. Only strip
     * wait semaphores if we consumed them above. */
    if (any_layer_swapchain) {
        VkPresentInfoKHR real_info = *info;
        /* pSwapchains stays the same — handles are already host handles */
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
    LOGI("layer_create_instance: ENTER ci=%p alloc=%p inst=%p",
         (const void *)ci, (const void *)alloc, (void *)inst);

    PFN_vkGetInstanceProcAddr fp_gipa = NULL;
    PFN_vkCreateInstance fp_create = NULL;
    int fat_layer_mode = 0;

    /* ------------------------------------------------------------------ *
     * PATH 1 (Khronos-compliant): walk the pNext chain for               *
     * VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO with function ==     *
     * VK_LAYER_LINK_INFO. Extract pfnNextGetInstanceProcAddr.            *
     * This path is taken when the loader discovers us via manifest.      *
     * ------------------------------------------------------------------ */
    const VkLayerInstanceCreateInfo *li = (const VkLayerInstanceCreateInfo *)ci->pNext;
    while (li) {
        if (li->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO &&
            li->function == VK_LAYER_LINK_INFO) {
            fp_gipa = li->u.pLayerInfo->pfnNextGetInstanceProcAddr;
            fp_create = (PFN_vkCreateInstance)fp_gipa(VK_NULL_HANDLE, "vkCreateInstance");
            LOGI("layer_create_instance: PATH 1 (chain walk) — fp_gipa=%p fp_create=%p",
                 (void *)fp_gipa, (void *)fp_create);
            break;
        }
        li = (const VkLayerInstanceCreateInfo *)li->pNext;
    }

    /* ------------------------------------------------------------------ *
     * PATH 2 (fat-layer fallback): if the chain walk failed (no          *
     * VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO found), the layer    *
     * was LD_PRELOAD'd instead of manifest-discovered. The loader did    *
     * NOT construct the chain node, so we must obtain vkCreateInstance   *
     * and vkGetInstanceProcAddr directly from the system Vulkan loader   *
     * via dlopen.                                                        *
     *                                                                    *
     * Trade-off: when this path is taken, NO other layers can interpose  *
     * between us and the system loader. Acceptable for production — our  *
     * layer IS the only layer we need.                                   *
     * ------------------------------------------------------------------ */
    if (!fp_create || !fp_gipa) {
        LOGW("layer_create_instance: PATH 1 failed (chain walk found no VK_LAYER_LINK_INFO) — entering fat-layer fallback");
        fat_layer_mode = 1;

        void *host_handle = get_host_vulkan_handle();
        if (!host_handle) {
            LOGE("layer_create_instance: fat-layer fallback FAILED — no host Vulkan handle");
            return VK_ERROR_INITIALIZATION_FAILED;
        }

        fp_gipa = (PFN_vkGetInstanceProcAddr)
            dlsym(host_handle, "vkGetInstanceProcAddr");
        fp_create = (PFN_vkCreateInstance)
            dlsym(host_handle, "vkCreateInstance");
        LOGI("layer_create_instance: PATH 2 (fat-layer) — host_handle=%p fp_gipa=%p fp_create=%p",
             host_handle, (void *)fp_gipa, (void *)fp_create);

        if (!fp_gipa || !fp_create) {
            LOGE("layer_create_instance: fat-layer fallback FAILED — dlsym returned NULL (fp_gipa=%p fp_create=%p)",
                 (void *)fp_gipa, (void *)fp_create);
            return VK_ERROR_INITIALIZATION_FAILED;
        }
    }

    /* STRIP the VkLayerInstanceCreateInfo from the pNext chain AND translate
     * VK_KHR_win32_surface → VK_KHR_xlib_surface before calling fp_create.
     *
     * Two issues:
     * 1. The HOST driver does NOT understand VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO
     *    (sType=47) — it's a loader-internal type. We must strip it.
     * 2. The HOST driver (Turnip via adrenotools) does NOT support VK_KHR_win32_surface.
     *    It supports VK_KHR_xlib_surface. DXVK requests VK_KHR_win32_surface.
     *    We must translate it in the extension list. */
    VkInstanceCreateInfo stripped_ci;
    const char **translated_exts = NULL;
    if (li) {
        stripped_ci = *ci;
        stripped_ci.pNext = li->pNext;  /* Skip the layer_info, restore original pNext */
    } else {
        stripped_ci = *ci;  /* PATH 2: no layer_info in chain, pass as-is */
    }

    /* Translate VK_KHR_win32_surface → VK_KHR_xlib_surface in the extension list.
     * Allocate a new array (don't modify the original) and replace the string. */
    if (stripped_ci.enabledExtensionCount > 0 && stripped_ci.ppEnabledExtensionNames) {
        translated_exts = (const char **)calloc(stripped_ci.enabledExtensionCount, sizeof(const char *));
        if (translated_exts) {
            for (uint32_t i = 0; i < stripped_ci.enabledExtensionCount; i++) {
                if (stripped_ci.ppEnabledExtensionNames[i] &&
                    strcmp(stripped_ci.ppEnabledExtensionNames[i], "VK_KHR_win32_surface") == 0) {
                    translated_exts[i] = "VK_KHR_xlib_surface";
                    fprintf(stderr, "WayLandIE layer: translated VK_KHR_win32_surface → VK_KHR_xlib_surface\n");
                } else {
                    translated_exts[i] = stripped_ci.ppEnabledExtensionNames[i];
                }
            }
            stripped_ci.ppEnabledExtensionNames = translated_exts;
        }
    }

    VkResult res = fp_create(&stripped_ci, alloc, inst);
    fprintf(stderr, "WayLandIE layer: vkCreateInstance returned res=%d instance=%p\n",
            res, (void *)(inst ? *inst : VK_NULL_HANDLE));
    if (translated_exts) free(translated_exts);
    LOGI("layer_create_instance: vkCreateInstance returned res=%d instance=%p",
         res, (void *)(inst ? *inst : VK_NULL_HANDLE));
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

    LOGI("layer_create_instance: SUCCESS instance=%p fat_layer_mode=%d destroy=%p phys_mem=%p",
         (void *)*inst, fat_layer_mode,
         (void *)data->vtable.destroy_instance, (void *)data->vtable.get_phys_mem_props);
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
    LOGI("layer_create_device: ENTER phys=%p ci=%p alloc=%p dev=%p",
         (void *)phys, (const void *)ci, (const void *)alloc, (void *)dev);

    pthread_mutex_lock(&g_lock);
    instance_data *inst = g_instances;
    pthread_mutex_unlock(&g_lock);
    if (!inst) {
        LOGE("layer_create_device: no instance_data — vkCreateInstance must precede vkCreateDevice");
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    PFN_vkGetDeviceProcAddr fp_gdpa = NULL;
    PFN_vkCreateDevice fp_create = NULL;
    int fat_layer_mode = 0;

    /* ------------------------------------------------------------------ *
     * PATH 1 (Khronos-compliant): walk the pNext chain for               *
     * VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO with function ==       *
     * VK_LAYER_LINK_INFO. Extract pfnNextGetDeviceProcAddr.              *
     * This path is taken when the loader discovers us via manifest.      *
     * ------------------------------------------------------------------ */
    const VkLayerDeviceCreateInfo *li = (const VkLayerDeviceCreateInfo *)ci->pNext;
    while (li) {
        if (li->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO &&
            li->function == VK_LAYER_LINK_INFO) {
            fp_gdpa = li->u.pLayerInfo->pfnNextGetDeviceProcAddr;
            fp_create = (PFN_vkCreateDevice)inst->vtable.get_instance_proc_addr(VK_NULL_HANDLE, "vkCreateDevice");
            LOGI("layer_create_device: PATH 1 (chain walk) — fp_gdpa=%p fp_create=%p",
                 (void *)fp_gdpa, (void *)fp_create);
            break;
        }
        li = (const VkLayerDeviceCreateInfo *)li->pNext;
    }

    /* ------------------------------------------------------------------ *
     * PATH 2 (fat-layer fallback): if the chain walk failed (no          *
     * VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO found), the layer      *
     * was LD_PRELOAD'd instead of manifest-discovered. The loader did    *
     * NOT construct the chain node, so we must obtain vkCreateDevice     *
     * and vkGetDeviceProcAddr directly.                                  *
     *                                                                    *
     * Per Vulkan spec §3.3, vkCreateDevice is an instance-level          *
     * function — it MUST be resolved via the instance's GIPA pointer     *
     * (cached in inst->vtable.get_instance_proc_addr), NOT via dlsym    *
     * on the host loader. The host loader's vkGetInstanceProcAddr can    *
     * resolve vkCreateDevice with a NULL instance because it is a        *
     * global function, but using the already-cached GIPA is the          *
     * Khronos-correct path and works in both manifest and LD_PRELOAD     *
     * modes.                                                              *
     *                                                                    *
     * For vkGetDeviceProcAddr, the host loader exports it as a global    *
     * symbol — we resolve it via dlsym on the host Vulkan handle.        *
     *                                                                    *
     * Trade-off: same as layer_create_instance — no other layers can     *
     * interpose when this path is taken. Acceptable for production.      *
     * ------------------------------------------------------------------ */
    if (!fp_create || !fp_gdpa) {
        LOGW("layer_create_device: PATH 1 failed (chain walk found no VK_LAYER_LINK_INFO) — entering fat-layer fallback");
        fat_layer_mode = 1;

        /* vkCreateDevice: resolve via the cached instance GIPA. */
        fp_create = (PFN_vkCreateDevice)inst->vtable.get_instance_proc_addr(VK_NULL_HANDLE, "vkCreateDevice");
        if (!fp_create) {
            /* Secondary fallback: dlsym on host loader (vkCreateDevice is
             * exported by libvulkan.so as a global function). */
            void *host_handle = get_host_vulkan_handle();
            if (host_handle) {
                fp_create = (PFN_vkCreateDevice)dlsym(host_handle, "vkCreateDevice");
                LOGI("layer_create_device: PATH 2 secondary — fp_create via dlsym(host)=%p", (void *)fp_create);
            }
        }

        /* vkGetDeviceProcAddr: resolve via dlsym on host Vulkan loader. */
        void *host_handle = get_host_vulkan_handle();
        if (host_handle) {
            fp_gdpa = (PFN_vkGetDeviceProcAddr)dlsym(host_handle, "vkGetDeviceProcAddr");
        }

        LOGI("layer_create_device: PATH 2 (fat-layer) — fp_gdpa=%p fp_create=%p",
             (void *)fp_gdpa, (void *)fp_create);

        if (!fp_gdpa || !fp_create) {
            LOGE("layer_create_device: fat-layer fallback FAILED (fp_gdpa=%p fp_create=%p)",
                 (void *)fp_gdpa, (void *)fp_create);
            return VK_ERROR_INITIALIZATION_FAILED;
        }
    }

    /* Do NOT inject extensions — winevulkan rejects unexposed ones. */
    VkResult res = fp_create(phys, ci, alloc, dev);
    LOGI("layer_create_device: vkCreateDevice returned res=%d device=%p",
         res, (void *)(dev ? *dev : VK_NULL_HANDLE));
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

    LOGI("create_device: device=%p family=%u passthrough=%d fat_layer_mode=%d",
         (void *)*dev, data->queue_family, is_passthrough(), fat_layer_mode);
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

/* ------------------------------------------------------------------ */
/* VK_EXT_map_memory_placed pNext stripping                           *
 *                                                                    *
 * Analysis of Impact (Protocol #4 — THINK BEFORE YOU BUILD):         *
 *   init_physical_device() in dlls/win32u/vulkan.c:560-595 enters    *
 *   the `if (zero_bits && has_VK_EXT_map_memory_placed &&            *
 *   has_VK_KHR_map_memory2)` block ONLY for WOW64 processes          *
 *   (FEX-emulated x86_64 on ARM64). This block calls:                 *
 *     1. p_vkGetPhysicalDeviceFeatures2KHR with pNext =              *
 *        VkPhysicalDeviceMapMemoryPlacedFeaturesEXT                  *
 *     2. p_vkGetPhysicalDeviceProperties2 with pNext =               *
 *        VkPhysicalDeviceMapMemoryPlacedPropertiesEXT                *
 *                                                                    *
 *   For native ARM64 wineboot (00d4), zero_bits=0 → block skipped   *
 *   → init_physical_device succeeds. For FEX/ROTR (0150),            *
 *   zero_bits!=0 → block entered → hard crash mid-listing-loop.      *
 *                                                                    *
 *   The wine trace shows the crash mid-TRACE for extension #119      *
 *   because stderr is UNBUFFERED (setbuf(stderr,NULL) in             *
 *   dlls/ntdll/unix/debug.c:426) — the partial write("  - VK_EXT_lo")
 *   is a real mid-syscall kill, not a buffer artifact. The crash     *
 *   propagates backward from the HOST driver call through the        *
 *   unbuffered stderr path.                                         *
 *                                                                    *
 *   Root cause: winevulkan (proton_11.0) defines                    *
 *   VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAP_MEMORY_PLACED_FEATURES_EXT *
 *   = 1000273000 (vk.xml extension #273). Mesa Turnip Gen8 V27       *
 *   also advertises VK_EXT_map_memory_placed, but the struct         *
 *   negotiation between wine's Unix side and Turnip via             *
 *   adrenotools' CreateInfoWrapper may corrupt memory in the         *
 *   FEX-emulated address space.                                     *
 *                                                                    *
 *   Fix: intercept the three *_Properties2 / *_Features2 functions   *
 *   in our layer, strip the map_placed structs from pNext, then     *
 *   forward to the HOST driver. VK_EXT_map_memory_placed is only    *
 *   used for WOW64 memory mapping (placed memory for 32-bit          *
 *   compatibility) — we don't need it for Wayland dmabuf display.   *
 *   Stripping it is safe: winevulkan's physical_device->map_placed_ *
 *   align stays 0, and use_external_memory() falls back to the      *
 *   non-placed path.                                                *
 * ------------------------------------------------------------------ */

/* VK_EXT_map_memory_placed sType values (from vk.xml extension #273) */
#define WAYLANDIE_STYPE_MAP_MEMORY_PLACED_FEATURES   ((VkStructureType)1000273000)
#define WAYLANDIE_STYPE_MAP_MEMORY_PLACED_PROPERTIES ((VkStructureType)1000273001)

/* Walk the pNext chain and unlink any node whose sType matches one of the
 * map_memory_placed struct types. Returns the (possibly new) head of the
 * pNext chain. The unlinked nodes are NOT freed — they're stack-allocated
 * by init_physical_device and will be reclaimed on function return. */
static void *waylandie_strip_map_placed_from_pnext(void *pNext_head)
{
    VkBaseOutStructure *prev = NULL;
    VkBaseOutStructure *node = (VkBaseOutStructure *)pNext_head;
    while (node)
    {
        VkBaseOutStructure *next = node->pNext;
        if (node->sType == WAYLANDIE_STYPE_MAP_MEMORY_PLACED_FEATURES ||
            node->sType == WAYLANDIE_STYPE_MAP_MEMORY_PLACED_PROPERTIES)
        {
            /* Unlink this node: prev->pNext = next (skip node) */
            if (prev) prev->pNext = next;
            else pNext_head = next;
            /* node is now orphaned — don't touch it, don't free it */
        }
        else
        {
            prev = node;
        }
        node = next;
    }
    return pNext_head;
}

/* Resolve a down-chain instance function pointer for forwarding.
 *
 * When called from vkGetPhysicalDeviceXxx hooks, we receive a VkPhysicalDevice
 * handle, not a VkInstance. The layer does not maintain a phys_dev→instance
 * map. However, in our use case there is exactly ONE VkInstance (DXVK creates
 * a single instance), so we can safely use the first instance_data in the
 * global list. The GIPA function pointer is the same loader GIPA for all
 * instances, and the returned function pointer is valid for any physical
 * device belonging to that instance.
 *
 * If `inst` is non-NULL and is a valid VkInstance, we use find_instance.
 * Otherwise (e.g., VkPhysicalDevice passed), we fall back to the first
 * instance in the global list. */
static PFN_vkVoidFunction waylandie_resolve_host_func(VkInstance inst, const char *name)
{
    instance_data *data = NULL;
    if (inst) {
        data = find_instance(inst);
    }
    if (!data) {
        /* Fallback: use the first (and typically only) instance */
        pthread_mutex_lock(&g_lock);
        data = g_instances;
        pthread_mutex_unlock(&g_lock);
    }
    if (!data || !data->vtable.get_instance_proc_addr) return NULL;
    return data->vtable.get_instance_proc_addr(data->instance, name);
}

static VKAPI_ATTR void VKAPI_CALL
layer_get_physical_device_features2_khr(VkPhysicalDevice physicalDevice,
                                        VkPhysicalDeviceFeatures2 *pFeatures)
{
    fprintf(stderr, "WayLandIE layer: ENTER vkGetPhysicalDeviceFeatures2KHR phys=%p features=%p (stripping map_placed)\n",
            (void *)physicalDevice, (void *)pFeatures);

    if (!pFeatures) {
        fprintf(stderr, "WayLandIE layer: vkGetPhysicalDeviceFeatures2KHR — NULL pFeatures\n");
        return;
    }

    /* Save the original pNext, strip map_placed, then restore after the call */
    void *orig_pNext = pFeatures->pNext;
    pFeatures->pNext = waylandie_strip_map_placed_from_pnext(pFeatures->pNext);

    /* Pass NULL as instance — waylandie_resolve_host_func falls back to the
     * first (only) instance in the global list. */
    PFN_vkGetPhysicalDeviceFeatures2KHR fp = (PFN_vkGetPhysicalDeviceFeatures2KHR)
        waylandie_resolve_host_func(NULL, "vkGetPhysicalDeviceFeatures2KHR");

    if (fp) {
        fp(physicalDevice, pFeatures);
    } else {
        fprintf(stderr, "WayLandIE layer: vkGetPhysicalDeviceFeatures2KHR — HOST func not resolvable\n");
    }

    /* Restore original pNext (the orphaned map_placed node is still valid,
     * it's just not in the chain anymore) */
    pFeatures->pNext = orig_pNext;

    fprintf(stderr, "WayLandIE layer: EXIT vkGetPhysicalDeviceFeatures2KHR\n");
}

static VKAPI_ATTR void VKAPI_CALL
layer_get_physical_device_properties2(VkPhysicalDevice physicalDevice,
                                      VkPhysicalDeviceProperties2 *pProperties)
{
    fprintf(stderr, "WayLandIE layer: ENTER vkGetPhysicalDeviceProperties2 phys=%p props=%p (stripping map_placed)\n",
            (void *)physicalDevice, (void *)pProperties);

    if (!pProperties) {
        fprintf(stderr, "WayLandIE layer: vkGetPhysicalDeviceProperties2 — NULL pProperties\n");
        return;
    }

    void *orig_pNext = pProperties->pNext;
    pProperties->pNext = waylandie_strip_map_placed_from_pnext(pProperties->pNext);

    PFN_vkGetPhysicalDeviceProperties2 fp = (PFN_vkGetPhysicalDeviceProperties2)
        waylandie_resolve_host_func(NULL, "vkGetPhysicalDeviceProperties2");

    if (fp) {
        fp(physicalDevice, pProperties);
    } else {
        fprintf(stderr, "WayLandIE layer: vkGetPhysicalDeviceProperties2 — HOST func not resolvable\n");
    }

    pProperties->pNext = orig_pNext;

    fprintf(stderr, "WayLandIE layer: EXIT vkGetPhysicalDeviceProperties2\n");
}

static VKAPI_ATTR void VKAPI_CALL
layer_get_physical_device_properties2_khr(VkPhysicalDevice physicalDevice,
                                          VkPhysicalDeviceProperties2 *pProperties)
{
    fprintf(stderr, "WayLandIE layer: ENTER vkGetPhysicalDeviceProperties2KHR phys=%p props=%p (stripping map_placed)\n",
            (void *)physicalDevice, (void *)pProperties);

    if (!pProperties) {
        fprintf(stderr, "WayLandIE layer: vkGetPhysicalDeviceProperties2KHR — NULL pProperties\n");
        return;
    }

    void *orig_pNext = pProperties->pNext;
    pProperties->pNext = waylandie_strip_map_placed_from_pnext(pProperties->pNext);

    PFN_vkGetPhysicalDeviceProperties2KHR fp = (PFN_vkGetPhysicalDeviceProperties2KHR)
        waylandie_resolve_host_func(NULL, "vkGetPhysicalDeviceProperties2KHR");

    if (fp) {
        fp(physicalDevice, pProperties);
    } else {
        fprintf(stderr, "WayLandIE layer: vkGetPhysicalDeviceProperties2KHR — HOST func not resolvable\n");
    }

    pProperties->pNext = orig_pNext;

    fprintf(stderr, "WayLandIE layer: EXIT vkGetPhysicalDeviceProperties2KHR\n");
}

/* Also intercept vkEnumerateDeviceExtensionProperties to verify the count
 * and pre-touch the properties buffer (avoids lazy-allocation page-fault
 * OOM kills during init_physical_device's listing loop). */
static VKAPI_ATTR VkResult VKAPI_CALL
layer_enumerate_device_extension_properties(VkPhysicalDevice physicalDevice,
                                             const char *pLayerName,
                                             uint32_t *pPropertyCount,
                                             VkExtensionProperties *pProperties)
{
    PFN_vkEnumerateDeviceExtensionProperties fp = (PFN_vkEnumerateDeviceExtensionProperties)
        waylandie_resolve_host_func(NULL, "vkEnumerateDeviceExtensionProperties");

    if (!fp) {
        fprintf(stderr, "WayLandIE layer: vkEnumerateDeviceExtensionProperties — HOST func not resolvable\n");
        if (pPropertyCount) *pPropertyCount = 0;
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    VkResult res = fp(physicalDevice, pLayerName, pPropertyCount, pProperties);

    /* When pProperties is non-NULL (second call), pre-touch all pages of
     * the buffer to force physical allocation. This avoids page-fault-
     * triggered OOM kills during the listing loop in init_physical_device. */
    if (res == VK_SUCCESS && pProperties && pPropertyCount && *pPropertyCount > 0) {
        size_t total = (size_t)(*pPropertyCount) * sizeof(VkExtensionProperties);
        /* Touch every page (4096 bytes) to force physical allocation */
        volatile char *touch = (volatile char *)pProperties;
        for (size_t i = 0; i < total; i += 4096) {
            touch[i] = touch[i]; /* read-write to force page-in */
        }
        /* Touch the last byte too (in case total isn't page-aligned) */
        if (total > 0) touch[total - 1] = touch[total - 1];

        fprintf(stderr, "WayLandIE layer: vkEnumerateDeviceExtensionProperties count=%u total=%zu bytes (pre-touched)\n",
                *pPropertyCount, total);
    } else if (pPropertyCount) {
        fprintf(stderr, "WayLandIE layer: vkEnumerateDeviceExtensionProperties count=%u res=%d (size query or empty)\n",
                *pPropertyCount, res);
    }

    return res;
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
    /* VK_EXT_map_memory_placed pNext stripping — prevents init_physical_device
     * crash in the `if (zero_bits && ...)` block for FEX-emulated processes. */
    if (!strcmp(name, "vkGetPhysicalDeviceFeatures2KHR")) return (PFN_vkVoidFunction)layer_get_physical_device_features2_khr;
    if (!strcmp(name, "vkGetPhysicalDeviceProperties2")) return (PFN_vkVoidFunction)layer_get_physical_device_properties2;
    if (!strcmp(name, "vkGetPhysicalDeviceProperties2KHR")) return (PFN_vkVoidFunction)layer_get_physical_device_properties2_khr;
    /* Pre-touch the extension properties buffer to avoid page-fault OOM */
    if (!strcmp(name, "vkEnumerateDeviceExtensionProperties")) return (PFN_vkVoidFunction)layer_enumerate_device_extension_properties;
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
    /* ------------------------------------------------------------------ *
     * Analysis of Impact (Protocol #1 + #3):                             *
     *                                                                    *
     * PREVIOUS IMPLEMENTATION (commit 9569b2f) used dlsym(RTLD_NEXT,     *
     * "vkEnumerateInstanceExtensionProperties"). This violated Protocol  *
     * #1 because RTLD_NEXT depends on load-order in the global symbol    *
     * scope, which is non-deterministic when multiple layers or          *
     * LD_PRELOAD libraries are present. It is also architecturally       *
     * incorrect per Khronos Loader & Layer Interface §3.4: layers must   *
     * obtain downstream function pointers via the chain established by   *
     * the loader, not via symbol interposition.                          *
     *                                                                    *
     * NEW IMPLEMENTATION:                                                *
     *   1. dlopen(NULL, RTLD_NOW) — returns a handle to the main         *
     *      program; dlsym on it searches the global symbol scope, which  *
     *      includes libvulkan.so if it's been loaded transitively.       *
     *      This is the most reliable path because adrenotools links its  *
     *      isolated namespace to the default namespace for "all libs"    *
     *      (driver.cpp:78), so the main-program handle can still reach   *
     *      the system libvulkan.so.                                      *
     *   2. Fallback: explicit dlopen("libvulkan.so", RTLD_NOW) —         *
     *      re-opens the system loader's libvulkan.so (cached; no actual  *
     *      re-mapping) and gives us a deterministic handle.              *
     *                                                                    *
     * The handle and function pointer are cached in statics to avoid     *
     * repeated dlopen/dlsym calls. dlopen is thread-safe per POSIX.      *
     *                                                                    *
     * LOGGING (Protocol #3): every milestone is logged — entry, layer    *
     * arg, both dlsym attempts, final fp status, host call VkResult,     *
     * returned count, exit. This is the diagnostic surface area we need  *
     * to determine whether the layer is even reached and what fails.     *
     * ------------------------------------------------------------------ */

    LOGI("vkEnumerateInstanceExtensionProperties: ENTER layer=%s count=%p props=%p",
         layer ? layer : "(NULL)", (void *)count, (void *)props);

    /* When layer != NULL, the caller is asking for THIS layer's extensions.
     * We expose none, so return 0/empty. */
    if (layer != NULL) {
        LOGI("vkEnumerateInstanceExtensionProperties: layer-specific query for '%s' → returning 0 extensions",
             layer);
        if (count) *count = 0;
        return VK_SUCCESS;
    }

    /* Resolve HOST driver's vkEnumerateInstanceExtensionProperties
     * via the shared host Vulkan handle (lazy-init'd via pthread_once). */
    static PFN_vkEnumerateInstanceExtensionProperties fp_host = NULL;
    static int fp_resolved = 0; /* 0=unresolved, 1=resolved-ok, -1=failed */
    if (!fp_resolved) {
        void *host_handle = get_host_vulkan_handle();
        if (host_handle) {
            fp_host = (PFN_vkEnumerateInstanceExtensionProperties)
                dlsym(host_handle, "vkEnumerateInstanceExtensionProperties");
            LOGI("vkEnumerateInstanceExtensionProperties: host_handle=%p dlsym=%p",
                 host_handle, (void *)fp_host);
        } else {
            LOGE("vkEnumerateInstanceExtensionProperties: no host Vulkan handle available");
        }
        fp_resolved = fp_host ? 1 : -1;
        LOGI("vkEnumerateInstanceExtensionProperties: fp_host resolution status=%d",
             fp_resolved);
    }

    if (fp_host) {
        VkResult res = fp_host(NULL, count, props);
        LOGI("vkEnumerateInstanceExtensionProperties: HOST returned res=%d count=%u",
             res, count ? *count : 0u);
        return res;
    }

    /* Fallback: no HOST driver found — return empty list with VK_SUCCESS.
     * Note: this does NOT cause the winevulkan loader.c:466 assert because
     * that assert checks NTSTATUS (IPC status), not VkResult. Our layer
     * returning VK_SUCCESS with count=0 is harmless. */
    LOGE("vkEnumerateInstanceExtensionProperties: no HOST driver resolvable — returning 0 extensions");
    if (count) *count = 0;
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
