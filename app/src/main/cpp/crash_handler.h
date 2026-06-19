/*
 * crash_handler.h — native signal handler for WayLandIE.
 *
 * See crash_handler.c for full documentation. This header exposes the
 * single entry point install_native_crash_handler() which is called
 * from JNI_OnLoad of libwaylandie_display_native.so.
 */
#ifndef WAYLANDIE_CRASH_HANDLER_H
#define WAYLANDIE_CRASH_HANDLER_H

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Installs sigaction handlers for SIGSEGV, SIGABRT, SIGBUS that write
 * a tombstone to <crash_dir>/crash-<timestamp>-native.txt before
 * calling _exit(11).
 *
 * @param crash_dir  Absolute path to a writable directory for crash
 *                   files. Typically the app's
 *                   getExternalFilesDir(null)/logs/. The directory
 *                   will be created if it does not exist (mkdir 0700).
 */
void install_native_crash_handler(const char *crash_dir);

#ifdef __cplusplus
}
#endif

#endif /* WAYLANDIE_CRASH_HANDLER_H */
