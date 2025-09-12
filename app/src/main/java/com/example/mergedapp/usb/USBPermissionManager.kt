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
 * USB Device Type enumeration for different device categories
 */
enum class USBDeviceType {
    UVC_CAMERA,
    RADAR_SENSOR,
    UNKNOWN
}

/**
 * Enhanced USB Permission Manager
 * Handles USB device detection and permission requests for cameras and radar sensors
 * Based on patterns from usb_22 project with radar support added
 */
class USBPermissionManager(
    private val context: Context,
    private val listener: USBPermissionListener
) {
    
    companion object {
        private const val TAG = "USBPermissionManager"
        private const val DEBUG_TAG = "DEBUG_USB_RESTART"
        private const val ACTION_USB_PERMISSION = "com.example.mergedapp.UNIFIED_USB_PERMISSION"
        
        // Radar sensor constants (from radar_demo)
        private const val RADAR_VID = 1419  // OPS7243A (IFX CDC)
        private const val RADAR_PID = 88
    }
    
    private val _usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var isReceiverRegistered = false
    
    /**
     * Determine device type based on VID/PID and device class
     */
    private fun getDeviceType(device: UsbDevice): USBDeviceType {
        return when {
            // Radar sensor detection (exact VID/PID match)
            device.vendorId == RADAR_VID && device.productId == RADAR_PID -> {
                USBDeviceType.RADAR_SENSOR
            }
            // UVC camera detection (device class based)
            device.deviceClass == 239 || device.deviceClass == 14 -> {
                USBDeviceType.UVC_CAMERA
            }
            else -> USBDeviceType.UNKNOWN
        }
    }
    
    /**
     * Check if device should be handled by this manager
     */
    private fun shouldHandleDevice(device: UsbDevice): Boolean {
        val deviceType = getDeviceType(device)
        return deviceType == USBDeviceType.UVC_CAMERA || deviceType == USBDeviceType.RADAR_SENSOR
    }
    
    interface USBPermissionListener {
        fun onPermissionGranted(device: UsbDevice, deviceType: USBDeviceType)
        fun onPermissionDenied(device: UsbDevice, deviceType: USBDeviceType)
        fun onUsbDeviceAttached(device: UsbDevice, deviceType: USBDeviceType)
        fun onUsbDeviceDetached(device: UsbDevice, deviceType: USBDeviceType)
    }
    
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            Log.d(TAG, "USB broadcast received: $action")
            Log.d(DEBUG_TAG, "USBPermissionManager.onReceive: Action=$action, Thread=${Thread.currentThread().name}")
            
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
                            val deviceType = getDeviceType(device)
                            val isGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            Log.d(DEBUG_TAG, "USBPermissionManager.onReceive: Permission result for ${device.deviceName} (Type: $deviceType) - Granted: $isGranted")
                            
                            if (isGranted) {
                                Log.d(TAG, "✅ USB permission granted for device: ${device.deviceName} (Type: $deviceType)")
                                Log.d(DEBUG_TAG, "USBPermissionManager.onReceive: Calling listener.onPermissionGranted for ${device.deviceName}")
                                listener.onPermissionGranted(device, deviceType)
                                Log.d(DEBUG_TAG, "USBPermissionManager.onReceive: listener.onPermissionGranted completed for ${device.deviceName}")
                            } else {
                                Log.w(TAG, "❌ USB permission denied for device: ${device.deviceName} (Type: $deviceType)")
                                Log.d(DEBUG_TAG, "USBPermissionManager.onReceive: Calling listener.onPermissionDenied for ${device.deviceName}")
                                listener.onPermissionDenied(device, deviceType)
                            }
                        } ?: Log.e(DEBUG_TAG, "USBPermissionManager.onReceive: Device is null in permission broadcast")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.d(DEBUG_TAG, "USBPermissionManager.onReceive: USB_DEVICE_ATTACHED received")
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    
                    device?.let {
                        val deviceType = getDeviceType(device)
                        Log.d(TAG, "USB device attached: ${device.deviceName} (Type: $deviceType)")
                        Log.d(DEBUG_TAG, "USBPermissionManager.onReceive: Device attached - ${device.deviceName} (Type: $deviceType, VID=${device.vendorId}, PID=${device.productId})")
                        
                        if (shouldHandleDevice(device)) {
                            Log.d(DEBUG_TAG, "USBPermissionManager.onReceive: Calling listener.onUsbDeviceAttached for ${device.deviceName}")
                            listener.onUsbDeviceAttached(device, deviceType)
                            Log.d(DEBUG_TAG, "USBPermissionManager.onReceive: listener.onUsbDeviceAttached completed for ${device.deviceName}")
                        } else {
                            Log.d(DEBUG_TAG, "USBPermissionManager.onReceive: Device ${device.deviceName} not handled (Type: $deviceType)")
                        }
                    } ?: Log.e(DEBUG_TAG, "USBPermissionManager.onReceive: Device is null in attach broadcast")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.d(DEBUG_TAG, "USBPermissionManager.onReceive: USB_DEVICE_DETACHED received")
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    
                    device?.let {
                        val deviceType = getDeviceType(device)
                        Log.d(TAG, "USB device detached: ${device.deviceName} (Type: $deviceType)")
                        Log.d(DEBUG_TAG, "USBPermissionManager.onReceive: Device detached - ${device.deviceName} (Type: $deviceType)")
                        
                        if (shouldHandleDevice(device)) {
                            Log.d(DEBUG_TAG, "USBPermissionManager.onReceive: Calling listener.onUsbDeviceDetached for ${device.deviceName}")
                            listener.onUsbDeviceDetached(device, deviceType)
                        } else {
                            Log.d(DEBUG_TAG, "USBPermissionManager.onReceive: Detached device ${device.deviceName} not handled (Type: $deviceType)")
                        }
                    } ?: Log.e(DEBUG_TAG, "USBPermissionManager.onReceive: Device is null in detach broadcast")
                }
            }
        }
    }
    
    /**
     * Register USB receiver to listen for permission and device events
     */
    fun register() {
        Log.d(DEBUG_TAG, "USBPermissionManager.register: Starting registration, isReceiverRegistered=$isReceiverRegistered")
        
        if (isReceiverRegistered) {
            Log.w(TAG, "USB receiver already registered")
            Log.d(DEBUG_TAG, "USBPermissionManager.register: Already registered, returning")
            return
        }
        
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        
        Log.d(DEBUG_TAG, "USBPermissionManager.register: Registering receiver with filter actions: ${filter.actionsIterator().asSequence().toList()}")
        
        ContextCompat.registerReceiver(
            context, 
            usbReceiver, 
            filter, 
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        
        isReceiverRegistered = true
        Log.d(TAG, "USB receiver registered")
        Log.d(DEBUG_TAG, "USBPermissionManager.register: Registration completed successfully")
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
        return _usbManager.deviceList.values.toList()
    }
    
    /**
     * Get all connected UVC camera devices
     */
    fun getConnectedCameras(): List<UsbDevice> {
        return getConnectedDevices().filter { getDeviceType(it) == USBDeviceType.UVC_CAMERA }
    }
    
    /**
     * Get all connected radar sensor devices
     */
    fun getConnectedRadarSensors(): List<UsbDevice> {
        return getConnectedDevices().filter { getDeviceType(it) == USBDeviceType.RADAR_SENSOR }
    }
    
    /**
     * Get all connected devices of a specific type
     */
    fun getConnectedDevices(deviceType: USBDeviceType): List<UsbDevice> {
        return getConnectedDevices().filter { getDeviceType(it) == deviceType }
    }
    
    /**
     * Check if device has USB permission
     */
    fun hasPermission(device: UsbDevice): Boolean {
        return _usbManager.hasPermission(device)
    }
    
    /**
     * Request permission for USB device
     */
    fun requestPermission(device: UsbDevice) {
        Log.d(DEBUG_TAG, "USBPermissionManager.requestPermission: Starting for device ${device.deviceName} (VID=${device.vendorId}, PID=${device.productId})")
        
        val alreadyHasPermission = hasPermission(device)
        Log.d(DEBUG_TAG, "USBPermissionManager.requestPermission: hasPermission check result: $alreadyHasPermission")
        
        if (alreadyHasPermission) {
            val deviceType = getDeviceType(device)
            Log.d(TAG, "Permission already granted for: ${device.deviceName} (Type: $deviceType)")
            Log.d(DEBUG_TAG, "USBPermissionManager.requestPermission: Permission already granted, calling listener.onPermissionGranted")
            listener.onPermissionGranted(device, deviceType)
            Log.d(DEBUG_TAG, "USBPermissionManager.requestPermission: listener.onPermissionGranted completed for existing permission")
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
        Log.d(DEBUG_TAG, "USBPermissionManager.requestPermission: Calling _usbManager.requestPermission with intent action: ${ACTION_USB_PERMISSION}")
        _usbManager.requestPermission(device, permissionIntent)
        Log.d(DEBUG_TAG, "USBPermissionManager.requestPermission: _usbManager.requestPermission call completed")
    }
    
    /**
     * Check and request permissions for all connected supported devices (cameras and radar)
     */
    fun checkAndRequestPermissions() {
        Log.d(DEBUG_TAG, "USBPermissionManager.checkAndRequestPermissions: Starting permission check")
        
        val allConnectedDevices = getConnectedDevices()
        Log.d(DEBUG_TAG, "USBPermissionManager.checkAndRequestPermissions: Found ${allConnectedDevices.size} total connected devices")
        
        val allSupportedDevices = allConnectedDevices.filter { shouldHandleDevice(it) }
        Log.d(TAG, "Checking permissions for ${allSupportedDevices.size} supported devices")
        Log.d(DEBUG_TAG, "USBPermissionManager.checkAndRequestPermissions: Supported devices: ${allSupportedDevices.map { "${it.deviceName} (Type: ${getDeviceType(it)})" }}")
        
        for (device in allSupportedDevices) {
            val deviceType = getDeviceType(device)
            val hasCurrentPermission = hasPermission(device)
            Log.d(DEBUG_TAG, "USBPermissionManager.checkAndRequestPermissions: Device ${device.deviceName} - hasPermission: $hasCurrentPermission")
            
            if (!hasCurrentPermission) {
                Log.d(DEBUG_TAG, "USBPermissionManager.checkAndRequestPermissions: Requesting permission for ${device.deviceName}")
                requestPermission(device)
                Log.d(DEBUG_TAG, "USBPermissionManager.checkAndRequestPermissions: Permission request initiated, returning (one at a time)")
                return // Request one at a time
            } else {
                Log.d(TAG, "✅ Permission already granted for: ${device.productName ?: device.deviceName} (Type: $deviceType)")
                Log.d(DEBUG_TAG, "USBPermissionManager.checkAndRequestPermissions: Calling listener.onPermissionGranted for existing permission ${device.deviceName}")
                listener.onPermissionGranted(device, deviceType)
            }
        }
        
        Log.d(DEBUG_TAG, "USBPermissionManager.checkAndRequestPermissions: Permission check completed for all devices")
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
