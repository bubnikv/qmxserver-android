package com.ok1iak.qmxserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import android.os.Build

class UsbPermissionReceiver : BroadcastReceiver() {
    companion object {
        var onPermissionResult: ((UsbDevice, Boolean) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MainActivity.ACTION_USB_PERMISSION) return

        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

        if (device != null) {
            onPermissionResult?.invoke(device, granted)
        }
    }
}
