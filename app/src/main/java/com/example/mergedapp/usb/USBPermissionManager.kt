package com.example.mergedapp.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * USB Permission Manager
 * Handles USB device detection and permission requests
 * Based on patterns from usb_22 project
 */
class USBPermissionManager(
    private val context: Context,
    private val listener: USBPermissionListener
) {
    
    companion object {
        private const val TAG = "USBPermissionManager"
        private const val ACTION_USB_PERMISSION = "com.example.mergedapp.USB_PERMISSION"
    }
    
    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var isReceiverRegistered = false
    
    interface USBPermissionListener {
        fun onPermissionGranted(device: UsbDevice)
        fun onPermissionDenied(device: UsbDevice)
        fun onUsbDeviceAttached(device: UsbDevice)
        fun onUsbDeviceDetached(device: UsbDevice)
    }
    
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            Log.d(TAG, "USB broadcast received: $action")
            
            when (action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        
                        device?.let {
                            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                                Log.d(TAG, "✅ USB permission granted for device: ${device.deviceName}")
                                listener.onPermissionGranted(device)
                            } else {
                                Log.w(TAG, "❌ USB permission denied for device: ${device.deviceName}")
                                listener.onPermissionDenied(device)
                            }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    
                    device?.let {
                        Log.d(TAG, "USB device attached: ${device.deviceName}")
                        if (isUVCCamera(device)) {
                            listener.onUsbDeviceAttached(device)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    
                    device?.let {
                        Log.d(TAG, "USB device detached: ${device.deviceName}")
                        if (isUVCCamera(device)) {
                            listener.onUsbDeviceDetached(device)
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Register USB receiver to listen for permission and device events
     */
    fun register() {
        if (isReceiverRegistered) {
            Log.w(TAG, "USB receiver already registered")
            return
        }
        
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        
        ContextCompat.registerReceiver(
            context, 
            usbReceiver, 
            filter, 
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        
        isReceiverRegistered = true
        Log.d(TAG, "USB receiver registered")
    }
    
    /**
     * Unregister USB receiver
     */
    fun unregister() {
        if (!isReceiverRegistered) {
            return
        }
        
        try {
            context.unregisterReceiver(usbReceiver)
            isReceiverRegistered = false
            Log.d(TAG, "USB receiver unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering USB receiver: ${e.message}")
        }
    }
    
    /**
     * Get all connected USB devices
     */
    fun getConnectedDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.toList()
    }
    
    /**
     * Get all connected UVC camera devices
     */
    fun getConnectedCameras(): List<UsbDevice> {
        return getConnectedDevices().filter { isUVCCamera(it) }
    }
    
    /**
     * Check if device has USB permission
     */
    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }
    
    /**
     * Request permission for USB device
     */
    fun requestPermission(device: UsbDevice) {
        if (hasPermission(device)) {
            Log.d(TAG, "Permission already granted for: ${device.deviceName}")
            listener.onPermissionGranted(device)
            return
        }
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val permissionIntent = PendingIntent.getBroadcast(
            context, 
            0, 
            Intent(ACTION_USB_PERMISSION), 
            flags
        )
        
        Log.d(TAG, "🔑 Requesting USB permission for: ${device.productName ?: device.deviceName}")
        usbManager.requestPermission(device, permissionIntent)
    }
    
    /**
     * Check and request permissions for all connected UVC cameras
     */
    fun checkAndRequestPermissions() {
        val cameras = getConnectedCameras()
        Log.d(TAG, "Checking permissions for ${cameras.size} UVC cameras")
        
        for (device in cameras) {
            if (!hasPermission(device)) {
                requestPermission(device)
                return // Request one at a time
            } else {
                Log.d(TAG, "✅ Permission already granted for: ${device.productName ?: device.deviceName}")
                listener.onPermissionGranted(device)
            }
        }
    }
    
    /**
     * Check if device is a UVC camera
     * Based on device class (239 or 14 are common for UVC cameras)
     */
    private fun isUVCCamera(device: UsbDevice): Boolean {
        return device.deviceClass == 239 || device.deviceClass == 14
    }
    
    /**
     * Log detailed device information for debugging
     */
    fun logDeviceInfo(device: UsbDevice) {
        Log.d(TAG, "=== USB DEVICE INFO ===")
        Log.d(TAG, "Device Name: ${device.deviceName}")
        Log.d(TAG, "Device ID: ${device.deviceId}")
        Log.d(TAG, "Vendor ID: ${device.vendorId}")
        Log.d(TAG, "Product ID: ${device.productId}")
        Log.d(TAG, "Device Class: ${device.deviceClass}")
        Log.d(TAG, "Device Subclass: ${device.deviceSubclass}")
        Log.d(TAG, "Device Protocol: ${device.deviceProtocol}")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Log.d(TAG, "Product Name: ${device.productName}")
            Log.d(TAG, "Manufacturer Name: ${device.manufacturerName}")
        }
        
        Log.d(TAG, "Interface Count: ${device.interfaceCount}")
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            Log.d(TAG, "  Interface $i - Class: ${usbInterface.interfaceClass}, " +
                    "Subclass: ${usbInterface.interfaceSubclass}, " +
                    "Protocol: ${usbInterface.interfaceProtocol}")
        }
        Log.d(TAG, "========================")
    }
}
