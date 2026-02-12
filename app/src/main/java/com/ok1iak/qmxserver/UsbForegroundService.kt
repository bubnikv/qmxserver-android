package com.ok1iak.qmxserver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class UsbForegroundService : Service() {

    companion object {
        const val ACTION_SERVICE_STOPPED = "com.ok1iak.qmxserver.SERVICE_STOPPED"
    }

    private val channelId = "usb_streamer"
    private val notifId = 1
    private var connection: UsbDeviceConnection? = null
    private var currentDevice: UsbDevice? = null
    private var detachReceiverRegistered = false

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val detachedDevice = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (detachedDevice != null && detachedDevice == currentDevice) {
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("QMX server running")
            .setContentText("SDR over UDP")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
        startForeground(notifId, notif)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val usbManager = getSystemService(UsbManager::class.java)
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        if (device == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (connection != null) {
            return START_NOT_STICKY
        }

        currentDevice = device

        if (!detachReceiverRegistered) {
            val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(detachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(detachReceiver, filter)
            }
            detachReceiverRegistered = true
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        this.connection = connection

        val fd = connection.fileDescriptor  // integer
        val vid = device.vendorId
        val pid = device.productId
        val deviceName = device.deviceName

        // TODO: choose your UDP destination
        val rc = NativeBridge.startStreaming(fd, vid, pid, deviceName, "notused", 9000)
        if (rc < 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Keep running
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        NativeBridge.stopStreaming()
        connection?.close()
        connection = null
        currentDevice = null
        if (detachReceiverRegistered) {
            try {
                unregisterReceiver(detachReceiver)
            } catch (_: IllegalArgumentException) {
                // Receiver was not registered or already unregistered.
            }
            detachReceiverRegistered = false
        }
        // Notify the Activity so it can update its UI
        sendBroadcast(Intent(ACTION_SERVICE_STOPPED).setPackage(packageName))
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        
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