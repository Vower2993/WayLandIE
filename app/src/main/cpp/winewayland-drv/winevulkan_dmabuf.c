/*
 * WayLandIE dmabuf forwarding hooks for winevulkan.
 *
 * This file provides manual unix-side thunks for swapchain/present functions.
 * Instead of calling the host driver's vkCreateSwapchainKHR (which creates a
 * real swapchain that presents to ANativeWindow, bypassing our bridge), these
 * thunks create opaque-fd-backed images and forward their dmabuf fds to the
 * WaylandIE bridge socket on present.
 *
 * The bridge (waylandie.display.bridge.v1) receives the dmabuf and presents
 * it via SurfaceControl — zero CPU memcpy.
 *
 * Build: compiled into winevulkan.so alongside the generated thunks.
 * Enabled at runtime via WAYLANDIE_DMABUF_FORWARD=1 env var.
 */

#include "config.h"
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>

#include "vulkan_private.h"
#include "wine/vulkan_driver.h"
#include "wine/debug.h"

WINE_DEFAULT_DEBUG_CHANNEL(vulkan);

#define LOG_TAG "WayLandIE/Vk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define WAYLANDIE_MAX_SWAPCHAIN_IMAGES 8
#define WAYLANDIE_BRIDGE_SOCKET "waylandie.display.bridge.v1"

/* DRM fourcc codes for dmabuf-present command. */
#define DRM_FORMAT_ARGB8888 0x34325241U
#define DRM_FORMAT_ABGR8888 0x34324241U
#define DRM_FORMAT_RGBA8888 0x34424152U

/* Per-swapchain state for our virtual swapchain. */
struct waylandie_swapchain
{
    VkDevice device;
    uint32_t image_count;
    VkImage images[WAYLANDIE_MAX_SWAPCHAIN_IMAGES];
    VkDeviceMemory memory[WAYLANDIE_MAX_SWAPCHAIN_IMAGES];
    int dmabuf_fds[WAYLANDIE_MAX_SWAPCHAIN_IMAGES];
    VkExtent2D extent;
    VkFormat format;
    uint32_t drm_format;
    uint32_t acquire_index;
    uint64_t present_count;
    int bridge_sock;
    VkCommandPool command_pool;
    VkCommandBuffer scratch_cmd;
    VkFence scratch_fence;
    VkQueue queue;
};

static struct waylandie_swapchain *g_swapchains[16];
static int g_swapchain_count = 0;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_enabled = -1;

static int is_enabled(void)
{
    if (g_enabled < 0)
    {
        const char *env = getenv("WAYLANDIE_DMABUF_FORWARD");
        g_enabled = (env && env[0] == '1') ? 1 : 0;
        if (g_enabled)
            LOGI("WayLandIE dmabuf forwarding ENABLED");
        else
            LOGI("WayLandIE dmabuf forwarding disabled (WAYLANDIE_DMABUF_FORWARD not set)");
    }
    return g_enabled;
}

static uint32_t vk_format_to_drm(VkFormat format)
{
    switch (format)
    {
        case VK_FORMAT_B8G8R8A8_UNORM:
        case VK_FORMAT_B8G8R8A8_SRGB:
            return DRM_FORMAT_ARGB8888;
        case VK_FORMAT_R8G8B8A8_UNORM:
        case VK_FORMAT_R8G8B8A8_SRGB:
            return DRM_FORMAT_ABGR8888;
        case VK_FORMAT_A8B8G8R8_UNORM_PACK32:
        case VK_FORMAT_A8B8G8R8_SRGB_PACK32:
            return DRM_FORMAT_RGBA8888;
        default:
            return DRM_FORMAT_ARGB8888;
    }
}

static int bridge_connect(const char *socket_name)
{
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    size_t name_len = strlen(socket_name);
    if (name_len + 1 > sizeof(addr.sun_path)) { close(fd); return -1; }
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, socket_name, name_len);
    socklen_t addr_len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + name_len);
    if (connect(fd, (struct sockaddr *)&addr, addr_len) != 0)
    {
        close(fd);
        return -1;
    }
    return fd;
}

static int bridge_send_dmabuf(int sock, int dmabuf_fd,
                              uint32_t width, uint32_t height,
                              uint32_t drm_format, uint64_t modifier,
                              uint32_t stride, uint64_t size)
{
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

    char response[256];
    struct timeval tv = { .tv_sec = 1, .tv_usec = 0 };
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    ssize_t rlen = recv(sock, response, sizeof(response) - 1, 0);
    if (rlen > 0)
    {
        response[rlen] = '\0';
        if (strstr(response, "status=pass")) return 0;
    }
    return 0;
}

static struct waylandie_swapchain *find_swapchain(VkSwapchainKHR sw)
{
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < g_swapchain_count; i++)
    {
        if ((VkSwapchainKHR)(uintptr_t)g_swapchains[i] == sw)
        {
            pthread_mutex_unlock(&g_lock);
            return g_swapchains[i];
        }
    }
    pthread_mutex_unlock(&g_lock);
    return NULL;
}

/* Create an opaque-fd-backed image that we can export as dmabuf. */
static VkResult create_opaque_fd_image(struct vulkan_device *device,
                                       VkFormat format, VkExtent2D extent,
                                       VkImageUsageFlags usage,
                                       VkImage *out_image,
                                       VkDeviceMemory *out_memory,
                                       int *out_fd)
{
    VkExternalMemoryImageCreateInfo ext_mem;
    memset(&ext_mem, 0, sizeof(ext_mem));
    ext_mem.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    ext_mem.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT_KHR;

    VkImageCreateInfo img_info;
    memset(&img_info, 0, sizeof(img_info));
    img_info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    img_info.pNext = &ext_mem;
    img_info.imageType = VK_IMAGE_TYPE_2D;
    img_info.format = format;
    img_info.extent.width = extent.width;
    img_info.extent.height = extent.height;
    img_info.extent.depth = 1;
    img_info.mipLevels = 1;
    img_info.arrayLayers = 1;
    img_info.samples = VK_SAMPLE_COUNT_1_BIT;
    img_info.tiling = VK_IMAGE_TILING_OPTIMAL;
    img_info.usage = usage | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                   | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    img_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VkResult res = device->p_vkCreateImage(device->host.device, &img_info, NULL, out_image);
    if (res != VK_SUCCESS)
    {
        LOGE("vkCreateImage failed res=%d", res);
        return res;
    }

    VkMemoryRequirements2 mem_reqs2;
    memset(&mem_reqs2, 0, sizeof(mem_reqs2));
    mem_reqs2.sType = VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2;
    VkImageMemoryRequirementsInfo2 req_info;
    memset(&req_info, 0, sizeof(req_info));
    req_info.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_REQUIREMENTS_INFO_2;
    req_info.image = *out_image;
    device->p_vkGetImageMemoryRequirements2(device->host.device, &req_info, &mem_reqs2);

    /* physical_device already caches memory_properties. */
    VkPhysicalDeviceMemoryProperties *mem_props = &device->physical_device->memory_properties;

    uint32_t mem_type = 0;
    for (uint32_t i = 0; i < mem_props->memoryTypeCount; i++)
    {
        if ((mem_reqs2.memoryRequirements.memoryTypeBits & (1u << i)) &&
            (mem_props->memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
        {
            mem_type = i;
            break;
        }
    }

    VkMemoryAllocateInfo alloc_info;
    memset(&alloc_info, 0, sizeof(alloc_info));
    alloc_info.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc_info.allocationSize = mem_reqs2.memoryRequirements.size;
    alloc_info.memoryTypeIndex = mem_type;

    res = device->p_vkAllocateMemory(device->host.device, &alloc_info, NULL, out_memory);
    if (res != VK_SUCCESS)
    {
        LOGE("vkAllocateMemory failed res=%d", res);
        device->p_vkDestroyImage(device->host.device, *out_image, NULL);
        return res;
    }

    res = device->p_vkBindImageMemory(device->host.device, *out_image, *out_memory, 0);
    if (res != VK_SUCCESS)
    {
        LOGE("vkBindImageMemory failed res=%d", res);
        device->p_vkFreeMemory(device->host.device, *out_memory, NULL);
        device->p_vkDestroyImage(device->host.device, *out_image, NULL);
        return res;
    }

    VkMemoryGetFdInfoKHR fd_info;
    memset(&fd_info, 0, sizeof(fd_info));
    fd_info.sType = VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR;
    fd_info.memory = *out_memory;
    fd_info.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT_KHR;

    /* Get vkGetMemoryFdKHR from the host driver via GetDeviceProcAddr. */
    PFN_vkGetMemoryFdKHR p_get_fd = (PFN_vkGetMemoryFdKHR)
        vk_funcs->p_vkGetDeviceProcAddr(device->host.device, "vkGetMemoryFdKHR");
    if (!p_get_fd)
    {
        LOGE("vkGetMemoryFdKHR not available");
        device->p_vkFreeMemory(device->host.device, *out_memory, NULL);
        device->p_vkDestroyImage(device->host.device, *out_image, NULL);
        return VK_ERROR_EXTENSION_NOT_PRESENT;
    }

    int fd = -1;
    res = p_get_fd(device->host.device, &fd_info, &fd);
    if (res != VK_SUCCESS || fd < 0)
    {
        LOGE("vkGetMemoryFdKHR failed res=%d", res);
        device->p_vkFreeMemory(device->host.device, *out_memory, NULL);
        device->p_vkDestroyImage(device->host.device, *out_image, NULL);
        return res;
    }
    *out_fd = fd;
    LOGI("created opaque-fd image %ux%u dmabuf_fd=%d", extent.width, extent.height, fd);
    return VK_SUCCESS;
}

/* Manual thunk: vkCreateSwapchainKHR */
NTSTATUS wine_vkCreateSwapchainKHR(void *args)
{
    struct vkCreateSwapchainKHR_params *params = args;
    VkDevice client_device = params->device;
    const VkSwapchainCreateInfoKHR *create_info = params->pCreateInfo;
    VkSwapchainKHR *ret = params->pSwapchain;

    if (!is_enabled())
    {
        struct vulkan_device *device = vulkan_device_from_handle(client_device);
        VkSwapchainKHR host_swapchain;
        VkResult res = device->p_vkCreateSwapchainKHR(device->host.device, create_info, NULL, &host_swapchain);
        if (res == VK_SUCCESS) *ret = host_swapchain;
        params->result = res;
        return STATUS_SUCCESS;
    }

    struct vulkan_device *device = vulkan_device_from_handle(client_device);
    LOGI("wine_vkCreateSwapchainKHR: %ux%u fmt=%d imgCount=%u",
         create_info->imageExtent.width, create_info->imageExtent.height,
         create_info->imageFormat, create_info->minImageCount);

    uint32_t image_count = create_info->minImageCount;
    if (image_count > WAYLANDIE_MAX_SWAPCHAIN_IMAGES) image_count = WAYLANDIE_MAX_SWAPCHAIN_IMAGES;
    if (image_count < 2) image_count = 2;

    struct waylandie_swapchain *sw = calloc(1, sizeof(*sw));
    if (!sw)
    {
        params->result = VK_ERROR_OUT_OF_HOST_MEMORY;
        return STATUS_SUCCESS;
    }
    sw->device = client_device;
    sw->image_count = image_count;
    sw->extent = create_info->imageExtent;
    sw->format = create_info->imageFormat;
    sw->drm_format = vk_format_to_drm(create_info->imageFormat);
    sw->acquire_index = 0;
    sw->present_count = 0;
    sw->bridge_sock = -1;
    sw->queue = VK_NULL_HANDLE;

    for (uint32_t i = 0; i < image_count; i++)
    {
        sw->dmabuf_fds[i] = -1;
        VkResult res = create_opaque_fd_image(device, sw->format, sw->extent,
                                              create_info->imageUsage,
                                              &sw->images[i], &sw->memory[i],
                                              &sw->dmabuf_fds[i]);
        if (res != VK_SUCCESS)
        {
            LOGE("create_opaque_fd_image failed for image %u", i);
            for (uint32_t j = 0; j < i; j++)
            {
                if (sw->dmabuf_fds[j] >= 0) close(sw->dmabuf_fds[j]);
                device->p_vkFreeMemory(device->host.device, sw->memory[j], NULL);
                device->p_vkDestroyImage(device->host.device, sw->images[j], NULL);
            }
            free(sw);
            params->result = res;
            return STATUS_SUCCESS;
        }
    }

    pthread_mutex_lock(&g_lock);
    if (g_swapchain_count < 16)
        g_swapchains[g_swapchain_count++] = sw;
    pthread_mutex_unlock(&g_lock);

    *ret = (VkSwapchainKHR)(uintptr_t)sw;
    params->result = VK_SUCCESS;
    LOGI("wine_vkCreateSwapchainKHR: success, %u images, swapchain=%p", image_count, (void *)*ret);
    return STATUS_SUCCESS;
}

/* Manual thunk: vkDestroySwapchainKHR */
NTSTATUS wine_vkDestroySwapchainKHR(void *args)
{
    struct vkDestroySwapchainKHR_params *params = args;
    VkDevice client_device = params->device;
    VkSwapchainKHR swapchain = params->swapchain;

    struct waylandie_swapchain *sw = find_swapchain(swapchain);
    if (!sw)
    {
        struct vulkan_device *device = vulkan_device_from_handle(client_device);
        device->p_vkDestroySwapchainKHR(device->host.device, swapchain, NULL);
        return STATUS_SUCCESS;
    }

    struct vulkan_device *device = vulkan_device_from_handle(client_device);
    LOGI("wine_vkDestroySwapchainKHR: swapchain=%p presents=%llu",
         (void *)swapchain, (unsigned long long)sw->present_count);

    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < g_swapchain_count; i++)
    {
        if (g_swapchains[i] == sw)
        {
            g_swapchains[i] = g_swapchains[--g_swapchain_count];
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);

    for (uint32_t i = 0; i < sw->image_count; i++)
    {
        if (sw->dmabuf_fds[i] >= 0) close(sw->dmabuf_fds[i]);
        if (sw->memory[i]) device->p_vkFreeMemory(device->host.device, sw->memory[i], NULL);
        if (sw->images[i]) device->p_vkDestroyImage(device->host.device, sw->images[i], NULL);
    }
    if (sw->bridge_sock >= 0) close(sw->bridge_sock);
    free(sw);
    return STATUS_SUCCESS;
}

/* Manual thunk: vkGetSwapchainImagesKHR */
NTSTATUS wine_vkGetSwapchainImagesKHR(void *args)
{
    struct vkGetSwapchainImagesKHR_params *params = args;
    VkSwapchainKHR swapchain = params->swapchain;

    struct waylandie_swapchain *sw = find_swapchain(swapchain);
    if (!sw)
    {
        struct vulkan_device *device = vulkan_device_from_handle(params->device);
        params->result = device->p_vkGetSwapchainImagesKHR(device->host.device, swapchain,
                                                           params->pSwapchainImageCount,
                                                           params->pSwapchainImages);
        return STATUS_SUCCESS;
    }

    if (!params->pSwapchainImages || *params->pSwapchainImageCount < sw->image_count)
    {
        *params->pSwapchainImageCount = sw->image_count;
        params->result = params->pSwapchainImages ? VK_INCOMPLETE : VK_SUCCESS;
        return STATUS_SUCCESS;
    }
    *params->pSwapchainImageCount = sw->image_count;
    for (uint32_t i = 0; i < sw->image_count; i++)
        params->pSwapchainImages[i] = sw->images[i];
    params->result = VK_SUCCESS;
    return STATUS_SUCCESS;
}

/* Manual thunk: vkAcquireNextImageKHR */
NTSTATUS wine_vkAcquireNextImageKHR(void *args)
{
    struct vkAcquireNextImageKHR_params *params = args;
    VkSwapchainKHR swapchain = params->swapchain;

    struct waylandie_swapchain *sw = find_swapchain(swapchain);
    if (!sw)
    {
        struct vulkan_device *device = vulkan_device_from_handle(params->device);
        params->result = device->p_vkAcquireNextImageKHR(device->host.device, swapchain,
                                                         params->timeout, params->semaphore,
                                                         params->fence, params->pImageIndex);
        return STATUS_SUCCESS;
    }

    uint32_t idx = sw->acquire_index % sw->image_count;
    sw->acquire_index++;
    *params->pImageIndex = idx;

    if (params->semaphore || params->fence)
    {
        struct vulkan_device *device = vulkan_device_from_handle(params->device);
        if (sw->queue == VK_NULL_HANDLE && device->queue_count > 0)
            sw->queue = device->queues[0].host.queue;
        if (sw->queue && device->p_vkQueueSubmit)
        {
            VkSubmitInfo submit;
            memset(&submit, 0, sizeof(submit));
            submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            if (params->semaphore)
            {
                submit.signalSemaphoreCount = 1;
                submit.pSignalSemaphores = &params->semaphore;
            }
            device->p_vkQueueSubmit(sw->queue, 0, NULL, params->fence);
        }
    }

    params->result = VK_SUCCESS;
    return STATUS_SUCCESS;
}

/* Manual thunk: vkQueuePresentKHR */
NTSTATUS wine_vkQueuePresentKHR(void *args)
{
    struct vkQueuePresentKHR_params *params = args;

    if (!is_enabled())
    {
        struct vulkan_queue *queue = vulkan_queue_from_handle(params->queue);
        struct vulkan_device *device = queue->device;
        params->result = device->p_vkQueuePresentKHR(queue->host.queue, params->pPresentInfo);
        return STATUS_SUCCESS;
    }

    VkResult final_result = VK_SUCCESS;
    for (uint32_t i = 0; i < params->pPresentInfo->swapchainCount; i++)
    {
        VkSwapchainKHR sw_handle = params->pPresentInfo->pSwapchains[i];
        uint32_t img_idx = params->pPresentInfo->pImageIndices[i];
        struct waylandie_swapchain *sw = find_swapchain(sw_handle);
        if (!sw || img_idx >= sw->image_count) continue;

        if (sw->dmabuf_fds[img_idx] < 0) continue;

        int dmabuf_fd = dup(sw->dmabuf_fds[img_idx]);
        if (dmabuf_fd < 0) continue;

        uint32_t stride = sw->extent.width * 4;
        uint64_t size = (uint64_t)stride * sw->extent.height;

        if (sw->bridge_sock < 0)
        {
            sw->bridge_sock = bridge_connect(WAYLANDIE_BRIDGE_SOCKET);
            if (sw->bridge_sock >= 0)
                LOGI("bridge connected sock=%d", sw->bridge_sock);
        }

        if (sw->bridge_sock >= 0)
        {
            int rc = bridge_send_dmabuf(sw->bridge_sock, dmabuf_fd,
                                        sw->extent.width, sw->extent.height,
                                        sw->drm_format, 0ULL, stride, size);
            if (rc != 0)
            {
                close(sw->bridge_sock);
                sw->bridge_sock = bridge_connect(WAYLANDIE_BRIDGE_SOCKET);
                if (sw->bridge_sock >= 0)
                    bridge_send_dmabuf(sw->bridge_sock, dmabuf_fd,
                                       sw->extent.width, sw->extent.height,
                                       sw->drm_format, 0ULL, stride, size);
            }
        }

        close(dmabuf_fd);
        sw->present_count++;
        if (sw->present_count <= 3 || (sw->present_count % 60) == 0)
            LOGI("present #%llu: %ux%u drm=0x%08x fd=%d",
                 (unsigned long long)sw->present_count,
                 sw->extent.width, sw->extent.height, sw->drm_format, dmabuf_fd);
    }

    params->result = final_result;
    return STATUS_SUCCESS;
}
