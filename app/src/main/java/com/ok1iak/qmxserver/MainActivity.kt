package com.ok1iak.qmxserver

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ok1iak.qmxserver.databinding.ActivityMainBinding
import java.net.InetAddress
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        const val ACTION_USB_PERMISSION = "com.ok1iak.qmxserver.USB_PERMISSION"
        private const val REQUEST_NOTIFICATION_PERMISSION = 100
        
        // Used to load the 'qmxserver' library on application startup.
        init {
            System.loadLibrary("qmxserver")
        }
    }

    private fun getLocalIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "Not connected"
            
            val allAddresses = interfaces.toList()
                .flatMap { iface ->
                    Log.d("QMXServer", "Interface: ${iface.name}, up=${iface.isUp}")
                    iface.inetAddresses.toList()
                }
            
            Log.d("QMXServer", "Total addresses found: ${allAddresses.size}")
            
            val result = allAddresses
                .firstOrNull { address ->
                    val hostAddr = address.hostAddress ?: ""
                    Log.d("QMXServer", "Address: $hostAddr, loopback=${address.isLoopbackAddress}")
                    !address.isLoopbackAddress && !hostAddr.contains(":")
                }
                ?.hostAddress
            
            Log.d("QMXServer", "Selected IP: $result")
            result ?: "Not connected"
        } catch (e: Exception) {
            Log.e("QMXServer", "IP error", e)
            "Error: ${e.message}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Request notification permission for Android 13+ (needed for foreground service)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }
        
        // Example of a call to a native method
        binding.sampleText.text = stringFromJNI()

        // Display local IP address
        val ipAddress = getLocalIpAddress()
        binding.ipText.text = "IP: $ipAddress"

        val usbManager = getSystemService(UsbManager::class.java)

        // Optional: if launched by USB attach intent, use that device
        val attachedDevice = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        val device = attachedDevice ?: usbManager.deviceList.values.firstOrNull()

        if (device == null) {
            binding.startButton.isEnabled = false
            binding.stopButton.isEnabled = false
            return
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)

        // Register receiver dynamically (keeps manifest simpler for modern Android)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // Android 13+
            registerReceiver(
                UsbPermissionReceiver(),
                IntentFilter(ACTION_USB_PERMISSION),
                Context.RECEIVER_NOT_EXPORTED  // Add this flag
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(
                UsbPermissionReceiver(),
                IntentFilter(ACTION_USB_PERMISSION)
            )
        }

        // Setup button listeners
        binding.startButton.setOnClickListener {
            if (usbManager.hasPermission(device)) {
                val serviceIntent = Intent(this, UsbForegroundService::class.java).apply {
                    putExtra(UsbManager.EXTRA_DEVICE, device)
                }
                // Use startForegroundService() on Android 8+ (API 26+)
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(serviceIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startService(serviceIntent)
                }
                binding.startButton.isEnabled = false
                binding.stopButton.isEnabled = true
            } else {
                usbManager.requestPermission(device, permissionIntent)
            }
        }

        binding.stopButton.setOnClickListener {
            stopService(Intent(this, UsbForegroundService::class.java))
            binding.startButton.isEnabled = true
            binding.stopButton.isEnabled = false
        }

        // Initially enable start button, disable stop
        binding.stopButton.isEnabled = false
    }

        /**
     * A native method that is implemented by the 'qmxserver' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String
}