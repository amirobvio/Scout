package com.example.mergedapp.test

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.mergedapp.camera.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Test activity for Internal Camera implementation
 * Based on USBCameraTestActivity patterns but using CameraX with PreviewView
 */
class InternalCameraTestActivity : AppCompatActivity(), 
    CameraStateListener,
    RecordingCallback,
    FrameCallback {
    
    companion object {
        private const val TAG = "InternalCameraTestActivity"
        private const val PERMISSION_REQUEST_CODE = 2001
    }
    
    // UI components
    private var rootView: FrameLayout? = null
    private var previewView: PreviewView? = null
    private var statusText: TextView? = null
    private var recordButton: ImageView? = null
    private var captureButton: ImageView? = null
    private var frameCountText: TextView? = null
    private var controlPanel: LinearLayout? = null
    
    // Camera management
    private var internalCamera: InternalCameraImpl? = null
    
    // State
    private var frameCount = 0L
    private var isRecording = false
    private var recordingStartTime = 0L
    private var recordingTimer: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "InternalCameraTestActivity started")
        
        // Create UI
        createUI()
        
        // Request permissions
        requestNecessaryPermissions()
        
        // Initialize camera with delay (similar to USB pattern)
        Handler(Looper.getMainLooper()).postDelayed({
            initializeCamera()
        }, 1000)
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
        
        // Camera preview view
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        
        // Status text
        statusText = TextView(this).apply {
            text = "📱 Internal Camera Test\n🔄 Initializing...\n\n⚠️ Grant camera permissions"
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
        
        // Add views to container (order matters for layering)
        rootView?.addView(previewView)     // Background layer
        rootView?.addView(statusText)      // Overlay
        rootView?.addView(frameCountText)  // Overlay
        rootView?.addView(controlPanel)    // Overlay
        
        setContentView(rootView)
        Log.d(TAG, "UI created successfully with PreviewView")
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
            setBackgroundColor(0xCC4CAF50.toInt()) // Green background to distinguish from USB test
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
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "📋 Requesting permissions for CameraX: $permissionsToRequest")
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            Log.d(TAG, "✅ All necessary permissions already granted")
        }
    }
    
    private fun initializeCamera() {
        Log.d(TAG, "Initializing internal camera")
        
        try {
            updateStatus("🔄 Initializing Internal Camera\nSetting up CameraX...")
            
            // Create internal camera instance
            internalCamera = InternalCameraImpl(this, this)
            internalCamera?.setCameraStateListener(this)
            
            // Start camera with frame callback enabled for testing
            val config = CameraConfig(
                width = 1280,
                height = 720,
                enableFrameCallback = true
            )
            
            internalCamera?.startCamera(config, this)
            
            Log.d(TAG, "Internal camera initialization started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize camera", e)
            updateStatus("❌ Camera Initialization Failed\n${e.message}")
        }
    }
    
    private fun connectPreview() {
        // Connect CameraX preview to PreviewView
        internalCamera?.getPreview()?.setSurfaceProvider(previewView?.surfaceProvider)
        Log.d(TAG, "Preview connected to PreviewView")
    }
    
    private fun toggleRecording() {
        if (internalCamera == null) {
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
        val fileName = "Internal_Test_$timestamp.mp4"
        
        // Use external storage like detection_test project - accessible via file manager
        val outputDir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "MergedAppRecordings")
        outputDir.mkdirs()
        val outputPath = File(outputDir, fileName).absolutePath
        
        Log.d(TAG, "🎥 Starting recording to EXTERNAL storage: $outputPath")
        Log.d(TAG, "📁 You can find this recording in: Android/data/com.example.mergedapp/files/Movies/MergedAppRecordings/")
        internalCamera?.startRecording(outputPath, this)
    }
    
    private fun stopRecording() {
        Log.d(TAG, "Stopping recording")
        internalCamera?.stopRecording()
    }
    
    private fun testFrameCallback() {
        if (internalCamera == null) {
            Toast.makeText(this, "Camera not ready!", Toast.LENGTH_SHORT).show()
            return
        }
        
        Toast.makeText(this, "Frame callback test - check logs for frame data", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "🔍 Frame callback test - current frame count: $frameCount")
    }
    
    private fun updateStatus(message: String) {
        runOnUiThread {
            statusText?.text = message
        }
    }
    
    // CameraStateListener implementation
    override fun onCameraOpened() {
        Log.i(TAG, "Camera opened successfully")
        
        // Connect preview now that camera is ready
        connectPreview()
        
        runOnUiThread {
            updateStatus("✅ Internal Camera Ready\nFrame callbacks enabled\nPreview active")
            controlPanel?.visibility = View.VISIBLE
            Toast.makeText(this, "Internal camera ready!", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCameraClosed() {
        Log.i(TAG, "Camera closed")
        runOnUiThread {
            updateStatus("📱 Internal Camera\nClosed")
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
                
                // Continue with camera initialization if CAMERA permission is granted
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    initializeCamera()
                }
            }
        }
    }
    
    override fun onDestroy() {
        Log.d(TAG, "Activity destroying")
        
        // Stop camera
        internalCamera?.stopCamera()
        
        // Clean up timer
        recordingTimer?.removeCallbacksAndMessages(null)
        
        super.onDestroy()
    }
}
