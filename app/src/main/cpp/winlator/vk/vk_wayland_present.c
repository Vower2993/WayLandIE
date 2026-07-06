/* vk_wayland_present.c — Wayland dmabuf → swapchain blit + present.
 *
 * This file provides nativePresentDmaBufWayland() which imports a dmabuf
 * fd as a VkImage, blits it to the VulkanRenderer's swapchain image, and
 * presents via vkQueuePresentKHR. This goes through the BLASTBufferQueue
 * path that SurfaceFlinger always composites — unlike ASurfaceTransaction
 * which was invisible on Samsung S25/Android 16.
 *
 * The function is called from the render thread (VkRenderer) when a
 * dmabuf is available from the Wayland bridge. It replaces the X11 scene
 * rendering path (nativeRenderFrame) in Wayland mode.
 */

#include "vk_driver.h"
#include "vk_state.h"
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#define VK_WL_TAG "WaylandVkPresent"
#define VK_WL_LOGI(...) __android_log_print(ANDROID_LOG_INFO, VK_WL_TAG, __VA_ARGS__)
#define VK_WL_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, VK_WL_TAG, __VA_ARGS__)

/* Cached per-renderer Wayland present state */
struct VkWaylandPresent {
    VkImage src_image;
    VkDeviceMemory src_memory;
    VkCommandBuffer cmd;
    VkSemaphore render_finished;
    VkFence fence;
    int last_dmabuf_fd;
    uint32_t last_width;
    uint32_t last_height;
    int initialized;
};

/* Get or create the Wayland present state on the renderer */
static struct VkWaylandPresent* get_wl_present(VkRenderer* r) {
    if (!r->wl_present) {
        r->wl_present = calloc(1, sizeof(struct VkWaylandPresent));
        if (!r->wl_present) return NULL;
        r->wl_present->last_dmabuf_fd = -1;
    }
    return r->wl_present;
}

/* Import a dmabuf fd as a VkImage using VK_KHR_external_memory_fd */
static VkResult import_dmabuf_image(VkRenderer* r, struct VkWaylandPresent* wl,
                                     int dmabuf_fd, uint32_t width, uint32_t height,
                                     uint32_t stride, uint32_t drm_format) {
    VkDevice device = r->device;
    VkPhysicalDevice phys = r->physical_device;

    /* Destroy previous image if dimensions changed */
    if (wl->src_image != VK_NULL_HANDLE && (wl->last_width != width || wl->last_height != height)) {
        vkDestroyImage(device, wl->src_image, NULL);
        vkFreeMemory(device, wl->src_memory, NULL);
        wl->src_image = VK_NULL_HANDLE;
        wl->src_memory = VK_NULL_HANDLE;
    }

    /* Create image if not cached */
    if (wl->src_image == VK_NULL_HANDLE) {
        /* Determine VkFormat from DRM format */
        VkFormat vk_format = VK_FORMAT_R8G8B8A8_UNORM;
        if (drm_format == 0x34325241U /* ARGB8888 */) vk_format = VK_FORMAT_B8G8R8A8_UNORM;
        else if (drm_format == 0x34324241U /* ABGR8888 */) vk_format = VK_FORMAT_R8G8B8A8_UNORM;

        VkExternalMemoryImageCreateInfo ext_info = {VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
        ext_info.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;

        VkImageCreateInfo img_info = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        img_info.pNext = &ext_info;
        img_info.imageType = VK_IMAGE_TYPE_2D;
        img_info.format = vk_format;
        img_info.extent.width = width;
        img_info.extent.height = height;
        img_info.extent.depth = 1;
        img_info.mipLevels = 1;
        img_info.arrayLayers = 1;
        img_info.samples = VK_SAMPLE_COUNT_1_BIT;
        img_info.tiling = VK_IMAGE_TILING_LINEAR;
        img_info.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        img_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

        VkResult res = vkCreateImage(device, &img_info, NULL, &wl->src_image);
        if (res != VK_SUCCESS) {
            VK_WL_LOGE("vkCreateImage failed: %d", res);
            return res;
        }

        /* Get memory requirements */
        VkMemoryRequirements mem_reqs;
        vkGetImageMemoryRequirements(device, wl->src_image, &mem_reqs);

        /* Find memory type */
        VkPhysicalDeviceMemoryProperties mem_props;
        vkGetPhysicalDeviceMemoryProperties(phys, &mem_props);
        uint32_t mem_type = 0;
        for (uint32_t i = 0; i < mem_props.memoryTypeCount; i++) {
            if ((mem_reqs.memoryTypeBits & (1u << i)) &&
                (mem_props.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
                mem_type = i;
                break;
            }
        }

        /* Import the dmabuf fd */
        VkImportMemoryFdInfoKHR import_info = {VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR};
        import_info.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
        import_info.fd = dup(dmabuf_fd);  /* vkAllocateMemory takes ownership */
        if (import_info.fd < 0) {
            VK_WL_LOGE("dup(dmabuf_fd) failed");
            vkDestroyImage(device, wl->src_image, NULL);
            wl->src_image = VK_NULL_HANDLE;
            return VK_ERROR_OUT_OF_HOST_MEMORY;
        }

        VkMemoryAllocateInfo alloc_info = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        alloc_info.pNext = &import_info;
        alloc_info.allocationSize = mem_reqs.size;
        alloc_info.memoryTypeIndex = mem_type;

        res = vkAllocateMemory(device, &alloc_info, NULL, &wl->src_memory);
        if (res != VK_SUCCESS) {
            VK_WL_LOGE("vkAllocateMemory failed: %d", res);
            close(import_info.fd);
            vkDestroyImage(device, wl->src_image, NULL);
            wl->src_image = VK_NULL_HANDLE;
            return res;
        }

        res = vkBindImageMemory(device, wl->src_image, wl->src_memory, 0);
        if (res != VK_SUCCESS) {
            VK_WL_LOGE("vkBindImageMemory failed: %d", res);
            vkFreeMemory(device, wl->src_memory, NULL);
            vkDestroyImage(device, wl->src_image, NULL);
            wl->src_image = VK_NULL_HANDLE;
            wl->src_memory = VK_NULL_HANDLE;
            return res;
        }

        wl->last_width = width;
        wl->last_height = height;
        VK_WL_LOGI("Imported dmabuf %dx%d format=0x%08x as VkImage", width, height, drm_format);
    }

    return VK_SUCCESS;
}

/* Main entry: called from Java via JNI to present a dmabuf through the
 * VulkanRenderer's swapchain. Returns JNI_TRUE on success. */
JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_runtime_display_renderer_VulkanRenderer_nativePresentDmaBufWayland(
        JNIEnv* env, jclass clazz, jlong handle,
        jint dmabuf_fd, jint width, jint height,
        jint stride, jint drm_format) {
    (void)env; (void)clazz;
    VkRenderer* r = (VkRenderer*)(intptr_t)handle;
    if (!r || !r->surface_ready || dmabuf_fd < 0) return JNI_FALSE;

    struct VkWaylandPresent* wl = get_wl_present(r);
    if (!wl) return JNI_FALSE;

    /* Import dmabuf as VkImage */
    VkResult res = import_dmabuf_image(r, wl, dmabuf_fd, width, height, stride, drm_format);
    if (res != VK_SUCCESS) return JNI_FALSE;

    /* Acquire swapchain image */
    VkFrame* f = &r->frames[r->frame_index % r->frame_count];
    r->frame_index++;
    wait_inflight_frames(r);

    uint32_t image_index = 0;
    res = vkAcquireNextImageKHR(r->device, r->swapchain, UINT64_MAX,
                                 f->image_available, VK_NULL_HANDLE, &image_index);
    if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR) {
        VK_WL_LOGE("vkAcquireNextImageKHR: %d", res);
        return JNI_FALSE;
    }

    VkSemaphore render_finished = r->swapchain_render_finished[image_index];
    vkResetFences(r->device, 1, &f->in_flight);

    /* Record blit command buffer */
    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(f->cmd, &bi);

    /* Transition source image to TRANSFER_SRC */
    VkImageMemoryBarrier src_barrier = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    src_barrier.srcAccessMask = 0;
    src_barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    src_barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    src_barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    src_barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    src_barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    src_barrier.image = wl->src_image;
    src_barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    src_barrier.subresourceRange.levelCount = 1;
    src_barrier.subresourceRange.layerCount = 1;

    /* Transition swapchain image to TRANSFER_DST */
    VkImageMemoryBarrier dst_barrier = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    dst_barrier.srcAccessMask = 0;
    dst_barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    dst_barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    dst_barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    dst_barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    dst_barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    dst_barrier.image = r->swapchain_images[image_index];
    dst_barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    dst_barrier.subresourceRange.levelCount = 1;
    dst_barrier.subresourceRange.layerCount = 1;

    VkImageMemoryBarrier barriers[2] = {src_barrier, dst_barrier};
    vkCmdPipelineBarrier(f->cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 2, barriers);

    /* Blit source → swapchain (scaled to fill) */
    VkImageBlit blit = {0};
    blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.srcSubresource.layerCount = 1;
    blit.srcOffsets[0] = (VkOffset3D){0, 0, 0};
    blit.srcOffsets[1] = (VkOffset3D){width, height, 1};
    blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.dstSubresource.layerCount = 1;
    blit.dstOffsets[0] = (VkOffset3D){0, 0, 0};
    blit.dstOffsets[1] = (VkOffset3D){r->surface_extent.width, r->surface_extent.height, 1};
    vkCmdBlitImage(f->cmd, wl->src_image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                   r->swapchain_images[image_index], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                   1, &blit, VK_FILTER_LINEAR);

    /* Transition swapchain image to PRESENT_SRC */
    VkImageMemoryBarrier present_barrier = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    present_barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    present_barrier.dstAccessMask = 0;
    present_barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    present_barrier.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    present_barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    present_barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    present_barrier.image = r->swapchain_images[image_index];
    present_barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    present_barrier.subresourceRange.levelCount = 1;
    present_barrier.subresourceRange.layerCount = 1;
    vkCmdPipelineBarrier(f->cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, NULL, 0, NULL, 1, &present_barrier);

    vkEndCommandBuffer(f->cmd);

    /* Submit */
    VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.waitSemaphoreCount = 1;
    si.pWaitSemaphores = &f->image_available;
    si.pWaitDstStageMask = &wait_stage;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &f->cmd;
    si.signalSemaphoreCount = 1;
    si.pSignalSemaphores = &render_finished;

    pthread_mutex_lock(&r->queue_mutex);
    res = vkQueueSubmit(r->graphics_queue, 1, &si, f->in_flight);
    pthread_mutex_unlock(&r->queue_mutex);
    if (res != VK_SUCCESS) {
        VK_WL_LOGE("vkQueueSubmit: %d", res);
        return JNI_FALSE;
    }

    /* Present */
    VkPresentInfoKHR pi = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    pi.waitSemaphoreCount = 1;
    pi.pWaitSemaphores = &render_finished;
    pi.swapchainCount = 1;
    pi.pSwapchains = &r->swapchain;
    pi.pImageIndices = &image_index;

    pthread_mutex_lock(&r->queue_mutex);
    vkQueuePresentKHR(r->graphics_queue, &pi);
    pthread_mutex_unlock(&r->queue_mutex);

    return JNI_TRUE;
}
