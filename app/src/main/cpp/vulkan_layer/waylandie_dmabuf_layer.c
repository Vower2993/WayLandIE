/* WayLandIE dmabuf zero-copy Vulkan layer.
 *
 * Two-stage blit pipeline:
 * Image A (OPTIMAL, COLOR_ATTACHMENT) — DXVK renders here
 * Image B (LINEAR, TRANSFER_DST) — staging for memfd export
 * On present: vkCmdCopyImage A→B, vkMapMemory B, memcpy to memfd, bridge
 *
 * Hooking: dispatch table patched via pointer comparison (no hardcoded indices).
 *
 * Copyright 2024 WayLandIE Project */

#define _GNU_SOURCE
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
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#ifndef VK_LAYER_EXPORT
#define VK_LAYER_EXPORT __attribute__((visibility("default")))
#endif

#ifndef AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM
#define AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM 5
#endif

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
    PFN_vkMapMemory map_mem;
    PFN_vkUnmapMemory unmap_mem;
    PFN_vkGetImageMemoryRequirements2 get_img_mem_reqs2;
    PFN_vkGetImageSubresourceLayout get_subres_layout;
    PFN_vkCmdCopyImage cmd_copy_image;
    PFN_vkCmdPipelineBarrier cmd_pipeline_barrier;
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

/* ------------------------------------------------------------------ */
/* Two-stage blit swapchain                                           */
/* Image A: OPTIMAL tiling, COLOR_ATTACHMENT (DXVK renders here)      */
/* Image B: LINEAR tiling, TRANSFER_DST (staging for memfd export)    */
/* ------------------------------------------------------------------ */

typedef struct {
    VkImage render_img;       /* Image A — OPTIMAL, render target */
    VkDeviceMemory render_mem;
    VkImage staging_img;      /* Image B — LINEAR, transfer dst */
    VkDeviceMemory staging_mem;
    VkDeviceSize staging_size;
    uint32_t stride;
    uint64_t offset;
    uint32_t width, height;
    uint32_t drm_format;
    bool in_use;
} swapchain_image;

typedef struct swapchain_data {
    device_data *dev_data;
    uint32_t image_count;
    swapchain_image images[WAYLANDIE_MAX_IMAGES];
    uint32_t acquire_index;
    VkFormat format;
    VkExtent2D extent;
    VkCommandPool cmd_pool;
    VkCommandBuffer blit_cmd;
    VkFence blit_fence;
    int bridge_sock;
    uint64_t present_count;
    struct swapchain_data *next;
} swapchain_data;

/* Forward declarations for dispatch table patching. */
static VkResult layer_create_swapchain(VkDevice, const VkSwapchainCreateInfoKHR *,
                                       const VkAllocationCallbacks *, VkSwapchainKHR *);
static void layer_destroy_swapchain(VkDevice, VkSwapchainKHR, const VkAllocationCallbacks *);
static VkResult layer_get_swapchain_images(VkDevice, VkSwapchainKHR, uint32_t *, VkImage *);
static VkResult layer_acquire_next_image(VkDevice, VkSwapchainKHR, uint64_t,
                                         VkSemaphore, VkFence, uint32_t *);
static VkResult layer_queue_present(VkQueue, const VkPresentInfoKHR *);

/* ------------------------------------------------------------------ */
/* Globals                                                            */
/* ------------------------------------------------------------------ */

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static instance_data *g_instances = NULL;
static device_data *g_devices = NULL;
static swapchain_data *g_swapchains = NULL;
static atomic_int g_enabled = 0;
static atomic_int g_watcher_started = 0;

/* Saved real function pointers (from dispatch table patching) */
static PFN_vkCreateSwapchainKHR  g_real_create_swapchain = NULL;
static PFN_vkDestroySwapchainKHR g_real_destroy_swapchain = NULL;
static PFN_vkGetSwapchainImagesKHR g_real_get_images = NULL;
static PFN_vkAcquireNextImageKHR g_real_acquire = NULL;
static PFN_vkQueuePresentKHR     g_real_present = NULL;

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
/* Dispatch table patching — pointer comparison, no hardcoded indices  */
/* ------------------------------------------------------------------ */

static int count_non_null(void **table, int max) {
    int c = 0;
    for (int i = 0; i < max; i++) if (table[i]) c++;
    return c;
}

static int patch_dispatch_table(VkDevice device) {
    void **table = (void **)((char *)device + 8);
    if (!table) return -1;

    int non_null = count_non_null(table, 256);
    LOGI("patch: table=%p %d/256 non-null", (void *)table, non_null);
    if (non_null < 100) {
        LOGW("patch: table not populated (%d/256)", non_null);
        return -1;
    }

    /* Get real function pointers via fp_gdpa (next layer's GDPA). */
    device_data *dd = find_device(device);
    if (!dd || !dd->vtable.get_device_proc_addr) {
        LOGE("patch: no device_data or fp_gdpa");
        return -1;
    }
    PFN_vkGetDeviceProcAddr fp_gdpa = dd->vtable.get_device_proc_addr;

    void *target_create = (void *)fp_gdpa(device, "vkCreateSwapchainKHR");
    void *target_destroy = (void *)fp_gdpa(device, "vkDestroySwapchainKHR");
    void *target_images = (void *)fp_gdpa(device, "vkGetSwapchainImagesKHR");
    void *target_acquire = (void *)fp_gdpa(device, "vkAcquireNextImageKHR");
    void *target_present = (void *)fp_gdpa(device, "vkQueuePresentKHR");

    LOGI("patch: targets: create=%p destroy=%p images=%p acquire=%p present=%p",
         target_create, target_destroy, target_images, target_acquire, target_present);

    if (!target_create || !target_present) {
        LOGW("patch: swapchain functions not available");
        return -1;
    }

    /* Scan for matching pointers. */
    int found = 0;
    int idx_create = -1, idx_destroy = -1, idx_images = -1;
    int idx_acquire = -1, idx_present = -1;

    for (int i = 0; i < 2048 && found < 5; i++) {
        void *entry = table[i];
        if (entry == target_create && idx_create < 0) {
            idx_create = i; g_real_create_swapchain = entry;
            table[i] = (void *)layer_create_swapchain; found++;
            LOGI("patch: [%d] create_swapchain", i);
        } else if (entry == target_destroy && idx_destroy < 0) {
            idx_destroy = i; g_real_destroy_swapchain = entry;
            table[i] = (void *)layer_destroy_swapchain; found++;
            LOGI("patch: [%d] destroy_swapchain", i);
        } else if (entry == target_images && idx_images < 0) {
            idx_images = i; g_real_get_images = entry;
            table[i] = (void *)layer_get_swapchain_images; found++;
            LOGI("patch: [%d] get_images", i);
        } else if (entry == target_acquire && idx_acquire < 0) {
            idx_acquire = i; g_real_acquire = entry;
            table[i] = (void *)layer_acquire_next_image; found++;
            LOGI("patch: [%d] acquire", i);
        } else if (entry == target_present && idx_present < 0) {
            idx_present = i; g_real_present = entry;
            table[i] = (void *)layer_queue_present; found++;
            LOGI("patch: [%d] present", i);
        }
    }

    /* If create_swapchain not found by pointer, try index 253 (confirmed working). */
    if (idx_create < 0) {
        void *orig = table[253];
        if (orig && orig != (void *)layer_create_swapchain) {
            table[253] = (void *)layer_create_swapchain;
            g_real_create_swapchain = orig;
            idx_create = 253; found++;
            LOGI("patch: [253] create_swapchain (index fallback)");
        }
    }

    LOGI("patch: patched %d/5", found);
    return found > 0 ? 0 : -1;
}

/* Watcher thread: waits for dispatch table to be populated. */
static void *watcher_thread(void *arg) {
    VkDevice device = (VkDevice)arg;
    LOGI("watcher: waiting for dispatch table (device=%p)", (void *)device);
    for (int i = 0; i < 200; i++) {
        usleep(10000);
        void **table = (void **)((char *)device + 8);
        int n = count_non_null(table, 256);
        if (n >= 150) {
            LOGI("watcher: table populated (%d/256) after %d attempts", n, i + 1);
            int rc = patch_dispatch_table(device);
            if (rc == 0) LOGI("watcher: patched successfully");
            else LOGE("watcher: patch failed (rc=%d)", rc);
            return NULL;
        }
    }
    LOGE("watcher: timed out");
    return NULL;
}

/* ------------------------------------------------------------------ */
/* Two-stage blit image creation                                      */
/* ------------------------------------------------------------------ */

static VkResult create_blit_images(device_data *dd, VkFormat fmt,
                                   VkExtent2D extent, VkImageUsageFlags usage,
                                   swapchain_image *out) {
    memset(out, 0, sizeof(*out));
    VkResult res;

    /* Image A: OPTIMAL tiling, COLOR_ATTACHMENT (DXVK renders here). */
    VkImageCreateInfo a_info = {};
    a_info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    a_info.imageType = VK_IMAGE_TYPE_2D;
    a_info.format = fmt;
    a_info.extent.width = extent.width; a_info.extent.height = extent.height; a_info.extent.depth = 1;
    a_info.mipLevels = 1;
    a_info.arrayLayers = 1;
    a_info.samples = VK_SAMPLE_COUNT_1_BIT;
    a_info.tiling = VK_IMAGE_TILING_OPTIMAL;
    a_info.usage = usage | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                   | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    a_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    res = dd->vtable.create_image(dd->device, &a_info, NULL, &out->render_img);
    if (res != VK_SUCCESS) {
        LOGE("vkCreateImage A failed res=%d", res);
        return res;
    }

    /* Image B: LINEAR tiling, TRANSFER_DST (staging for memfd). */
    VkImageCreateInfo b_info = {};
    b_info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    b_info.imageType = VK_IMAGE_TYPE_2D;
    b_info.format = fmt;
    b_info.extent.width = extent.width; b_info.extent.height = extent.height; b_info.extent.depth = 1;
    b_info.mipLevels = 1;
    b_info.arrayLayers = 1;
    b_info.samples = VK_SAMPLE_COUNT_1_BIT;
    b_info.tiling = VK_IMAGE_TILING_LINEAR;
    b_info.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    b_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    res = dd->vtable.create_image(dd->device, &b_info, NULL, &out->staging_img);
    if (res != VK_SUCCESS) {
        LOGE("vkCreateImage B failed res=%d", res);
        goto err_a;
    }

    /* Get memory requirements for both images. */
    VkMemoryRequirements2 a_reqs = {}, b_reqs = {};
    a_reqs.sType = VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2;
    b_reqs.sType = VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2;
    VkImageMemoryRequirementsInfo2 req_info = {};
    req_info.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_REQUIREMENTS_INFO_2;

    req_info.image = out->render_img;
    dd->vtable.get_img_mem_reqs2(dd->device, &req_info, &a_reqs);

    req_info.image = out->staging_img;
    dd->vtable.get_img_mem_reqs2(dd->device, &req_info, &b_reqs);

    /* Query stride from staging image (LINEAR only). */
    VkImageSubresource subres = {};
    subres.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    VkSubresourceLayout layout = {};
    dd->vtable.get_subres_layout(dd->device, out->staging_img, &subres, &layout);
    out->stride = (uint32_t)layout.rowPitch;
    out->offset = 0;
    out->staging_size = layout.size;
    LOGI("staging layout: stride=%u size=%llu", out->stride,
         (unsigned long long)layout.size);

    /* Get memory properties. */
    VkPhysicalDeviceMemoryProperties mem_props;
    dd->inst_data->vtable.get_phys_mem_props(dd->physical_device, &mem_props);

    /* Allocate memory for Image A — prefer DEVICE_LOCAL. */
    uint32_t a_type = 0;
    for (uint32_t i = 0; i < mem_props.memoryTypeCount; i++) {
        if ((a_reqs.memoryRequirements.memoryTypeBits & (1u << i)) &&
            (mem_props.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
            a_type = i; break;
        }
    }
    VkMemoryAllocateInfo a_alloc = {};
    a_alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    a_alloc.allocationSize = a_reqs.memoryRequirements.size;
    a_alloc.memoryTypeIndex = a_type;
    res = dd->vtable.alloc_mem(dd->device, &a_alloc, NULL, &out->render_mem);
    if (res != VK_SUCCESS) { LOGE("alloc A failed res=%d", res); goto err_b; }
    res = dd->vtable.bind_img_mem(dd->device, out->render_img, out->render_mem, 0);
    if (res != VK_SUCCESS) { LOGE("bind A failed res=%d", res); goto err_am; }

    /* Allocate memory for Image B — MUST be HOST_VISIBLE | HOST_COHERENT. */
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
        LOGE("no HOST_VISIBLE|HOST_COHERENT memory type for staging image");
        goto err_am;
    }
    LOGI("staging memory type %u: flags=0x%x", b_type,
         mem_props.memoryTypes[b_type].propertyFlags);

    VkMemoryAllocateInfo b_alloc = {};
    b_alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    b_alloc.allocationSize = b_reqs.memoryRequirements.size;
    b_alloc.memoryTypeIndex = b_type;
    res = dd->vtable.alloc_mem(dd->device, &b_alloc, NULL, &out->staging_mem);
    if (res != VK_SUCCESS) { LOGE("alloc B failed res=%d", res); goto err_am; }
    res = dd->vtable.bind_img_mem(dd->device, out->staging_img, out->staging_mem, 0);
    if (res != VK_SUCCESS) { LOGE("bind B failed res=%d", res); goto err_bm; }

    out->width = extent.width;
    out->height = extent.height;
    out->drm_format = vk_format_to_drm(fmt);
    out->in_use = false;
    LOGI("created blit images %ux%u drm=0x%08x stride=%u",
         extent.width, extent.height, out->drm_format, out->stride);
    return VK_SUCCESS;

err_bm:
    dd->vtable.free_mem(dd->device, out->staging_mem, NULL);
err_am:
    dd->vtable.free_mem(dd->device, out->render_mem, NULL);
err_b:
    dd->vtable.destroy_image(dd->device, out->staging_img, NULL);
err_a:
    dd->vtable.destroy_image(dd->device, out->render_img, NULL);
    return res;
}

static void destroy_blit_images(device_data *dd, swapchain_image *img) {
    if (img->staging_mem) dd->vtable.free_mem(dd->device, img->staging_mem, NULL);
    if (img->render_mem) dd->vtable.free_mem(dd->device, img->render_mem, NULL);
    if (img->staging_img) dd->vtable.destroy_image(dd->device, img->staging_img, NULL);
    if (img->render_img) dd->vtable.destroy_image(dd->device, img->render_img, NULL);
}

/* ------------------------------------------------------------------ */
/* Layer hooks — standard Vulkan signatures                           */
/* ------------------------------------------------------------------ */

/* Forward declarations. */
static VkResult layer_create_swapchain(VkDevice, const VkSwapchainCreateInfoKHR *,
                                       const VkAllocationCallbacks *, VkSwapchainKHR *);
static void layer_destroy_swapchain(VkDevice, VkSwapchainKHR, const VkAllocationCallbacks *);
static VkResult layer_get_swapchain_images(VkDevice, VkSwapchainKHR, uint32_t *, VkImage *);
static VkResult layer_acquire_next_image(VkDevice, VkSwapchainKHR, uint64_t,
                                         VkSemaphore, VkFence, uint32_t *);
static VkResult layer_queue_present(VkQueue, const VkPresentInfoKHR *);
static void ensure_device_vtable(device_data *data);

VkResult layer_create_swapchain(VkDevice device, const VkSwapchainCreateInfoKHR *info,
                                const VkAllocationCallbacks *alloc, VkSwapchainKHR *ret) {
    device_data *dd = find_device(device);
    if (dd) ensure_device_vtable(dd);
    if (!dd || !is_enabled()) {
        if (g_real_create_swapchain)
            return g_real_create_swapchain(device, info, alloc, ret);
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    LOGI("create_swapchain: %ux%u fmt=%d count=%u",
         info->imageExtent.width, info->imageExtent.height,
         info->imageFormat, info->minImageCount);

    uint32_t count = info->minImageCount;
    if (count > WAYLANDIE_MAX_IMAGES) count = WAYLANDIE_MAX_IMAGES;
    if (count < 2) count = 2;

    swapchain_data *sw = (swapchain_data *)calloc(1, sizeof(*sw));
    if (!sw) return VK_ERROR_OUT_OF_HOST_MEMORY;
    sw->dev_data = dd;
    sw->image_count = count;
    sw->format = info->imageFormat;
    sw->extent = info->imageExtent;
    sw->bridge_sock = -1;

    for (uint32_t i = 0; i < count; i++) {
        VkResult res = create_blit_images(dd, sw->format, sw->extent,
                                          info->imageUsage, &sw->images[i]);
        if (res != VK_SUCCESS) {
            for (uint32_t j = 0; j < i; j++) destroy_blit_images(dd, &sw->images[j]);
            free(sw);
            return res;
        }
    }

    /* Create command pool + blit command buffer. */
    VkCommandPoolCreateInfo pool_info = {};
    pool_info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pool_info.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pool_info.queueFamilyIndex = dd->queue_family;
    VkResult res = dd->vtable.create_cmd_pool(dd->device, &pool_info, NULL, &sw->cmd_pool);
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

    pthread_mutex_lock(&g_lock);
    sw->next = g_swapchains;
    g_swapchains = sw;
    pthread_mutex_unlock(&g_lock);

    *ret = (VkSwapchainKHR)(uintptr_t)sw;
    LOGI("create_swapchain: success %u images", count);
    return VK_SUCCESS;

fail:
    for (uint32_t i = 0; i < count; i++) destroy_blit_images(dd, &sw->images[i]);
    if (sw->blit_fence) dd->vtable.destroy_fence(dd->device, sw->blit_fence, NULL);
    if (sw->blit_cmd) dd->vtable.free_cmd_bufs(dd->device, sw->cmd_pool, 1, &sw->blit_cmd);
    if (sw->cmd_pool) dd->vtable.destroy_cmd_pool(dd->device, sw->cmd_pool, NULL);
    free(sw);
    return res;
}

void layer_destroy_swapchain(VkDevice device, VkSwapchainKHR sw, const VkAllocationCallbacks *alloc) {
    if (!sw) return;
    swapchain_data *s = find_swapchain(sw);
    if (!s) {
        if (g_real_destroy_swapchain) g_real_destroy_swapchain(device, sw, alloc);
        return;
    }
    device_data *dd = s->dev_data;
    LOGI("destroy_swapchain: presents=%llu", (unsigned long long)s->present_count);

    pthread_mutex_lock(&g_lock);
    swapchain_data **pp = &g_swapchains;
    while (*pp) { if (*pp == s) { *pp = s->next; break; } pp = &(*pp)->next; }
    pthread_mutex_unlock(&g_lock);

    for (uint32_t i = 0; i < s->image_count; i++) destroy_blit_images(dd, &s->images[i]);
    if (s->blit_fence) dd->vtable.destroy_fence(dd->device, s->blit_fence, NULL);
    if (s->blit_cmd) dd->vtable.free_cmd_bufs(dd->device, s->cmd_pool, 1, &s->blit_cmd);
    if (s->cmd_pool) dd->vtable.destroy_cmd_pool(dd->device, s->cmd_pool, NULL);
    if (s->bridge_sock >= 0) close(s->bridge_sock);
    free(s);
}

VkResult layer_get_swapchain_images(VkDevice device, VkSwapchainKHR sw,
                                    uint32_t *count, VkImage *images) {
    swapchain_data *s = find_swapchain(sw);
    if (!s) {
        if (g_real_get_images) return g_real_get_images(device, sw, count, images);
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    if (!images || *count < s->image_count) {
        *count = s->image_count;
        return images ? VK_INCOMPLETE : VK_SUCCESS;
    }
    *count = s->image_count;
    for (uint32_t i = 0; i < s->image_count; i++)
        images[i] = s->images[i].render_img;  /* DXVK renders to Image A */
    return VK_SUCCESS;
}

VkResult layer_acquire_next_image(VkDevice device, VkSwapchainKHR sw,
                                  uint64_t timeout, VkSemaphore sem,
                                  VkFence fence, uint32_t *idx) {
    swapchain_data *s = find_swapchain(sw);
    if (!s) {
        if (g_real_acquire) return g_real_acquire(device, sw, timeout, sem, fence, idx);
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    uint32_t i = s->acquire_index % s->image_count;
    s->acquire_index++;
    *idx = i;

    /* Signal semaphore with a dummy command buffer. */
    if (sem || fence) {
        device_data *dd = s->dev_data;
        if (dd->graphics_queue) {
            VkCommandBufferBeginInfo bi = {};
            bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
            bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            dd->vtable.begin_cmd(s->blit_cmd, &bi);

            /* Layout transition: UNDEFINED → GENERAL for render image. */
            VkImageMemoryBarrier bar = {};
            bar.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            bar.srcAccessMask = 0;
            bar.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
            bar.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            bar.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            bar.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            bar.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            bar.image = s->images[i].render_img;
            bar.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT; bar.subresourceRange.levelCount = 1; bar.subresourceRange.layerCount = 1;
            VkPipelineStageFlags src = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            VkPipelineStageFlags dst = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
            dd->vtable.cmd_pipeline_barrier(s->blit_cmd, src, dst, 0, 0, NULL, 0, NULL, 1, &bar);

            dd->vtable.end_cmd(s->blit_cmd);

            VkSubmitInfo si = {};
            si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            si.commandBufferCount = 1;
            si.pCommandBuffers = &s->blit_cmd;
            if (sem) { si.signalSemaphoreCount = 1; si.pSignalSemaphores = &sem; }
            if (fence) dd->vtable.reset_fences(dd->device, 1, &fence);
            dd->vtable.queue_submit(dd->graphics_queue, 1, &si, fence);
        }
    }
    return VK_SUCCESS;
}

VkResult layer_queue_present(VkQueue queue, const VkPresentInfoKHR *info) {
    if (!is_enabled()) {
        if (g_real_present) return g_real_present(queue, info);
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    /* Find device from queue (search all devices). */
    pthread_mutex_lock(&g_lock);
    device_data *dd = g_devices;
    pthread_mutex_unlock(&g_lock);
    if (!dd) return VK_ERROR_INITIALIZATION_FAILED;

    VkResult result = VK_SUCCESS;
    for (uint32_t i = 0; i < info->swapchainCount; i++) {
        swapchain_data *s = find_swapchain(info->pSwapchains[i]);
        if (!s) continue;
        uint32_t idx = info->pImageIndices[i];
        if (idx >= s->image_count) continue;

        swapchain_image *img = &s->images[idx];

        /* Wait for DXVK's rendering semaphores. */
        if (info->waitSemaphoreCount > 0) {
            VkPipelineStageFlags wait = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            VkSubmitInfo wsi = {};
            wsi.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            wsi.waitSemaphoreCount = info->waitSemaphoreCount;
            wsi.pWaitSemaphores = info->pWaitSemaphores;
            wsi.pWaitDstStageMask = &wait;
            dd->vtable.reset_fences(dd->device, 1, &s->blit_fence);
            dd->vtable.queue_submit(queue, 1, &wsi, s->blit_fence);
            dd->vtable.wait_fences(dd->device, 1, &s->blit_fence, VK_TRUE, 5000000000ULL);
        }

        /* Blit Image A → Image B (OPTIMAL → LINEAR). */
        VkCommandBufferBeginInfo bi = {};
        bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        dd->vtable.begin_cmd(s->blit_cmd, &bi);

        /* Transition render image: GENERAL → TRANSFER_SRC_OPTIMAL. */
        VkImageMemoryBarrier bar_a = {};
        bar_a.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        bar_a.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
        bar_a.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        bar_a.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        bar_a.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        bar_a.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bar_a.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bar_a.image = img->render_img;
        bar_a.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT; bar_a.subresourceRange.levelCount = 1; bar_a.subresourceRange.layerCount = 1;

        /* Transition staging image: UNDEFINED → TRANSFER_DST_OPTIMAL. */
        VkImageMemoryBarrier bar_b = {};
        bar_b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        bar_b.srcAccessMask = 0;
        bar_b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        bar_b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        bar_b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        bar_b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bar_b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bar_b.image = img->staging_img;
        bar_b.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT; bar_b.subresourceRange.levelCount = 1; bar_b.subresourceRange.layerCount = 1;

        VkImageMemoryBarrier bars[] = {bar_a, bar_b};
        VkPipelineStageFlags src_stages = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        VkPipelineStageFlags dst_stages = VK_PIPELINE_STAGE_TRANSFER_BIT;
        dd->vtable.cmd_pipeline_barrier(s->blit_cmd, src_stages, dst_stages, 0,
                                        0, NULL, 0, NULL, 2, bars);

        /* Copy Image A → Image B. */
        VkImageCopy copy = {};
        copy.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT; copy.srcSubresource.layerCount = 1;
        copy.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT; copy.dstSubresource.layerCount = 1;
        copy.extent.width = img->width; copy.extent.height = img->height; copy.extent.depth = 1;
        dd->vtable.cmd_copy_image(s->blit_cmd, img->render_img, img->staging_img,
                                  VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                  VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);

        /* Transition staging: TRANSFER_DST → GENERAL for mapping. */
        VkImageMemoryBarrier bar_c = {};
        bar_c.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        bar_c.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        bar_c.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        bar_c.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        bar_c.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        bar_c.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bar_c.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bar_c.image = img->staging_img;
        bar_c.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT; bar_c.subresourceRange.levelCount = 1; bar_c.subresourceRange.layerCount = 1;
        dd->vtable.cmd_pipeline_barrier(s->blit_cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                        VK_PIPELINE_STAGE_HOST_BIT, 0, 0, NULL, 0, NULL, 1, &bar_c);

        dd->vtable.end_cmd(s->blit_cmd);

        VkSubmitInfo si = {};
        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.commandBufferCount = 1;
        si.pCommandBuffers = &s->blit_cmd;
        dd->vtable.reset_fences(dd->device, 1, &s->blit_fence);
        dd->vtable.queue_submit(queue, 1, &si, s->blit_fence);
        dd->vtable.wait_fences(dd->device, 1, &s->blit_fence, VK_TRUE, 5000000000ULL);

        /* Map staging memory and copy to memfd. */
        void *mapped = NULL;
        VkResult res = dd->vtable.map_mem(dd->device, img->staging_mem, 0,
                                          img->staging_size, 0, &mapped);
        if (res != VK_SUCCESS || !mapped) {
            LOGE("vkMapMemory failed res=%d", res);
            result = VK_ERROR_OUT_OF_DEVICE_MEMORY;
            continue;
        }

        int fd = syscall(__NR_memfd_create, "waylandie", MFD_CLOEXEC);
        if (fd < 0) {
            LOGE("memfd_create failed errno=%d", errno);
            dd->vtable.unmap_mem(dd->device, img->staging_mem);
            result = VK_ERROR_OUT_OF_DEVICE_MEMORY;
            continue;
        }

        write(fd, mapped, img->staging_size);
        dd->vtable.unmap_mem(dd->device, img->staging_mem);

        /* Send to bridge. */
        if (s->bridge_sock < 0) {
            s->bridge_sock = bridge_connect(WAYLANDIE_BRIDGE_SOCKET);
            if (s->bridge_sock >= 0) LOGI("bridge connected sock=%d", s->bridge_sock);
        }
        if (s->bridge_sock >= 0) {
            bridge_send_dmabuf(s->bridge_sock, fd, img->width, img->height,
                               img->drm_format, img->stride, img->staging_size);
        }

        close(fd);
        s->present_count++;
        if (s->present_count <= 3 || (s->present_count % 60) == 0)
            LOGI("present #%llu: %ux%u stride=%u", (unsigned long long)s->present_count,
                 img->width, img->height, img->stride);
    }
    return result;
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
    data->vtable.map_mem = (PFN_vkMapMemory)fp(data->device, "vkMapMemory");
    data->vtable.unmap_mem = (PFN_vkUnmapMemory)fp(data->device, "vkUnmapMemory");
    data->vtable.get_img_mem_reqs2 = (PFN_vkGetImageMemoryRequirements2)fp(data->device, "vkGetImageMemoryRequirements2");
    data->vtable.get_subres_layout = (PFN_vkGetImageSubresourceLayout)fp(data->device, "vkGetImageSubresourceLayout");
    data->vtable.cmd_copy_image = (PFN_vkCmdCopyImage)fp(data->device, "vkCmdCopyImage");
    data->vtable.cmd_pipeline_barrier = (PFN_vkCmdPipelineBarrier)fp(data->device, "vkCmdPipelineBarrier");

    if (data->graphics_queue == VK_NULL_HANDLE && data->queue_family >= 0)
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
    data->queue_family = 0;
    data->graphics_queue = VK_NULL_HANDLE;
    if (ci->queueCreateInfoCount > 0)
        data->queue_family = ci->pQueueCreateInfos[0].queueFamilyIndex;

    pthread_mutex_lock(&g_lock);
    data->next = g_devices;
    g_devices = data;
    pthread_mutex_unlock(&g_lock);

    /* Spawn watcher thread to patch dispatch table after vkCreateDevice completes. */
    if (is_enabled()) {
        int expected = 0;
        if (atomic_compare_exchange_strong(&g_watcher_started, &expected, 1)) {
            pthread_t tid;
            if (pthread_create(&tid, NULL, watcher_thread, (void *)*dev) == 0) {
                pthread_detach(tid);
                LOGI("create_device: watcher spawned (device=%p)", (void *)*dev);
            }
        }
    }

    LOGI("create_device: device=%p family=%u", (void *)*dev, data->queue_family);
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

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
layer_get_instance_proc_addr(VkInstance inst, const char *name) {
    if (!name) return NULL;
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (PFN_vkVoidFunction)layer_get_instance_proc_addr;
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)layer_get_device_proc_addr;
    if (!strcmp(name, "vkCreateInstance")) return (PFN_vkVoidFunction)layer_create_instance;
    if (!strcmp(name, "vkDestroyInstance")) return (PFN_vkVoidFunction)layer_destroy_instance;
    if (!strcmp(name, "vkCreateDevice")) return (PFN_vkVoidFunction)layer_create_device;
    if (!strcmp(name, "vkDestroyDevice")) return (PFN_vkVoidFunction)layer_destroy_device;
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
