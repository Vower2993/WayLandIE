/* Stub android/log.h for local syntax checking. */
#ifndef _STUB_LOG_H
#define _STUB_LOG_H
enum {
    ANDROID_LOG_INFO = 4,
    ANDROID_LOG_WARN = 5,
    ANDROID_LOG_ERROR = 6,
};
static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    (void)prio; (void)tag; (void)fmt;
    return 0;
}
#endif
