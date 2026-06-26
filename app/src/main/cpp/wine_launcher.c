/*
 * wine_launcher.c — Tiny native launcher that execve()'s a bionic Wine binary.
 *
 * WHY THIS EXISTS:
 *   Android 10+ (API 29+) enforces W^X (writable XOR executable) on app data
 *   directories for apps with targetSdk >= 29. This blocks execve() of
 *   binaries in getFilesDir() — which is where Wine lives.
 *
 *   This launcher is packaged as libwine_launcher.so in jniLibs/. Android
 *   extracts it to nativeLibraryDir at install time, where SELinux allows
 *   execve(). The launcher then execve()'s the actual wine binary from
 *   getFilesDir() — the launcher's execve is allowed because it's already
 *   running with the right SELinux context.
 *
 *   This eliminates PRoot entirely. PRoot was adding 2-5x overhead to every
 *   syscall (ptrace traps), which is brutal for games. The launcher has zero
 *   overhead — it's a single execve, then Wine runs natively.
 *
 * USAGE:
 *   libwine_launcher.so <wine-binary-path> <exe-path> [extra-args...]
 *
 *   Environment variables must be set by the caller (Java ProcessBuilder):
 *     LD_LIBRARY_PATH  — colon-separated list of lib search paths
 *     HOME             — Wine's home dir (e.g. <rootfs>/home/xuser)
 *     WINEPREFIX       — Wine prefix dir (e.g. <rootfs>/home/xuser/.wine)
 *     PATH             — executable search path
 *     (plus all other Wine env vars: WINEDLLOVERRIDES, WINEDEBUG, etc.)
 *
 *   The launcher passes through ALL environment variables unchanged. It
 *   does NOT modify the environment — it just execve()'s with the existing
 *   environ.
 *
 * BUILD:
 *   Compiled by app/src/main/cpp/CMakeLists.txt as a PIE executable named
 *   libwine_launcher.so. The `lib` prefix + `.so` suffix are required so
 *   Android packages it in jniLibs (Android only extracts files matching
 *   lib*.so from jniLibs).
 */

#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <syslog.h>
#include <android/log.h>

#define TAG "WayLandIE/Launcher"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

int main(int argc, char *argv[]) {
    /* argv[0] = launcher path (ignored)
     * argv[1] = wine binary path (absolute)
     * argv[2] = exe path (the .exe to run)
     * argv[3..] = extra args passed to wine
     */
    if (argc < 3) {
        LOGE("usage: %s <wine-binary> <exe-path> [extra-args...]", argv[0]);
        fprintf(stderr, "usage: %s <wine-binary> <exe-path> [extra-args...]\n", argv[0]);
        return 2;
    }

    const char *wineBinary = argv[1];
    const char *exePath    = argv[2];

    LOGI("Wine launcher starting");
    LOGI("  wine binary: %s", wineBinary);
    LOGI("  exe path:    %s", exePath);
    LOGI("  extra args:  %d", argc - 3);
    for (int i = 3; i < argc; i++) {
        LOGI("    argv[%d] = %s", i, argv[i]);
    }

    /* Log key env vars for debugging (Java sets these before calling us) */
    const char *ldLibPath = getenv("LD_LIBRARY_PATH");
    const char *home      = getenv("HOME");
    const char *winepref  = getenv("WINEPREFIX");
    const char *path      = getenv("PATH");
    LOGI("  LD_LIBRARY_PATH=%s", ldLibPath ? ldLibPath : "(unset)");
    LOGI("  HOME=%s",            home      ? home      : "(unset)");
    LOGI("  WINEPREFIX=%s",      winepref  ? winepref  : "(unset)");
    LOGI("  PATH=%s",            path      ? path      : "(unset)");

    /* Build new argv for execve.
     * Wine expects: argv[0] = wine binary name, argv[1] = exe path, ...
     * Some Wine builds inspect argv[0] to find their lib dir, so use the
     * actual binary path (not just "wine").
     */
    char **newArgv = (char **)calloc(argc, sizeof(char *));
    if (!newArgv) {
        LOGE("calloc failed");
        return 1;
    }
    newArgv[0] = (char *)wineBinary;  /* Wine sees its own path as argv[0] */
    newArgv[1] = (char *)exePath;
    for (int i = 3; i < argc; i++) {
        newArgv[i - 1] = argv[i];  /* shift extra args by 1 (we drop launcher's argv[0]) */
    }
    /* last element is NULL (calloc zeroed it) — execve expects NULL-terminated argv */

    LOGI("Calling execve(%s, ...)", wineBinary);

    /* execve replaces this process with wine. If it returns, it failed. */
    execve(wineBinary, newArgv, environ);

    /* If we get here, execve failed. Log the error and exit. */
    LOGE("execve FAILED: %s (errno=%d)", strerror(errno), errno);
    fprintf(stderr, "wine_launcher: execve(%s) failed: %s\n",
            wineBinary, strerror(errno));
    perror("execve");

    free(newArgv);
    return 127;  /* 127 = "command not found" — matches shell convention */
}
