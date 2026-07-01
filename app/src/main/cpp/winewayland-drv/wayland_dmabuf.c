/*
 * Wayland linux-dmabuf buffer support
 *
 * Copyright 2024 Alexandros Frantzis for Collabora Ltd
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
 */

#if 0
#pragma makedep unix
#endif

#include "config.h"

#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "waylanddrv.h"
#include "linux-dmabuf-unstable-v1-client-protocol.h"
#include "wine/debug.h"

WINE_DEFAULT_DEBUG_CHANNEL(waylanddrv);

/* DRM fourcc format codes — not available in all build environments. */
#ifndef DRM_FORMAT_XRGB8888
#define DRM_FORMAT_XRGB8888 0x34325258U
#endif
#ifndef DRM_FORMAT_ARGB8888
#define DRM_FORMAT_ARGB8888 0x34325241U
#endif
#ifndef DRM_FORMAT_XBGR8888
#define DRM_FORMAT_XBGR8888 0x34324258U
#endif
#ifndef DRM_FORMAT_ABGR8888
#define DRM_FORMAT_ABGR8888 0x34324241U
#endif
#ifndef DRM_FORMAT_MOD_INVALID
#define DRM_FORMAT_MOD_INVALID ((uint64_t)0x00FFFFFFFFFFFFFFULL)
#endif
#ifndef DRM_FORMAT_MOD_LINEAR
#define DRM_FORMAT_MOD_LINEAR 0ULL
#endif

#define WINEWAYLAND_MAX_DMABUF_FORMATS 64
#define WINEWAYLAND_DEFAULT_FORMAT DRM_FORMAT_XRGB8888

/* Track format+modifier entries advertised by the compositor. */
struct wayland_dmabuf_format
{
    uint32_t format;
    uint64_t modifier; /* DRM_FORMAT_MOD_INVALID if no modifier sent */
};

/* Cached compositor format table filled from format/modifier events. */
static struct
{
    struct wayland_dmabuf_format formats[WINEWAYLAND_MAX_DMABUF_FORMATS];
    int count;
    BOOL has_modifiers;
    BOOL received;
} dmabuf_state;

/**********************************************************************
 *          dmabuf_handle_format
 *
 * Single format advertisement (v1–v2, no modifier).
 */
static void dmabuf_handle_format(void *data, struct zwp_linux_dmabuf_v1 *dmabuf,
                                 uint32_t format)
{
    if (dmabuf_state.count >= WINEWAYLAND_MAX_DMABUF_FORMATS) return;
    TRACE("format=0x%08x\n", format);
    dmabuf_state.formats[dmabuf_state.count].format = format;
    dmabuf_state.formats[dmabuf_state.count].modifier = DRM_FORMAT_MOD_INVALID;
    dmabuf_state.count++;
}

/**********************************************************************
 *          dmabuf_handle_modifier
 *
 * format+modifier advertisement (v3+).
 */
static void dmabuf_handle_modifier(void *data, struct zwp_linux_dmabuf_v1 *dmabuf,
                                   uint32_t format, uint32_t modifier_hi,
                                   uint32_t modifier_lo)
{
    uint64_t modifier = ((uint64_t)modifier_hi << 32) | modifier_lo;

    if (dmabuf_state.count >= WINEWAYLAND_MAX_DMABUF_FORMATS) return;
    TRACE("format=0x%08x modifier=0x%016lx\n", format, (unsigned long)modifier);
    dmabuf_state.formats[dmabuf_state.count].format = format;
    dmabuf_state.formats[dmabuf_state.count].modifier = modifier;
    dmabuf_state.has_modifiers = TRUE;
    dmabuf_state.count++;
}

static const struct zwp_linux_dmabuf_v1_listener dmabuf_listener =
{
    dmabuf_handle_format,
    dmabuf_handle_modifier
};

/**********************************************************************
 *          wayland_dmabuf_init
 *
 * Bind zwp_linux_dmabuf_v1 and cache format/modifier advertisements.
 */
BOOL wayland_dmabuf_init(struct wl_registry *registry, uint32_t id,
                         uint32_t version)
{
    if (process_wayland.zwp_linux_dmabuf_v1)
    {
        WARN("zwp_linux_dmabuf_v1 already bound, ignoring duplicate\n");
        return TRUE;
    }

    process_wayland.zwp_linux_dmabuf_v1 =
        wl_registry_bind(registry, id, &zwp_linux_dmabuf_v1_interface,
                         version < 4 ? version : 4);
    if (!process_wayland.zwp_linux_dmabuf_v1)
    {
        ERR("Failed to bind zwp_linux_dmabuf_v1\n");
        return FALSE;
    }

    zwp_linux_dmabuf_v1_add_listener(process_wayland.zwp_linux_dmabuf_v1,
                                     &dmabuf_listener, NULL);
    TRACE("zwp_linux_dmabuf_v1 bound version=%u\n", version < 4 ? version : 4);
    dmabuf_state.received = TRUE;
    return TRUE;
}

/**********************************************************************
 *          wayland_dmabuf_find_format
 *
 * Look up a format+modifier pair in the cached advertisement table.
 * Returns TRUE and fills out chosen fmt/mod if found.
 */
static BOOL wayland_dmabuf_find_format(uint32_t format, uint64_t modifier,
                                       uint32_t *out_format,
                                       uint64_t *out_modifier)
{
    int i;

    for (i = 0; i < dmabuf_state.count; i++)
    {
        if (dmabuf_state.formats[i].format == format &&
            dmabuf_state.formats[i].modifier == modifier)
        {
            *out_format = format;
            *out_modifier = modifier;
            return TRUE;
        }
    }
    /* Fallback: try to find any modifier for this format. */
    for (i = 0; i < dmabuf_state.count; i++)
    {
        if (dmabuf_state.formats[i].format == format)
        {
            *out_format = format;
            *out_modifier = dmabuf_state.formats[i].modifier;
            return TRUE;
        }
    }
    return FALSE;
}

/**********************************************************************
 *          wayland_dmabuf_buffer_destroy
 *
 * Destroy a dmabuf buffer, closing FDs and freeing resources.
 */
void wayland_dmabuf_buffer_destroy(struct wayland_dmabuf_buffer *buffer)
{
    int i;

    if (!buffer) return;

    TRACE("buffer=%p\n", buffer);

    if (buffer->wl_buffer)
        wl_buffer_destroy(buffer->wl_buffer);

    for (i = 0; i < buffer->plane_count; i++)
    {
        if (buffer->fds[i] >= 0)
            close(buffer->fds[i]);
    }

    free(buffer);
}

/**********************************************************************
 *          dmabuf_buffer_release
 *
 * wl_buffer release callback — signals the buffer is free for reuse.
 */
static void dmabuf_buffer_release(void *data, struct wl_buffer *wl_buffer)
{
    struct wayland_dmabuf_buffer *buffer = data;
    TRACE("buffer=%p\n", buffer);
    buffer->busy = FALSE;
    if (buffer->release_callback)
        buffer->release_callback(buffer);
}

static const struct wl_buffer_listener dmabuf_buffer_listener =
{
    dmabuf_buffer_release
};

/**********************************************************************
 *          wayland_dmabuf_buffer_create
 *
 * Create a wl_buffer from dmabuf FDs via zwp_linux_buffer_params_v1.
 * Uses create_immed (v2+) for synchronous buffer creation.
 */
struct wayland_dmabuf_buffer *wayland_dmabuf_buffer_create(
    int width, int height, uint32_t format, uint64_t modifier,
    int plane_count, const int *fds, const uint32_t *offsets,
    const uint32_t *strides, dmabuf_buffer_release_cb release_cb)
{
    struct wayland_dmabuf_buffer *buffer;
    struct zwp_linux_buffer_params_v1 *params;
    uint32_t use_format;
    uint64_t use_modifier;
    int i;

    if (!process_wayland.zwp_linux_dmabuf_v1)
    {
        ERR("zwp_linux_dmabuf_v1 not available\n");
        return NULL;
    }

    if (!wayland_dmabuf_find_format(format, modifier, &use_format, &use_modifier))
    {
        WARN("format 0x%08x modifier 0x%016lx not supported by compositor\n",
             (unsigned long)format, (unsigned long)modifier);
        /* Try the default format as a fallback. */
        use_format = WINEWAYLAND_DEFAULT_FORMAT;
        use_modifier = DRM_FORMAT_MOD_LINEAR;
    }

    buffer = calloc(1, sizeof(*buffer));
    if (!buffer) goto err;

    buffer->width = width;
    buffer->height = height;
    buffer->format = use_format;
    buffer->modifier = use_modifier;
    buffer->plane_count = plane_count > WINEWAYLAND_MAX_DMABUF_PLANES ?
                          WINEWAYLAND_MAX_DMABUF_PLANES : plane_count;
    buffer->release_callback = release_cb;
    buffer->busy = TRUE;

    for (i = 0; i < buffer->plane_count; i++)
    {
        buffer->fds[i] = fds[i] >= 0 ? dup(fds[i]) : -1;
        buffer->offsets[i] = offsets ? offsets[i] : 0;
        buffer->strides[i] = strides ? strides[i] : width * 4;
    }

    params = zwp_linux_dmabuf_v1_create_params(process_wayland.zwp_linux_dmabuf_v1);
    if (!params) goto err;

    for (i = 0; i < buffer->plane_count; i++)
    {
        zwp_linux_buffer_params_v1_add(params, buffer->fds[i], i,
                                       buffer->offsets[i],
                                       buffer->strides[i],
                                       (uint32_t)(use_modifier >> 32),
                                       (uint32_t)(use_modifier & 0xFFFFFFFF));
    }

    /* Use create_immed for v2+ to get the buffer object synchronously. */
    buffer->wl_buffer =
        zwp_linux_buffer_params_v1_create_immed(params, width, height,
                                                use_format, 0);
    zwp_linux_buffer_params_v1_destroy(params);

    if (!buffer->wl_buffer)
    {
        ERR("Failed to create dmabuf wl_buffer\n");
        goto err;
    }

    wl_buffer_add_listener(buffer->wl_buffer, &dmabuf_buffer_listener, buffer);

    TRACE("buffer=%p wl_buffer=%p %dx%d format=0x%08x modifier=0x%016lx planes=%d\n",
          buffer, buffer->wl_buffer, width, height, use_format,
          (unsigned long)use_modifier, buffer->plane_count);

    return buffer;

err:
    if (buffer) wayland_dmabuf_buffer_destroy(buffer);
    return NULL;
}

/**********************************************************************
 *          wayland_dmabuf_wl_buffer_for_gpu_fd
 *
 * Convenience: build a single-plane dmabuf buffer from a raw GPU FD
 * and present it directly. This is the hot path for DXVK/Vulkan frames.
 */
struct wayland_dmabuf_buffer *wayland_dmabuf_wl_buffer_for_gpu_fd(
    int width, int height, int gpu_fd, uint32_t gpu_format,
    uint64_t gpu_modifier, uint32_t gpu_stride,
    dmabuf_buffer_release_cb release_cb)
{
    uint32_t offsets[1] = {0};
    uint32_t strides[1] = {gpu_stride ? gpu_stride : (uint32_t)(width * 4)};
    int fds[1] = {gpu_fd};

    return wayland_dmabuf_buffer_create(width, height, gpu_format,
                                        gpu_modifier, 1, fds,
                                        offsets, strides, release_cb);
}

/**********************************************************************
 *          wayland_dmabuf_has_support
 *
 * Query whether dmabuf protocol is available and negotiated.
 */
BOOL wayland_dmabuf_has_support(void)
{
    return process_wayland.zwp_linux_dmabuf_v1 != NULL && dmabuf_state.received;
}