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
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID get_ahb_properties;
    PFN_vkGetImageMemoryRequirements2 get_image_memory_requirements2;
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
/* AHB-backed virtual swapchain                                       */
/* ------------------------------------------------------------------ */

typedef struct {
    AHardwareBuffer *ahb;
    VkImage image;
    VkDeviceMemory memory;
    VkDeviceSize allocation_size;
    uint32_t width, height, stride;
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
/* Layer create/destroy swapchain (AHB-backed)                        */
/* ------------------------------------------------------------------ */

static VkResult create_ahb_backed_image(device_data *dev_data,
                                        VkFormat format,
                                        VkExtent2D extent,
                                        VkImageUsageFlags usage,
                                        swapchain_image *out) {
    memset(out, 0, sizeof(*out));

    /* If the AHB properties function isn't available, we can't import AHBs. */
    if (!dev_data->vtable.get_ahb_properties) {
        LOGE("vkGetAndroidHardwareBufferPropertiesANDROID not available — "
             "VK_ANDROID_external_memory_android_hardware_buffer missing");
        return VK_ERROR_EXTENSION_NOT_PRESENT;
    }

    /* 1. Allocate AHardwareBuffer. */
    AHardwareBuffer_Desc ahb_desc;
    memset(&ahb_desc, 0, sizeof(ahb_desc));
    ahb_desc.width = extent.width;
    ahb_desc.height = extent.height;
    ahb_desc.layers = 1;
    ahb_desc.format = vk_format_to_ahb(format);
    ahb_desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE
                   | AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER
                   | AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN
                   | AHARDWAREBUFFER_USAGE_CPU_WRITE_NEVER;
    ahb_desc.rfu0 = 0;
    ahb_desc.rfu1 = 0;

    int rc = AHardwareBuffer_allocate(&ahb_desc, &out->ahb);
    if (rc != 0 || !out->ahb) {
        LOGE("AHardwareBuffer_allocate failed rc=%d %dx%d fmt=%u",
             rc, extent.width, extent.height, ahb_desc.format);
        return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    }

    /* Query actual stride from the allocated AHB. */
    AHardwareBuffer_Desc actual_desc;
    AHardwareBuffer_describe(out->ahb, &actual_desc);
    out->width = actual_desc.width;
    out->height = actual_desc.height;
    out->stride = actual_desc.stride;
    out->drm_format = vk_format_to_drm(format);

    /* 2. Get AHB memory properties from Vulkan. */
    VkAndroidHardwareBufferPropertiesANDROID ahb_props;
    memset(&ahb_props, 0, sizeof(ahb_props));
    ahb_props.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    ahb_props.pNext = NULL;
    VkResult res = dev_data->vtable.get_ahb_properties(
        dev_data->device, out->ahb, &ahb_props);
    if (res != VK_SUCCESS) {
        LOGE("vkGetAndroidHardwareBufferPropertiesANDROID failed res=%d", res);
        goto err_ahb;
    }
    out->allocation_size = ahb_props.allocationSize;

    /* 3. Find a compatible memory type. */
    VkPhysicalDeviceMemoryProperties mem_props;
    dev_data->inst_data->vtable.get_physical_device_memory_properties(
        dev_data->physical_device, &mem_props);
    uint32_t mem_type_index = 0;
    bool found = false;
    for (uint32_t i = 0; i < mem_props.memoryTypeCount; i++) {
        if ((ahb_props.memoryTypeBits & (1u << i)) &&
            (mem_props.memoryTypes[i].propertyFlags &
             VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
            mem_type_index = i;
            found = true;
            break;
        }
    }
    if (!found) {
        for (uint32_t i = 0; i < mem_props.memoryTypeCount; i++) {
            if (ahb_props.memoryTypeBits & (1u << i)) {
                mem_type_index = i;
                found = true;
                break;
            }
        }
    }
    if (!found) {
        LOGE("no compatible memory type for AHB import (bits=0x%x)",
             ahb_props.memoryTypeBits);
        goto err_ahb;
    }

    /* 4. Allocate (import) device memory backed by the AHB. */
    VkImportAndroidHardwareBufferInfoANDROID import_info;
    memset(&import_info, 0, sizeof(import_info));
    import_info.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    import_info.pNext = NULL;
    import_info.buffer = out->ahb;

    VkMemoryAllocateInfo alloc_info;
    memset(&alloc_info, 0, sizeof(alloc_info));
    alloc_info.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc_info.pNext = &import_info;
    alloc_info.allocationSize = ahb_props.allocationSize;
    alloc_info.memoryTypeIndex = mem_type_index;

    res = dev_data->vtable.allocate_memory(dev_data->device, &alloc_info, NULL, &out->memory);
    if (res != VK_SUCCESS) {
        LOGE("vkAllocateMemory (AHB import) failed res=%d", res);
        goto err_ahb;
    }

    /* 5. Create image with external memory handle type AHB. */
    VkExternalMemoryImageCreateInfo ext_mem_info;
    memset(&ext_mem_info, 0, sizeof(ext_mem_info));
    ext_mem_info.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    ext_mem_info.pNext = NULL;
    ext_mem_info.handleTypes =
        VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

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
    /* Use the application's requested usage flags, OR in the ones we need
     * for dmabuf export and internal operations. */
    img_info.usage = usage
                   | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                   | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                   | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                   | VK_IMAGE_USAGE_SAMPLED_BIT;
    img_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    img_info.queueFamilyIndexCount = 0;
    img_info.pQueueFamilyIndices = NULL;
    img_info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    res = dev_data->vtable.create_image(dev_data->device, &img_info, NULL, &out->image);
    if (res != VK_SUCCESS) {
        LOGE("vkCreateImage (AHB-backed) failed res=%d", res);
        goto err_mem;
    }

    /* 6. Bind imported memory to the image. */
    res = dev_data->vtable.bind_image_memory(dev_data->device, out->image, out->memory, 0);
    if (res != VK_SUCCESS) {
        LOGE("vkBindImageMemory (AHB-backed) failed res=%d", res);
        goto err_img;
    }

    /* 7. Create a fence for render completion signaling. */
    VkFenceCreateInfo fence_info;
    memset(&fence_info, 0, sizeof(fence_info));
    fence_info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    res = dev_data->vtable.create_fence(dev_data->device, &fence_info, NULL, &out->render_fence);
    if (res != VK_SUCCESS) {
        LOGE("vkCreateFence (render fence) failed res=%d", res);
        goto err_img;
    }

    out->in_use = false;
    LOGI("created AHB image %dx%d stride=%u drm=0x%08x",
         out->width, out->height, out->stride, out->drm_format);
    return VK_SUCCESS;

err_img:
    dev_data->vtable.destroy_image(dev_data->device, out->image, NULL);
    out->image = VK_NULL_HANDLE;
err_mem:
    dev_data->vtable.free_memory(dev_data->device, out->memory, NULL);
    out->memory = VK_NULL_HANDLE;
err_ahb:
    AHardwareBuffer_release(out->ahb);
    out->ahb = NULL;
    return res != VK_SUCCESS ? res : VK_ERROR_OUT_OF_DEVICE_MEMORY;
}

static void destroy_ahb_backed_image(device_data *dev_data, swapchain_image *img) {
    if (img->render_fence != VK_NULL_HANDLE) {
        dev_data->vtable.destroy_fence(dev_data->device, img->render_fence, NULL);
        img->render_fence = VK_NULL_HANDLE;
    }
    if (img->image != VK_NULL_HANDLE) {
        dev_data->vtable.destroy_image(dev_data->device, img->image, NULL);
        img->image = VK_NULL_HANDLE;
    }
    if (img->memory != VK_NULL_HANDLE) {
        dev_data->vtable.free_memory(dev_data->device, img->memory, NULL);
        img->memory = VK_NULL_HANDLE;
    }
    if (img->ahb) {
        AHardwareBuffer_release(img->ahb);
        img->ahb = NULL;
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
        VkResult res = create_ahb_backed_image(dev_data, sw->format, sw->extent,
                                                pCreateInfo->imageUsage, &sw->images[i]);
        if (res != VK_SUCCESS) {
            LOGE("create_ahb_backed_image failed for image %u", i);
            for (uint32_t j = 0; j < i; j++) {
                destroy_ahb_backed_image(dev_data, &sw->images[j]);
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
            destroy_ahb_backed_image(dev_data, &sw->images[i]);
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
            destroy_ahb_backed_image(dev_data, &sw->images[i]);
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
            destroy_ahb_backed_image(dev_data, &sw->images[i]);
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
        destroy_ahb_backed_image(sw->dev_data, &sw->images[i]);
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

        /* Export dmabuf fd from the AHB. */
        int dmabuf_fd = ahb_get_dmabuf_fd(img->ahb);
        if (dmabuf_fd < 0) {
            LOGE("ahb_get_dmabuf_fd failed for image %u", img_idx);
            final_result = VK_ERROR_OUT_OF_DEVICE_MEMORY;
            continue;
        }

        /* Compute dmabuf size. */
        uint64_t dmabuf_size = (uint64_t)img->stride * (uint64_t)img->height * 4ULL;
        (void)dmabuf_size;

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
                                             img->stride,
                                             (uint64_t)img->stride * img->height * 4ULL);
            if (send_rc != 0) {
                LOGW("bridge_send_dmabuf failed — reconnecting");
                close(sw->bridge_sock);
                sw->bridge_sock = bridge_connect(sw->bridge_socket_name);
                if (sw->bridge_sock >= 0) {
                    bridge_send_dmabuf(sw->bridge_sock, dmabuf_fd,
                                       img->width, img->height,
                                       img->drm_format, 0ULL,
                                       img->stride,
                                       (uint64_t)img->stride * img->height * 4ULL);
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
                 img->width, img->height, img->stride, img->drm_format, dmabuf_fd);
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
/* winevulkan.so builds its OWN dispatch table by calling             */
/* vkGetDeviceProcAddr once per function name at vkCreateDevice       */
/* time. After that, all per-frame calls (vkQueuePresentKHR, etc.)    */
/* go through that table — NOT through the Vulkan loader's layer      */
/* chain. So our implicit layer hooks are bypassed for swapchain and  */
/* present calls.                                                     */
/*                                                                    */
/* Fix: after the real vkCreateDevice returns, we walk the dispatch   */
/* table at *(void***)*pDevice looking for the real driver's          */
/* vkCreateSwapchainKHR / vkQueuePresentKHR / etc. pointers, and      */
/* overwrite them in-place with our hooks. The table is in mmap'd     */
/* memory, so we mprotect it RW first.                                */
/*                                                                    */
/* This is the same technique MangoHud, vkBasalt, and adrenotools     */
/* use to inject into winevulkan's dispatch path.                     */
/* ------------------------------------------------------------------ */

/* winevulkan's dispatch table has ~1024+ entries (one per Vulkan      */
/* function). We scan a generous range to find our targets.            */
#define WINEVULKAN_DISPATCH_TABLE_MAX_ENTRIES 2048

static void *g_real_create_swapchain = NULL;
static void *g_real_destroy_swapchain = NULL;
static void *g_real_get_swapchain_images = NULL;
static void *g_real_acquire_next_image = NULL;
static void *g_real_queue_present = NULL;

static int patch_dispatch_table(VkDevice device) {
    /* winevulkan's VkDevice handle is a `struct wine_vk_device` whose layout is:
     *   struct wine_vk_device {
     *       struct wine_vk_base base;            // offset 0: { void *loader_magic; ... }
     *       struct vulkan_device_funcs funcs;    // offset 8: THE dispatch table (array of fn ptrs)
     *       VkDevice host_device;                // offset 8 + sizeof(funcs)
     *       ...
     *   };
     *
     * The first 8 bytes are the loader dispatch magic (0x10aded040410aded),
     * NOT a pointer to the dispatch table. The actual function pointers live
     * in the `funcs` struct at offset 8.
     *
     * We try multiple base offsets (8, 16, 0, 24) to find the dispatch table
     * empirically — winevulkan versions may differ slightly. */
    void *device_base = (void *)device;
    void **dispatch_table = NULL;
    int table_base_offset = -1;

    /* Try offsets 8, 16, 0, 24, 32 bytes. Look for a table with many non-NULL
     * entries (a real dispatch table has hundreds of function pointers). */
    int offsets_to_try[] = {8, 16, 0, 24, 32};
    int best_offset = -1;
    int best_non_null = 0;
    for (size_t i = 0; i < sizeof(offsets_to_try)/sizeof(offsets_to_try[0]); i++) {
        int off = offsets_to_try[i];
        void **candidate = (void **)((char *)device_base + off);
        /* Count non-NULL entries in the first 256 slots. */
        int non_null = 0;
        for (int j = 0; j < 256; j++) {
            if (candidate[j]) non_null++;
        }
        LOGI("patch_dispatch_table: offset %d: first entry=%p, %d/256 non-null",
             off, candidate[0], non_null);
        if (non_null > best_non_null) {
            best_non_null = non_null;
            best_offset = off;
        }
        /* Heuristic: a real dispatch table has at least 50 non-NULL entries. */
        if (non_null >= 50) {
            dispatch_table = candidate;
            table_base_offset = off;
            break;
        }
    }
    if (!dispatch_table) {
        /* Fall back to the best offset found, even if it has few entries. */
        if (best_offset >= 0) {
            dispatch_table = (void **)((char *)device_base + best_offset);
            table_base_offset = best_offset;
            LOGW("patch_dispatch_table: no offset had >=50 entries, using offset %d (%d non-null)",
                 best_offset, best_non_null);
        } else {
            LOGE("patch_dispatch_table: could not find dispatch table");
            return -1;
        }
    }
    LOGI("patch_dispatch_table: using dispatch_table at offset %d = %p (device=%p)",
         table_base_offset, (void *)dispatch_table, (void *)device);

    /* We need to find our target function pointers by comparing against
     * the ones we resolved via vkGetDeviceProcAddr. */
    device_data *dev_data = find_device_data(device);
    if (!dev_data) {
        LOGE("patch_dispatch_table: no device_data for %p", (void *)device);
        return -1;
    }

    /* Get the real driver's function pointers via GDPA. These are what
     * winevulkan stored in its dispatch table during vkCreateDevice. */
    void *target_create_swapchain = (void *)dev_data->vtable.real_create_swapchain;
    void *target_destroy_swapchain = (void *)dev_data->vtable.real_destroy_swapchain;
    void *target_get_swapchain_images = (void *)dev_data->vtable.real_get_swapchain_images;
    void *target_acquire_next_image = (void *)dev_data->vtable.real_acquire_next_image;
    void *target_queue_present = (void *)dev_data->vtable.real_queue_present;

    LOGI("patch_dispatch_table: dispatch_table=%p device=%p", (void *)dispatch_table, (void *)device);
    LOGI("patch_dispatch_table: GDPA targets: swapchain=%p present=%p acquire=%p",
         target_create_swapchain, target_queue_present, target_acquire_next_image);

    /* If real_create_swapchain is NULL, we couldn't resolve it via GDPA.
     * This happens if VK_KHR_swapchain isn't enabled — abort gracefully. */
    if (!target_create_swapchain || !target_queue_present) {
        LOGW("patch_dispatch_table: swapchain functions not available — skipping patch");
        return -1;
    }

    /* DIAGNOSTIC: Dump the first 64 entries of the dispatch table so we can
     * see what's actually there. winevulkan's dispatch table contains its
     * OWN thunk wrappers (wine_vkCreateSwapchainKHR etc.), not the raw
     * driver functions. The GDPA targets above are the raw driver functions
     * (or the next layer's wrappers), so they won't match.
     *
     * We dump:
     *   - First 16 entries (core Vulkan functions)
     *   - Any entries that match our GDPA targets
     *   - Total non-NULL entry count
     * This lets us determine the table layout empirically. */
    int total_non_null = 0;
    int match_count = 0;
    for (int i = 0; i < WINEVULKAN_DISPATCH_TABLE_MAX_ENTRIES; i++) {
        void *entry = dispatch_table[i];
        if (!entry) continue;
        total_non_null++;
        if (i < 16) {
            LOGI("patch_dispatch_table: [%d] = %p", i, entry);
        }
        if (entry == target_create_swapchain) {
            LOGI("patch_dispatch_table: [%d] MATCHES create_swapchain target!", i);
            match_count++;
        }
        if (entry == target_queue_present) {
            LOGI("patch_dispatch_table: [%d] MATCHES queue_present target!", i);
            match_count++;
        }
        if (entry == target_acquire_next_image) {
            LOGI("patch_dispatch_table: [%d] MATCHES acquire_next_image target!", i);
            match_count++;
        }
    }
    LOGI("patch_dispatch_table: scanned %d entries, %d non-null, %d matches",
         WINEVULKAN_DISPATCH_TABLE_MAX_ENTRIES, total_non_null, match_count);

    /* If we found no matches, the dispatch table contains winevulkan's
     * thunks, not the raw driver functions. We need a different strategy:
     * dlopen winevulkan.so and dlsym its thunk functions to get their
     * addresses, then match those. */
    if (match_count == 0) {
        LOGW("patch_dispatch_table: no matches via GDPA targets — trying winevulkan thunk addresses");

        /* Try to dlopen winevulkan.so and get the thunk addresses. */
        void *winevulkan = dlopen("winevulkan.so", RTLD_NOW | RTLD_NOLOAD);
        if (!winevulkan) {
            winevulkan = dlopen("winevulkan.dll.so", RTLD_NOW | RTLD_NOLOAD);
        }
        if (!winevulkan) {
            const char *err = dlerror();
            LOGW("patch_dispatch_table: dlopen winevulkan failed: %s — trying dladdr", err ? err : "(no error)");
            /* Use dladdr on the GDPA targets to find the loaded module. */
            Dl_info info;
            memset(&info, 0, sizeof(info));
            if (dladdr((void *)target_create_swapchain, &info)) {
                LOGI("patch_dispatch_table: dladdr create_swapchain: fname=%s fbase=%p sname=%s saddr=%p",
                     info.dli_fname ? info.dli_fname : "(null)",
                     info.dli_fbase,
                     info.dli_sname ? info.dli_sname : "(null)",
                     info.dli_saddr);
                if (info.dli_fname) {
                    winevulkan = dlopen(info.dli_fname, RTLD_NOW | RTLD_NOLOAD);
                    if (winevulkan) {
                        LOGI("patch_dispatch_table: opened %s via dladdr", info.dli_fname);
                    } else {
                        LOGW("patch_dispatch_table: dlopen(%s) failed: %s",
                             info.dli_fname, dlerror());
                    }
                }
            } else {
                LOGW("patch_dispatch_table: dladdr failed for %p", (void *)target_create_swapchain);
            }
        }

        if (winevulkan) {
            /* winevulkan's thunks are named wine_vkXxxKHR (or wine_vkXxx).
             * dlsym them and use as targets. */
            void *thunk_create_swapchain = dlsym(winevulkan, "wine_vkCreateSwapchainKHR");
            void *thunk_destroy_swapchain = dlsym(winevulkan, "wine_vkDestroySwapchainKHR");
            void *thunk_get_swapchain_images = dlsym(winevulkan, "wine_vkGetSwapchainImagesKHR");
            void *thunk_acquire_next_image = dlsym(winevulkan, "wine_vkAcquireNextImageKHR");
            void *thunk_queue_present = dlsym(winevulkan, "wine_vkQueuePresentKHR");

            LOGI("patch_dispatch_table: winevulkan thunks: swapchain=%p present=%p acquire=%p",
                 thunk_create_swapchain, thunk_queue_present, thunk_acquire_next_image);

            if (thunk_create_swapchain) target_create_swapchain = thunk_create_swapchain;
            if (thunk_destroy_swapchain) target_destroy_swapchain = thunk_destroy_swapchain;
            if (thunk_get_swapchain_images) target_get_swapchain_images = thunk_get_swapchain_images;
            if (thunk_acquire_next_image) target_acquire_next_image = thunk_acquire_next_image;
            if (thunk_queue_present) target_queue_present = thunk_queue_present;

            /* Also try without the "wine_" prefix — some builds export vkXxx. */
            if (!thunk_create_swapchain) {
                void *alt = dlsym(winevulkan, "vkCreateSwapchainKHR");
                if (alt) {
                    target_create_swapchain = alt;
                    LOGI("patch_dispatch_table: alt vkCreateSwapchainKHR=%p", alt);
                }
            }
        } else {
            LOGE("patch_dispatch_table: could not open winevulkan — cannot match thunks");
        }
    }

    /* Determine the page-aligned region of the dispatch table for mprotect. */
    uintptr_t table_start = (uintptr_t)dispatch_table;
    uintptr_t table_end = table_start + (WINEVULKAN_DISPATCH_TABLE_MAX_ENTRIES * sizeof(void *));
    uintptr_t page_start = table_start & ~(uintptr_t)(sysconf(_SC_PAGE_SIZE) - 1);
    uintptr_t page_end = (table_end + sysconf(_SC_PAGE_SIZE) - 1) & ~(uintptr_t)(sysconf(_SC_PAGE_SIZE) - 1);
    size_t prot_len = page_end - page_start;

    /* Make the table writable. */
    if (mprotect((void *)page_start, prot_len, PROT_READ | PROT_WRITE) != 0) {
        LOGE("patch_dispatch_table: mprotect RW failed errno=%d (%s)", errno, strerror(errno));
        return -1;
    }

    int found_create_swapchain = 0, found_destroy_swapchain = 0;
    int found_get_swapchain_images = 0, found_acquire_next_image = 0;
    int found_queue_present = 0;
    int patched_count = 0;

    /* Scan the table for our target pointers. */
    for (int i = 0; i < WINEVULKAN_DISPATCH_TABLE_MAX_ENTRIES; i++) {
        void *entry = dispatch_table[i];
        if (entry == target_create_swapchain && !found_create_swapchain) {
            dispatch_table[i] = (void *)layer_create_swapchain;
            g_real_create_swapchain = entry;
            found_create_swapchain = 1;
            patched_count++;
            LOGI("patch_dispatch_table: [%d] swapchain: %p -> %p",
                 i, entry, (void *)layer_create_swapchain);
        } else if (entry == target_destroy_swapchain && !found_destroy_swapchain) {
            dispatch_table[i] = (void *)layer_destroy_swapchain;
            g_real_destroy_swapchain = entry;
            found_destroy_swapchain = 1;
            patched_count++;
            LOGI("patch_dispatch_table: [%d] destroy_swapchain: %p -> %p",
                 i, entry, (void *)layer_destroy_swapchain);
        } else if (entry == target_get_swapchain_images && !found_get_swapchain_images) {
            dispatch_table[i] = (void *)layer_get_swapchain_images;
            g_real_get_swapchain_images = entry;
            found_get_swapchain_images = 1;
            patched_count++;
            LOGI("patch_dispatch_table: [%d] get_swapchain_images: %p -> %p",
                 i, entry, (void *)layer_get_swapchain_images);
        } else if (entry == target_acquire_next_image && !found_acquire_next_image) {
            dispatch_table[i] = (void *)layer_acquire_next_image;
            g_real_acquire_next_image = entry;
            found_acquire_next_image = 1;
            patched_count++;
            LOGI("patch_dispatch_table: [%d] acquire_next_image: %p -> %p",
                 i, entry, (void *)layer_acquire_next_image);
        } else if (entry == target_queue_present && !found_queue_present) {
            dispatch_table[i] = (void *)layer_queue_present;
            g_real_queue_present = entry;
            found_queue_present = 1;
            patched_count++;
            LOGI("patch_dispatch_table: [%d] queue_present: %p -> %p",
                 i, entry, (void *)layer_queue_present);
        }
        if (patched_count == 5) break;
    }

    /* If we found destroy_swapchain but NOT create_swapchain, the create
     * entry is at index (destroy_index - 1). winevulkan's dispatch table
     * stores functions in alphabetical-ish order: CreateSwapchainKHR is
     * right before DestroySwapchainKHR. Patch by index. */
    if (!found_create_swapchain && found_destroy_swapchain) {
        /* Re-scan to find the destroy_swapchain index. */
        for (int i = 1; i < WINEVULKAN_DISPATCH_TABLE_MAX_ENTRIES; i++) {
            if (dispatch_table[i] == (void *)layer_destroy_swapchain) {
                /* The entry at i-1 should be the original create_swapchain. */
                void *orig = dispatch_table[i - 1];
                if (orig && orig != (void *)layer_create_swapchain) {
                    dispatch_table[i - 1] = (void *)layer_create_swapchain;
                    g_real_create_swapchain = orig;
                    found_create_swapchain = 1;
                    patched_count++;
                    LOGI("patch_dispatch_table: [%d] swapchain (by index): %p -> %p",
                         i - 1, orig, (void *)layer_create_swapchain);
                }
                break;
            }
        }
    }

    /* Restore read-only protection (best effort — not fatal if it fails). */
    if (mprotect((void *)page_start, prot_len, PROT_READ) != 0) {
        LOGW("patch_dispatch_table: mprotect RO restore failed (non-fatal) errno=%d", errno);
    }

    LOGI("patch_dispatch_table: patched %d/5 functions (swapchain=%d destroy=%d images=%d acquire=%d present=%d)",
         patched_count, found_create_swapchain, found_destroy_swapchain,
         found_get_swapchain_images, found_acquire_next_image, found_queue_present);

    return patched_count > 0 ? 0 : -1;
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

    /* Call the next layer's vkCreateDevice. */
    VkResult res = fp_create_device(physicalDevice, pCreateInfo, pAllocator, pDevice);
    if (res != VK_SUCCESS) return res;

    /* Allocate device data. */
    device_data *data = (device_data *)calloc(1, sizeof(device_data));
    if (!data) return VK_ERROR_OUT_OF_HOST_MEMORY;
    data->device = *pDevice;
    data->physical_device = physicalDevice;
    data->inst_data = inst_data;
    data->vtable.get_device_proc_addr = fp_gdpa;

    /* Resolve device-level functions. NOTE: we must NOT call fp_gdpa here
     * because winevulkan's vkCreateDevice is still in progress — the device
     * dispatch table isn't fully initialized yet. Calling fp_gdpa now can
     * trigger assertions in winevulkan/loader.c (line 764: !status).
     *
     * Instead, we store fp_gdpa and resolve functions lazily on first use.
     * The dispatch table patching is also deferred — it happens on the first
     * call to layer_get_device_proc_addr (which DXVK calls AFTER
     * vkCreateDevice completes). */
    data->vtable.get_device_proc_addr = fp_gdpa;
    /* All other vtable entries are NULL — resolved lazily by
     * ensure_device_vtable() on first use. */

    /* Get the graphics queue. Use the first queue family from create info. */
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

    LOGI("layer_create_device: device=%p family=%u (vtable + patching deferred)",
         (void *)*pDevice, data->queue_family);
    return VK_SUCCESS;
}

/* Lazy vtable resolver — called on first use of any device function.
 * At this point winevulkan's vkCreateDevice has completed and fp_gdpa
 * is safe to call. Also triggers dispatch table patching once. */
static atomic_int g_dispatch_patched = 0;

static void ensure_device_vtable(device_data *data) {
    if (data->vtable.destroy_device) return;  /* already resolved */
    PFN_vkGetDeviceProcAddr fp_gdpa = data->vtable.get_device_proc_addr;
    if (!fp_gdpa || !data->device) return;

    data->vtable.destroy_device = (PFN_vkDestroyDevice)
        fp_gdpa(data->device, "vkDestroyDevice");
    data->vtable.get_device_queue = (PFN_vkGetDeviceQueue)
        fp_gdpa(data->device, "vkGetDeviceQueue");
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
    data->vtable.get_ahb_properties = NULL;  /* resolve on demand only */
    data->vtable.get_image_memory_requirements2 = (PFN_vkGetImageMemoryRequirements2)
        fp_gdpa(data->device, "vkGetImageMemoryRequirements2");

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

    /* Get the graphics queue now that get_device_queue is resolved. */
    if (data->graphics_queue == VK_NULL_HANDLE && data->queue_family >= 0) {
        data->vtable.get_device_queue(data->device, data->queue_family, 0,
                                      &data->graphics_queue);
    }

    LOGI("ensure_device_vtable: resolved for device=%p queue=%p",
         (void *)data->device, (void *)data->graphics_queue);

    /* Now patch the dispatch table — winevulkan's vkCreateDevice is done. */
    int expected = 0;
    if (atomic_load(&g_enabled) &&
        atomic_compare_exchange_strong(&g_dispatch_patched, &expected, 1)) {
        int patch_rc = patch_dispatch_table(data->device);
        if (patch_rc == 0) {
            LOGI("ensure_device_vtable: dispatch table patched — layer hooks active");
        } else {
            LOGE("ensure_device_vtable: dispatch table patching FAILED — layer will be bypassed!");
            atomic_store(&g_dispatch_patched, 0);  /* allow retry */
        }
    }
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

    /* Trigger lazy vtable resolution + dispatch table patching on first call.
     * DXVK calls vkGetDeviceProcAddr after vkCreateDevice completes, so
     * winevulkan's device is fully initialized at this point. */
    if (device) {
        device_data *data = find_device_data(device);
        if (data) ensure_device_vtable(data);
    }

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
