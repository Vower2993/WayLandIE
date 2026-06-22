// JNI bindings for the WayLandIE minimal X server.
//
// Java calls these via XServerController.java:
//   nativeStart(width, height, display, bridgeSocket)
//   nativeStop()
//   nativeSendMouse(x, y, button, isDown)
//   nativeSendKey(keycode, isDown)
#include "x11_protocol.h"
#include "xserver_state.h"

#include <jni.h>
#include <android/log.h>
#include <string>

#define TAG "WayLandIE/XServerJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Defined in xserver_main.cpp
extern "C" {
int waylandie_xserver_start(int width, int height,
                              const char* display,
                              const char* bridge_socket);
void waylandie_xserver_stop();
void waylandie_xserver_send_mouse(int x, int y, int button, bool is_down);
void waylandie_xserver_send_key(int keycode, bool is_down);
}

extern "C" {

JNIEXPORT jint JNICALL
Java_io_waylandie_display_runtime_environment_XServerController_nativeStart(
        JNIEnv* env, jclass cls,
        jint width, jint height,
        jstring display_j, jstring bridge_socket_j) {
    (void)cls;
    const char* display = env->GetStringUTFChars(display_j, nullptr);
    const char* bridge_socket = bridge_socket_j
        ? env->GetStringUTFChars(bridge_socket_j, nullptr) : nullptr;

    int rc = waylandie_xserver_start(width, height, display, bridge_socket);

    env->ReleaseStringUTFChars(display_j, display);
    if (bridge_socket) env->ReleaseStringUTFChars(bridge_socket_j, bridge_socket);
    return rc;
}

JNIEXPORT void JNICALL
Java_io_waylandie_display_runtime_environment_XServerController_nativeStop(
        JNIEnv* env, jclass cls) {
    (void)env; (void)cls;
    waylandie_xserver_stop();
}

JNIEXPORT void JNICALL
Java_io_waylandie_display_runtime_environment_XServerController_nativeSendMouse(
        JNIEnv* env, jclass cls,
        jint x, jint y, jint button, jboolean is_down) {
    (void)env; (void)cls;
    waylandie_xserver_send_mouse(x, y, button, is_down == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_io_waylandie_display_runtime_environment_XServerController_nativeSendKey(
        JNIEnv* env, jclass cls,
        jint keycode, jboolean is_down) {
    (void)env; (void)cls;
    waylandie_xserver_send_key(keycode, is_down == JNI_TRUE);
}

}  // extern "C"
