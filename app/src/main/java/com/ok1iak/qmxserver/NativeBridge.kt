package com.ok1iak.qmxserver

object NativeBridge {
    init {
//        System.loadLibrary("native-lib")
    }

    external fun startStreaming(
        usbFd: Int,
        vid: Int,
        pid: Int,
        deviceName: String,
        udpHost: String,
        udpPort: Int
    ): Int

    external fun stopStreaming()
}
