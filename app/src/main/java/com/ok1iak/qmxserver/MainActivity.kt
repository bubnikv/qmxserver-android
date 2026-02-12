package com.ok1iak.qmxserver

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
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
    private var isActivityVisible = false
    private var pendingUsbDevice: UsbDevice? = null
    private var permissionReceiver: UsbPermissionReceiver? = null
    private var serviceStoppedReceiver: BroadcastReceiver? = null

    companion object {
        const val ACTION_USB_PERMISSION = "com.ok1iak.qmxserver.USB_PERMISSION"
        private const val REQUEST_NOTIFICATION_PERMISSION = 100
        
        // Used to load the 'qmxserver' library on application startup.
        init {
            System.loadLibrary("qmxserver")
        }
    }

    // Returns an IP address of the first active network interface, or "Not connected" string.
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
        //FIXME do a search through the usbManager.deviceList.values, search for QMX/QDX devices.
        val device = attachedDevice ?: usbManager.deviceList.values.firstOrNull()

        if (device == null) {
            binding.startButton.isEnabled = false
            binding.stopButton.isEnabled = false
            return
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)

        // Set up callback for USB permission result
        UsbPermissionReceiver.onPermissionResult = { resultDevice, granted ->
            Log.d("QMXServer", "Permission callback: granted=$granted, visible=$isActivityVisible")
            if (granted && isActivityVisible) {
                // Only start service if activity is still visible
                startUsbService(resultDevice)
            } else if (granted) {
                // Store device for when user returns to the app
                pendingUsbDevice = resultDevice
                binding.startButton.isEnabled = true
                binding.startButton.text = "Start"
            }
        }

        // Register receiver dynamically (keeps manifest simpler for modern Android)
        permissionReceiver = UsbPermissionReceiver().also { receiver ->
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                // Android 13+
                registerReceiver(
                    receiver,
                    IntentFilter(ACTION_USB_PERMISSION),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(
                    receiver,
                    IntentFilter(ACTION_USB_PERMISSION)
                )
            }
        }

        // Register a receiver to learn when the service stops itself (e.g. USB detach)
        serviceStoppedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == UsbForegroundService.ACTION_SERVICE_STOPPED) {
                    binding.startButton.isEnabled = true
                    binding.startButton.text = "Start"
                    binding.stopButton.isEnabled = false
                }
            }
        }.also { receiver ->
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    receiver,
                    IntentFilter(UsbForegroundService.ACTION_SERVICE_STOPPED),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(
                    receiver,
                    IntentFilter(UsbForegroundService.ACTION_SERVICE_STOPPED)
                )
            }
        }

        // Setup button listeners
        binding.startButton.setOnClickListener {
            val deviceToUse = pendingUsbDevice ?: device
            if (usbManager.hasPermission(deviceToUse)) {
                startUsbService(deviceToUse)
            } else {
                usbManager.requestPermission(deviceToUse, permissionIntent)
                binding.startButton.text = "Waiting for permission..."
            }
        }

        binding.stopButton.setOnClickListener {
            stopService(Intent(this, UsbForegroundService::class.java))
            binding.startButton.isEnabled = true
            binding.startButton.text = "Start"
            binding.stopButton.isEnabled = false
        }

        // Initially enable start button, disable stop
        binding.stopButton.isEnabled = false
    }

    override fun onStart() {
        super.onStart()
        isActivityVisible = true
        
        // If permission was granted while app was in background, enable start button
        pendingUsbDevice?.let { device ->
            val usbManager = getSystemService(UsbManager::class.java)
            if (usbManager.hasPermission(device)) {
                binding.startButton.isEnabled = true
                binding.startButton.text = "Start"
            }
        }
    }

    override fun onStop() {
        super.onStop()
        isActivityVisible = false
    }
    
    override fun onDestroy() {
        super.onDestroy()
        permissionReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Receiver was not registered or already unregistered.
            }
        }
        permissionReceiver = null
        serviceStoppedReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Receiver was not registered or already unregistered.
            }
        }
        serviceStoppedReceiver = null
        // Clean up callback to prevent memory leak
        UsbPermissionReceiver.onPermissionResult = null
    }
    
    private fun startUsbService(device: UsbDevice) {
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
        binding.startButton.text = "Start"
        binding.stopButton.isEnabled = true
        pendingUsbDevice = null // Clear pending device after starting
    }
}