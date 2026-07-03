/* Stub windows.h for Android cross-compilation.
 * Provides minimal types needed by vulkan_win32.h.
 * We never call Win32 APIs — these are opaque types passed through
 * from DXVK to our layer's surface creation function. */
#ifndef _STUB_WINDOWS_H
#define _STUB_WINDOWS_H

#ifndef _WINDEF_
typedef void *HINSTANCE;
typedef void *HWND;
typedef void *HMONITOR;
typedef void *HMODULE;
typedef void *HANDLE;
typedef unsigned long DWORD;
typedef int BOOL;
typedef const char *LPCSTR;
typedef const wchar_t *LPCWSTR;
typedef wchar_t WCHAR;
#endif

#ifndef _WINNT_
typedef struct _SECURITY_ATTRIBUTES {
    DWORD nLength;
    void *lpSecurityDescriptor;
    int bInheritHandle;
} SECURITY_ATTRIBUTES;
#endif

#endif /* _STUB_WINDOWS_H */
