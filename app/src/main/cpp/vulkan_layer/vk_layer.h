#ifndef VK_LAYER_H
#define VK_LAYER_H

#include <vulkan/vulkan.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Loader-specific structure types (not in the official Vulkan spec,
 * defined by the Khronos loader layer interface) */
#define VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO ((VkStructureType)47)
#define VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO   ((VkStructureType)48)

/* Layer function enum */
typedef enum VkLayerFunction_ {
    VK_LAYER_LINK_INFO = 0,
} VkLayerFunction;

/* Layer instance link — chain of layer proc addr pointers */
typedef struct VkLayerInstanceLink_ {
    struct VkLayerInstanceLink_ *pNext;
    PFN_vkGetInstanceProcAddr pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr pfnNextGetDeviceProcAddr;
} VkLayerInstanceLink;

/* Layer instance create info — found in VkInstanceCreateInfo::pNext */
typedef struct {
    VkStructureType sType;
    const void *pNext;
    VkLayerFunction function;
    union {
        VkLayerInstanceLink *pLayerInfo;
    } u;
} VkLayerInstanceCreateInfo;

/* Layer device link */
typedef struct VkLayerDeviceLink_ {
    struct VkLayerDeviceLink_ *pNext;
    PFN_vkGetInstanceProcAddr pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr pfnNextGetDeviceProcAddr;
} VkLayerDeviceLink;

/* Layer device create info — found in VkDeviceCreateInfo::pNext */
typedef struct {
    VkStructureType sType;
    const void *pNext;
    VkLayerFunction function;
    union {
        VkLayerDeviceLink *pLayerInfo;
    } u;
} VkLayerDeviceCreateInfo;

/* Layer properties for vkEnumerateInstanceLayerProperties */
typedef struct {
    char layerName[VK_MAX_EXTENSION_NAME_SIZE];
    uint32_t specVersion;
    uint32_t implementationVersion;
    char description[VK_MAX_DESCRIPTION_SIZE];
} VkLayerProperties;

#ifdef __cplusplus
}
#endif

#endif /* VK_LAYER_H */
