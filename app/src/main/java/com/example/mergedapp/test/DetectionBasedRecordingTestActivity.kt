package com.example.mergedapp.test

import android.Manifest
import android.content.Intent
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
import com.example.mergedapp.config.AppConfigManager
import com.example.mergedapp.detection.DetectionBasedRecorder
import com.example.mergedapp.detection.DetectionStats
import com.example.mergedapp.usb.USBPermissionManager
import com.example.mergedapp.usb.USBDeviceType
import com.example.mergedapp.radar.*
import com.example.mergedapp.utils.FilePermissionManager
import java.text.SimpleDateFormat
import java.util.*



class DetectionBasedRecordingTestActivity : AppCompatActivity(), 
    USBPermissionManager.USBPermissionListener,
    DetectionBasedRecorder.RecordingStateListener,
    RadarDataListener, // TODO: See if we should have just one listening contract from radar side e.g. RadarListener
    RadarStateListener {
    
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
    private var radarDataButton: Button? = null
    
    // Configuration manager
    private lateinit var appConfig: AppConfigManager
    
    // USB and camera management
    private var usbPermissionManager: USBPermissionManager? = null
    private var currentUsbDevice: UsbDevice? = null
    
    // Detection-based recording
    private var detectionRecorder: DetectionBasedRecorder? = null
    private var statsUpdateHandler: Handler? = null
    private var statsUpdateRunnable: Runnable? = null
    
    // Radar sensor
    // TODO: probably, we are getting more than required data stats 
    private var radarSensor: OneDimensionalRadarImpl? = null
    private var lastRadarValue: Float = 0.0f
    private var lastRadarUnit: String = ""
    private var lastRadarTimestamp: String = "--:--:--.---"
    private var isRadarConnected = false
    private var isRadarDataSaving = false
    
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
        
        // Load application configuration first
        appConfig = AppConfigManager.getInstance()
        if (!appConfig.loadConfig(this)) {
            Log.w(TAG, logFormat("onCreate", "Failed to load config, using defaults"))
        }
        
        // Log configuration summary
        Log.d(TAG, logFormat("onCreate", appConfig.getConfigSummary()))
        
        // Initialize USB permission manager only if USB camera or radar is enabled
        if (appConfig.isUsbCameraEnabled || appConfig.isRadarEnabled) {
            usbPermissionManager = USBPermissionManager(this, this)
        }
        
        // Create UI
        createUI()
        
        // Request permissions
        requestNecessaryPermissions()
        
        // Initialize USB monitoring with delay (only if needed)
        if (appConfig.isUsbCameraEnabled || appConfig.isRadarEnabled) {
            Handler(Looper.getMainLooper()).postDelayed({
                initializeUSBMonitoring()
            }, 2000)
        }
        
        // Initialize internal camera with delay (only if enabled)
        if (appConfig.isInternalCameraEnabled) {
            Handler(Looper.getMainLooper()).postDelayed({
                initializeInternalCamera()
            }, 3000)
        }
        
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
        
        // Title with dynamic component status
        val enabledComponents = mutableListOf<String>()
        if (appConfig.isUsbCameraEnabled) enabledComponents.add("USB")
        if (appConfig.isInternalCameraEnabled) enabledComponents.add("Internal")
        if (appConfig.isRadarEnabled) enabledComponents.add("Radar")
        
        val componentsText = if (enabledComponents.isNotEmpty()) {
            enabledComponents.joinToString(" + ")
        } else {
            "No Components Enabled"
        }
        
        val titleText = TextView(this).apply {
            text = "🎯 Detection-Based Recording Test\n📷 Active: $componentsText"
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
        
        // Stats section - dynamic based on enabled components
        val initialStatsText = buildString {
            append("📊 System Status:\n\n")
            
            if (appConfig.isUsbCameraEnabled) {
                append("🔌 USB Camera:\nFrames: 0 | FPS: 0.0 | Non-Detections: 0 | Status: ⚪ WAITING\n\n")
            } else {
                append("🔌 USB Camera: DISABLED\n\n")
            }
            
            if (appConfig.isInternalCameraEnabled) {
                append("📱 Internal Camera:\nFrames: 0 | FPS: 0.0 | Non-Detections: 0 | Status: ⚪ WAITING\n\n")
            } else {
                append("📱 Internal Camera: DISABLED\n\n")
            }
            
            if (appConfig.isRadarEnabled) {
                append("📡 Radar Sensor:\nSpeed: -- | Last Reading: --:--:--.--- | Status: 🔴 DISCONNECTED")
            } else {
                append("📡 Radar Sensor: DISABLED")
            }
        }
        
        statsText = TextView(this).apply {
            text = initialStatsText
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
        
        // Radar Data Saving Button (only if radar is enabled)
        if (appConfig.isRadarEnabled) {
            radarDataButton = Button(this).apply {
                text = "📡 Start Radar Data Saving"
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF2196F3.toInt())
                setPadding(20, 15, 20, 15)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 20
                }
                setOnClickListener { toggleRadarDataSaving() }
                isEnabled = false // Initially disabled until radar is connected
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
        radarDataButton?.let { statusContainer?.addView(it) }
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
        
        // Also request storage permissions for unified access (radar + internal camera)
        if (!FilePermissionManager.hasStoragePermissions(this)) {
            Log.d(TAG, logFormat("requestNecessaryPermissions", "📁 Requesting storage permissions for radar data and internal camera recordings"))
            addLogMessage("📁 Requesting storage permissions...")
            FilePermissionManager.requestStoragePermissions(this)
        } else {
            Log.d(TAG, logFormat("requestNecessaryPermissions", "✅ Storage permissions already granted"))
            addLogMessage("✅ Storage permissions already available")
        }
    }
    
    private fun initializeUSBMonitoring() {
        if (usbPermissionManager == null) {
            Log.w(TAG, logFormat("initializeUSBMonitoring", "USB permission manager not initialized (USB components disabled)"))
            return
        }
        
        Log.d(TAG, logFormat("initializeUSBMonitoring", "Initializing USB monitoring"))
        usbPermissionManager?.register()
        usbPermissionManager?.checkAndRequestPermissions()
    }
    
    private fun initializeInternalCamera() {
        if (!appConfig.isInternalCameraEnabled) {
            Log.d(TAG, logFormat("initializeInternalCamera", "Internal camera disabled in configuration"))
            return
        }
        
        Log.d(TAG, logFormat("initializeInternalCamera", "Initializing internal camera"))
        
        // Check if we have camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, logFormat("initializeInternalCamera", "Camera permission not granted yet"))
            return
        }
        
        // Check if detection recorder is initialized
        if (detectionRecorder == null ) {
            Log.d(TAG, logFormat("initializeInternalCamera", "Detection recorder not ready, creating new instance"))
            detectionRecorder = DetectionBasedRecorder(
                context = this,
                activityContext = this,
                appConfig = appConfig
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
    
    // NOTE: See when is this called 
    private fun updateStatsDisplay() {
        if (!isSystemReady && !isInternalCameraInitialized) return
        
        val stats = detectionRecorder?.getDetectionStats()
        if (stats != null) {
            // Calculate FPS
            calculateFPS(stats)
            
            runOnUiThread {
                statsText?.text = buildString {
                    append("📊 System Status:\n\n")
                    
                    // USB Camera stats (only if enabled)
                    if (appConfig.isUsbCameraEnabled) {
                        append("🔌 USB Camera:\n")
                        append("Frames: ${stats.usbFramesProcessed} | ")
                        append("FPS: ${String.format("%.1f", usbFps)} | ")
                        append("Non-Detections: ${stats.usbConsecutiveNonDetections} | ")
                        append("Status: ${if (stats.isUSBRecording) "🔴 RECORDING" else "⚪ WAITING"}\n\n")
                    } else {
                        append("🔌 USB Camera: DISABLED\n\n")
                    }
                    
                    // Internal Camera stats (only if enabled)
                    if (appConfig.isInternalCameraEnabled) {
                        append("📱 Internal Camera:\n")
                        append("Frames: ${stats.internalFramesProcessed} | ")
                        append("FPS: ${String.format("%.1f", internalFps)} | ")
                        append("Non-Detections: ${stats.internalConsecutiveNonDetections} | ")
                        append("Status: ${if (stats.isInternalRecording) "🔴 RECORDING" else "⚪ WAITING"}\n\n")
                    } else {
                        append("📱 Internal Camera: DISABLED\n\n")
                    }
                    
                    // Radar Sensor stats (only if enabled)
                    if (appConfig.isRadarEnabled) {
                        append("📡 Radar Sensor:\n")
                        append("Speed: ${if (lastRadarValue != 0.0f) String.format("%.1f", lastRadarValue) else "--"} ${lastRadarUnit} | ")
                        append("Last Reading: $lastRadarTimestamp | ")
                        val radarStatus = when {
                            radarSensor?.isReading() == true -> "🟢 ACTIVE"
                            isRadarConnected -> "⚪ READY"
                            else -> "🔴 DISCONNECTED"
                        }
                        append("Status: $radarStatus")
                    } else {
                        append("📡 Radar Sensor: DISABLED")
                    }
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
    override fun onPermissionGranted(device: UsbDevice, deviceType: USBDeviceType) {
        Log.d(TAG, logFormat("onPermissionGranted", "✅ USB permission granted for: ${device.deviceName} (Type: $deviceType)"))
        
        when (deviceType) {
            USBDeviceType.UVC_CAMERA -> {
                if (appConfig.isUsbCameraEnabled) {
                    updateStatus("✅ USB Camera Permission Granted\n🔄 Initializing detection system...")
                    addLogMessage("USB camera permission granted for ${device.productName ?: device.deviceName}")
                    currentUsbDevice = device
                    usbPermissionManager?.logDeviceInfo(device)
                    initializeDetectionSystem(device)
                } else {
                    Log.d(TAG, logFormat("onPermissionGranted", "USB camera disabled in configuration, ignoring"))
                    addLogMessage("USB camera detected but disabled in configuration")
                }
            }
            USBDeviceType.RADAR_SENSOR -> {
                if (appConfig.isRadarEnabled) {
                    updateStatus("✅ Radar Sensor Permission Granted\n🔄 Initializing radar...")
                    addLogMessage("Radar sensor permission granted for ${device.productName ?: device.deviceName}")
                    usbPermissionManager?.logDeviceInfo(device)
                    initializeRadarSensor(device)
                } else {
                    Log.d(TAG, logFormat("onPermissionGranted", "Radar sensor disabled in configuration, ignoring"))
                    addLogMessage("Radar sensor detected but disabled in configuration")
                }
            }
            USBDeviceType.UNKNOWN -> {
                Log.w(TAG, logFormat("onPermissionGranted", "Unknown device type, ignoring"))
            }
        }
    }
    
    override fun onPermissionDenied(device: UsbDevice, deviceType: USBDeviceType) {
        Log.w(TAG, logFormat("onPermissionDenied", "❌ USB permission denied for: ${device.deviceName} (Type: $deviceType)"))
        val deviceName = when (deviceType) {
            USBDeviceType.UVC_CAMERA -> "USB camera"
            USBDeviceType.RADAR_SENSOR -> "radar sensor"
            USBDeviceType.UNKNOWN -> "unknown device"
        }
        updateStatus("❌ $deviceName Permission Denied")
        addLogMessage("$deviceName permission denied")
        Toast.makeText(this, "$deviceName permission denied", Toast.LENGTH_LONG).show()
    }
    
    override fun onUsbDeviceAttached(device: UsbDevice, deviceType: USBDeviceType) {
        val deviceName = when (deviceType) {
            USBDeviceType.UVC_CAMERA -> "USB camera"
            USBDeviceType.RADAR_SENSOR -> "radar sensor"
            USBDeviceType.UNKNOWN -> "unknown device"
        }
        Log.d(TAG, logFormat("onUsbDeviceAttached", "🔌 $deviceName attached: ${device.deviceName}"))
        addLogMessage("$deviceName attached: ${device.productName ?: device.deviceName}")
        usbPermissionManager?.requestPermission(device)
    }
    
    override fun onUsbDeviceDetached(device: UsbDevice, deviceType: USBDeviceType) {
        val deviceName = when (deviceType) {
            USBDeviceType.UVC_CAMERA -> "USB camera"
            USBDeviceType.RADAR_SENSOR -> "radar sensor"
            USBDeviceType.UNKNOWN -> "unknown device"
        }
        Log.d(TAG, logFormat("onUsbDeviceDetached", "🔌 $deviceName detached: ${device.deviceName}"))
        addLogMessage("$deviceName detached")
        
        when (deviceType) {
            USBDeviceType.UVC_CAMERA -> {
                if (device == currentUsbDevice) {
                    updateStatus("📷 USB Camera Disconnected\nDetection system stopped")
                    shutdownDetectionSystem()
                    currentUsbDevice = null
                }
            }
            USBDeviceType.RADAR_SENSOR -> {
                shutdownRadarSensor()
            }
            USBDeviceType.UNKNOWN -> {
                // Do nothing for unknown devices
            }
        }
    }

    private fun initializeDetectionSystem(device: UsbDevice) {
        if (!appConfig.isUsbCameraEnabled) {
            Log.d(TAG, logFormat("initializeDetectionSystem", "USB camera disabled in configuration"))
            return
        }
        
        try {
            updateStatus("🔄 Initializing Detection System\n${device.productName ?: device.deviceName}")
            addLogMessage("Initializing detection system...")
            
            // Create detection-based recorder with activity context for USB management
            if (detectionRecorder == null && appConfig.shouldInitializeDetection()) {
                detectionRecorder = DetectionBasedRecorder(
                    context = this,
                    activityContext = this,
                    appConfig = appConfig
                )
                
                // Set recording state listener to get detection events
                detectionRecorder?.setRecordingStateListener(this)
                
                // Initialize detection system
                detectionRecorder?.initialize()
            }
            
            // Initialize USB camera through DetectionBasedRecorder
            detectionRecorder?.initializeUSBCamera(device)
            
            Log.d(TAG, logFormat("initializeDetectionSystem", "Detection system initialization started"))
            
        } catch (e: Exception) {
            Log.e(TAG, logFormat("initializeDetectionSystem", "Failed to initialize detection system: ${e.message}"), e)
            updateStatus("❌ Detection System Failed\n${e.message}")
            addLogMessage("ERROR: Failed to initialize detection system - ${e.message}")
        }
    }
    
    private fun initializeRadarSensor(device: UsbDevice) {
        if (!appConfig.isRadarEnabled) {
            Log.d(TAG, logFormat("initializeRadarSensor", "Radar sensor disabled in configuration"))
            return
        }
        
        try {
            Log.d(TAG, logFormat("initializeRadarSensor", "🎯 Starting radar sensor initialization"))
            Log.d(TAG, logFormat("initializeRadarSensor", "Device: ${device.productName ?: device.deviceName} (VID=${device.vendorId}, PID=${device.productId})"))
            
            updateStatus("🔄 Initializing Radar Sensor\n${device.productName ?: device.deviceName}")
            addLogMessage("Initializing radar sensor (VID=${device.vendorId}, PID=${device.productId})...")
            
            // Create radar sensor instance
            radarSensor = OneDimensionalRadarImpl(this, device)
            Log.d(TAG, logFormat("initializeRadarSensor", "Radar instance created, setting listeners..."))
            
            radarSensor?.setRadarDataListener(this)

            radarSensor?.setRadarStateListener(this)
            Log.d(TAG, logFormat("initializeRadarSensor", "State listener set - connection should initialize now"))
            
            Log.d(TAG, logFormat("initializeRadarSensor", "✅ Radar sensor initialization completed"))
            
        } catch (e: Exception) {
            Log.e(TAG, logFormat("initializeRadarSensor", "❌ Failed to initialize radar sensor: ${e.message}"), e)
            updateStatus("❌ Radar Sensor Failed\n${e.message}")
            addLogMessage("ERROR: Failed to initialize radar sensor - ${e.message}")
        }
    }
    
    private fun shutdownRadarSensor() {
        try {
            radarSensor?.shutdown()
            radarSensor = null
            isRadarConnected = false
            lastRadarValue = 0.0f
            lastRadarUnit = ""
            lastRadarTimestamp = "--:--:--.---"
            
            addLogMessage("Radar sensor disconnected")
            
        } catch (e: Exception) {
            Log.e(TAG, logFormat("shutdownRadarSensor", "Error shutting down radar sensor: ${e.message}"), e)
        }
    }
    
    private fun toggleRadarDataSaving() {
        val radar = radarSensor
        if (radar == null) {
            addLogMessage("❌ Radar sensor not connected")
            return
        }
        
        if (isRadarDataSaving) {
            // Stop data saving
            try {
                radar.stopDataSaving()
                isRadarDataSaving = false
                updateRadarDataButton()
                addLogMessage("🛑 Radar data saving stopped")
            } catch (e: Exception) {
                Log.e(TAG, logFormat("toggleRadarDataSaving", "Error stopping radar data saving: ${e.message}"), e)
                addLogMessage("❌ Error stopping radar data saving: ${e.message}")
            }
        } else {
            // Start data saving (permissions should already be requested at startup)
            try {
                radar.startDataSaving()  // Uses organized directory structure: SpeedingApp/<datestamp>/radar/
                isRadarDataSaving = true
                updateRadarDataButton()
                addLogMessage("✅ Radar data saving started (organized folder structure)")
            } catch (e: Exception) {
                Log.e(TAG, logFormat("toggleRadarDataSaving", "Error starting radar data saving: ${e.message}"), e)
                addLogMessage("❌ Error starting radar data saving: ${e.message}")
            }
        }
    }
    
    private fun updateRadarDataButton() {
        radarDataButton?.apply {
            if (isRadarDataSaving) {
                text = "🛑 Stop Radar Data Saving"
                setBackgroundColor(0xFFF44336.toInt()) // Red
            } else {
                text = "📡 Start Radar Data Saving"
                setBackgroundColor(0xFF2196F3.toInt()) // Blue
            }
            isEnabled = isRadarConnected
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
            FilePermissionManager.STORAGE_PERMISSION_REQUEST_CODE -> {
                // Handle file permission results for radar data saving
                FilePermissionManager.handlePermissionResult(
                    requestCode, permissions, grantResults,
                    onPermissionGranted = {
                        addLogMessage("✅ Storage permissions granted - you can now save radar data")
                        // Try to start radar data saving again
                        toggleRadarDataSaving()
                    },
                    onPermissionDenied = {
                        addLogMessage("❌ Storage permissions denied - radar data will save to internal storage")
                        // Try to start with fallback to internal storage
                        val radar = radarSensor
                        if (radar != null && !isRadarDataSaving) {
                            try {
                                radar.startDataSaving() // Will use internal storage
                                isRadarDataSaving = true
                                updateRadarDataButton()
                                addLogMessage("✅ Radar data saving started (internal storage)")
                            } catch (e: Exception) {
                                addLogMessage("❌ Failed to start radar data saving: ${e.message}")
                            }
                        }
                    }
                )
            }
        }
    }
    
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            FilePermissionManager.MANAGE_EXTERNAL_STORAGE_REQUEST_CODE -> {
                // Handle MANAGE_EXTERNAL_STORAGE result for Android 11+
                FilePermissionManager.handlePermissionResult(
                    requestCode, emptyArray(), intArrayOf(),
                    onPermissionGranted = {
                        addLogMessage("✅ File management permissions granted - you can now save radar data")
                        // Try to start radar data saving again
                        toggleRadarDataSaving()
                    },
                    onPermissionDenied = {
                        addLogMessage("❌ File management permissions denied - radar data will save to internal storage")
                        // Try to start with fallback to internal storage
                        val radar = radarSensor
                        if (radar != null && !isRadarDataSaving) {
                            try {
                                radar.startDataSaving() // Will use internal storage
                                isRadarDataSaving = true
                                updateRadarDataButton()
                                addLogMessage("✅ Radar data saving started (internal storage)")
                            } catch (e: Exception) {
                                addLogMessage("❌ Failed to start radar data saving: ${e.message}")
                            }
                        }
                    }
                )
            }
        }
    }
    
    // RadarDataListener implementation
    override fun onRadarDataReceived(value: Float, unit: String, timestamp: Long) {
        runOnUiThread {
            lastRadarValue = value
            lastRadarUnit = unit
            lastRadarTimestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
            
            addLogMessage("📡 Radar reading: $value $unit")
            Log.d(TAG, logFormat("onRadarDataReceived", "Radar data: $value $unit at $lastRadarTimestamp"))
        }
    }
    
    // RadarStateListener implementation - 3 functions 
    override fun onRadarConnected() {
        Log.d(TAG, logFormat("onRadarConnected", "🎯 Radar connected callback received"))
        runOnUiThread {
            isRadarConnected = true
            addLogMessage("📡 Radar sensor connected successfully")
            updateStatus("✅ Radar Sensor Connected\n🎯 Starting readings...")
            updateRadarDataButton()
            
            // Start reading automatically when connected
            radarSensor?.startReading()
            Log.d(TAG, logFormat("onRadarConnected", "Radar reading started"))
        }
    }
    
    override fun onRadarDisconnected() {
        Log.d(TAG, logFormat("onRadarDisconnected", "🔴 Radar disconnected callback received"))
        runOnUiThread {
            isRadarConnected = false
            isRadarDataSaving = false
            addLogMessage("📡 Radar sensor disconnected")
            updateStatus("🔴 Radar Sensor Disconnected")
            updateRadarDataButton()
        }
    }
    
    override fun onRadarError(error: String) {
        Log.e(TAG, logFormat("onRadarError", "❌ Radar error callback: $error"))
        runOnUiThread {
            addLogMessage("❌ Radar error: $error")
            updateStatus("❌ Radar Error\n$error")
            isRadarConnected = false
        }
    }
    
    override fun onDestroy() {
        Log.d(TAG, logFormat("onDestroy", "Activity destroying"))
        
        // Stop stats updates
        statsUpdateHandler?.removeCallbacksAndMessages(null)
        
        // Shutdown radar sensor
        shutdownRadarSensor()
        
        // Shutdown detection system
        shutdownDetectionSystem()
        
        // Unregister USB receiver
        usbPermissionManager?.unregister()
        
        super.onDestroy()
    }
}
