// Override Android API availability checks for the waylandie_bridge target.
// The NDK uses __ANDROID_MIN_SDK_VERSION__ to gate function availability.
// WinNative targets API 26 globally, but our bridge needs API 31+.
// At runtime, these functions are safe — S24 runs Android 14+ (API 34+).
#pragma once

// Undefine and redefine to 31
#undef __ANDROID_MIN_SDK_VERSION__
#define __ANDROID_MIN_SDK_VERSION__ 31

// Also override __ANDROID_API__ if set
#undef __ANDROID_API__
#define __ANDROID_API__ 31

// Tell the compiler we're targeting API 31+
// This unlocks: ASurfaceTransaction_setCrop (31), ASurfaceTransaction_setBuffer (29),
// AHardwareBuffer_isSupported (29), AHardwareBuffer_getId (31), memfd_create (30)
