/* WayLandIE dmabuf forwarding hooks for winevulkan.
 *
 * Manual unix-side thunks for swapchain/present functions. Creates
 * dmabuf-exportable images and forwards them to the WaylandIE bridge.
 *
 * Build: compiled into winevulkan.so via #pragma makedep unix.
 * Enabled at runtime via WAYLANDIE_DMABUF_FORWARD=1 env var.
 */

#if 0
#pragma makedep unix
#endif

#include "config.h"
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <android/log.h>
#include <pthread.h>

#include "vulkan_private.h"
#include "wine/vulkan_driver.h"
#include "wine/debug.h"

WINE_DEFAULT_DEBUG_CHANNEL(vulkan);

#define LOG_TAG "WayLandIE/Vk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define WAYLANDIE_MAX_IMAGES 8
#define WAYLANDIE_BRIDGE_SOCKET "waylandie.display.bridge.v1"

/* Handle types for dmabuf export. */
#define DMA_BUF_BIT_EXT 0x00000200
#define OPAQUE_FD_BIT   0x00000001

/* DRM fourcc codes. */
#define DRM_FORMAT_ARGB8888 0x34325241U
#define DRM_FORMAT_ABGR8888 0x34324241U
#define DRM_FORMAT_RGBA8888 0x34424152U

/* Risk 1: Embed struct vulkan_swapchain as first field so
 * vulkan_swapchain_from_handle() works on our handles. */
struct waylandie_swapchain
{
    struct vulkan_swapchain obj;  /* MUST be first */
    pthread_mutex_t lock;
    VkDevice client_device;
    struct vulkan_device *device;
    uint32_t image_count;
    VkImage images[WAYLANDIE_MAX_IMAGES];
    VkDeviceMemory memory[WAYLANDIE_MAX_IMAGES];
    int dmabuf_fds[WAYLANDIE_MAX_IMAGES];
    VkSubresourceLayout layouts[WAYLANDIE_MAX_IMAGES];
    VkExtent2D extent;
    VkFormat format;
    uint32_t drm_format;
    uint32_t acquire_index;
    uint64_t present_count;
    int bridge_sock;
    VkCommandPool cmd_pool;
    VkCommandBuffer acquire_cmds[WAYLANDIE_MAX_IMAGES];
    VkFence present_fence;
    VkQueue queue;
};

static struct waylandie_swapchain *g_swapchains[16];
static int g_swapchain_count;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_enabled = -1;

static int is_enabled(void)
{
    if (g_enabled < 0)
    {
        const char *env = getenv("WAYLANDIE_DMABUF_FORWARD");
        g_enabled = (env && env[0] == '1') ? 1 : 0;
        LOGI("WayLandIE dmabuf forwarding %s", g_enabled ? "ENABLED" : "disabled");
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
        default:
            return DRM_FORMAT_ARGB8888;
    }
}

static int bridge_connect(const char *name)
{
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
                (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + len)) != 0)
    { close(fd); return -1; }
    return fd;
}

static int bridge_send_dmabuf(int sock, int fd, uint32_t w, uint32_t h,
                              uint32_t fmt, uint64_t mod, uint32_t stride,
                              uint64_t offset, uint64_t size)
{
    char cmd[512];
    int n = snprintf(cmd, sizeof(cmd),
        "dmabuf-present fast=1 window=fullscreen width=%u height=%u "
        "format=%u modifier=0x%016llx planes=1 stride0=%u offset0=%llu "
        "size=%llu driver=turnip\n",
        w, h, fmt, (unsigned long long)mod, stride,
        (unsigned long long)offset, (unsigned long long)size);
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

/* Risk 1: find_swapchain uses the handle directly — our struct starts with
 * vulkan_swapchain, so the handle IS a valid pointer to our struct. */
static struct waylandie_swapchain *find_swapchain(VkSwapchainKHR handle)
{
    /* Check if this is one of our handles by scanning the list. */
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < g_swapchain_count; i++)
    {
        if ((VkSwapchainKHR)(uintptr_t)g_swapchains[i] == handle)
        {
            pthread_mutex_unlock(&g_lock);
            return g_swapchains[i];
        }
    }
    pthread_mutex_unlock(&g_lock);
    return NULL;
}

static void register_swapchain(struct waylandie_swapchain *sw)
{
    pthread_mutex_lock(&g_lock);
    if (g_swapchain_count < 16)
        g_swapchains[g_swapchain_count++] = sw;
    pthread_mutex_unlock(&g_lock);
}

static void unregister_swapchain(struct waylandie_swapchain *sw)
{
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
}

/* Risk 2+10: Create a dmabuf-exportable image using DMA_BUF_BIT_EXT
 * and LINEAR tiling for correct stride. Falls back to OPTIMAL+OPAQUE_FD. */
static VkResult create_dmabuf_image(struct vulkan_device *device,
                                    VkFormat format, VkExtent2D extent,
                                    VkImageUsageFlags usage,
                                    VkImage *out_image,
                                    VkDeviceMemory *out_memory,
                                    int *out_fd,
                                    VkSubresourceLayout *out_layout)
{
    VkResult res;
    int fd = -1;

    /* Obtain vkGetMemoryFdKHR via GetDeviceProcAddr (extension is UNEXPOSED). */
    PFN_vkGetMemoryFdKHR p_get_fd = (PFN_vkGetMemoryFdKHR)
        vk_funcs->p_vkGetDeviceProcAddr(device->host.device, "vkGetMemoryFdKHR");
    if (!p_get_fd)
    {
        LOGE("vkGetMemoryFdKHR not available");
        return VK_ERROR_EXTENSION_NOT_PRESENT;
    }

    /* Try DMA_BUF_BIT_EXT first (Risk 10), fall back to OPAQUE_FD. */
    uint32_t handle_types[] = { DMA_BUF_BIT_EXT, OPAQUE_FD_BIT };
    const char *handle_names[] = { "DMA_BUF_EXT", "OPAQUE_FD" };

    for (int hi = 0; hi < 2 && fd < 0; hi++)
    {
        /* Risk 2: Try LINEAR first for correct stride, fall back to OPTIMAL. */
        VkImageTiling tilings[] = { VK_IMAGE_TILING_LINEAR, VK_IMAGE_TILING_OPTIMAL };

        for (int ti = 0; ti < 2 && fd < 0; ti++)
        {
            VkExternalMemoryImageCreateInfo ext_mem = {};
            ext_mem.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
            ext_mem.handleTypes = handle_types[hi];

            VkImageCreateInfo img_info = {};
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
            img_info.tiling = tilings[ti];
            img_info.usage = usage | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                           | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            img_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

            VkImage img = VK_NULL_HANDLE;
            res = device->p_vkCreateImage(device->host.device, &img_info, NULL, &img);
            if (res != VK_SUCCESS)
            {
                LOGW("vkCreateImage failed tiling=%d handle=%s res=%d",
                     tilings[ti], handle_names[hi], res);
                continue;
            }

            VkMemoryRequirements2 mem_reqs2 = {};
            mem_reqs2.sType = VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2;
            VkImageMemoryRequirementsInfo2 req_info = {};
            req_info.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_REQUIREMENTS_INFO_2;
            req_info.image = img;
            device->p_vkGetImageMemoryRequirements2(device->host.device, &req_info, &mem_reqs2);

            VkPhysicalDeviceMemoryProperties *mp = &device->physical_device->memory_properties;
            uint32_t mem_type = 0;
            for (uint32_t i = 0; i < mp->memoryTypeCount; i++)
            {
                if ((mem_reqs2.memoryRequirements.memoryTypeBits & (1u << i)) &&
                    (mp->memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
                { mem_type = i; break; }
            }

            VkMemoryAllocateInfo alloc_info = {};
            alloc_info.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
            alloc_info.allocationSize = mem_reqs2.memoryRequirements.size;
            alloc_info.memoryTypeIndex = mem_type;

            VkDeviceMemory mem = VK_NULL_HANDLE;
            res = device->p_vkAllocateMemory(device->host.device, &alloc_info, NULL, &mem);
            if (res != VK_SUCCESS)
            {
                LOGW("vkAllocateMemory failed res=%d", res);
                device->p_vkDestroyImage(device->host.device, img, NULL);
                continue;
            }

            res = device->p_vkBindImageMemory(device->host.device, img, mem, 0);
            if (res != VK_SUCCESS)
            {
                LOGW("vkBindImageMemory failed res=%d", res);
                device->p_vkFreeMemory(device->host.device, mem, NULL);
                device->p_vkDestroyImage(device->host.device, img, NULL);
                continue;
            }

            VkMemoryGetFdInfoKHR fd_info = {};
            fd_info.sType = VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR;
            fd_info.memory = mem;
            fd_info.handleType = handle_types[hi];

            res = p_get_fd(device->host.device, &fd_info, &fd);
            if (res != VK_SUCCESS || fd < 0)
            {
                LOGW("vkGetMemoryFdKHR handle=%s res=%d fd=%d",
                     handle_names[hi], res, fd);
                device->p_vkFreeMemory(device->host.device, mem, NULL);
                device->p_vkDestroyImage(device->host.device, img, NULL);
                fd = -1;
                continue;
            }

            *out_image = img;
            *out_memory = mem;
            *out_fd = fd;

            /* Risk 2: Query layout for correct stride (LINEAR only). */
            if (tilings[ti] == VK_IMAGE_TILING_LINEAR)
            {
                VkImageSubresource subres = {};
                subres.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
                subres.mipLevel = 0;
                subres.arrayLayer = 0;
                device->p_vkGetImageSubresourceLayout(device->host.device, img,
                                                      &subres, out_layout);
            }
            else
            {
                out_layout->rowPitch = extent.width * 4;
                out_layout->offset = 0;
                out_layout->size = mem_reqs2.memoryRequirements.size;
            }

            LOGI("created image %ux%u tiling=%d handle=%s fd=%d stride=%llu",
                 extent.width, extent.height, tilings[ti],
                 handle_names[hi], fd,
                 (unsigned long long)out_layout->rowPitch);
            return VK_SUCCESS;
        }
    }

    LOGE("all image creation strategies failed");
    return VK_ERROR_OUT_OF_DEVICE_MEMORY;
}

static void destroy_swapchain_resources(struct waylandie_swapchain *sw)
{
    struct vulkan_device *device = sw->device;

    if (sw->present_fence != VK_NULL_HANDLE)
        device->p_vkDestroyFence(device->host.device, sw->present_fence, NULL);

    for (uint32_t i = 0; i < sw->image_count; i++)
    {
        if (sw->acquire_cmds[i])
        {
            device->p_vkFreeCommandBuffers(device->host.device, sw->cmd_pool,
                                           1, &sw->acquire_cmds[i]);
        }
        if (sw->dmabuf_fds[i] >= 0) close(sw->dmabuf_fds[i]);
        if (sw->memory[i]) device->p_vkFreeMemory(device->host.device, sw->memory[i], NULL);
        if (sw->images[i]) device->p_vkDestroyImage(device->host.device, sw->images[i], NULL);
    }
    if (sw->cmd_pool) device->p_vkDestroyCommandPool(device->host.device, sw->cmd_pool, NULL);
    if (sw->bridge_sock >= 0) close(sw->bridge_sock);
}

/* Risk 3+4: Signal semaphore with a dummy command buffer + layout transition. */
static VkResult signal_semaphore_with_barrier(struct waylandie_swapchain *sw,
                                              uint32_t img_idx,
                                              VkSemaphore semaphore,
                                              VkFence fence)
{
    struct vulkan_device *device = sw->device;
    VkCommandBuffer cmd = sw->acquire_cmds[img_idx];

    VkCommandBufferBeginInfo begin_info = {};
    begin_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin_info.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;

    VkResult res = device->p_vkBeginCommandBuffer(cmd, &begin_info);
    if (res != VK_SUCCESS) return res;

    /* Risk 4: Transition UNDEFINED -> GENERAL for external access. */
    VkImageMemoryBarrier barrier = {};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask = 0;
    barrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = sw->images[img_idx];
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.layerCount = 1;

    VkPipelineStageFlags src_stage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
    VkPipelineStageFlags dst_stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;

    device->p_vkCmdPipelineBarrier(cmd, src_stage, dst_stage, 0,
                                   0, NULL, 0, NULL, 1, &barrier);

    res = device->p_vkEndCommandBuffer(cmd);
    if (res != VK_SUCCESS) return res;

    VkSubmitInfo submit_info = {};
    submit_info.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit_info.commandBufferCount = 1;
    submit_info.pCommandBuffers = &cmd;
    if (semaphore)
    {
        submit_info.signalSemaphoreCount = 1;
        submit_info.pSignalSemaphores = &semaphore;
    }

    if (fence)
        device->p_vkResetFences(device->host.device, 1, &fence);

    return device->p_vkQueueSubmit(sw->queue, 1, &submit_info, fence);
}

/* === Manual thunk implementations === */

/* Risk 7: Handle oldSwapchain destruction. */
VkResult wine_vkCreateSwapchainKHR(VkDevice client_device,
                                   const VkSwapchainCreateInfoKHR *create_info,
                                   const VkAllocationCallbacks *allocator,
                                   VkSwapchainKHR *ret)
{
    struct vulkan_device *device = vulkan_device_from_handle(client_device);

    if (!is_enabled())
    {
        VkSwapchainKHR host_sw;
        VkResult res = device->p_vkCreateSwapchainKHR(device->host.device,
                                                      create_info, NULL, &host_sw);
        if (res == VK_SUCCESS) *ret = host_sw;
        return res;
    }

    LOGI("wine_vkCreateSwapchainKHR: %ux%u fmt=%d imgCount=%u",
         create_info->imageExtent.width, create_info->imageExtent.height,
         create_info->imageFormat, create_info->minImageCount);

    /* Risk 7: Destroy old swapchain if it's ours. */
    if (create_info->oldSwapchain != VK_NULL_HANDLE)
    {
        struct waylandie_swapchain *old = find_swapchain(create_info->oldSwapchain);
        if (old)
        {
            LOGI("destroying old swapchain=%p", (void *)create_info->oldSwapchain);
            unregister_swapchain(old);
            destroy_swapchain_resources(old);
            free(old);
        }
    }

    uint32_t img_count = create_info->minImageCount;
    if (img_count > WAYLANDIE_MAX_IMAGES) img_count = WAYLANDIE_MAX_IMAGES;
    if (img_count < 2) img_count = 2;

    struct waylandie_swapchain *sw = calloc(1, sizeof(*sw));
    if (!sw) return VK_ERROR_OUT_OF_HOST_MEMORY;

    pthread_mutex_init(&sw->lock, NULL);
    sw->obj.host.swapchain = VK_NULL_HANDLE;  /* Risk 1: no real host swapchain */
    sw->client_device = client_device;
    sw->device = device;
    sw->image_count = img_count;
    sw->extent = create_info->imageExtent;
    sw->format = create_info->imageFormat;
    sw->drm_format = vk_format_to_drm(create_info->imageFormat);
    sw->bridge_sock = -1;
    sw->queue = VK_NULL_HANDLE;

    /* Get the graphics queue. */
    if (device->queue_count > 0)
        sw->queue = device->queues[0].host.queue;

    /* Create images with dmabuf export. */
    for (uint32_t i = 0; i < img_count; i++)
    {
        sw->dmabuf_fds[i] = -1;
        VkResult res = create_dmabuf_image(device, sw->format, sw->extent,
                                           create_info->imageUsage,
                                           &sw->images[i], &sw->memory[i],
                                           &sw->dmabuf_fds[i], &sw->layouts[i]);
        if (res != VK_SUCCESS)
        {
            LOGE("create_dmabuf_image failed for image %u", i);
            destroy_swapchain_resources(sw);
            free(sw);
            return res;
        }
    }

    /* Risk 3: Create command pool + per-image command buffers. */
    VkCommandPoolCreateInfo pool_info = {};
    pool_info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pool_info.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pool_info.queueFamilyIndex = device->queues[0].info.queueFamilyIndex;
    VkResult res = device->p_vkCreateCommandPool(device->host.device,
                                                  &pool_info, NULL, &sw->cmd_pool);
    if (res != VK_SUCCESS)
    {
        LOGE("vkCreateCommandPool failed res=%d", res);
        destroy_swapchain_resources(sw);
        free(sw);
        return res;
    }

    VkCommandBufferAllocateInfo cmd_alloc = {};
    cmd_alloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmd_alloc.commandPool = sw->cmd_pool;
    cmd_alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmd_alloc.commandBufferCount = 1;

    for (uint32_t i = 0; i < img_count; i++)
    {
        res = device->p_vkAllocateCommandBuffers(device->host.device,
                                                  &cmd_alloc, &sw->acquire_cmds[i]);
        if (res != VK_SUCCESS)
        {
            LOGE("vkAllocateCommandBuffers failed res=%d", res);
            destroy_swapchain_resources(sw);
            free(sw);
            return res;
        }
    }

    /* Create present fence for waiting on rendering completion. */
    VkFenceCreateInfo fence_info = {};
    fence_info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    res = device->p_vkCreateFence(device->host.device, &fence_info, NULL,
                                  &sw->present_fence);
    if (res != VK_SUCCESS)
    {
        LOGE("vkCreateFence failed res=%d", res);
        destroy_swapchain_resources(sw);
        free(sw);
        return res;
    }

    register_swapchain(sw);
    *ret = (VkSwapchainKHR)(uintptr_t)sw;
    LOGI("wine_vkCreateSwapchainKHR: success %u images swapchain=%p", img_count, (void *)*ret);
    return VK_SUCCESS;
}

void wine_vkDestroySwapchainKHR(VkDevice client_device, VkSwapchainKHR swapchain,
                                const VkAllocationCallbacks *allocator)
{
    if (!swapchain) return;

    struct waylandie_swapchain *sw = find_swapchain(swapchain);
    if (!sw)
    {
        struct vulkan_device *device = vulkan_device_from_handle(client_device);
        device->p_vkDestroySwapchainKHR(device->host.device, swapchain, NULL);
        return;
    }

    LOGI("wine_vkDestroySwapchainKHR: swapchain=%p presents=%llu",
         (void *)swapchain, (unsigned long long)sw->present_count);

    unregister_swapchain(sw);
    destroy_swapchain_resources(sw);
    pthread_mutex_destroy(&sw->lock);
    free(sw);
}

VkResult wine_vkGetSwapchainImagesKHR(VkDevice client_device, VkSwapchainKHR swapchain,
                                      uint32_t *pSwapchainImageCount, VkImage *pSwapchainImages)
{
    struct waylandie_swapchain *sw = find_swapchain(swapchain);
    if (!sw)
    {
        struct vulkan_device *device = vulkan_device_from_handle(client_device);
        return device->p_vkGetSwapchainImagesKHR(device->host.device, swapchain,
                                                  pSwapchainImageCount, pSwapchainImages);
    }

    if (!pSwapchainImages || *pSwapchainImageCount < sw->image_count)
    {
        *pSwapchainImageCount = sw->image_count;
        return pSwapchainImages ? VK_INCOMPLETE : VK_SUCCESS;
    }
    *pSwapchainImageCount = sw->image_count;
    for (uint32_t i = 0; i < sw->image_count; i++)
        pSwapchainImages[i] = sw->images[i];
    return VK_SUCCESS;
}

VkResult wine_vkAcquireNextImageKHR(VkDevice client_device, VkSwapchainKHR swapchain,
                                    uint64_t timeout, VkSemaphore semaphore,
                                    VkFence fence, uint32_t *pImageIndex)
{
    struct waylandie_swapchain *sw = find_swapchain(swapchain);
    if (!sw)
    {
        struct vulkan_device *device = vulkan_device_from_handle(client_device);
        return device->p_vkAcquireNextImageKHR(device->host.device, swapchain,
                                               timeout, semaphore, fence, pImageIndex);
    }

    /* Risk 6: Per-swapchain lock. */
    pthread_mutex_lock(&sw->lock);

    uint32_t idx = sw->acquire_index % sw->image_count;
    sw->acquire_index++;
    *pImageIndex = idx;

    /* Risk 3+4: Signal semaphore with dummy command buffer + layout transition. */
    if (semaphore || fence)
    {
        VkResult res = signal_semaphore_with_barrier(sw, idx, semaphore, fence);
        if (res != VK_SUCCESS)
        {
            LOGE("signal_semaphore_with_barrier failed res=%d", res);
            pthread_mutex_unlock(&sw->lock);
            return res;
        }
    }

    pthread_mutex_unlock(&sw->lock);
    return VK_SUCCESS;
}

VkResult wine_vkQueuePresentKHR(VkQueue client_queue, const VkPresentInfoKHR *pPresentInfo)
{
    if (!is_enabled())
    {
        struct vulkan_queue *queue = vulkan_queue_from_handle(client_queue);
        struct vulkan_device *device = queue->device;
        return device->p_vkQueuePresentKHR(queue->host.queue, pPresentInfo);
    }

    struct vulkan_queue *queue = vulkan_queue_from_handle(client_queue);
    struct vulkan_device *device = queue->device;
    VkResult final_result = VK_SUCCESS;

    for (uint32_t i = 0; i < pPresentInfo->swapchainCount; i++)
    {
        VkSwapchainKHR sw_handle = pPresentInfo->pSwapchains[i];
        uint32_t img_idx = pPresentInfo->pImageIndices[i];
        struct waylandie_swapchain *sw = find_swapchain(sw_handle);
        if (!sw || img_idx >= sw->image_count) continue;
        if (sw->dmabuf_fds[img_idx] < 0) continue;

        /* Risk 6: Per-swapchain lock. */
        pthread_mutex_lock(&sw->lock);

        /* Risk 3+4: Wait for DXVK's rendering semaphores before exporting dmabuf. */
        if (pPresentInfo->waitSemaphoreCount > 0)
        {
            VkSubmitInfo wait_submit = {};
            wait_submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            wait_submit.waitSemaphoreCount = pPresentInfo->waitSemaphoreCount;
            wait_submit.pWaitSemaphores = pPresentInfo->pWaitSemaphores;
            VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            wait_submit.pWaitDstStageMask = &wait_stage;

            device->p_vkResetFences(device->host.device, 1, &sw->present_fence);
            VkResult res = device->p_vkQueueSubmit(queue->host.queue, 1,
                                                    &wait_submit, sw->present_fence);
            if (res == VK_SUCCESS)
            {
                device->p_vkWaitForFences(device->host.device, 1,
                                          &sw->present_fence, VK_TRUE,
                                          5000000000ULL);
            }
        }

        int dmabuf_fd = dup(sw->dmabuf_fds[img_idx]);
        if (dmabuf_fd < 0)
        {
            pthread_mutex_unlock(&sw->lock);
            continue;
        }

        uint32_t stride = (uint32_t)sw->layouts[img_idx].rowPitch;
        uint64_t offset = sw->layouts[img_idx].offset;
        uint64_t size = sw->layouts[img_idx].size;
        uint64_t modifier = 0;  /* LINEAR modifier */

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
                                        sw->drm_format, modifier,
                                        stride, offset, size);
            if (rc != 0)
            {
                close(sw->bridge_sock);
                sw->bridge_sock = bridge_connect(WAYLANDIE_BRIDGE_SOCKET);
                if (sw->bridge_sock >= 0)
                    bridge_send_dmabuf(sw->bridge_sock, dmabuf_fd,
                                       sw->extent.width, sw->extent.height,
                                       sw->drm_format, modifier,
                                       stride, offset, size);
            }
        }

        close(dmabuf_fd);
        sw->present_count++;
        if (sw->present_count <= 3 || (sw->present_count % 60) == 0)
            LOGI("present #%llu: %ux%u drm=0x%08x stride=%u fd=%d",
                 (unsigned long long)sw->present_count,
                 sw->extent.width, sw->extent.height,
                 sw->drm_format, stride, dmabuf_fd);

        pthread_mutex_unlock(&sw->lock);
    }

    return final_result;
}

/* Risk 1: Add vkAcquireNextImage2KHR to MANUAL_UNIX_THUNKS — pass-through
 * that returns VK_ERROR_OUT_OF_DATE_KHR to force DXVK to use
 * vkAcquireNextImageKHR instead. */
VkResult wine_vkAcquireNextImage2KHR(VkDevice client_device,
                                     const VkAcquireNextImageInfoKHR *pAcquireInfo,
                                     uint32_t *pImageIndex)
{
    struct waylandie_swapchain *sw = find_swapchain(pAcquireInfo->swapchain);
    if (!sw)
    {
        struct vulkan_device *device = vulkan_device_from_handle(client_device);
        return device->p_vkAcquireNextImage2KHR(device->host.device,
                                                pAcquireInfo, pImageIndex);
    }
    /* Force DXVK to fall back to vkAcquireNextImageKHR. */
    return VK_ERROR_OUT_OF_DATE_KHR;
}
