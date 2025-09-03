package com.example.mergedapp.test

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.mergedapp.camera.*
import com.example.mergedapp.detection.DetectionBasedRecorder
import com.example.mergedapp.detection.DetectionStats
import com.example.mergedapp.usb.USBPermissionManager
import java.text.SimpleDateFormat
import java.util.*



class DetectionBasedRecordingTestActivity : AppCompatActivity(), 
    USBPermissionManager.USBPermissionListener,
    DetectionBasedRecorder.RecordingStateListener {
    
    companion object {
        private const val TAG = "DetectionRecordingTest"
        private const val PERMISSION_REQUEST_CODE = 1002
        private const val STATS_UPDATE_INTERVAL = 1000L // Update stats every second
        
        // Helper function for consistent logging format
        private fun logFormat(functionName: String, message: String): String {
            return "DetectionBasedRecordingTestActivity.$functionName: $message"
        }
    }
    
    // UI components
    private var rootView: ScrollView? = null
    private var statusContainer: LinearLayout? = null
    private var statusText: TextView? = null
    private var statsText: TextView? = null
    private var logText: TextView? = null
    
    // USB and camera management
    private lateinit var usbPermissionManager: USBPermissionManager
    private var currentUsbDevice: UsbDevice? = null
    
    // Detection-based recording
    private var detectionRecorder: DetectionBasedRecorder? = null
    private var statsUpdateHandler: Handler? = null
    private var statsUpdateRunnable: Runnable? = null
    
    // State tracking
    private var isSystemReady = false
    private var isInternalCameraInitialized = false
    private val logMessages = mutableListOf<String>()
    private val maxLogMessages = 20
    
    // FPS tracking
    private var lastUsbFrameCount = 0L
    private var lastInternalFrameCount = 0L
    private var lastFpsUpdateTime = 0L
    private var usbFps = 0.0
    private var internalFps = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, logFormat("onCreate", "DetectionBasedRecordingTestActivity started"))
        
        // Initialize USB permission manager
        usbPermissionManager = USBPermissionManager(this, this)
        
        // Create UI
        createUI()
        
        // Request permissions
        requestNecessaryPermissions()
        
        // Initialize USB monitoring with delay
        Handler(Looper.getMainLooper()).postDelayed({
            initializeUSBMonitoring()
        }, 2000)
        
        // Initialize internal camera with delay (after permissions)
        Handler(Looper.getMainLooper()).postDelayed({
            initializeInternalCamera()
        }, 3000)
        
        // Start stats updates
        startStatsUpdates()
    }
    
    private fun createUI() {
        // Create scrollable container
        rootView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF1A1A1A.toInt())
            setPadding(20, 20, 20, 20)
        }
        
        // Main container
        statusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Title
        val titleText = TextView(this).apply {
            text = "🎯 Detection-Based Recording Test\n📷 USB + Internal Cameras"
            textSize = 20f
            setTextColor(0xFF4CAF50.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Status section
        statusText = TextView(this).apply {
            text = "🔌 Initializing USB camera system...\n⚠️ Connect USB camera and grant permissions"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2D2D2D.toInt())
            setPadding(20, 20, 20, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 20
            }
        }
        
        // Stats section
        statsText = TextView(this).apply {
            text = "📊 Detection Statistics:\n\n🔌 USB Camera:\nFrames: 0 | FPS: 0.0 | Non-Detections: 0 | Status: ⚪ WAITING\n\n📱 Internal Camera:\nFrames: 0 | FPS: 0.0 | Non-Detections: 0 | Status: ⚪ WAITING"
            textSize = 14f
            setTextColor(0xFF81C784.toInt())
            setBackgroundColor(0xFF1B5E20.toInt())
            setPadding(20, 20, 20, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 20
            }
        }
        
        // Log section header
        val logHeader = TextView(this).apply {
            text = "📝 Recording Events Log:"
            textSize = 16f
            setTextColor(0xFF90CAF9.toInt())
            setPadding(0, 0, 0, 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Log section
        logText = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(0xFFE0E0E0.toInt())
            setBackgroundColor(0xFF263238.toInt())
            setPadding(15, 15, 15, 15)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Add views to container
        statusContainer?.addView(titleText)
        statusContainer?.addView(statusText)
        statusContainer?.addView(statsText)
        statusContainer?.addView(logHeader)
        statusContainer?.addView(logText)
        
        rootView?.addView(statusContainer)
        setContentView(rootView)
        
        Log.d(TAG, logFormat("createUI", "UI created successfully"))
    }
    
    private fun requestNecessaryPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, logFormat("requestNecessaryPermissions", "📋 Requesting permissions: $permissionsToRequest"))
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            Log.d(TAG, logFormat("requestNecessaryPermissions", "✅ All necessary permissions already granted"))
        }
    }
    
    private fun initializeUSBMonitoring() {
        Log.d(TAG, logFormat("initializeUSBMonitoring", "Initializing USB monitoring"))
        usbPermissionManager.register()
        usbPermissionManager.checkAndRequestPermissions()
    }
    
    private fun initializeInternalCamera() {
        Log.d(TAG, logFormat("initializeInternalCamera", "Initializing internal camera"))
        
        // Check if we have camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, logFormat("initializeInternalCamera", "Camera permission not granted yet"))
            return
        }
        
        // Check if detection recorder is initialized
        if (detectionRecorder == null) {
            Log.d(TAG, logFormat("initializeInternalCamera", "Detection recorder not ready, creating new instance"))
            detectionRecorder = DetectionBasedRecorder(
                context = this,
                activityContext = this
            )
            detectionRecorder?.setRecordingStateListener(this)
            detectionRecorder?.initialize()
        }
        
        try {
            updateStatus("🔄 Initializing Internal Camera...")
            addLogMessage("Initializing internal camera for detection...")
            
            // Initialize internal camera through DetectionBasedRecorder
            detectionRecorder?.initializeInternalCamera()
            
            isInternalCameraInitialized = true
            
            Log.d(TAG, logFormat("initializeInternalCamera", "Internal camera initialization started"))
            
        } catch (e: Exception) {
            Log.e(TAG, logFormat("initializeInternalCamera", "Failed to initialize internal camera: ${e.message}"), e)
            addLogMessage("ERROR: Failed to initialize internal camera - ${e.message}")
        }
    }
    
    private fun startStatsUpdates() {
        statsUpdateHandler = Handler(Looper.getMainLooper())
        statsUpdateRunnable = object : Runnable {
            override fun run() {
                updateStatsDisplay()
                statsUpdateHandler?.postDelayed(this, STATS_UPDATE_INTERVAL)
            }
        }   
        statsUpdateHandler?.postDelayed(statsUpdateRunnable!!, STATS_UPDATE_INTERVAL)
    }
    
    private fun updateStatsDisplay() {
        if (!isSystemReady && !isInternalCameraInitialized) return
        
        val stats = detectionRecorder?.getDetectionStats()
        if (stats != null) {
            // Calculate FPS
            calculateFPS(stats)
            
            runOnUiThread {
                statsText?.text = buildString {
                    append("📊 Detection Statistics:\n\n")
                    
                    // USB Camera stats
                    append("🔌 USB Camera:\n")
                    append("Frames: ${stats.usbFramesProcessed} | ")
                    append("FPS: ${String.format("%.1f", usbFps)} | ")
                    append("Non-Detections: ${stats.usbConsecutiveNonDetections} | ")
                    append("Status: ${if (stats.isUSBRecording) "🔴 RECORDING" else "⚪ WAITING"}\n\n")
                    
                    // Internal Camera stats
                    append("📱 Internal Camera:\n")
                    append("Frames: ${stats.internalFramesProcessed} | ")
                    append("FPS: ${String.format("%.1f", internalFps)} | ")
                    append("Non-Detections: ${stats.internalConsecutiveNonDetections} | ")
                    append("Status: ${if (stats.isInternalRecording) "🔴 RECORDING" else "⚪ WAITING"}")
                }
            }
        }
    }
    
    private fun calculateFPS(stats: DetectionStats) {
        val currentTime = System.currentTimeMillis()
        
        // Initialize on first run
        if (lastFpsUpdateTime == 0L) {
            lastFpsUpdateTime = currentTime
            lastUsbFrameCount = stats.usbFramesProcessed
            lastInternalFrameCount = stats.internalFramesProcessed
            return
        }
        
        val timeDelta = currentTime - lastFpsUpdateTime
        
        // Only calculate FPS if at least 1 second has passed
        if (timeDelta >= 1000) {
            val usbFrameDelta = stats.usbFramesProcessed - lastUsbFrameCount
            val internalFrameDelta = stats.internalFramesProcessed - lastInternalFrameCount
            
            // Calculate FPS (frames per second)
            usbFps = (usbFrameDelta * 1000.0) / timeDelta
            internalFps = (internalFrameDelta * 1000.0) / timeDelta
            
            // Update last values for next calculation
            lastFpsUpdateTime = currentTime
            lastUsbFrameCount = stats.usbFramesProcessed
            lastInternalFrameCount = stats.internalFramesProcessed
        }
    }
    
    private fun updateStatus(message: String) {
        runOnUiThread {
            statusText?.text = message
        }
    }
    
    private fun addLogMessage(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] $message"
        
        runOnUiThread {
            logMessages.add(0, logEntry) // Add to beginning
            
            // Keep only recent messages
            if (logMessages.size > maxLogMessages) {
                logMessages.removeAt(logMessages.size - 1)
            }
            
            logText?.text = logMessages.joinToString("\n")
        }
        
        Log.d(TAG, logFormat("addLogMessage", logEntry))
    }
    
    // USBPermissionManager.USBPermissionListener implementation
    override fun onPermissionGranted(device: UsbDevice) {
        Log.d(TAG, logFormat("onPermissionGranted", "✅ USB permission granted for: ${device.deviceName}"))
        updateStatus("✅ USB Permission Granted\n🔄 Initializing detection system...")
        addLogMessage("USB permission granted for ${device.productName ?: device.deviceName}")
        
        currentUsbDevice = device
        usbPermissionManager.logDeviceInfo(device)
        
        initializeDetectionSystem(device)
    }
    
    override fun onPermissionDenied(device: UsbDevice) {
        Log.w(TAG, logFormat("onPermissionDenied", "❌ USB permission denied for: ${device.deviceName}"))
        updateStatus("❌ USB Permission Denied\nDetection system cannot start")
        addLogMessage("USB permission denied - cannot proceed")
        Toast.makeText(this, "USB permission denied - detection won't work", Toast.LENGTH_LONG).show()
    }
    
    override fun onUsbDeviceAttached(device: UsbDevice) {
        Log.d(TAG, logFormat("onUsbDeviceAttached", "🔌 USB camera attached: ${device.deviceName}"))
        addLogMessage("USB camera attached: ${device.productName ?: device.deviceName}")
        usbPermissionManager.requestPermission(device)
    }
    
    override fun onUsbDeviceDetached(device: UsbDevice) {
        Log.d(TAG, logFormat("onUsbDeviceDetached", "🔌 USB camera detached: ${device.deviceName}"))
        addLogMessage("USB camera detached")
        
        if (device == currentUsbDevice) {
            updateStatus("📷 USB Camera Disconnected\nDetection system stopped")
            shutdownDetectionSystem()
            currentUsbDevice = null
        }
    }

    private fun initializeDetectionSystem(device: UsbDevice) {
        try {
            updateStatus("🔄 Initializing Detection System\n${device.productName ?: device.deviceName}")
            addLogMessage("Initializing detection system...")
            
            // Create detection-based recorder with activity context for USB management
            detectionRecorder = DetectionBasedRecorder(
                context = this,
                activityContext = this
            )
            
            // Set recording state listener to get detection events
            detectionRecorder?.setRecordingStateListener(this)
            
            // Initialize detection system
            detectionRecorder?.initialize()
            
            // Initialize USB camera through DetectionBasedRecorder
            detectionRecorder?.initializeUSBCamera(device)
            
            Log.d(TAG, logFormat("initializeDetectionSystem", "Detection system initialization started"))
            
        } catch (e: Exception) {
            Log.e(TAG, logFormat("initializeDetectionSystem", "Failed to initialize detection system: ${e.message}"), e)
            updateStatus("❌ Detection System Failed\n${e.message}")
            addLogMessage("ERROR: Failed to initialize detection system - ${e.message}")
        }
    }
    
    private fun shutdownDetectionSystem() {
        isSystemReady = false
        isInternalCameraInitialized = false
        
        // Reset FPS tracking
        lastUsbFrameCount = 0L
        lastInternalFrameCount = 0L
        lastFpsUpdateTime = 0L
        usbFps = 0.0
        internalFps = 0.0
        
        try {
            detectionRecorder?.stopMonitoring()
            detectionRecorder?.shutdown()
            
            detectionRecorder = null
            
            addLogMessage("Detection system shut down (both cameras)")
            
        } catch (e: Exception) {
            Log.e(TAG, logFormat("shutdownDetectionSystem", "Error shutting down detection system: ${e.message}"), e)
        }
    }
    
    private fun onUSBCameraReady() {
        Log.i(TAG, logFormat("onUSBCameraReady", "USB Camera opened successfully via DetectionBasedRecorder"))
        runOnUiThread {
            val internalStatus = if (isInternalCameraInitialized) "\n📱 Internal Camera also active" else ""
            updateStatus("✅ USB Camera Ready$internalStatus\n🎯 Detection monitoring active...")
            addLogMessage("USB camera opened successfully")
            
            isSystemReady = true
            addLogMessage("USB camera active, looking for target object")
        }
    }
    
    private fun onInternalCameraReady() {
        Log.i(TAG, logFormat("onInternalCameraReady", "Internal Camera opened successfully via DetectionBasedRecorder"))
        runOnUiThread {
            val usbStatus = if (isSystemReady) "\n🔌 USB Camera also active" else ""
            updateStatus("✅ Internal Camera Ready$usbStatus\n🎯 Detection monitoring active...")
            addLogMessage("Internal camera opened successfully")
            
            isInternalCameraInitialized = true
            addLogMessage("Internal camera active, looking for target object")
        }
    }
    
    // DetectionBasedRecorder.RecordingStateListener implementation
    override fun onRecordingStarted(cameraType: CameraType, outputPath: String) {
        val cameraName = when (cameraType) {
            CameraType.USB -> "🔌 USB"
            CameraType.INTERNAL -> "📱 Internal"
        }
        
        addLogMessage("🔴 $cameraName: Object detected, recording started")
        updateStatus("🔴 RECORDING ACTIVE ($cameraName)\nTarget object detected!\nSaving to: ${outputPath.substringAfterLast("/")}")
    }
    
    override fun onRecordingStopped(cameraType: CameraType, outputPath: String) {
        val cameraName = when (cameraType) {
            CameraType.USB -> "🔌 USB"
            CameraType.INTERNAL -> "📱 Internal"
        }
        
        addLogMessage("⚪ $cameraName: No object detected, recording saved")
        updateStatus("✅ Recording Saved ($cameraName)\n📁 ${outputPath.substringAfterLast("/")}\n🎯 Looking for target object...")
        
        // Show brief success message
        Handler(Looper.getMainLooper()).postDelayed({
            if (isSystemReady || isInternalCameraInitialized) {
                val activeCameras = buildString {
                    if (isSystemReady) append("🔌 USB")
                    if (isSystemReady && isInternalCameraInitialized) append(" + ")
                    if (isInternalCameraInitialized) append("📱 Internal")
                }
                updateStatus("🎯 Cameras Active ($activeCameras)\nLooking for target object...")
                addLogMessage("Cameras active, looking for target object")
            }
        }, 3000)
    }
    
    override fun onRecordingError(cameraType: CameraType, error: String) {
        val cameraName = when (cameraType) {
            CameraType.USB -> "🔌 USB"
            CameraType.INTERNAL -> "📱 Internal"
        }
        
        addLogMessage("❌ $cameraName: Recording error - $error")
        updateStatus("❌ Recording Error ($cameraName)\n$error\n🎯 Continuing detection...")
        
        // If this is a camera initialization error, show it prominently
        if (error.contains("initialize") || error.contains("Camera error")) {
            Toast.makeText(this, "$cameraName camera error: $error", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onDetectionStateChanged(cameraType: CameraType, hasDetection: Boolean, objectCount: Int) {
        // Check if this is the first detection state change - indicates camera is processing frames
        if (cameraType == CameraType.USB && !isSystemReady) {
            onUSBCameraReady()
        } else if (cameraType == CameraType.INTERNAL && !isInternalCameraInitialized) {
            onInternalCameraReady()
        }
        
        val cameraName = when (cameraType) {
            CameraType.USB -> "🔌 USB"
            CameraType.INTERNAL -> "📱 Internal"
        }
        
        if (hasDetection) {
            addLogMessage("👁️ $cameraName: Target object detected (count: $objectCount)")
        } else {
            // Don't log every non-detection to avoid spam
            val stats = detectionRecorder?.getDetectionStats()
            if (stats != null) {
                val consecutiveNonDetections = when (cameraType) {
                    CameraType.USB -> stats.usbConsecutiveNonDetections
                    CameraType.INTERNAL -> stats.internalConsecutiveNonDetections
                }
                
                if (consecutiveNonDetections > 0 && consecutiveNonDetections % 5 == 0) {
                    addLogMessage("⏳ $cameraName: Still looking... ($consecutiveNonDetections frames without detection)")
                }
            }
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                val denied = mutableListOf<String>()
                
                for (i in permissions.indices) {
                    if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                        denied.add(permissions[i])
                    }
                }
                
                if (denied.isNotEmpty()) {
                    Log.w(TAG, logFormat("onRequestPermissionsResult", "Permissions denied: $denied"))
                    addLogMessage("Some permissions denied - functionality may be limited")
                    Toast.makeText(this, "Some permissions denied. Functionality may be limited.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    override fun onDestroy() {
        Log.d(TAG, logFormat("onDestroy", "Activity destroying"))
        
        // Stop stats updates
        statsUpdateHandler?.removeCallbacksAndMessages(null)
        
        // Shutdown detection system
        shutdownDetectionSystem()
        
        // Unregister USB receiver
        usbPermissionManager.unregister()
        
        super.onDestroy()
    }
}
