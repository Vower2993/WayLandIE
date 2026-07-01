#ifndef LINUX_DMABUF_UNSTABLE_V1_CLIENT_PROTOCOL_H
#define LINUX_DMABUF_UNSTABLE_V1_CLIENT_PROTOCOL_H
#include <stdint.h>
struct wl_proxy;
struct wl_display;
struct wl_surface;
struct wl_buffer;
struct zwp_linux_dmabuf_v1;
struct zwp_linux_buffer_params_v1;
struct zwp_linux_dmabuf_v1_listener {
    void (*format)(void *data, struct zwp_linux_dmabuf_v1 *dmabuf, uint32_t format);
    void (*modifier)(void *data, struct zwp_linux_dmabuf_v1 *dmabuf, uint32_t format, uint32_t modifier_hi, uint32_t modifier_lo);
};
struct zwp_linux_buffer_params_v1_listener {
    void (*created)(void *data, struct zwp_linux_buffer_params_v1 *params);
    void (*failed)(void *data, struct zwp_linux_buffer_params_v1 *params);
};
struct zwp_linux_buffer_params_v1 *zwp_linux_dmabuf_v1_create_params(struct zwp_linux_dmabuf_v1 *dmabuf);
void zwp_linux_dmabuf_v1_destroy(struct zwp_linux_dmabuf_v1 *dmabuf);
int zwp_linux_dmabuf_v1_add_listener(struct zwp_linux_dmabuf_v1 *dmabuf, const struct zwp_linux_dmabuf_v1_listener *listener, void *data);
int zwp_linux_buffer_params_v1_add_listener(struct zwp_linux_buffer_params_v1 *params, const struct zwp_linux_buffer_params_v1_listener *listener, void *data);
void zwp_linux_buffer_params_v1_add(struct zwp_linux_buffer_params_v1 *params, int32_t fd, uint32_t plane_idx, uint32_t offset, uint32_t stride, uint32_t modifier_hi, uint32_t modifier_lo);
struct wl_buffer *zwp_linux_buffer_params_v1_create_immed(struct zwp_linux_buffer_params_v1 *params, int32_t width, int32_t height, uint32_t format, uint32_t flags);
struct wl_buffer *zwp_linux_buffer_params_v1_create(struct zwp_linux_buffer_params_v1 *params, int32_t width, int32_t height, uint32_t format, uint32_t flags);
void zwp_linux_buffer_params_v1_destroy(struct zwp_linux_buffer_params_v1 *params);
#endif
