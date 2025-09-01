package com.example.mergedapp.detection

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import com.example.mergedapp.camera.*

/**
 * Example showing how to use DetectionBasedRecorder with both cameras
 * 
 * This demonstrates the complete setup for detection-triggered recording
 * using the optimized frame delivery system we implemented.
 */
class DetectionRecorderExample(
    private val context: Context,
    private val usbDevice: UsbDevice? = null,
    private val activity: AppCompatActivity? = null,
    private val lifecycleOwner: LifecycleOwner? = null
) : DetectionBasedRecorder.RecordingStateListener {
    
    companion object {
        private const val TAG = "DetectionRecorderExample"
    }
    
    // Camera instances (nullable for optional cameras)
    private val usbCamera: USBCameraImpl? = if (usbDevice != null && activity != null) {
        USBCameraImpl(context, usbDevice, activity)
    } else null
    
    private val internalCamera: InternalCameraImpl? = if (lifecycleOwner != null) {
        InternalCameraImpl(context, lifecycleOwner)
    } else null
    
    // Detection-based recorder
    private val detectionRecorder = DetectionBasedRecorder(
        context = context,
        usbCamera = usbCamera,
        internalCamera = internalCamera
    )
    
    /**
     * Start the complete detection-based recording system
     */
    fun startDetectionRecording() {
        try {
            Log.d(TAG, "🚀 Starting detection-based recording system")
            
            // 1. Initialize detection recorder
            detectionRecorder.setRecordingStateListener(this)
            detectionRecorder.initialize()
            
            // 2. Start cameras with detection frames enabled
            val detectionConfig = CameraConfig(
                width = 1280,
                height = 720,
                enableDetectionFrames = true,  // Enable optimized detection frame delivery
                enableFrameCallback = false   // Disable legacy frame callback for performance
            )
            
            // Start USB camera if available
            if (usbCamera?.isAvailable() == true) {
                usbCamera.startCamera(detectionConfig)
                Log.d(TAG, "✅ USB camera started with detection frames")
            } else {
                Log.w(TAG, "⚠️ USB camera not available")
            }
            
            // Start internal camera if available
            if (internalCamera != null) {
                internalCamera.startCamera(detectionConfig)
                Log.d(TAG, "✅ Internal camera started with detection frames")
            } else {
                Log.w(TAG, "⚠️ Internal camera not available (no lifecycle owner)")
            }
            
            // 3. Start detection monitoring
            detectionRecorder.startMonitoring()
            Log.d(TAG, "✅ Detection monitoring started")
            
            Log.d(TAG, "🎯 Detection-based recording system is now active!")
            Log.d(TAG, "📝 Recording will start automatically when objects are detected")
            Log.d(TAG, "⏹️ Recording will stop after ${org.tensorflow.lite.examples.objectdetection.config.DetectionConfig.CONSECUTIVE_NON_DETECTIONS_TO_STOP} consecutive frames without detection")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start detection recording system", e)
        }
    }
    
    /**
     * Stop the detection-based recording system
     */
    fun stopDetectionRecording() {
        try {
            Log.d(TAG, "⏹️ Stopping detection-based recording system")
            
            // Stop monitoring and recording
            detectionRecorder.stopMonitoring()
            
            // Stop cameras
            usbCamera?.stopCamera()
            internalCamera?.stopCamera()
            
            // Shutdown detection recorder
            detectionRecorder.shutdown()
            
            Log.d(TAG, "✅ Detection-based recording system stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping detection recording system", e)
        }
    }
    
    /**
     * Get current detection and recording statistics
     */
    fun getStats(): DetectionStats {
        return detectionRecorder.getDetectionStats()
    }
    
    /**
     * Print current system status
     */
    fun printStatus() {
        val stats = getStats()
        Log.d(TAG, """
            📊 Detection Recording Status:
            🔍 USB frames processed: ${stats.usbFramesProcessed}
            🔍 Internal frames processed: ${stats.internalFramesProcessed}
            📹 USB recording: ${if (stats.isUSBRecording) "🔴 ACTIVE" else "⚫ STOPPED"}
            📹 Internal recording: ${if (stats.isInternalRecording) "🔴 ACTIVE" else "⚫ STOPPED"}
            ⏰ USB non-detections: ${stats.usbConsecutiveNonDetections}
            ⏰ Internal non-detections: ${stats.internalConsecutiveNonDetections}
        """.trimIndent())
    }
    
    // DetectionBasedRecorder.RecordingStateListener implementation
    override fun onRecordingStarted(cameraType: CameraType, outputPath: String) {
        Log.i(TAG, "🔴 ${cameraType} recording STARTED: $outputPath")
    }
    
    override fun onRecordingStopped(cameraType: CameraType, outputPath: String) {
        Log.i(TAG, "⚫ ${cameraType} recording STOPPED: $outputPath")
    }
    
    override fun onRecordingError(cameraType: CameraType, error: String) {
        Log.e(TAG, "❌ ${cameraType} recording ERROR: $error")
    }
    
    override fun onDetectionStateChanged(cameraType: CameraType, hasDetection: Boolean, objectCount: Int) {
        if (hasDetection) {
            Log.d(TAG, "👀 ${cameraType} DETECTION: $objectCount objects found")
        } else {
            Log.v(TAG, "🔍 ${cameraType} scanning...")
        }
    }
    
    /**
     * Example of how to handle specific object detection
     * You can override this to implement custom detection logic
     */
    fun handleCustomDetection() {
        // Example: Only start recording for person detection
        detectionRecorder.setRecordingStateListener(object : DetectionBasedRecorder.RecordingStateListener {
            override fun onRecordingStarted(cameraType: CameraType, outputPath: String) {
                Log.i(TAG, "🚶 Person detected! Recording started: $outputPath")
            }
            
            override fun onRecordingStopped(cameraType: CameraType, outputPath: String) {
                Log.i(TAG, "👻 Person left scene. Recording stopped: $outputPath")
            }
            
            override fun onRecordingError(cameraType: CameraType, error: String) {
                Log.e(TAG, "Recording error: $error")
            }
            
            override fun onDetectionStateChanged(cameraType: CameraType, hasDetection: Boolean, objectCount: Int) {
                // Custom logic based on detection state
                when {
                    hasDetection && objectCount > 1 -> {
                        Log.d(TAG, "🏃‍♂️ Multiple objects detected (${objectCount}) - High activity!")
                    }
                    hasDetection -> {
                        Log.d(TAG, "👤 Single object detected")
                    }
                    else -> {
                        Log.v(TAG, "🔍 No objects in scene")
                    }
                }
            }
        })
    }
}
