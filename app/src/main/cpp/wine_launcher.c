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
#include <errno.h>
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

    /* ===== PRE-FLIGHT VALIDATION =====
     * Check all conditions that MUST be true before execve. Log failures
     * to both logcat and stderr (so they appear in the trace file).
     * We don't abort on failure — Wine might still work, and the log
     * helps diagnose why it doesn't. */
    #define LAUNCH_VALIDATE(cond, msg, ...) do { \
        if (!(cond)) { \
            LOGE("[launcher] VALIDATION FAIL: " msg, ##__VA_ARGS__); \
            fprintf(stderr, "[launcher] VALIDATION FAIL: " msg "\n", ##__VA_ARGS__); \
            fflush(stderr); \
        } \
    } while (0)

    /* Validate wine binary */
    LAUNCH_VALIDATE(wineBinary != NULL && wineBinary[0] == '/',
                    "wine binary path is NULL or not absolute: %s",
                    wineBinary ? wineBinary : "(null)");
    if (wineBinary && wineBinary[0] == '/') {
        if (access(wineBinary, X_OK) != 0) {
            LAUNCH_VALIDATE(0, "wine binary not executable: %s (errno=%d %s)",
                            wineBinary, errno, strerror(errno));
        }
    }

    /* Validate exe path */
    LAUNCH_VALIDATE(exePath != NULL && exePath[0] != '\0',
                    "exe path is NULL or empty");
    if (exePath && exePath[0] != '\0') {
        if (access(exePath, R_OK) != 0) {
            LAUNCH_VALIDATE(0, "exe path not readable: %s (errno=%d %s)",
                            exePath, errno, strerror(errno));
        }
    }

    /* Validate critical env vars */
    const char *home = getenv("HOME");
    LAUNCH_VALIDATE(home != NULL && home[0] == '/',
                    "HOME not set or not absolute: %s", home ? home : "(null)");
    const char *winepref = getenv("WINEPREFIX");
    LAUNCH_VALIDATE(winepref != NULL && winepref[0] == '/',
                    "WINEPREFIX not set or not absolute: %s",
                    winepref ? winepref : "(null)");
    const char *ldpath = getenv("LD_LIBRARY_PATH");
    LAUNCH_VALIDATE(ldpath != NULL && ldpath[0] != '\0',
                    "LD_LIBRARY_PATH not set");
    const char *waylandDisp = getenv("WAYLAND_DISPLAY");
    LAUNCH_VALIDATE(waylandDisp != NULL && waylandDisp[0] != '\0',
                    "WAYLAND_DISPLAY not set — Wine will fail to connect to Wayland");
    const char *xdgRuntime = getenv("XDG_RUNTIME_DIR");
    LAUNCH_VALIDATE(xdgRuntime != NULL && xdgRuntime[0] == '/',
                    "XDG_RUNTIME_DIR not set or not absolute: %s",
                    xdgRuntime ? xdgRuntime : "(null)");
    const char *wineDll = getenv("WINEDLLOVERRIDES");
    LAUNCH_VALIDATE(wineDll != NULL && strstr(wineDll, "winewayland.drv") != NULL,
                    "WINEDLLOVERRIDES missing winewayland.drv: %s",
                    wineDll ? wineDll : "(null)");

    /* Log validation complete */
    LOGI("[launcher] pre-flight validation complete");
    fprintf(stderr, "[launcher] pre-flight validation complete\n");
    fflush(stderr);

    /* IMPORTANT: We log EVERYTHING to BOTH __android_log_print (logcat) AND
     * stderr. The logcat output is for live debugging via adb logcat. The
     * stderr output is captured by Java's ProcessBuilder (which redirects
     * stderr→stdout via redirectErrorStream(true)), and ends up in the
     * GameLaunchTracer's trace file via the wl-wine-output thread. Without
     * the stderr copy, the trace file has no record of what the launcher
     * actually did, which makes debugging impossible.
     *
     * The "[launcher]" prefix lets the trace file distinguish launcher
     * output from Wine's own stdout/stderr.
     *
     * We fflush(stderr) after each write because stderr is line-buffered
     * by default in glibc, but bionic libc may fully-buffer it when not
     * attached to a tty (which is our case — ProcessBuilder uses a pipe).
     * Without the flush, the writes may sit in the buffer and never reach
     * Java before execve() replaces the process image. */
    #define LAUNCH_LOG(fmt, ...) do { \
        LOGI(fmt, ##__VA_ARGS__); \
        fprintf(stderr, "[launcher] " fmt "\n", ##__VA_ARGS__); \
        fflush(stderr); \
    } while (0)

    LAUNCH_LOG("Wine launcher starting");
    LAUNCH_LOG("  wine binary: %s", wineBinary);
    LAUNCH_LOG("  exe path:    %s", exePath);
    LAUNCH_LOG("  extra args:  %d", argc - 3);
    for (int i = 3; i < argc; i++) {
        LAUNCH_LOG("    argv[%d] = %s", i, argv[i]);
    }

    /* Log key env vars for debugging (Java sets these before calling us).
     * NOTE: home, winepref, waylandDisp, xdgRuntime, wineDll were already
     * declared in the validation section above — reuse them. */
    const char *ldLibPath = getenv("LD_LIBRARY_PATH");
    const char *path      = getenv("PATH");
    const char *display   = getenv("DISPLAY");
    LAUNCH_LOG("  LD_LIBRARY_PATH=%s", ldLibPath ? ldLibPath : "(unset)");
    LAUNCH_LOG("  HOME=%s",            home      ? home      : "(unset)");
    LAUNCH_LOG("  WINEPREFIX=%s",      winepref  ? winepref  : "(unset)");
    LAUNCH_LOG("  PATH=%s",            path      ? path      : "(unset)");
    LAUNCH_LOG("  DISPLAY=%s",         display   ? display   : "(unset)");
    LAUNCH_LOG("  WAYLAND_DISPLAY=%s", waylandDisp ? waylandDisp : "(unset)");
    LAUNCH_LOG("  WINEDLLOVERRIDES=%s", wineDll  ? wineDll   : "(unset)");

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
    fprintf(stderr, "[launcher] Calling execve(%s, ...)\n", wineBinary);
    fflush(stderr);

    /* execve replaces this process with wine. If it returns, it failed. */
    execve(wineBinary, newArgv, environ);

    /* If we get here, execve failed. Log the error and exit. */
    LOGE("execve FAILED: %s (errno=%d)", strerror(errno), errno);
    fprintf(stderr, "[launcher] execve FAILED: %s (errno=%d)\n",
            strerror(errno), errno);
    fprintf(stderr, "[launcher] (errno %d = %s)\n", errno, strerror(errno));
    fflush(stderr);

    free(newArgv);
    return 127;  /* 127 = "command not found" — matches shell convention */
}
