// WayLandIE minimal X server — X11 core protocol constants & wire types.
//
// This is NOT a full X.org server. It implements just enough of the X11
// core wire protocol (see X11/Protocol.java in Winlator for reference,
// or the official X11 protocol spec at https://xcb.freedesktop.org/)
// to satisfy Wine's winex11.drv.
//
// All multi-byte integers on the wire are BIG-ENDIAN (network byte order),
// regardless of the host CPU. We use explicit byte-swap helpers below.
//
// Reference: "X Window System Protocol" (X Consortium Standard, X Version 11),
// MIT Project Athena, 1987.
#pragma once

#include <cstdint>
#include <endian.h>

namespace waylandie_x11 {

// ----- X11 major opcodes (from X11/requests.h, but only the ones we need) -----
enum XRequest : uint8_t {
    X_CreateWindow            = 1,
    X_ChangeWindowAttributes  = 2,
    X_GetWindowAttributes     = 3,
    X_DestroyWindow           = 4,
    X_DestroySubwindows       = 5,
    X_ChangeSaveSet           = 6,
    X_ReparentWindow          = 7,
    X_MapWindow               = 8,
    X_MapSubwindows           = 9,
    X_UnmapWindow             = 10,
    X_UnmapSubwindows         = 11,
    X_ConfigureWindow         = 12,
    X_CirculateWindow         = 13,
    X_GetGeometry             = 14,
    X_QueryTree               = 15,
    X_InternAtom              = 16,
    X_GetAtomName             = 17,
    X_ChangeProperty          = 18,
    X_DeleteProperty          = 19,
    X_GetProperty             = 20,
    X_ListProperties          = 21,
    X_SetSelectionOwner       = 22,
    X_GetSelectionOwner       = 23,
    X_ConvertSelection        = 24,
    X_SendEvent               = 25,
    X_GrabPointer             = 26,
    X_UngrabPointer           = 27,
    X_GrabButton              = 28,
    X_UngrabButton            = 29,
    X_ChangeActivePointerGrab = 30,
    X_GrabKeyboard            = 31,
    X_UngrabKeyboard          = 32,
    X_GrabKey                 = 33,
    X_UngrabKey               = 34,
    X_AllowEvents             = 35,
    X_GrabServer              = 36,
    X_UngrabServer            = 37,
    X_QueryPointer            = 38,
    X_GetMotionEvents         = 39,
    X_TranslateCoordinates    = 40,
    X_WarpPointer             = 41,
    X_SetInputFocus           = 42,
    X_GetInputFocus           = 43,
    X_QueryKeymap             = 44,
    X_OpenFont                = 45,
    X_CloseFont               = 46,
    X_QueryFont               = 47,
    X_QueryTextExtents        = 48,
    X_ListFonts               = 49,
    X_ListFontsWithInfo       = 50,
    X_SetFontPath             = 51,
    X_GetFontPath             = 52,
    X_CreatePixmap            = 53,
    X_FreePixmap              = 54,
    X_CreateGC                = 55,
    X_ChangeGC                = 56,
    X_CopyGC                  = 57,
    X_SetDashes               = 58,
    X_SetClipRectangles       = 59,
    X_FreeGC                  = 60,
    X_ClearArea               = 61,
    X_CopyArea                = 62,
    X_CopyPlane               = 63,
    X_PolyPoint               = 64,
    X_PolyLine                = 65,
    X_PolySegment             = 66,
    X_PolyRectangle           = 67,
    X_PolyArc                 = 68,
    X_FillPoly                = 69,
    X_PolyFillRectangle       = 70,
    X_PolyFillArc             = 71,
    X_PutImage                = 72,
    X_GetImage                = 73,
    X_PolyText8               = 74,
    X_PolyText16              = 75,
    X_ImageText8              = 76,
    X_ImageText16             = 77,
    X_CreateColormap          = 78,
    X_FreeColormap            = 79,
    X_CopyColormapAndFree     = 80,
    X_InstallColormap         = 81,
    X_UninstallColormap       = 82,
    X_ListInstalledColormaps  = 83,
    X_AllocColor              = 84,
    X_AllocNamedColor         = 85,
    X_AllocColorCells         = 86,
    X_AllocColorPlanes        = 87,
    X_FreeColors              = 88,
    X_StoreColors             = 89,
    X_StoreNamedColor         = 90,
    X_QueryColors             = 91,
    X_LookupColor             = 92,
    X_CreateCursor            = 93,
    X_CreateGlyphCursor       = 94,
    X_FreeCursor              = 95,
    X_RecolorCursor           = 96,
    X_QueryBestSize           = 97,
    X_QueryExtension          = 98,
    X_ListExtensions          = 99,
    X_ChangeKeyboardMapping   = 100,
    X_GetKeyboardMapping      = 101,
    X_ChangeKeyboardControl   = 102,
    X_GetKeyboardControl      = 103,
    X_Bell                    = 104,
    X_ChangePointerControl    = 105,
    X_GetPointerControl       = 106,
    X_SetScreenSaver          = 107,
    X_GetScreenSaver          = 108,
    X_ChangeHosts             = 109,
    X_ListHosts               = 110,
    X_SetAccessControl        = 111,
    X_SetCloseDownMode        = 112,
    X_KillClient              = 113,
    X_RotateProperties        = 114,
    X_ForceScreenSaver        = 115,
    X_SetPointerMapping       = 116,
    X_GetPointerMapping       = 117,
    X_SetModifierMapping      = 118,
    X_GetModifierMapping      = 119,
    X_NoOperation             = 127,
};

// ----- Event codes (event type byte) -----
enum XEvent : uint8_t {
    EV_KeyPress               = 2,
    EV_KeyRelease             = 3,
    EV_ButtonPress            = 4,
    EV_ButtonRelease          = 5,
    EV_MotionNotify           = 6,
    EV_EnterNotify            = 7,
    EV_LeaveNotify            = 8,
    EV_FocusIn                = 9,
    EV_FocusOut               = 10,
    EV_KeymapNotify           = 11,
    EV_Expose                 = 12,
    EV_GraphicsExpose         = 13,
    EV_NoExpose               = 14,
    EV_VisibilityNotify       = 15,
    EV_CreateNotify           = 16,
    EV_DestroyNotify          = 17,
    EV_UnmapNotify            = 18,
    EV_MapNotify              = 19,
    EV_MapRequest             = 20,
    EV_ReparentNotify         = 21,
    EV_ConfigureNotify        = 22,
    EV_ConfigureRequest       = 23,
    EV_GravityNotify          = 24,
    EV_ResizeRequest          = 25,
    EV_CirculateNotify        = 26,
    EV_CirculateRequest       = 27,
    EV_PropertyNotify         = 28,
    EV_SelectionClear         = 29,
    EV_SelectionRequest       = 30,
    EV_SelectionNotify        = 31,
    EV_ColormapNotify         = 32,
    EV_ClientMessage          = 33,
    EV_MappingNotify          = 34,
};

// ----- Error codes -----
enum XError : uint8_t {
    ERR_Success        = 0,
    ERR_Request        = 1,
    ERR_Value          = 2,
    ERR_Window         = 3,
    ERR_Pixmap         = 4,
    ERR_Atom           = 5,
    ERR_Cursor         = 6,
    ERR_Font           = 7,
    ERR_Match          = 8,
    ERR_Drawable       = 9,
    ERR_Access         = 10,
    ERR_Alloc          = 11,
    ERR_Colormap       = 12,
    ERR_GContext       = 13,
    ERR_IDChoice       = 14,
    ERR_Name           = 15,
    ERR_Length         = 16,
    ERR_Implementation = 17,
};

// ----- Window attribute bit masks (ChangeWindowAttributes value-mask) -----
enum WindowAttrMask : uint32_t {
    WA_BackgroundPixmap      = 1u << 0,
    WA_BackgroundPixel       = 1u << 1,
    WA_BorderPixmap          = 1u << 2,
    WA_BorderPixel           = 1u << 3,
    WA_BitGravity            = 1u << 4,
    WA_WinGravity            = 1u << 5,
    WA_BackingStore          = 1u << 6,
    WA_BackingPlanes         = 1u << 7,
    WA_BackingPixel          = 1u << 8,
    WA_OverrideRedirect      = 1u << 9,
    WA_SaveUnder             = 1u << 10,
    WA_EventMask             = 1u << 11,
    WA_DoNotPropagateMask    = 1u << 12,
    WA_Colormap              = 1u << 13,
    WA_Cursor                = 1u << 14,
};

// ----- ConfigureWindow value-mask -----
enum ConfigWindowMask : uint32_t {
    CW_X           = 1u << 0,
    CW_Y           = 1u << 1,
    CW_Width       = 1u << 2,
    CW_Height      = 1u << 3,
    CW_BorderWidth = 1u << 4,
    CW_Sibling     = 1u << 5,
    CW_StackMode   = 1u << 6,
};

// ----- GC attribute masks -----
enum GCAttrMask : uint32_t {
    GC_Function          = 1u << 0,
    GC_PlaneMask         = 1u << 1,
    GC_Foreground        = 1u << 2,
    GC_Background        = 1u << 3,
    GC_LineWidth         = 1u << 4,
    GC_LineStyle         = 1u << 5,
    GC_CapStyle          = 1u << 6,
    GC_JoinStyle         = 1u << 7,
    GC_FillStyle         = 1u << 8,
    GC_FillRule          = 1u << 9,
    GC_Tile              = 1u << 10,
    GC_Stipple           = 1u << 11,
    GC_TileStippleXOrigin= 1u << 12,
    GC_TileStippleYOrigin= 1u << 13,
    GC_Font              = 1u << 14,
    GC_SubwindowMode     = 1u << 15,
    GC_GraphicsExposures = 1u << 16,
    GC_ClipXOrigin       = 1u << 17,
    GC_ClipYOrigin       = 1u << 18,
    GC_ClipMask          = 1u << 19,
    GC_DashOffset        = 1u << 20,
    GC_DashList          = 1u << 21,
    GC_ArcMode           = 1u << 22,
};

// ----- GC function values (GXcopy etc.) -----
enum GXFunc : uint32_t {
    GX_clear        = 0,
    GX_and          = 1,
    GX_andReverse   = 2,
    GX_copy         = 3,
    GX_andInverted  = 4,
    GX_noop         = 5,
    GX_xor          = 6,
    GX_or           = 7,
    GX_nor          = 8,
    GX_equiv        = 9,
    GX_invert       = 10,
    GX_orReverse    = 11,
    GX_copyInverted = 12,
    GX_orInverted   = 13,
    GX_nand         = 14,
    GX_set          = 15,
};

// ----- PutImage format -----
enum ImageFormat : uint8_t {
    IMG_Bitmap = 0,
    IMG_XYPixmap = 1,
    IMG_ZPixmap = 2,
};

// ----- Property modes -----
enum PropertyMode : uint8_t {
    PropMode_Replace = 0,
    PropMode_Prepend = 1,
    PropMode_Append  = 2,
};

// ----- Predefined atoms -----
enum PredefinedAtom : uint32_t {
    ATOM_None              = 0,
    ATOM_Primary           = 1,
    ATOM_Secondary         = 2,
    ATOM_Arc               = 3,
    ATOM_Atom              = 4,
    ATOM_Bitmap            = 5,
    ATOM_Cardinal          = 6,
    ATOM_Colormap          = 7,
    ATOM_Cursor            = 8,
    ATOM_CutBuffer0        = 9,
    ATOM_CutBuffer1        = 10,
    ATOM_CutBuffer2        = 11,
    ATOM_CutBuffer3        = 12,
    ATOM_CutBuffer4        = 13,
    ATOM_CutBuffer5        = 14,
    ATOM_CutBuffer6        = 15,
    ATOM_CutBuffer7        = 16,
    ATOM_Drawable          = 17,
    ATOM_Font              = 18,
    ATOM_Integer           = 19,
    ATOM_Pixmap            = 20,
    ATOM_Point             = 21,
    ATOM_Rectangle         = 22,
    ATOM_ResourceManager   = 23,
    ATOM_RGB_Color_Map     = 24,
    ATOM_RGB_Best_Map      = 25,
    ATOM_RGB_Red_Map       = 26,
    ATOM_RGB_Green_Map     = 27,
    ATOM_RGB_Blue_Map      = 28,
    ATOM_RGB_Default_Map   = 29,
    ATOM_String            = 31,
    ATOM_VisualID          = 32,
    ATOM_Window            = 33,
    ATOM_WM_Class          = 67,
    ATOM_WM_Name           = 39,
    ATOM_WM_Protocols      = 78,  // Actual value assigned dynamically; we reserve
};

// ----- Event masks (used in ChangeWindowAttributes & CreateWindow) -----
enum EventMask : uint64_t {
    EM_KeyPressMask             = 1ull << 0,
    EM_KeyReleaseMask           = 1ull << 1,
    EM_ButtonPressMask          = 1ull << 2,
    EM_ButtonReleaseMask        = 1ull << 3,
    EM_EnterWindowMask          = 1ull << 4,
    EM_LeaveWindowMask          = 1ull << 5,
    EM_PointerMotionMask        = 1ull << 6,
    EM_PointerMotionHintMask    = 1ull << 7,
    EM_Button1MotionMask        = 1ull << 8,
    EM_Button2MotionMask        = 1ull << 9,
    EM_Button3MotionMask        = 1ull << 10,
    EM_Button4MotionMask        = 1ull << 11,
    EM_Button5MotionMask        = 1ull << 12,
    EM_ButtonMotionMask         = 1ull << 13,
    EM_KeymapStateMask          = 1ull << 14,
    EM_ExposureMask             = 1ull << 15,
    EM_VisibilityChangeMask     = 1ull << 16,
    EM_StructureNotifyMask      = 1ull << 17,
    EM_ResizeRedirectMask       = 1ull << 18,
    EM_SubstructureNotifyMask   = 1ull << 19,
    EM_SubstructureRedirectMask = 1ull << 20,
    EM_FocusChangeMask          = 1ull << 21,
    EM_PropertyChangeMask       = 1ull << 22,
    EM_ColormapChangeMask       = 1ull << 23,
    EM_OwnerGrabButtonMask      = 1ull << 24,
};

// ----- Big-endian helpers -----
// X11 wire protocol is ALWAYS big-endian. On little-endian ARM64, we swap.
inline uint16_t be16(uint16_t v) { return htobe16(v); }
inline uint32_t be32(uint32_t v) { return htobe32(v); }
inline uint16_t le16_from_be(uint16_t v) { return be16toh(v); }
inline uint32_t le32_from_be(uint32_t v) { return be32toh(v); }

// ----- Wire format structures (all packed, big-endian on wire) -----
// We don't use these directly; we read/write bytes via cursor helpers to
// avoid alignment issues. But these constants document the wire layout.

// Setup failed reply (sent if we reject the connection)
struct SetupFailed {
    uint8_t  status;       // 0 = failed, 1 = authenticate, 2 = success
    uint8_t  reason_len;
    uint16_t protocol_major_version;
    uint16_t protocol_minor_version;
    uint16_t length;       // in 4-byte units, of additional data
    // followed by reason_len bytes of reason, padded to 4-byte boundary
} __attribute__((packed));

// Setup success reply
struct SetupSuccess {
    uint8_t  status;            // 2 = success
    uint8_t  pad;
    uint16_t protocol_major_version;
    uint16_t protocol_minor_version;
    uint16_t length;            // additional data length in 4-byte units
    uint32_t release_number;
    uint32_t resource_id_base;
    uint32_t resource_id_mask;
    uint32_t motion_buffer_size;
    uint16_t vendor_len;
    uint16_t max_request_size;
    uint8_t  num_screen_roots;
    uint8_t  num_pixmap_formats;
    uint8_t  image_byte_order;       // 0 = LSB, 1 = MSB
    uint8_t  bitmap_bit_order;       // 0 = LSB, 1 = MSB
    uint8_t  bitmap_scanline_unit;
    uint8_t  bitmap_scanline_pad;
    uint8_t  min_keycode;
    uint8_t  max_keycode;
    uint32_t pad2;
    // followed by: vendor string (padded), pixmap formats, screens
} __attribute__((packed));

// Visual type (32 bytes)
struct VisualTypeWire {
    uint32_t visual_id;
    uint8_t  class_;
    uint8_t  bits_per_rgb;
    uint16_t colormap_entries;
    uint32_t red_mask;
    uint32_t green_mask;
    uint32_t blue_mask;
    uint32_t pad;
} __attribute__((packed));

// Depth info (8 bytes + visuals)
struct DepthWire {
    uint8_t  depth;
    uint8_t  pad;
    uint16_t num_visuals;
    uint32_t pad2;
    // followed by num_visuals * VisualTypeWire
} __attribute__((packed));

// Screen info (40 bytes + depths)
struct ScreenWire {
    uint32_t root;
    uint32_t default_colormap;
    uint32_t white_pixel;
    uint32_t black_pixel;
    uint32_t current_input_masks;
    uint16_t width_in_pixels;
    uint16_t height_in_pixels;
    uint16_t width_in_millimeters;
    uint16_t height_in_millimeters;
    uint16_t min_installed_maps;
    uint16_t max_installed_maps;
    uint32_t root_visual;
    uint8_t  backing_stores;
    uint8_t  save_unders;
    uint8_t  root_depth;
    uint8_t  num_depths;
    // followed by num_depths * DepthWire
} __attribute__((packed));

// Pixmap format (8 bytes)
struct PixmapFormatWire {
    uint8_t  depth;
    uint8_t  bits_per_pixel;
    uint8_t  scanline_pad;
    uint8_t  pad[5];
} __attribute__((packed));

// X11 request header (every request starts with this)
struct RequestHeader {
    uint8_t  major_opcode;
    uint8_t  data;        // depends on request (e.g. depth for CreateWindow)
    uint16_t length;      // in 4-byte units, including this header (BE)
} __attribute__((packed));

// X11 reply header (for requests that have replies)
struct ReplyHeader {
    uint8_t  is_reply;    // always 1
    uint8_t  data;        // depends on request
    uint16_t sequence;    // BE
    uint32_t length;      // BE, additional length in 4-byte units
} __attribute__((packed));

// X11 error header
struct ErrorHeader {
    uint8_t  is_error;    // always 0
    uint8_t  code;        // error code
    uint16_t sequence;    // BE
    uint32_t bad_value;   // the value that caused the error
    uint16_t minor_opcode;
    uint8_t  major_opcode;
    uint8_t  pad;
    uint32_t pad2[5];
    // (32 bytes total, including the unused pad2)
} __attribute__((packed));

// X11 event header (32 bytes total)
struct EventHeader {
    uint8_t  type;        // event code
    // ... 31 more bytes depending on event type
} __attribute__((packed));

}  // namespace waylandie_x11
