package com.ok1iak.qmxserver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class UsbForegroundService : Service() {

    private val channelId = "usb_streamer"
    private val notifId = 1

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("USB Streamer running")
            .setContentText("Streaming USB → UDP")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
        startForeground(notifId, notif)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val usbManager = getSystemService(UsbManager::class.java)
        val device = intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        if (device == null) return START_NOT_STICKY

        val connection = usbManager.openDevice(device)
        if (connection == null) return START_NOT_STICKY

        val fd = connection.fileDescriptor  // integer
        val vid = device.vendorId
        val pid = device.productId
        val deviceName = device.deviceName

        // TODO: choose your UDP destination
        NativeBridge.startStreaming(fd, vid, pid, deviceName, "192.168.1.10", 9000)

        // Keep running
        return START_STICKY
    }

    override fun onDestroy() {
        NativeBridge.stopStreaming()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(channelId, "USB Streamer", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }
}