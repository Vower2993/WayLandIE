/* WayLandIE dmabuf zero-copy Vulkan layer.
 *
 * Intercepts the swapchain lifecycle to create AHardwareBuffer-backed images
 * whose dmabuf fds can be exported, then forwards each presented frame to
 * the WaylandIE bridge socket (waylandie.display.bridge.v1). The Java
 * WaylandBridgeServer receives the dmabuf and presents it via SurfaceControl.
 *
 * This is the Level 2 zero-copy path: DXVK renders into AHB-backed images,
 * the layer exports each AHB's dmabuf fd on present, and the bridge forwards
 * it to SurfaceFlinger with zero CPU memcpy.
 *
 * Copyright 2024 WayLandIE Project */

/* Must come before any system header for Dl_info / dladdr. */
#define _GNU_SOURCE

/* Vulkan header must come first for the layer dispatch macros. */
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

/* Fallback defines for older NDK / Vulkan header versions. */
#ifndef VK_LAYER_EXPORT
#define VK_LAYER_EXPORT __attribute__((visibility("default")))
#endif

/* AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM was added in API 26 but may not be
 * exposed in all NDK versions. Define it if missing. */
#ifndef AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM
#define AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM 5
#endif

#define LOG_TAG "WayLandIE/Layer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define WAYLANDIE_MAX_SWAPCHAIN_IMAGES 8
#define WAYLANDIE_BRIDGE_SOCKET_DEFAULT "waylandie.display.bridge.v1"

/* DRM fourcc codes for the dmabuf-present command. */
#define DRM_FORMAT_ARGB8888 0x34325241U
#define DRM_FORMAT_XRGB8888 0x34325258U
#define DRM_FORMAT_ABGR8888 0x34324241U
#define DRM_FORMAT_XBGR8888 0x34324258U
#define DRM_FORMAT_RGBA8888 0x34424152U
#define DRM_FORMAT_BGRA8888 0x34414742U

/* ------------------------------------------------------------------ */
/* Layer dispatch tables                                               */
/* ------------------------------------------------------------------ */

typedef struct {
    PFN_vkGetInstanceProcAddr get_instance_proc_addr;
    PFN_vkGetDeviceProcAddr   get_device_proc_addr;
    /* Instance-level functions we pass through. */
    PFN_vkCreateInstance       create_instance;
    PFN_vkDestroyInstance      destroy_instance;
    PFN_vkEnumeratePhysicalDevices enumerate_physical_devices;
    PFN_vkGetPhysicalDeviceProperties get_physical_device_properties;
    PFN_vkGetPhysicalDeviceMemoryProperties get_physical_device_memory_properties;
    PFN_vkGetInstanceProcAddr  gipa;
} instance_dispatch;

typedef struct {
    PFN_vkGetDeviceProcAddr get_device_proc_addr;
    PFN_vkDestroyDevice     destroy_device;
    PFN_vkGetDeviceQueue    get_device_queue;
    PFN_vkCreateCommandPool create_command_pool;
    PFN_vkDestroyCommandPool destroy_command_pool;
    PFN_vkAllocateCommandBuffers allocate_command_buffers;
    PFN_vkFreeCommandBuffers free_command_buffers;
    PFN_vkBeginCommandBuffer begin_command_buffer;
    PFN_vkEndCommandBuffer   end_command_buffer;
    PFN_vkQueueSubmit        queue_submit;
    PFN_vkQueueWaitIdle      queue_wait_idle;
    PFN_vkCreateFence        create_fence;
    PFN_vkDestroyFence       destroy_fence;
    PFN_vkWaitForFences      wait_for_fences;
    PFN_vkResetFences        reset_fences;
    PFN_vkCreateImage        create_image;
    PFN_vkDestroyImage       destroy_image;
    PFN_vkAllocateMemory     allocate_memory;
    PFN_vkFreeMemory         free_memory;
    PFN_vkBindImageMemory    bind_image_memory;
    PFN_vkGetImageMemoryRequirements2 get_image_memory_requirements2;  /* returns void */
    PFN_vkGetMemoryFdKHR     get_memory_fd;
    /* Swapchain functions (real driver). We don't create a real swapchain,
     * but we keep the pointers for completeness. */
    PFN_vkCreateSwapchainKHR  real_create_swapchain;
    PFN_vkDestroySwapchainKHR real_destroy_swapchain;
    PFN_vkGetSwapchainImagesKHR real_get_swapchain_images;
    PFN_vkAcquireNextImageKHR real_acquire_next_image;
    PFN_vkQueuePresentKHR     real_queue_present;
} device_dispatch;

/* ------------------------------------------------------------------ */
/* Per-instance / per-device tracking                                  */
/* ------------------------------------------------------------------ */

typedef struct instance_data {
    instance_dispatch vtable;
    VkInstance instance;
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
/* Opaque-FD-backed virtual swapchain                                 */
/* ------------------------------------------------------------------ */

typedef struct {
    VkImage image;
    VkDeviceMemory memory;
    VkDeviceSize allocation_size;
    int dmabuf_fd;  /* exported via vkGetMemoryFdKHR, -1 if not yet exported */
    uint32_t width, height;
    uint32_t drm_format;
    bool in_use;
    VkFence render_fence;  /* signaled when DXVK finishes rendering */
} swapchain_image;

typedef struct swapchain_data {
    device_data *dev_data;
    uint32_t image_count;
    swapchain_image images[WAYLANDIE_MAX_SWAPCHAIN_IMAGES];
    uint32_t acquire_index;  /* round-robin index for acquire */
    VkFormat format;
    VkExtent2D extent;
    /* Command pool + scratch command buffer for semaphore signaling. */
    VkCommandPool command_pool;
    VkCommandBuffer scratch_cmd;
    VkFence scratch_fence;
    /* Bridge socket connection (reused across presents). */
    int bridge_sock;
    char bridge_socket_name[256];
    uint64_t present_count;
    struct swapchain_data *next;
} swapchain_data;

/* ------------------------------------------------------------------ */
/* Global registries (protected by mutexes)                           */
/* ------------------------------------------------------------------ */

static pthread_mutex_t g_instance_lock = PTHREAD_MUTEX_INITIALIZER;
static instance_data *g_instances = NULL;

static pthread_mutex_t g_device_lock = PTHREAD_MUTEX_INITIALIZER;
static device_data *g_devices = NULL;

static pthread_mutex_t g_swapchain_lock = PTHREAD_MUTEX_INITIALIZER;
static swapchain_data *g_swapchains = NULL;

/* Enable flag — read once per present, cheap. */
static atomic_int g_enabled = 0;
static atomic_int g_layer_active = 0;

/* ------------------------------------------------------------------ */
/* Helpers: look up tracked objects                                   */
/* ------------------------------------------------------------------ */

static instance_data *find_instance_data(VkInstance instance) {
    pthread_mutex_lock(&g_instance_lock);
    for (instance_data *d = g_instances; d; d = d->next) {
        if (d->instance == instance) {
            pthread_mutex_unlock(&g_instance_lock);
            return d;
        }
    }
    pthread_mutex_unlock(&g_instance_lock);
    return NULL;
}

static device_data *find_device_data(VkDevice device) {
    pthread_mutex_lock(&g_device_lock);
    for (device_data *d = g_devices; d; d = d->next) {
        if (d->device == device) {
            pthread_mutex_unlock(&g_device_lock);
            return d;
        }
    }
    pthread_mutex_unlock(&g_device_lock);
    return NULL;
}

static device_data *find_device_data_by_physical(VkPhysicalDevice phys) {
    /* We don't track physical devices directly; find via device list. */
    (void)phys;
    return NULL;
}

static swapchain_data *find_swapchain_data(VkSwapchainKHR swapchain) {
    pthread_mutex_lock(&g_swapchain_lock);
    for (swapchain_data *s = g_swapchains; s; s = s->next) {
        if ((VkSwapchainKHR)(uintptr_t)s == swapchain) {
            pthread_mutex_unlock(&g_swapchain_lock);
            return s;
        }
    }
    pthread_mutex_unlock(&g_swapchain_lock);
    return NULL;
}

/* ------------------------------------------------------------------ */
/* Bridge socket helpers                                              */
/* ------------------------------------------------------------------ */

static int bridge_connect(const char *socket_name) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    size_t name_len = strlen(socket_name);
    if (name_len + 1 > sizeof(addr.sun_path)) { close(fd); return -1; }
    addr.sun_path[0] = '\0';  /* abstract socket */
    memcpy(addr.sun_path + 1, socket_name, name_len);
    socklen_t addr_len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + name_len);
    if (connect(fd, (struct sockaddr *)&addr, addr_len) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int bridge_send_dmabuf(int sock, int dmabuf_fd,
                              uint32_t width, uint32_t height,
                              uint32_t drm_format, uint64_t modifier,
                              uint32_t stride, uint64_t size) {
    char command[512];
    int cmd_len = snprintf(command, sizeof(command),
        "dmabuf-present fast=1 window=fullscreen width=%u height=%u "
        "format=%u modifier=0x%016llx planes=1 stride0=%u offset0=0 "
        "size=%llu driver=turnip\n",
        width, height, drm_format,
        (unsigned long long)modifier, stride,
        (unsigned long long)size);
    if (cmd_len <= 0 || (size_t)cmd_len >= sizeof(command)) return -1;

    char control[CMSG_SPACE(sizeof(int))];
    struct iovec iov = { .iov_base = command, .iov_len = (size_t)cmd_len };
    struct msghdr msg;
    memset(control, 0, sizeof(control));
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    if (!cmsg) return -1;
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &dmabuf_fd, sizeof(int));
    msg.msg_controllen = cmsg->cmsg_len;

    ssize_t sent = sendmsg(sock, &msg, MSG_NOSIGNAL);
    if (sent < 0 || (size_t)sent != iov.iov_len) return -1;

    /* Read response (don't block forever). */
    char response[256];
    struct timeval tv = { .tv_sec = 1, .tv_usec = 0 };
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    ssize_t rlen = recv(sock, response, sizeof(response) - 1, 0);
    if (rlen > 0) {
        response[rlen] = '\0';
        if (strstr(response, "status=pass")) return 0;
        LOGW("bridge responded: %s", response);
    }
    return 0;  /* treat as success even on timeout — frame is sent */
}

/* ------------------------------------------------------------------ */
/* AHardwareBuffer helpers                                            */
/* ------------------------------------------------------------------ */

/* native_handle_t — matches Android's <cutils/native_handle.h>.
 * We declare it locally because the NDK sysroot doesn't always expose it. */
typedef struct native_handle {
    int version;
    int numFds;
    int numInts;
    int data[0];
} native_handle_t;

/* AHardwareBuffer_getNativeHandle is a hidden API. We dlsym it at runtime. */
typedef const native_handle_t *(*PFN_AHardwareBuffer_getNativeHandle)(const AHardwareBuffer *);

static PFN_AHardwareBuffer_getNativeHandle g_get_native_handle = NULL;

static void init_ahb_native_handle(void) {
    if (g_get_native_handle) return;
    void *lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        LOGW("dlopen libandroid.so failed: %s", dlerror());
        return;
    }
    g_get_native_handle = (PFN_AHardwareBuffer_getNativeHandle)
        dlsym(lib, "AHardwareBuffer_getNativeHandle");
    if (!g_get_native_handle) {
        LOGW("AHardwareBuffer_getNativeHandle not found — dmabuf export will fail");
    }
}

static int ahb_get_dmabuf_fd(AHardwareBuffer *ahb) {
    if (!g_get_native_handle) {
        init_ahb_native_handle();
        if (!g_get_native_handle) return -1;
    }
    const native_handle_t *h = g_get_native_handle(ahb);
    if (!h || h->numFds < 1) return -1;
    return dup(h->data[0]);
}

static uint32_t vk_format_to_drm(VkFormat format) {
    switch (format) {
        case VK_FORMAT_B8G8R8A8_UNORM:
        case VK_FORMAT_B8G8R8A8_SRGB:
            return DRM_FORMAT_ARGB8888;
        case VK_FORMAT_R8G8B8A8_UNORM:
        case VK_FORMAT_R8G8B8A8_SRGB:
            return DRM_FORMAT_ABGR8888;
        case VK_FORMAT_R8G8B8A8_SNORM:
            return DRM_FORMAT_ABGR8888;
        case VK_FORMAT_A8B8G8R8_SRGB_PACK32:
        case VK_FORMAT_A8B8G8R8_UNORM_PACK32:
            return DRM_FORMAT_RGBA8888;
        default:
            return DRM_FORMAT_XRGB8888;  /* fallback — bridge handles it */
    }
}

/* Map Vulkan format to AHardwareBuffer format. */
static uint32_t vk_format_to_ahb(VkFormat format) {
    switch (format) {
        case VK_FORMAT_B8G8R8A8_UNORM:
        case VK_FORMAT_B8G8R8A8_SRGB:
            return AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM;
        case VK_FORMAT_R8G8B8A8_UNORM:
        case VK_FORMAT_R8G8B8A8_SRGB:
            return AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        case VK_FORMAT_A8B8G8R8_SRGB_PACK32:
        case VK_FORMAT_A8B8G8R8_UNORM_PACK32:
            return AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        default:
            return AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    }
}

/* ------------------------------------------------------------------ */
/* Forward declarations for layer swapchain/present hooks.            */
/* Needed because patch_dispatch_table references them, and           */
/* layer_create_swapchain references layer_destroy_swapchain.         */
/* ------------------------------------------------------------------ */
static VkResult layer_create_swapchain(VkDevice device,
                                       const VkSwapchainCreateInfoKHR *pCreateInfo,
                                       const VkAllocationCallbacks *pAllocator,
                                       VkSwapchainKHR *pSwapchain);
static void layer_destroy_swapchain(VkDevice device, VkSwapchainKHR swapchain,
                                    const VkAllocationCallbacks *pAllocator);
static VkResult layer_get_swapchain_images(VkDevice device, VkSwapchainKHR swapchain,
                                           uint32_t *pSwapchainImageCount,
                                           VkImage *pSwapchainImages);
static VkResult layer_acquire_next_image(VkDevice device, VkSwapchainKHR swapchain,
                                         uint64_t timeout, VkSemaphore semaphore,
                                         VkFence fence, uint32_t *pImageIndex);
static VkResult layer_queue_present(VkQueue queue, const VkPresentInfoKHR *pPresentInfo);

/* Forward declaration — lazy vtable resolver + dispatch table patcher. */
static void ensure_device_vtable(device_data *data);

/* ------------------------------------------------------------------ */
/* Layer create/destroy swapchain (opaque-FD-backed)                  */
/* ------------------------------------------------------------------ */

static VkResult create_opaque_fd_image(device_data *dev_data,
                                       VkFormat format,
                                       VkExtent2D extent,
                                       VkImageUsageFlags usage,
                                       swapchain_image *out) {
    memset(out, 0, sizeof(*out));
    out->dmabuf_fd = -1;

    /* 1. Create image with VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT_KHR.
     * This allows us to export the image's memory as a dmabuf fd via
     * vkGetMemoryFdKHR later. The driver supports VK_KHR_external_memory_fd
     * (confirmed in the wine log's physical device extensions list). */
    VkExternalMemoryImageCreateInfo ext_mem_info;
    memset(&ext_mem_info, 0, sizeof(ext_mem_info));
    ext_mem_info.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    ext_mem_info.pNext = NULL;
    ext_mem_info.handleTypes =
        VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT_KHR;

    VkImageCreateInfo img_info;
    memset(&img_info, 0, sizeof(img_info));
    img_info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    img_info.pNext = &ext_mem_info;
    img_info.imageType = VK_IMAGE_TYPE_2D;
    img_info.format = format;
    img_info.extent.width = extent.width;
    img_info.extent.height = extent.height;
    img_info.extent.depth = 1;
    img_info.mipLevels = 1;
    img_info.arrayLayers = 1;
    img_info.samples = VK_SAMPLE_COUNT_1_BIT;
    img_info.tiling = VK_IMAGE_TILING_OPTIMAL;
    img_info.usage = usage
                   | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                   | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                   | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                   | VK_IMAGE_USAGE_SAMPLED_BIT;
    img_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    img_info.queueFamilyIndexCount = 0;
    img_info.pQueueFamilyIndices = NULL;
    img_info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    VkResult res = dev_data->vtable.create_image(dev_data->device, &img_info, NULL, &out->image);
    if (res != VK_SUCCESS) {
        LOGE("vkCreateImage (opaque-fd) failed res=%d format=%d %ux%u",
             res, format, extent.width, extent.height);
        return res;
    }

    /* 2. Get image memory requirements. */
    VkMemoryRequirements2 mem_reqs2;
    memset(&mem_reqs2, 0, sizeof(mem_reqs2));
    mem_reqs2.sType = VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2;
    VkImageMemoryRequirementsInfo2 img_req_info;
    memset(&img_req_info, 0, sizeof(img_req_info));
    img_req_info.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_REQUIREMENTS_INFO_2;
    img_req_info.image = out->image;
    /* Use vkGetImageMemoryRequirements2 (returns void, not VkResult). */
    if (dev_data->vtable.get_image_memory_requirements2) {
        dev_data->vtable.get_image_memory_requirements2(
            dev_data->device, &img_req_info, &mem_reqs2);
    } else {
        LOGE("vkGetImageMemoryRequirements2 not available");
        goto err_img;
    }
    out->allocation_size = mem_reqs2.memoryRequirements.size;
    uint32_t mem_type_bits = mem_reqs2.memoryRequirements.memoryTypeBits;

    /* 3. Find a compatible memory type — prefer DEVICE_LOCAL. */
    VkPhysicalDeviceMemoryProperties mem_props;
    dev_data->inst_data->vtable.get_physical_device_memory_properties(
        dev_data->physical_device, &mem_props);
    uint32_t mem_type_index = 0;
    bool found = false;
    for (uint32_t i = 0; i < mem_props.memoryTypeCount; i++) {
        if ((mem_type_bits & (1u << i)) &&
            (mem_props.memoryTypes[i].propertyFlags &
             VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
            mem_type_index = i;
            found = true;
            break;
        }
    }
    if (!found) {
        for (uint32_t i = 0; i < mem_props.memoryTypeCount; i++) {
            if (mem_type_bits & (1u << i)) {
                mem_type_index = i;
                found = true;
                break;
            }
        }
    }
    if (!found) {
        LOGE("no compatible memory type for opaque-fd image (bits=0x%x)", mem_type_bits);
        goto err_img;
    }

    /* 4. Allocate device memory (regular allocation, not import). */
    VkMemoryAllocateInfo alloc_info;
    memset(&alloc_info, 0, sizeof(alloc_info));
    alloc_info.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc_info.pNext = NULL;
    alloc_info.allocationSize = out->allocation_size;
    alloc_info.memoryTypeIndex = mem_type_index;

    res = dev_data->vtable.allocate_memory(dev_data->device, &alloc_info, NULL, &out->memory);
    if (res != VK_SUCCESS) {
        LOGE("vkAllocateMemory (opaque-fd) failed res=%d size=%llu", res,
             (unsigned long long)out->allocation_size);
        goto err_img;
    }

    /* 5. Bind memory to image. */
    res = dev_data->vtable.bind_image_memory(dev_data->device, out->image, out->memory, 0);
    if (res != VK_SUCCESS) {
        LOGE("vkBindImageMemory (opaque-fd) failed res=%d", res);
        goto err_mem;
    }

    /* 6. Export dmabuf fd via vkGetMemoryFdKHR. */
    if (!dev_data->vtable.get_memory_fd) {
        LOGE("vkGetMemoryFdKHR not available — VK_KHR_external_memory_fd missing");
        goto err_mem;
    }
    VkMemoryGetFdInfoKHR fd_info;
    memset(&fd_info, 0, sizeof(fd_info));
    fd_info.sType = VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR;
    fd_info.pNext = NULL;
    fd_info.memory = out->memory;
    fd_info.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT_KHR;
    int fd = -1;
    res = dev_data->vtable.get_memory_fd(dev_data->device, &fd_info, &fd);
    if (res != VK_SUCCESS || fd < 0) {
        LOGE("vkGetMemoryFdKHR failed res=%d fd=%d", res, fd);
        goto err_mem;
    }
    out->dmabuf_fd = fd;

    /* 7. Create a fence for render completion signaling. */
    VkFenceCreateInfo fence_info;
    memset(&fence_info, 0, sizeof(fence_info));
    fence_info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    res = dev_data->vtable.create_fence(dev_data->device, &fence_info, NULL, &out->render_fence);
    if (res != VK_SUCCESS) {
        LOGE("vkCreateFence (render fence) failed res=%d", res);
        goto err_fd;
    }

    out->width = extent.width;
    out->height = extent.height;
    out->drm_format = vk_format_to_drm(format);
    out->in_use = false;
    LOGI("created opaque-fd image %dx%d drm=0x%08x dmabuf_fd=%d size=%llu",
         out->width, out->height, out->drm_format, out->dmabuf_fd,
         (unsigned long long)out->allocation_size);
    return VK_SUCCESS;

err_fd:
    close(out->dmabuf_fd);
    out->dmabuf_fd = -1;
err_mem:
    dev_data->vtable.free_memory(dev_data->device, out->memory, NULL);
    out->memory = VK_NULL_HANDLE;
err_img:
    dev_data->vtable.destroy_image(dev_data->device, out->image, NULL);
    out->image = VK_NULL_HANDLE;
    return res != VK_SUCCESS ? res : VK_ERROR_OUT_OF_DEVICE_MEMORY;
}

static void destroy_opaque_fd_image(device_data *dev_data, swapchain_image *img) {
    if (img->render_fence != VK_NULL_HANDLE) {
        dev_data->vtable.destroy_fence(dev_data->device, img->render_fence, NULL);
        img->render_fence = VK_NULL_HANDLE;
    }
    if (img->dmabuf_fd >= 0) {
        close(img->dmabuf_fd);
        img->dmabuf_fd = -1;
    }
    if (img->image != VK_NULL_HANDLE) {
        dev_data->vtable.destroy_image(dev_data->device, img->image, NULL);
        img->image = VK_NULL_HANDLE;
    }
    if (img->memory != VK_NULL_HANDLE) {
        dev_data->vtable.free_memory(dev_data->device, img->memory, NULL);
        img->memory = VK_NULL_HANDLE;
    }
}

static VkResult layer_create_swapchain(VkDevice device,
                                       const VkSwapchainCreateInfoKHR *pCreateInfo,
                                       const VkAllocationCallbacks *pAllocator,
                                       VkSwapchainKHR *pSwapchain) {
    device_data *dev_data = find_device_data(device);
    if (dev_data) ensure_device_vtable(dev_data);
    if (!dev_data || !atomic_load(&g_enabled)) {
        /* Layer disabled or device not tracked — pass through to real driver. */
        if (dev_data && dev_data->vtable.real_create_swapchain) {
            return dev_data->vtable.real_create_swapchain(device, pCreateInfo, pAllocator, pSwapchain);
        }
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    LOGI("layer_create_swapchain: %ux%u format=%d imageCount=%u",
         pCreateInfo->imageExtent.width, pCreateInfo->imageExtent.height,
         pCreateInfo->imageFormat, pCreateInfo->minImageCount);

    /* If DXVK is recreating the swapchain, destroy the old one first. */
    if (pCreateInfo->oldSwapchain != VK_NULL_HANDLE) {
        LOGI("layer_create_swapchain: destroying old swapchain=%p",
             (void *)pCreateInfo->oldSwapchain);
        layer_destroy_swapchain(device, pCreateInfo->oldSwapchain, pAllocator);
    }

    uint32_t image_count = pCreateInfo->minImageCount;
    if (image_count > WAYLANDIE_MAX_SWAPCHAIN_IMAGES) {
        image_count = WAYLANDIE_MAX_SWAPCHAIN_IMAGES;
    }
    if (image_count < 2) image_count = 2;

    swapchain_data *sw = (swapchain_data *)calloc(1, sizeof(swapchain_data));
    if (!sw) return VK_ERROR_OUT_OF_HOST_MEMORY;
    sw->dev_data = dev_data;
    sw->image_count = image_count;
    sw->format = pCreateInfo->imageFormat;
    sw->extent = pCreateInfo->imageExtent;
    sw->acquire_index = 0;
    sw->bridge_sock = -1;
    sw->present_count = 0;

    /* Read bridge socket name from env (set by GuestProgramLauncherComponent). */
    const char *sock_name = getenv("WAYLANDIE_BRIDGE_SOCKET");
    if (!sock_name || !*sock_name) sock_name = WAYLANDIE_BRIDGE_SOCKET_DEFAULT;
    strncpy(sw->bridge_socket_name, sock_name, sizeof(sw->bridge_socket_name) - 1);

    /* Create AHB-backed images. */
    for (uint32_t i = 0; i < image_count; i++) {
        VkResult res = create_opaque_fd_image(dev_data, sw->format, sw->extent,
                                                pCreateInfo->imageUsage, &sw->images[i]);
        if (res != VK_SUCCESS) {
            LOGE("create_opaque_fd_image failed for image %u", i);
            for (uint32_t j = 0; j < i; j++) {
                destroy_opaque_fd_image(dev_data, &sw->images[j]);
            }
            free(sw);
            return res;
        }
    }

    /* Create command pool for semaphore signaling. */
    VkCommandPoolCreateInfo pool_info;
    memset(&pool_info, 0, sizeof(pool_info));
    pool_info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pool_info.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pool_info.queueFamilyIndex = dev_data->queue_family;
    VkResult res = dev_data->vtable.create_command_pool(device, &pool_info, NULL, &sw->command_pool);
    if (res != VK_SUCCESS) {
        LOGE("vkCreateCommandPool failed res=%d", res);
        for (uint32_t i = 0; i < image_count; i++) {
            destroy_opaque_fd_image(dev_data, &sw->images[i]);
        }
        free(sw);
        return res;
    }

    VkCommandBufferAllocateInfo cmd_alloc;
    memset(&cmd_alloc, 0, sizeof(cmd_alloc));
    cmd_alloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmd_alloc.commandPool = sw->command_pool;
    cmd_alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmd_alloc.commandBufferCount = 1;
    res = dev_data->vtable.allocate_command_buffers(device, &cmd_alloc, &sw->scratch_cmd);
    if (res != VK_SUCCESS) {
        LOGE("vkAllocateCommandBuffers failed res=%d", res);
        dev_data->vtable.destroy_command_pool(device, sw->command_pool, NULL);
        for (uint32_t i = 0; i < image_count; i++) {
            destroy_opaque_fd_image(dev_data, &sw->images[i]);
        }
        free(sw);
        return res;
    }

    VkFenceCreateInfo fence_info;
    memset(&fence_info, 0, sizeof(fence_info));
    fence_info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    res = dev_data->vtable.create_fence(device, &fence_info, NULL, &sw->scratch_fence);
    if (res != VK_SUCCESS) {
        LOGE("vkCreateFence (scratch) failed res=%d", res);
        dev_data->vtable.free_command_buffers(device, sw->command_pool, 1, &sw->scratch_cmd);
        dev_data->vtable.destroy_command_pool(device, sw->command_pool, NULL);
        for (uint32_t i = 0; i < image_count; i++) {
            destroy_opaque_fd_image(dev_data, &sw->images[i]);
        }
        free(sw);
        return res;
    }

    /* Register the swapchain. */
    pthread_mutex_lock(&g_swapchain_lock);
    sw->next = g_swapchains;
    g_swapchains = sw;
    pthread_mutex_unlock(&g_swapchain_lock);

    /* Return the swapchain_data pointer as the VkSwapchainKHR handle. */
    *pSwapchain = (VkSwapchainKHR)(uintptr_t)sw;
    atomic_store(&g_layer_active, 1);
    LOGI("layer_create_swapchain: success, %u AHB-backed images, swapchain=%p",
         image_count, (void *)*pSwapchain);
    return VK_SUCCESS;
}

static void layer_destroy_swapchain(VkDevice device, VkSwapchainKHR swapchain,
                                    const VkAllocationCallbacks *pAllocator) {
    (void)pAllocator;
    if (!swapchain) return;
    swapchain_data *sw = find_swapchain_data(swapchain);
    if (!sw) {
        /* Not our swapchain — pass through. */
        device_data *dev_data = find_device_data(device);
        if (dev_data && dev_data->vtable.real_destroy_swapchain) {
            dev_data->vtable.real_destroy_swapchain(device, swapchain, pAllocator);
        }
        return;
    }

    LOGI("layer_destroy_swapchain: swapchain=%p presents=%llu",
         (void *)swapchain, (unsigned long long)sw->present_count);

    /* Remove from registry. */
    pthread_mutex_lock(&g_swapchain_lock);
    swapchain_data **pp = &g_swapchains;
    while (*pp) {
        if (*pp == sw) { *pp = sw->next; break; }
        pp = &(*pp)->next;
    }
    pthread_mutex_unlock(&g_swapchain_lock);

    /* Wait for any pending operations. */
    if (sw->dev_data->vtable.queue_wait_idle && sw->dev_data->graphics_queue) {
        sw->dev_data->vtable.queue_wait_idle(sw->dev_data->graphics_queue);
    }

    /* Cleanup. */
    if (sw->scratch_fence != VK_NULL_HANDLE) {
        sw->dev_data->vtable.destroy_fence(device, sw->scratch_fence, NULL);
    }
    if (sw->scratch_cmd != VK_NULL_HANDLE) {
        sw->dev_data->vtable.free_command_buffers(device, sw->command_pool, 1, &sw->scratch_cmd);
    }
    if (sw->command_pool != VK_NULL_HANDLE) {
        sw->dev_data->vtable.destroy_command_pool(device, sw->command_pool, NULL);
    }
    for (uint32_t i = 0; i < sw->image_count; i++) {
        destroy_opaque_fd_image(sw->dev_data, &sw->images[i]);
    }
    if (sw->bridge_sock >= 0) {
        close(sw->bridge_sock);
    }
    free(sw);
    atomic_store(&g_layer_active, 0);
}

static VkResult layer_get_swapchain_images(VkDevice device, VkSwapchainKHR swapchain,
                                           uint32_t *pSwapchainImageCount,
                                           VkImage *pSwapchainImages) {
    swapchain_data *sw = find_swapchain_data(swapchain);
    if (!sw) {
        device_data *dev_data = find_device_data(device);
        if (dev_data && dev_data->vtable.real_get_swapchain_images) {
            return dev_data->vtable.real_get_swapchain_images(device, swapchain,
                                                              pSwapchainImageCount, pSwapchainImages);
        }
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    if (!pSwapchainImages || *pSwapchainImageCount < sw->image_count) {
        *pSwapchainImageCount = sw->image_count;
        return *pSwapchainImages ? VK_INCOMPLETE : VK_SUCCESS;
    }
    *pSwapchainImageCount = sw->image_count;
    for (uint32_t i = 0; i < sw->image_count; i++) {
        pSwapchainImages[i] = sw->images[i].image;
    }
    return VK_SUCCESS;
}

static VkResult layer_acquire_next_image(VkDevice device, VkSwapchainKHR swapchain,
                                         uint64_t timeout, VkSemaphore semaphore,
                                         VkFence fence, uint32_t *pImageIndex) {
    swapchain_data *sw = find_swapchain_data(swapchain);
    if (!sw) {
        device_data *dev_data = find_device_data(device);
        if (dev_data && dev_data->vtable.real_acquire_next_image) {
            return dev_data->vtable.real_acquire_next_image(device, swapchain, timeout,
                                                            semaphore, fence, pImageIndex);
        }
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    /* Simple round-robin acquisition. */
    uint32_t idx = sw->acquire_index % sw->image_count;
    sw->acquire_index++;
    sw->images[idx].in_use = true;

    /* Signal the app's semaphore and fence via a no-op submit. */
    if (semaphore != VK_NULL_HANDLE || fence != VK_NULL_HANDLE) {
        VkCommandBufferBeginInfo begin_info;
        memset(&begin_info, 0, sizeof(begin_info));
        begin_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin_info.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        VkResult res = sw->dev_data->vtable.begin_command_buffer(sw->scratch_cmd, &begin_info);
        if (res != VK_SUCCESS) {
            LOGE("begin_command_buffer (acquire) failed res=%d", res);
            return res;
        }
        res = sw->dev_data->vtable.end_command_buffer(sw->scratch_cmd);
        if (res != VK_SUCCESS) {
            LOGE("end_command_buffer (acquire) failed res=%d", res);
            return res;
        }

        VkSubmitInfo submit_info;
        memset(&submit_info, 0, sizeof(submit_info));
        submit_info.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit_info.commandBufferCount = 1;
        submit_info.pCommandBuffers = &sw->scratch_cmd;
        if (semaphore != VK_NULL_HANDLE) {
            submit_info.signalSemaphoreCount = 1;
            submit_info.pSignalSemaphores = &semaphore;
        }

        res = sw->dev_data->vtable.queue_submit(sw->dev_data->graphics_queue,
                                                 1, &submit_info, fence);
        if (res != VK_SUCCESS) {
            LOGE("queue_submit (acquire signal) failed res=%d", res);
            return res;
        }
    }

    *pImageIndex = idx;
    return VK_SUCCESS;
}

static VkResult layer_queue_present(VkQueue queue, const VkPresentInfoKHR *pPresentInfo) {
    if (!atomic_load(&g_enabled)) {
        /* Layer disabled — pass through to real driver. */
        /* We need to find the device from the queue. VkQueue doesn't directly
         * map to a device in our tracking, so we search all devices. This is
         * a rare case (layer disabled at runtime). */
        pthread_mutex_lock(&g_device_lock);
        device_data *dev_data = g_devices;
        pthread_mutex_unlock(&g_device_lock);
        if (dev_data && dev_data->vtable.real_queue_present) {
            return dev_data->vtable.real_queue_present(queue, pPresentInfo);
        }
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    /* Process each swapchain in the present info. */
    VkResult final_result = VK_SUCCESS;
    for (uint32_t i = 0; i < pPresentInfo->swapchainCount; i++) {
        VkSwapchainKHR sw_handle = pPresentInfo->pSwapchains[i];
        uint32_t img_idx = pPresentInfo->pImageIndices[i];
        swapchain_data *sw = find_swapchain_data(sw_handle);
        if (!sw) {
            /* Not our swapchain — we can't present it. Skip. */
            LOGW("queue_present: unknown swapchain %p — skipping", (void *)sw_handle);
            continue;
        }
        if (img_idx >= sw->image_count) {
            LOGE("queue_present: image index %u out of range (%u)", img_idx, sw->image_count);
            final_result = VK_ERROR_OUT_OF_DATE_KHR;
            continue;
        }

        swapchain_image *img = &sw->images[img_idx];

        /* Wait for the render semaphores before exporting dmabuf. We submit
         * a no-op command buffer that waits on the present wait semaphores,
         * then wait for completion via the scratch fence. */
        if (pPresentInfo->waitSemaphoreCount > 0) {
            VkCommandBufferBeginInfo begin_info;
            memset(&begin_info, 0, sizeof(begin_info));
            begin_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
            begin_info.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            VkResult res = sw->dev_data->vtable.begin_command_buffer(sw->scratch_cmd, &begin_info);
            if (res == VK_SUCCESS) {
                res = sw->dev_data->vtable.end_command_buffer(sw->scratch_cmd);
            }
            if (res != VK_SUCCESS) {
                LOGE("begin/end cmd (present wait) failed res=%d", res);
                final_result = res;
                continue;
            }

            VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            VkSubmitInfo submit_info;
            memset(&submit_info, 0, sizeof(submit_info));
            submit_info.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            submit_info.waitSemaphoreCount = pPresentInfo->waitSemaphoreCount;
            submit_info.pWaitSemaphores = pPresentInfo->pWaitSemaphores;
            submit_info.pWaitDstStageMask = &wait_stage;
            submit_info.commandBufferCount = 1;
            submit_info.pCommandBuffers = &sw->scratch_cmd;

            res = sw->dev_data->vtable.reset_fences(sw->dev_data->device,
                                                     1, &sw->scratch_fence);
            if (res != VK_SUCCESS) {
                LOGE("reset_fences failed res=%d", res);
                final_result = res;
                continue;
            }
            res = sw->dev_data->vtable.queue_submit(queue, 1, &submit_info, sw->scratch_fence);
            if (res != VK_SUCCESS) {
                LOGE("queue_submit (present wait) failed res=%d", res);
                final_result = res;
                continue;
            }
            /* Wait for the wait semaphores to be consumed. */
            res = sw->dev_data->vtable.wait_for_fences(sw->dev_data->device,
                                                        1, &sw->scratch_fence,
                                                        VK_TRUE, 5000000000ULL);
            if (res != VK_SUCCESS) {
                LOGE("wait_for_fences (present) failed res=%d (timeout?)", res);
                final_result = res;
                continue;
            }
        }

        /* Use the dmabuf fd we exported at image creation time. dup() it
         * because bridge_send_dmabuf + the bridge server will close it. */
        if (img->dmabuf_fd < 0) {
            LOGE("dmabuf_fd not available for image %u", img_idx);
            final_result = VK_ERROR_OUT_OF_DEVICE_MEMORY;
            continue;
        }
        int dmabuf_fd = dup(img->dmabuf_fd);
        if (dmabuf_fd < 0) {
            LOGE("dup(dmabuf_fd) failed errno=%d", errno);
            final_result = VK_ERROR_OUT_OF_DEVICE_MEMORY;
            continue;
        }

        /* Stride = width * 4 bytes per pixel (BGRA8/RGBA8). */
        uint32_t stride = img->width * 4;
        uint64_t dmabuf_size = (uint64_t)stride * (uint64_t)img->height;

        /* Connect to bridge if needed. */
        if (sw->bridge_sock < 0) {
            sw->bridge_sock = bridge_connect(sw->bridge_socket_name);
            if (sw->bridge_sock >= 0) {
                LOGI("bridge connected sock=%d", sw->bridge_sock);
            }
        }

        /* Send dmabuf to bridge. */
        if (sw->bridge_sock >= 0) {
            int send_rc = bridge_send_dmabuf(sw->bridge_sock, dmabuf_fd,
                                             img->width, img->height,
                                             img->drm_format, 0ULL,
                                             stride, dmabuf_size);
            if (send_rc != 0) {
                LOGW("bridge_send_dmabuf failed — reconnecting");
                close(sw->bridge_sock);
                sw->bridge_sock = bridge_connect(sw->bridge_socket_name);
                if (sw->bridge_sock >= 0) {
                    bridge_send_dmabuf(sw->bridge_sock, dmabuf_fd,
                                       img->width, img->height,
                                       img->drm_format, 0ULL,
                                       stride, dmabuf_size);
                }
            }
        } else {
            LOGW("bridge not connected — frame %llu dropped",
                 (unsigned long long)sw->present_count);
        }

        close(dmabuf_fd);
        img->in_use = false;
        sw->present_count++;

        if (sw->present_count <= 3 || (sw->present_count % 60) == 0) {
            LOGI("present #%llu: %ux%u stride=%u drm=0x%08x fd=%d",
                 (unsigned long long)sw->present_count,
                 img->width, img->height, img->width * 4, img->drm_format, dmabuf_fd);
        }
    }

    /* We do NOT call the real vkQueuePresentKHR — the bridge handles display. */
    return final_result;
}

/* ------------------------------------------------------------------ */
/* Layer instance/device intercepts                                   */
/* ------------------------------------------------------------------ */

static VkResult layer_create_instance(const VkInstanceCreateInfo *pCreateInfo,
                                      const VkAllocationCallbacks *pAllocator,
                                      VkInstance *pInstance) {
    /* Read enable flag. */
    const char *env = getenv("WAYLANDIE_DMABUF_LAYER_ENABLE");
    if (env && env[0] == '1') {
        atomic_store(&g_enabled, 1);
        LOGI("WayLandIE dmabuf layer ENABLED");
    } else {
        atomic_store(&g_enabled, 0);
        LOGI("WayLandIE dmabuf layer disabled (WAYLANDIE_DMABUF_LAYER_ENABLE not set)");
    }

    /* Get the next layer's GetInstanceProcAddr from the chain info. */
    PFN_vkGetInstanceProcAddr fp_gipa = NULL;
    PFN_vkCreateInstance fp_create_instance = NULL;
    const VkLayerInstanceCreateInfo *layer_info = (const VkLayerInstanceCreateInfo *)
        pCreateInfo->pNext;
    while (layer_info) {
        if (layer_info->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO &&
            layer_info->function == VK_LAYER_LINK_INFO) {
            PFN_vkGetInstanceProcAddr gipa =
                layer_info->u.pLayerInfo->pfnNextGetInstanceProcAddr;
            fp_gipa = gipa;
            fp_create_instance = (PFN_vkCreateInstance)
                gipa(VK_NULL_HANDLE, "vkCreateInstance");
            break;
        }
        layer_info = (const VkLayerInstanceCreateInfo *)layer_info->pNext;
    }
    if (!fp_create_instance || !fp_gipa) {
        LOGE("could not find layer link info");
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    /* Call the next layer's vkCreateInstance. */
    VkResult res = fp_create_instance(pCreateInfo, pAllocator, pInstance);
    if (res != VK_SUCCESS) return res;

    /* Allocate instance data. */
    instance_data *data = (instance_data *)calloc(1, sizeof(instance_data));
    if (!data) return VK_ERROR_OUT_OF_HOST_MEMORY;
    data->instance = *pInstance;
    data->vtable.get_instance_proc_addr = fp_gipa;
    data->vtable.create_instance = fp_create_instance;
    data->vtable.gipa = fp_gipa;

    /* Resolve instance-level functions. */
    data->vtable.destroy_instance = (PFN_vkDestroyInstance)
        fp_gipa(*pInstance, "vkDestroyInstance");
    data->vtable.enumerate_physical_devices = (PFN_vkEnumeratePhysicalDevices)
        fp_gipa(*pInstance, "vkEnumeratePhysicalDevices");
    data->vtable.get_physical_device_properties = (PFN_vkGetPhysicalDeviceProperties)
        fp_gipa(*pInstance, "vkGetPhysicalDeviceProperties");
    data->vtable.get_physical_device_memory_properties = (PFN_vkGetPhysicalDeviceMemoryProperties)
        fp_gipa(*pInstance, "vkGetPhysicalDeviceMemoryProperties");
    data->vtable.get_device_proc_addr = NULL;

    /* Advance the chain so the next layer doesn't double-link. */
    /* (layer_info->u.pLayerInfo = layer_info->u.pLayerInfo->pNext;) */

    pthread_mutex_lock(&g_instance_lock);
    data->next = g_instances;
    g_instances = data;
    pthread_mutex_unlock(&g_instance_lock);

    return VK_SUCCESS;
}

static void layer_destroy_instance(VkInstance instance,
                                   const VkAllocationCallbacks *pAllocator) {
    instance_data *data = find_instance_data(instance);
    if (!data) return;
    PFN_vkDestroyInstance fp_destroy = data->vtable.destroy_instance;

    pthread_mutex_lock(&g_instance_lock);
    instance_data **pp = &g_instances;
    while (*pp) {
        if (*pp == data) { *pp = data->next; break; }
        pp = &(*pp)->next;
    }
    pthread_mutex_unlock(&g_instance_lock);
    free(data);
    if (fp_destroy) fp_destroy(instance, pAllocator);
}

/* ------------------------------------------------------------------ */
/* winevulkan dispatch table patching                                 */
/*                                                                    */
/* PROBLEM: winevulkan.so builds its OWN dispatch table AFTER          */
/* vkCreateDevice returns. Our layer_create_device runs DURING          */
/* vkCreateDevice (in the layer chain), so the table is NOT yet        */
/* populated when we try to patch it.                                 */
/*                                                                    */
/* SOLUTION: Spawn a watcher thread from layer_create_device that      */
/* polls the dispatch table until it's fully populated (>= 900 non-    */
/* null entries), then patches it. The watcher finds swapchain          */
/* function indices dynamically by dlopen'ing winevulkan.so and         */
/* dlsym'ing the wine_vkXxx thunks, then scanning the table for        */
/* matching pointers.                                                 */
/*                                                                    */
/* This completely decouples patching from the vkCreateDevice call      */
/* chain, avoiding all timing/assertion issues.                        */
/* ------------------------------------------------------------------ */

#define WINEVULKAN_DISPATCH_TABLE_MAX_ENTRIES 2048

static void *g_real_create_swapchain = NULL;
static void *g_real_destroy_swapchain = NULL;
static void *g_real_get_swapchain_images = NULL;
static void *g_real_acquire_next_image = NULL;
static void *g_real_queue_present = NULL;
static atomic_int g_watcher_started = 0;

/* Count non-NULL entries in the first N slots of a dispatch table. */
static int count_non_null_entries(void **table, int max) {
    int count = 0;
    for (int i = 0; i < max; i++) {
        if (table[i]) count++;
    }
    return count;
}

/* Find the winevulkan module handle via dladdr on a known function. */
static void *find_winevulkan_module(void *known_ptr) {
    if (!known_ptr) return NULL;
    Dl_info info;
    memset(&info, 0, sizeof(info));
    if (dladdr(known_ptr, &info) && info.dli_fname) {
        LOGI("patch: dladdr: fname=%s fbase=%p", info.dli_fname, info.dli_fbase);
        return dlopen(info.dli_fname, RTLD_NOW | RTLD_NOLOAD);
    }
    return NULL;
}

/* Try to find winevulkan thunk addresses via dlsym. */
static int find_winevulkan_thunks(void *winevulkan_mod,
                                   void **out_create, void **out_destroy,
                                   void **out_get_images, void **out_acquire,
                                   void **out_present) {
    if (!winevulkan_mod) return -1;
    *out_create = dlsym(winevulkan_mod, "wine_vkCreateSwapchainKHR");
    *out_destroy = dlsym(winevulkan_mod, "wine_vkDestroySwapchainKHR");
    *out_get_images = dlsym(winevulkan_mod, "wine_vkGetSwapchainImagesKHR");
    *out_acquire = dlsym(winevulkan_mod, "wine_vkAcquireNextImageKHR");
    *out_present = dlsym(winevulkan_mod, "wine_vkQueuePresentKHR");
    LOGI("patch: winevulkan thunks: create=%p destroy=%p images=%p acquire=%p present=%p",
         *out_create, *out_destroy, *out_get_images, *out_acquire, *out_present);
    return (*out_create && *out_present) ? 0 : -1;
}

/* Patch the dispatch table by finding and overwriting swapchain entries. */
static int patch_dispatch_table_now(VkDevice device) {
    void **dispatch_table = (void **)((char *)device + 8);
    if (!dispatch_table) {
        LOGE("patch: dispatch table is NULL");
        return -1;
    }

    int non_null = count_non_null_entries(dispatch_table, 256);
    LOGI("patch: table=%p, %d/256 non-null in first 256", (void *)dispatch_table, non_null);
    if (non_null < 100) {
        LOGW("patch: table not populated enough (%d/256), aborting", non_null);
        return -1;
    }

    /* Find winevulkan thunk addresses. We need a known pointer from
     * winevulkan — use the first non-NULL entry in the dispatch table
     * (it's a winevulkan thunk). */
    void *known_ptr = NULL;
    for (int i = 0; i < 256; i++) {
        if (dispatch_table[i]) {
            known_ptr = dispatch_table[i];
            break;
        }
    }
    void *winevulkan_mod = find_winevulkan_module(known_ptr);
    if (!winevulkan_mod) {
        /* Try direct dlopen. */
        winevulkan_mod = dlopen("winevulkan.so", RTLD_NOW | RTLD_NOLOAD);
        if (!winevulkan_mod) winevulkan_mod = dlopen("winevulkan.dll.so", RTLD_NOW | RTLD_NOLOAD);
    }
    if (!winevulkan_mod) {
        LOGE("patch: could not find winevulkan module");
        return -1;
    }

    void *thunk_create, *thunk_destroy, *thunk_images, *thunk_acquire, *thunk_present;
    if (find_winevulkan_thunks(winevulkan_mod, &thunk_create, &thunk_destroy,
                                &thunk_images, &thunk_acquire, &thunk_present) != 0) {
        LOGE("patch: could not find winevulkan thunks");
        return -1;
    }

    /* mprotect the table region RW. */
    uintptr_t table_start = (uintptr_t)dispatch_table;
    uintptr_t table_end = table_start + (WINEVULKAN_DISPATCH_TABLE_MAX_ENTRIES * sizeof(void *));
    uintptr_t page_start = table_start & ~(uintptr_t)(sysconf(_SC_PAGE_SIZE) - 1);
    uintptr_t page_end = (table_end + sysconf(_SC_PAGE_SIZE) - 1) & ~(uintptr_t)(sysconf(_SC_PAGE_SIZE) - 1);
    size_t prot_len = page_end - page_start;
    if (mprotect((void *)page_start, prot_len, PROT_READ | PROT_WRITE) != 0) {
        LOGE("patch: mprotect RW failed errno=%d", errno);
        return -1;
    }

    /* Scan for thunk pointers and overwrite with our hooks. */
    int patched = 0;
    int idx_create = -1, idx_destroy = -1, idx_images = -1, idx_acquire = -1, idx_present = -1;
    for (int i = 0; i < WINEVULKAN_DISPATCH_TABLE_MAX_ENTRIES && patched < 5; i++) {
        void *entry = dispatch_table[i];
        if (entry == thunk_create && idx_create < 0) {
            idx_create = i;
            g_real_create_swapchain = entry;
            dispatch_table[i] = (void *)layer_create_swapchain;
            patched++;
            LOGI("patch: [%d] create_swapchain: %p -> %p", i, entry, (void *)layer_create_swapchain);
        } else if (entry == thunk_destroy && idx_destroy < 0) {
            idx_destroy = i;
            g_real_destroy_swapchain = entry;
            dispatch_table[i] = (void *)layer_destroy_swapchain;
            patched++;
            LOGI("patch: [%d] destroy_swapchain: %p -> %p", i, entry, (void *)layer_destroy_swapchain);
        } else if (entry == thunk_images && idx_images < 0) {
            idx_images = i;
            g_real_get_swapchain_images = entry;
            dispatch_table[i] = (void *)layer_get_swapchain_images;
            patched++;
            LOGI("patch: [%d] get_images: %p -> %p", i, entry, (void *)layer_get_swapchain_images);
        } else if (entry == thunk_acquire && idx_acquire < 0) {
            idx_acquire = i;
            g_real_acquire_next_image = entry;
            dispatch_table[i] = (void *)layer_acquire_next_image;
            patched++;
            LOGI("patch: [%d] acquire: %p -> %p", i, entry, (void *)layer_acquire_next_image);
        } else if (entry == thunk_present && idx_present < 0) {
            idx_present = i;
            g_real_queue_present = entry;
            dispatch_table[i] = (void *)layer_queue_present;
            patched++;
            LOGI("patch: [%d] present: %p -> %p", i, entry, (void *)layer_queue_present);
        }
    }

    /* Restore RO. */
    mprotect((void *)page_start, prot_len, PROT_READ);

    LOGI("patch: patched %d/5 (create=%d destroy=%d images=%d acquire=%d present=%d)",
         patched, idx_create, idx_destroy, idx_images, idx_acquire, idx_present);
    return patched > 0 ? 0 : -1;
}

/* Watcher thread: polls until the dispatch table is populated, then patches. */
static void *dispatch_watcher_thread(void *arg) {
    VkDevice device = (VkDevice)arg;
    LOGI("watcher: started, waiting for dispatch table to populate (device=%p)", (void *)device);

    /* The dispatch table is INLINE at offset 8 (struct vulkan_device_funcs).
     * It's populated by winevulkan AFTER vkCreateDevice returns.
     * We poll until it has enough non-NULL entries to indicate population. */
    for (int attempt = 0; attempt < 200; attempt++) {
        usleep(10000);  /* 10ms */
        void **dispatch_table = (void **)((char *)device + 8);
        int non_null = count_non_null_entries(dispatch_table, 256);
        /* Log first few attempts and every 10th for diagnostics. */
        if (attempt < 3 || (attempt % 20) == 0) {
            LOGI("watcher: attempt %d, %d/256 non-null in first 256", attempt + 1, non_null);
        }
        /* The table has ~201 non-null entries in first 256 when populated.
         * Use 150 as threshold (robust against minor variations). */
        if (non_null >= 150) {
            LOGI("watcher: table populated (%d/256 non-null) after %d attempts", non_null, attempt + 1);
            int rc = patch_dispatch_table_now(device);
            if (rc == 0) {
                LOGI("watcher: dispatch table patched successfully — layer hooks active");
            } else {
                LOGE("watcher: patch_dispatch_table_now failed (rc=%d)", rc);
            }
            return NULL;
        }
    }
    LOGE("watcher: timed out waiting for dispatch table to populate");
    return NULL;
}

static int patch_dispatch_table(VkDevice device) {
    /* Spawn a watcher thread that polls until the table is populated. */
    int expected = 0;
    if (atomic_compare_exchange_strong(&g_watcher_started, &expected, 1)) {
        pthread_t tid;
        if (pthread_create(&tid, NULL, dispatch_watcher_thread, (void *)device) == 0) {
            pthread_detach(tid);
            LOGI("patch_dispatch_table: watcher thread spawned");
            return 0;
        } else {
            LOGE("patch_dispatch_table: pthread_create failed");
            atomic_store(&g_watcher_started, 0);
            return -1;
        }
    }
    LOGW("patch_dispatch_table: watcher already started");
    return 0;
}


static VkResult layer_create_device(VkPhysicalDevice physicalDevice,
                                    const VkDeviceCreateInfo *pCreateInfo,
                                    const VkAllocationCallbacks *pAllocator,
                                    VkDevice *pDevice) {
    /* Find the instance data by scanning. We don't have a direct
     * physical-device → instance map, so we use the first registered instance. */
    pthread_mutex_lock(&g_instance_lock);
    instance_data *inst_data = g_instances;
    pthread_mutex_unlock(&g_instance_lock);
    if (!inst_data) {
        LOGE("layer_create_device: no instance data found");
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    /* Get the next layer's GetDeviceProcAddr from the chain info. */
    PFN_vkGetDeviceProcAddr fp_gdpa = NULL;
    PFN_vkCreateDevice fp_create_device = NULL;
    const VkLayerDeviceCreateInfo *layer_info = (const VkLayerDeviceCreateInfo *)
        pCreateInfo->pNext;
    while (layer_info) {
        if (layer_info->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO &&
            layer_info->function == VK_LAYER_LINK_INFO) {
            fp_gdpa = layer_info->u.pLayerInfo->pfnNextGetDeviceProcAddr;
            fp_create_device = (PFN_vkCreateDevice)
                inst_data->vtable.gipa(VK_NULL_HANDLE, "vkCreateDevice");
            break;
        }
        layer_info = (const VkLayerDeviceCreateInfo *)layer_info->pNext;
    }
    if (!fp_create_device || !fp_gdpa) {
        LOGE("could not find device layer link info");
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    /* Force-inject VK_KHR_external_memory_fd + VK_KHR_external_memory into
     * the device extension list. DXVK doesn't request these (it uses
     * VK_KHR_external_memory_win32 which Wine maps), but we need them to
     * export dmabuf fds via vkGetMemoryFdKHR.
     *
     * We build a modified VkDeviceCreateInfo with the extra extensions
     * appended. The original pCreateInfo is const, so we allocate a copy. */
    const char *extra_exts[] = {
        "VK_KHR_external_memory_fd",
        "VK_KHR_external_memory",
        "VK_EXT_external_memory_dma_buf",
    };
    int extra_count = 0;
    /* Check which extensions are already requested by DXVK. */
    for (int e = 0; e < 3; e++) {
        bool already = false;
        for (uint32_t i = 0; i < pCreateInfo->enabledExtensionCount; i++) {
            if (pCreateInfo->ppEnabledExtensionNames[i] &&
                strcmp(pCreateInfo->ppEnabledExtensionNames[i], extra_exts[e]) == 0) {
                already = true;
                break;
            }
        }
        if (!already) extra_count++;
    }

    VkDeviceCreateInfo modified_create_info;
    const char **modified_exts = NULL;
    if (extra_count > 0 && atomic_load(&g_enabled)) {
        modified_create_info = *pCreateInfo;
        modified_exts = (const char **)malloc(
            (pCreateInfo->enabledExtensionCount + extra_count) * sizeof(char *));
        if (modified_exts) {
            uint32_t idx = 0;
            for (uint32_t i = 0; i < pCreateInfo->enabledExtensionCount; i++) {
                modified_exts[idx++] = pCreateInfo->ppEnabledExtensionNames[i];
            }
            for (int e = 0; e < 3; e++) {
                bool already = false;
                for (uint32_t i = 0; i < pCreateInfo->enabledExtensionCount; i++) {
                    if (pCreateInfo->ppEnabledExtensionNames[i] &&
                        strcmp(pCreateInfo->ppEnabledExtensionNames[i], extra_exts[e]) == 0) {
                        already = true;
                        break;
                    }
                }
                if (!already) {
                    modified_exts[idx++] = extra_exts[e];
                    LOGI("layer_create_device: injecting extension %s", extra_exts[e]);
                }
            }
            modified_create_info.enabledExtensionCount = idx;
            modified_create_info.ppEnabledExtensionNames = modified_exts;
            pCreateInfo = &modified_create_info;
        }
    }

    /* Call the next layer's vkCreateDevice. */
    VkResult res = fp_create_device(physicalDevice, pCreateInfo, pAllocator, pDevice);
    if (modified_exts) free(modified_exts);
    if (res != VK_SUCCESS) return res;

    /* Allocate device data. */
    device_data *data = (device_data *)calloc(1, sizeof(device_data));
    if (!data) return VK_ERROR_OUT_OF_HOST_MEMORY;
    data->device = *pDevice;
    data->physical_device = physicalDevice;
    data->inst_data = inst_data;
    data->vtable.get_device_proc_addr = fp_gdpa;

    /* NOTE: The dispatch table at offset 8 is NOT yet populated when
     * layer_create_device runs — winevulkan populates it AFTER vkCreateDevice
     * returns to the caller. So we must NOT read swapchain ptrs here.
     * Instead, we spawn a watcher thread that polls until the table is
     * populated, then patches it. See patch_dispatch_table() above. */
    data->queue_family = 0;
    data->graphics_queue = VK_NULL_HANDLE;
    if (pCreateInfo->queueCreateInfoCount > 0) {
        data->queue_family = pCreateInfo->pQueueCreateInfos[0].queueFamilyIndex;
    }

    pthread_mutex_lock(&g_device_lock);
    data->next = g_devices;
    g_devices = data;
    pthread_mutex_unlock(&g_device_lock);

    /* Initialize AHB native handle resolver. */
    init_ahb_native_handle();

    /* Spawn a watcher thread that waits for the dispatch table to be
     * fully populated, then patches it with our hooks. This decouples
     * patching from the vkCreateDevice call chain. */
    if (atomic_load(&g_enabled)) {
        int patch_rc = patch_dispatch_table(*pDevice);
        if (patch_rc == 0) {
            LOGI("layer_create_device: watcher thread spawned — will patch when table is ready");
        } else {
            LOGE("layer_create_device: failed to spawn watcher thread");
        }
    } else {
        LOGI("layer_create_device: layer disabled — skipping dispatch table patch");
    }

    LOGI("layer_create_device: device=%p family=%u",
         (void *)*pDevice, data->queue_family);
    return VK_SUCCESS;
}

/* Lazy vtable resolver — called on first use of any device function.
 * At this point winevulkan's vkCreateDevice has completed and fp_gdpa
 * is safe to call. */

static void ensure_device_vtable(device_data *data) {
    if (data->vtable.destroy_device) return;  /* already resolved */
    if (!data->device) return;

    /* Resolve all device functions via fp_gdpa. This is safe because
     * ensure_device_vtable is only called from layer_create_swapchain,
     * which fires AFTER winevulkan's vkCreateDevice has fully completed. */
    PFN_vkGetDeviceProcAddr fp_gdpa = data->vtable.get_device_proc_addr;
    if (fp_gdpa) {
        data->vtable.destroy_device = (PFN_vkDestroyDevice)
            fp_gdpa(data->device, "vkDestroyDevice");
        data->vtable.get_device_queue = (PFN_vkGetDeviceQueue)
            fp_gdpa(data->device, "vkGetDeviceQueue");
        data->vtable.queue_submit = (PFN_vkQueueSubmit)
            fp_gdpa(data->device, "vkQueueSubmit");
        data->vtable.queue_wait_idle = (PFN_vkQueueWaitIdle)
            fp_gdpa(data->device, "vkQueueWaitIdle");
        data->vtable.create_fence = (PFN_vkCreateFence)
            fp_gdpa(data->device, "vkCreateFence");
        data->vtable.destroy_fence = (PFN_vkDestroyFence)
            fp_gdpa(data->device, "vkDestroyFence");
        data->vtable.wait_for_fences = (PFN_vkWaitForFences)
            fp_gdpa(data->device, "vkWaitForFences");
        data->vtable.reset_fences = (PFN_vkResetFences)
            fp_gdpa(data->device, "vkResetFences");
        data->vtable.create_command_pool = (PFN_vkCreateCommandPool)
            fp_gdpa(data->device, "vkCreateCommandPool");
        data->vtable.destroy_command_pool = (PFN_vkDestroyCommandPool)
            fp_gdpa(data->device, "vkDestroyCommandPool");
        data->vtable.allocate_command_buffers = (PFN_vkAllocateCommandBuffers)
            fp_gdpa(data->device, "vkAllocateCommandBuffers");
        data->vtable.free_command_buffers = (PFN_vkFreeCommandBuffers)
            fp_gdpa(data->device, "vkFreeCommandBuffers");
        data->vtable.begin_command_buffer = (PFN_vkBeginCommandBuffer)
            fp_gdpa(data->device, "vkBeginCommandBuffer");
        data->vtable.end_command_buffer = (PFN_vkEndCommandBuffer)
            fp_gdpa(data->device, "vkEndCommandBuffer");
        data->vtable.create_image = (PFN_vkCreateImage)
            fp_gdpa(data->device, "vkCreateImage");
        data->vtable.destroy_image = (PFN_vkDestroyImage)
            fp_gdpa(data->device, "vkDestroyImage");
        data->vtable.allocate_memory = (PFN_vkAllocateMemory)
            fp_gdpa(data->device, "vkAllocateMemory");
        data->vtable.free_memory = (PFN_vkFreeMemory)
            fp_gdpa(data->device, "vkFreeMemory");
        data->vtable.bind_image_memory = (PFN_vkBindImageMemory)
            fp_gdpa(data->device, "vkBindImageMemory");
        data->vtable.get_image_memory_requirements2 = (PFN_vkGetImageMemoryRequirements2)
            fp_gdpa(data->device, "vkGetImageMemoryRequirements2");
        data->vtable.get_memory_fd = (PFN_vkGetMemoryFdKHR)
            fp_gdpa(data->device, "vkGetMemoryFdKHR");
        /* Also resolve swapchain functions for pass-through fallback. */
        data->vtable.real_create_swapchain = (PFN_vkCreateSwapchainKHR)
            fp_gdpa(data->device, "vkCreateSwapchainKHR");
        data->vtable.real_destroy_swapchain = (PFN_vkDestroySwapchainKHR)
            fp_gdpa(data->device, "vkDestroySwapchainKHR");
        data->vtable.real_get_swapchain_images = (PFN_vkGetSwapchainImagesKHR)
            fp_gdpa(data->device, "vkGetSwapchainImagesKHR");
        data->vtable.real_acquire_next_image = (PFN_vkAcquireNextImageKHR)
            fp_gdpa(data->device, "vkAcquireNextImageKHR");
        data->vtable.real_queue_present = (PFN_vkQueuePresentKHR)
            fp_gdpa(data->device, "vkQueuePresentKHR");
    }

    /* Get the graphics queue. */
    if (data->graphics_queue == VK_NULL_HANDLE && data->queue_family >= 0 &&
        data->vtable.get_device_queue) {
        data->vtable.get_device_queue(data->device, data->queue_family, 0,
                                      &data->graphics_queue);
    }

    LOGI("ensure_device_vtable: resolved for device=%p queue=%p",
         (void *)data->device, (void *)data->graphics_queue);
    /* Note: dispatch table patching is handled by the watcher thread
     * spawned in layer_create_device. No need to patch here. */
}

static void layer_destroy_device(VkDevice device,
                                 const VkAllocationCallbacks *pAllocator) {
    device_data *data = find_device_data(device);
    if (!data) return;
    PFN_vkDestroyDevice fp_destroy = data->vtable.destroy_device;

    pthread_mutex_lock(&g_device_lock);
    device_data **pp = &g_devices;
    while (*pp) {
        if (*pp == data) { *pp = data->next; break; }
        pp = &(*pp)->next;
    }
    pthread_mutex_unlock(&g_device_lock);
    free(data);
    if (fp_destroy) fp_destroy(device, pAllocator);
}

/* ------------------------------------------------------------------ */
/* Layer entry points (GIPA / GDPA)                                   */
/* ------------------------------------------------------------------ */

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
layer_get_device_proc_addr(VkDevice device, const char *pName);

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
layer_get_instance_proc_addr(VkInstance instance, const char *pName) {
    if (!pName) return NULL;

    /* Core functions the layer implements. */
    if (strcmp(pName, "vkGetInstanceProcAddr") == 0)
        return (PFN_vkVoidFunction)layer_get_instance_proc_addr;
    if (strcmp(pName, "vkGetDeviceProcAddr") == 0)
        return (PFN_vkVoidFunction)layer_get_device_proc_addr;
    if (strcmp(pName, "vkCreateInstance") == 0)
        return (PFN_vkVoidFunction)layer_create_instance;
    if (strcmp(pName, "vkDestroyInstance") == 0)
        return (PFN_vkVoidFunction)layer_destroy_instance;
    if (strcmp(pName, "vkCreateDevice") == 0)
        return (PFN_vkVoidFunction)layer_create_device;
    if (strcmp(pName, "vkDestroyDevice") == 0)
        return (PFN_vkVoidFunction)layer_destroy_device;
    if (strcmp(pName, "vkCreateSwapchainKHR") == 0)
        return (PFN_vkVoidFunction)layer_create_swapchain;
    if (strcmp(pName, "vkDestroySwapchainKHR") == 0)
        return (PFN_vkVoidFunction)layer_destroy_swapchain;
    if (strcmp(pName, "vkGetSwapchainImagesKHR") == 0)
        return (PFN_vkVoidFunction)layer_get_swapchain_images;
    if (strcmp(pName, "vkAcquireNextImageKHR") == 0)
        return (PFN_vkVoidFunction)layer_acquire_next_image;
    if (strcmp(pName, "vkQueuePresentKHR") == 0)
        return (PFN_vkVoidFunction)layer_queue_present;

    /* Pass through to the next layer. */
    if (instance) {
        instance_data *data = find_instance_data(instance);
        if (data && data->vtable.gipa) {
            return data->vtable.gipa(instance, pName);
        }
    }
    return NULL;
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
layer_get_device_proc_addr(VkDevice device, const char *pName) {
    if (!pName) return NULL;

    /* Do NOT trigger ensure_device_vtable here — winevulkan calls
     * vkGetDeviceProcAddr during vkCreateDevice to populate its dispatch
     * table, and calling fp_gdpa at that point triggers assertions.
     * The dispatch table is patched from layer_create_device instead. */

    /* Device-level functions the layer implements. */
    if (strcmp(pName, "vkGetDeviceProcAddr") == 0)
        return (PFN_vkVoidFunction)layer_get_device_proc_addr;
    if (strcmp(pName, "vkCreateSwapchainKHR") == 0)
        return (PFN_vkVoidFunction)layer_create_swapchain;
    if (strcmp(pName, "vkDestroySwapchainKHR") == 0)
        return (PFN_vkVoidFunction)layer_destroy_swapchain;
    if (strcmp(pName, "vkGetSwapchainImagesKHR") == 0)
        return (PFN_vkVoidFunction)layer_get_swapchain_images;
    if (strcmp(pName, "vkAcquireNextImageKHR") == 0)
        return (PFN_vkVoidFunction)layer_acquire_next_image;
    if (strcmp(pName, "vkQueuePresentKHR") == 0)
        return (PFN_vkVoidFunction)layer_queue_present;
    if (strcmp(pName, "vkDestroyDevice") == 0)
        return (PFN_vkVoidFunction)layer_destroy_device;

    /* Pass through to the next layer. */
    if (device) {
        device_data *data = find_device_data(device);
        if (data && data->vtable.get_device_proc_addr) {
            return data->vtable.get_device_proc_addr(device, pName);
        }
    }
    return NULL;
}

/* ------------------------------------------------------------------ */
/* Layer enumeration (manifest exports)                               */
/* ------------------------------------------------------------------ */

VK_LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL
vkEnumerateInstanceLayerProperties(uint32_t *pPropertyCount,
                                   VkLayerProperties *pProperties) {
    if (!pProperties) {
        *pPropertyCount = 1;
        return VK_SUCCESS;
    }
    if (*pPropertyCount < 1) {
        *pPropertyCount = 0;
        return VK_INCOMPLETE;
    }
    memset(pProperties, 0, sizeof(VkLayerProperties));
    strncpy(pProperties[0].layerName, "VK_LAYER_waylandie_dmabuf",
            sizeof(pProperties[0].layerName) - 1);
    strncpy(pProperties[0].description, "WayLandIE dmabuf zero-copy present layer",
            sizeof(pProperties[0].description) - 1);
    pProperties[0].implementationVersion = 1;
    pProperties[0].specVersion = VK_MAKE_VERSION(1, 3, 0);
    *pPropertyCount = 1;
    return VK_SUCCESS;
}

VK_LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL
vkEnumerateInstanceExtensionProperties(const char *pLayerName,
                                       uint32_t *pPropertyCount,
                                       VkExtensionProperties *pProperties) {
    if (pLayerName && strcmp(pLayerName, "VK_LAYER_waylandie_dmabuf") == 0) {
        /* The layer doesn't add new instance extensions. */
        if (!pProperties) {
            *pPropertyCount = 0;
            return VK_SUCCESS;
        }
        *pPropertyCount = 0;
        return VK_SUCCESS;
    }
    /* Not our layer — return 0 to let the loader continue. */
    *pPropertyCount = 0;
    return VK_SUCCESS;
}

/* The loader calls these exported names to get our dispatch. */
VK_LAYER_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
vkGetInstanceProcAddr(VkInstance instance, const char *pName) {
    return layer_get_instance_proc_addr(instance, pName);
}

VK_LAYER_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
vkGetDeviceProcAddr(VkDevice device, const char *pName) {
    return layer_get_device_proc_addr(device, pName);
}
