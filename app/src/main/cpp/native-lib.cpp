#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <thread>
#include <string>

#include <libusb.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "QMXServer", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "QMXServer", __VA_ARGS__)

static std::atomic<bool> g_run{false};
static std::thread g_thread;

int main_loop(int fd, const std::string &device_path);

static void worker(int usbFd, int vid, int pid, std::string deviceName, std::string host, int port) {
    LOGI("worker start: fd=%d vid=%04x pid=%04x device=%s udp=%s:%d", usbFd, vid, pid, deviceName.c_str(), host.c_str(), port);

    // TODO:
    // 1) Initialize libusb (compiled for Android) OR do I/O via Java and pass buffers into native.
    // 2) Read from USB and send to UDP.

    main_loop(usbFd, deviceName);

    LOGI("worker stop");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ok1iak_qmxserver_NativeBridge_startStreaming(
        JNIEnv* env, jobject /*thiz*/,
        jint usbFd, jint vid, jint pid,
        jstring deviceName, jstring udpHost, jint udpPort) {

    if (g_run.exchange(true)) {
        LOGE("Already running");
        return -1;
    }

    const char* deviceNameC = env->GetStringUTFChars(deviceName, nullptr);
    std::string deviceNameStr(deviceNameC ? deviceNameC : "");
    env->ReleaseStringUTFChars(deviceName, deviceNameC);

    const char* hostC = env->GetStringUTFChars(udpHost, nullptr);
    std::string host(hostC ? hostC : "");
    env->ReleaseStringUTFChars(udpHost, hostC);

    g_thread = std::thread(worker, (int)usbFd, (int)vid, (int)pid, deviceNameStr, host, (int)udpPort);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ok1iak_qmxserver_NativeBridge_stopStreaming(
        JNIEnv*, jobject /*thiz*/) {

    if (!g_run.exchange(false)) return;
    if (g_thread.joinable()) g_thread.join();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ok1iak_qmxserver_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    libusb_context *context = 0;
    int rc = libusb_init(&context);
    return env->NewStringUTF(hello.c_str());
}
