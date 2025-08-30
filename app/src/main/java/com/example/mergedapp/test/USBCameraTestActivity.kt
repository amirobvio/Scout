package com.example.mergedapp.test

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.mergedapp.camera.*
import com.example.mergedapp.usb.USBPermissionManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Test activity for USB Camera implementation
 * Based on patterns from usb_22 MainActivity but using our new camera interface
 */
class USBCameraTestActivity : AppCompatActivity(), 
    USBPermissionManager.USBPermissionListener,
    CameraStateListener,
    RecordingCallback,
    FrameCallback {
    
    companion object {
        private const val TAG = "USBCameraTestActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
    }
    
    // UI components
    private var rootView: FrameLayout? = null
    private var statusText: TextView? = null
    private var recordButton: ImageView? = null
    private var captureButton: ImageView? = null
    private var frameCountText: TextView? = null
    private var controlPanel: LinearLayout? = null
    
    // USB and camera management
    private lateinit var usbPermissionManager: USBPermissionManager
    private var usbCamera: USBCameraImpl? = null
    private var currentUsbDevice: UsbDevice? = null
    
    // State
    private var frameCount = 0L
    private var isRecording = false
    private var recordingStartTime = 0L
    private var recordingTimer: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "USBCameraTestActivity started")
        
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
    }
    
    private fun createUI() {
        // Create main container
        rootView = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
        }
        
        // Status text
        statusText = TextView(this).apply {
            text = "📷 USB Camera Test\n🔌 Initializing...\n\n⚠️ Connect USB camera and grant permissions"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x80000000.toInt())
            setPadding(30, 30, 30, 30)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = 60
            }
        }
        
        // Frame count text
        frameCountText = TextView(this).apply {
            text = "Frames: 0"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x80000000.toInt())
            setPadding(20, 10, 20, 10)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 20
                rightMargin = 20
            }
        }
        
        // Control panel
        createControlPanel()
        
        // Add views to container
        rootView?.addView(statusText)
        rootView?.addView(frameCountText)
        rootView?.addView(controlPanel)
        
        setContentView(rootView)
        Log.d(TAG, "UI created successfully")
    }
    
    private fun createControlPanel() {
        controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
            }
            setBackgroundColor(0xCC2196F3.toInt())
            setPadding(20, 30, 20, 50)
            visibility = View.GONE // Initially hidden
        }
        
        // Buttons container
        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Record button
        recordButton = ImageView(this).apply {
            setImageResource(android.R.drawable.presence_video_online)
            setBackgroundResource(android.R.drawable.btn_default)
            setPadding(20, 20, 20, 20)
            layoutParams = LinearLayout.LayoutParams(100, 100).apply {
                marginEnd = 40
            }
            setOnClickListener { toggleRecording() }
        }
        
        // Test frame callback button
        captureButton = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            setBackgroundResource(android.R.drawable.btn_default)
            setPadding(20, 20, 20, 20)
            layoutParams = LinearLayout.LayoutParams(80, 80).apply {
                marginStart = 40
            }
            setOnClickListener { testFrameCallback() }
        }
        
        buttonContainer.addView(recordButton)
        buttonContainer.addView(captureButton)
        controlPanel?.addView(buttonContainer)
    }
    
    private fun requestNecessaryPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: $permissionsToRequest")
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }
    
    private fun initializeUSBMonitoring() {
        Log.d(TAG, "Initializing USB monitoring")
        
        // Register USB receiver
        usbPermissionManager.register()
        
        // Check for connected cameras and request permissions
        usbPermissionManager.checkAndRequestPermissions()
    }
    
    private fun toggleRecording() {
        if (usbCamera == null) {
            Toast.makeText(this, "Camera not ready!", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }
    
    private fun startRecording() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "USB_Test_$timestamp.mp4"
        val outputDir = File(getExternalFilesDir(null), "TestRecordings")
        outputDir.mkdirs()
        val outputPath = File(outputDir, fileName).absolutePath
        
        Log.d(TAG, "Starting recording to: $outputPath")
        usbCamera?.startRecording(outputPath, this)
    }
    
    private fun stopRecording() {
        Log.d(TAG, "Stopping recording")
        usbCamera?.stopRecording()
    }
    
    private fun testFrameCallback() {
        if (usbCamera == null) {
            Toast.makeText(this, "Camera not ready!", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Toggle preview visibility for debugging
        if (usbCamera is USBCameraImpl) {
            usbCamera?.showPreview()
            Toast.makeText(this, "Preview shown - check for camera preview overlay", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Frame callback test - check logs for frame data", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateStatus(message: String) {
        runOnUiThread {
            statusText?.text = message
        }
    }
    
    // USBPermissionManager.USBPermissionListener implementation
    override fun onPermissionGranted(device: UsbDevice) {
        Log.d(TAG, "✅ USB permission granted for: ${device.deviceName}")
        updateStatus("✅ USB Permission Granted\n🔄 Initializing camera...")
        
        currentUsbDevice = device
        usbPermissionManager.logDeviceInfo(device)
        
        // NOW initialize the camera after permission is granted
        initializeCamera(device)
    }
    
    override fun onPermissionDenied(device: UsbDevice) {
        Log.w(TAG, "❌ USB permission denied for: ${device.deviceName}")
        updateStatus("❌ USB Permission Denied\nCamera cannot start")
        Toast.makeText(this, "USB permission denied - camera won't work", Toast.LENGTH_LONG).show()
    }
    
    override fun onUsbDeviceAttached(device: UsbDevice) {
        Log.d(TAG, "🔌 USB camera attached: ${device.deviceName}")
        usbPermissionManager.requestPermission(device)
    }
    
    override fun onUsbDeviceDetached(device: UsbDevice) {
        Log.d(TAG, "🔌 USB camera detached: ${device.deviceName}")
        if (device == currentUsbDevice) {
            updateStatus("📷 USB Camera\nDisconnected")
            usbCamera?.stopCamera()
            usbCamera = null
            currentUsbDevice = null
            controlPanel?.visibility = View.GONE
        }
    }

    // Camera initialization - only called AFTER USB permission is granted
    private fun initializeCamera(device: UsbDevice) {
        try {
            updateStatus("🔄 Initializing USB Camera\n${device.productName ?: device.deviceName}")
            
            // Create USB camera instance with activity reference for fragment management
            usbCamera = USBCameraImpl(this, device, this)
            usbCamera?.setCameraStateListener(this)
            
            // Start camera with frame callback enabled for testing
            val config = CameraConfig(
                width = 1280,
                height = 720,
                enableFrameCallback = true
            )
            
            usbCamera?.startCamera(config, this)
            
            Log.d(TAG, "USB camera initialization started with fragment bridge")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize camera", e)
            updateStatus("❌ Camera Initialization Failed\n${e.message}")
        }
    }

    
    // CameraStateListener implementation
    override fun onCameraOpened() {
        Log.i(TAG, "Camera opened successfully")
        runOnUiThread {
            updateStatus("✅ USB Camera Ready\nFrame callbacks enabled")
            controlPanel?.visibility = View.VISIBLE
            Toast.makeText(this, "Camera ready!", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCameraClosed() {
        Log.i(TAG, "Camera closed")
        runOnUiThread {
            updateStatus("📷 USB Camera\nClosed")
            controlPanel?.visibility = View.GONE
        }
    }
    
    override fun onCameraError(error: String) {
        Log.e(TAG, "Camera error: $error")
        runOnUiThread {
            updateStatus("❌ Camera Error\n$error")
            Toast.makeText(this, "Camera error: $error", Toast.LENGTH_LONG).show()
        }
    }
    
    // RecordingCallback implementation
    override fun onRecordingStarted(outputPath: String) {
        Log.d(TAG, "Recording started: $outputPath")
        isRecording = true
        recordingStartTime = System.currentTimeMillis()
        
        runOnUiThread {
            recordButton?.setImageResource(android.R.drawable.ic_media_pause)
            recordButton?.setBackgroundColor(0xFFFF0000.toInt())
            Toast.makeText(this, "Recording started!", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onRecordingStopped(outputPath: String) {
        Log.d(TAG, "Recording stopped: $outputPath")
        isRecording = false
        
        runOnUiThread {
            recordButton?.setImageResource(android.R.drawable.presence_video_online)
            recordButton?.setBackgroundResource(android.R.drawable.btn_default)
            Toast.makeText(this, "Recording saved!", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onRecordingError(error: String) {
        Log.e(TAG, "Recording error: $error")
        isRecording = false
        
        runOnUiThread {
            recordButton?.setImageResource(android.R.drawable.presence_video_online)
            recordButton?.setBackgroundResource(android.R.drawable.btn_default)
            Toast.makeText(this, "Recording error: $error", Toast.LENGTH_LONG).show()
        }
    }
    
    // FrameCallback implementation
    override fun onFrameAvailable(frame: CameraFrame) {
        frameCount++
        
        // Update frame count every 30 frames to avoid too frequent UI updates
        if (frameCount % 30 == 0L) {
            runOnUiThread {
                frameCountText?.text = "Frames: $frameCount\nFormat: ${frame.format}\nSize: ${frame.width}x${frame.height}"
            }
        }
        
        // Log frame details occasionally
        if (frameCount % 300 == 0L) {
            Log.d(TAG, "Frame $frameCount: ${frame.width}x${frame.height}, format: ${frame.format}, data size: ${frame.data.size}")
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
                val granted = mutableListOf<String>()
                val denied = mutableListOf<String>()
                
                for (i in permissions.indices) {
                    if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                        granted.add(permissions[i])
                    } else {
                        denied.add(permissions[i])
                    }
                }
                
                if (granted.isNotEmpty()) {
                    Log.d(TAG, "Permissions granted: $granted")
                }
                
                if (denied.isNotEmpty()) {
                    Log.w(TAG, "Permissions denied: $denied")
                    Toast.makeText(this, "Some permissions denied. Functionality may be limited.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    override fun onDestroy() {
        Log.d(TAG, "Activity destroying")
        
        // Stop camera
        usbCamera?.stopCamera()
        
        // Unregister USB receiver
        usbPermissionManager.unregister()
        
        // Clean up timer
        recordingTimer?.removeCallbacksAndMessages(null)
        
        super.onDestroy()
    }
}
