#ifndef LINUX_DMABUF_UNSTABLE_V1_CLIENT_PROTOCOL_H
#define LINUX_DMABUF_UNSTABLE_V1_CLIENT_PROTOCOL_H
#include <stdint.h>
struct wl_proxy;
struct wl_display;
struct wl_surface;
struct wl_buffer;
typedef struct wl_proxy zwp_linux_dmabuf_v1;
typedef struct wl_proxy zwp_linux_buffer_params_v1;
typedef void (*zwp_linux_dmabuf_v1_format_func_t)(void *data, zwp_linux_dmabuf_v1 *dmabuf, uint32_t format);
typedef void (*zwp_linux_dmabuf_v1_modifier_func_t)(void *data, zwp_linux_dmabuf_v1 *dmabuf, uint32_t format, uint32_t modifier_hi, uint32_t modifier_lo);
typedef struct { zwp_linux_dmabuf_v1_format_func_t format; zwp_linux_dmabuf_v1_modifier_func_t modifier; } zwp_linux_dmabuf_v1_listener;
typedef void (*zwp_linux_buffer_params_v1_created_func_t)(void *data, zwp_linux_buffer_params_v1 *params);
typedef void (*zwp_linux_buffer_params_v1_failed_func_t)(void *data, zwp_linux_buffer_params_v1 *params);
typedef struct { zwp_linux_buffer_params_v1_created_func_t created; zwp_linux_buffer_params_v1_failed_func_t failed; } zwp_linux_buffer_params_v1_listener;
zwp_linux_buffer_params_v1 *zwp_linux_dmabuf_v1_create_params(zwp_linux_dmabuf_v1 *dmabuf);
void zwp_linux_dmabuf_v1_destroy(zwp_linux_dmabuf_v1 *dmabuf);
int zwp_linux_dmabuf_v1_add_listener(zwp_linux_dmabuf_v1 *dmabuf, const zwp_linux_dmabuf_v1_listener *listener, void *data);
int zwp_linux_buffer_params_v1_add_listener(zwp_linux_buffer_params_v1 *params, const zwp_linux_buffer_params_v1_listener *listener, void *data);
void zwp_linux_buffer_params_v1_add(zwp_linux_buffer_params_v1 *params, int32_t fd, uint32_t plane_idx, uint32_t offset, uint32_t stride, uint32_t modifier_hi, uint32_t modifier_lo);
struct wl_buffer *zwp_linux_buffer_params_v1_create_immed(zwp_linux_buffer_params_v1 *params, int32_t width, int32_t height, uint32_t format, uint32_t flags);
struct wl_buffer *zwp_linux_buffer_params_v1_create(zwp_linux_buffer_params_v1 *params, int32_t width, int32_t height, uint32_t format, uint32_t flags);
void zwp_linux_buffer_params_v1_destroy(zwp_linux_buffer_params_v1 *params);
#endif
