/**********************************************************************
 *          Wayland dmabuf buffer
 */

#define WINEWAYLAND_MAX_DMABUF_PLANES 4

/* Forward declaration needed before typedef. */
struct wayland_dmabuf_buffer;

typedef void (*dmabuf_buffer_release_cb)(struct wayland_dmabuf_buffer *buffer);

struct wayland_dmabuf_buffer
{
    struct wl_buffer *wl_buffer;
    int width, height;
    uint32_t format;
    uint64_t modifier;
    int plane_count;
    int fds[WINEWAYLAND_MAX_DMABUF_PLANES];
    uint32_t offsets[WINEWAYLAND_MAX_DMABUF_PLANES];
    uint32_t strides[WINEWAYLAND_MAX_DMABUF_PLANES];
    dmabuf_buffer_release_cb release_callback;
    BOOL busy;
};

BOOL wayland_dmabuf_init(struct wl_registry *registry, uint32_t id, uint32_t version);
BOOL wayland_dmabuf_has_support(void);
struct wayland_dmabuf_buffer *wayland_dmabuf_buffer_create(
    int width, int height, uint32_t format, uint64_t modifier,
    int plane_count, const int *fds, const uint32_t *offsets,
    const uint32_t *strides, dmabuf_buffer_release_cb release_cb);
struct wayland_dmabuf_buffer *wayland_dmabuf_wl_buffer_for_gpu_fd(
    int width, int height, int gpu_fd, uint32_t gpu_format,
    uint64_t gpu_modifier, uint32_t gpu_stride,
    dmabuf_buffer_release_cb release_cb);
void wayland_dmabuf_buffer_destroy(struct wayland_dmabuf_buffer *buffer);

void wayland_surface_attach_dmabuf(struct wayland_surface *surface,
                                   struct wayland_dmabuf_buffer *dmabuf_buffer,
                                   HRGN surface_damage_region);