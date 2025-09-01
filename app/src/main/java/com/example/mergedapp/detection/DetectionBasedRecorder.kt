package com.example.mergedapp.detection

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.mergedapp.camera.*
import org.tensorflow.lite.examples.objectdetection.config.DetectionConfig
import org.tensorflow.lite.examples.objectdetection.detectors.ObjectDetection
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * DetectionBasedRecorder - Manages detection-triggered recording for both cameras
 * 
 * This class acts as the intermediary between cameras and the detection system,
 * implementing the detection logic from detection_test project with recording control.
 * 
 * Features:
 * - Frame interval processing (every 15th frame like detection_test)
 * - Detection-triggered recording start/stop
 * - Consecutive non-detection counting for recording stop
 * - Unified handling for both USB and Internal cameras
 * - Thread-safe operations
 */
class DetectionBasedRecorder(
    private val context: Context,
    private val usbCamera: ICamera? = null,
    private val internalCamera: ICamera? = null
) : DetectionFrameCallback, DetectionModule.DetectionListener {
    
    companion object {
        private const val TAG = "DetectionBasedRecorder"
    }
    
    // Detection module for object detection
    private val detectionModule = DetectionModule(context, this)
    
    // Threading
    private val detectionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // State management
    private val isInitialized = AtomicBoolean(false)
    private val isRecording = AtomicBoolean(false)
    
    // Frame counting for detection interval (same as detection_test)
    private val usbFrameCounter = AtomicLong(0)
    private val internalFrameCounter = AtomicLong(0)
    
    // Detection state tracking
    private val usbConsecutiveNonDetections = AtomicInteger(0)
    private val internalConsecutiveNonDetections = AtomicInteger(0)
    
    // Recording listeners
    private var recordingListener: RecordingStateListener? = null
    
    /**
     * Interface for receiving recording state updates
     */
    interface RecordingStateListener {
        fun onRecordingStarted(cameraType: CameraType, outputPath: String)
        fun onRecordingStopped(cameraType: CameraType, outputPath: String)
        fun onRecordingError(cameraType: CameraType, error: String)
        fun onDetectionStateChanged(cameraType: CameraType, hasDetection: Boolean, objectCount: Int)
    }
    
    /**
     * Initialize the detection-based recorder
     */
    fun initialize() {
        if (isInitialized.get()) {
            Log.w(TAG, "DetectionBasedRecorder already initialized")
            return
        }
        
        try {
            // Initialize detection module
            detectionModule.initialize()
            
            // Set detection frame callbacks on cameras
            usbCamera?.setDetectionFrameCallback(this)
            internalCamera?.setDetectionFrameCallback(this)
            
            isInitialized.set(true)
            Log.d(TAG, "DetectionBasedRecorder initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DetectionBasedRecorder", e)
        }
    }
    
    /**
     * Set recording state listener
     */
    fun setRecordingStateListener(listener: RecordingStateListener?) {
        this.recordingListener = listener
    }
    
    /**
     * Start detection-based recording monitoring
     * Cameras should already be started with enableDetectionFrames = true
     */
    fun startMonitoring() {
        if (!isInitialized.get()) {
            Log.w(TAG, "DetectionBasedRecorder not initialized. Call initialize() first.")
            return
        }
        
        Log.d(TAG, "Starting detection-based recording monitoring")
        // Reset counters
        usbFrameCounter.set(0)
        internalFrameCounter.set(0)
        usbConsecutiveNonDetections.set(0)
        internalConsecutiveNonDetections.set(0)
    }
    
    /**
     * Stop detection-based recording monitoring
     */
    fun stopMonitoring() {
        Log.d(TAG, "Stopping detection-based recording monitoring")
        
        // Stop any active recordings
        if (usbCamera?.isRecording() == true) {
            usbCamera.stopRecording()
        }
        if (internalCamera?.isRecording() == true) {
            internalCamera.stopRecording()
        }
        
        isRecording.set(false)
    }
    
    /**
     * Shutdown the detection-based recorder
     */
    fun shutdown() {
        try {
            stopMonitoring()
            isInitialized.set(false)
            
            // Clear detection callbacks
            usbCamera?.setDetectionFrameCallback(null)
            internalCamera?.setDetectionFrameCallback(null)
            
            // Shutdown detection module
            detectionModule.shutdown()
            detectionExecutor.shutdown()
            
            Log.d(TAG, "DetectionBasedRecorder shutdown completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
        }
    }
    
    // DetectionFrameCallback implementation
    override fun onDetectionFrameAvailable(bitmap: Bitmap, rotation: Int, timestamp: Long) {
        if (!isInitialized.get()) return
        
        // Determine camera type from call context
        // TODO: This shit needs to be changed
        val cameraType = determineCameraType()
        
        // Apply frame interval logic (same as detection_test)
        val shouldProcess = when (cameraType) {
            CameraType.USB -> {
                val currentFrame = usbFrameCounter.incrementAndGet()
                currentFrame % DetectionConfig.DETECTION_FRAME_INTERVAL == 0L
            }
            CameraType.INTERNAL -> {
                val currentFrame = internalFrameCounter.incrementAndGet()
                currentFrame % DetectionConfig.DETECTION_FRAME_INTERVAL == 0L
            }
        }
        
        if (shouldProcess) {
            Log.v(TAG, "Processing detection frame from $cameraType camera")
            
            // Process frame asynchronously (non-blocking)
            detectionExecutor.submit {
                try {
                    processDetectionFrame(bitmap, rotation, cameraType, timestamp)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing detection frame", e)
                }
            }
        }
    }
    
    /**
     * Process detection frame on background thread
     */
    private fun processDetectionFrame(bitmap: Bitmap, rotation: Int, cameraType: CameraType, timestamp: Long) {
        // Use DetectionModule for actual detection (async)
        detectionModule.processFrameAsync(bitmap, rotation)
        
        // Note: Results will come back via onDetectionResults callback
        // We store the camera type context for when results arrive
        // This is a simplified approach - in production you might want to queue frames with metadata
    }
    
    /**
     * Determine camera type from thread context
     * This is a simplified approach - in production you might pass camera type explicitly
     */
    private fun determineCameraType(): CameraType {
        // For now, we'll need to enhance this logic
        // Could be based on thread ID, or we could modify the interface to pass camera type
        // For simplicity, let's assume if USB camera exists and is available, frames from it are USB
        return if (usbCamera?.isAvailable() == true) CameraType.USB else CameraType.INTERNAL
    }
    
    // DetectionModule.DetectionListener implementation
    override fun onDetectionResults(
        results: List<ObjectDetection>,
        inferenceTime: Long,
        imageWidth: Int,
        imageHeight: Int
    ) {
        mainHandler.post {
            processDetectionResults(results, inferenceTime, imageWidth, imageHeight)
        }
    }
    
    override fun onDetectionError(error: String) {
        Log.e(TAG, "Detection error: $error")
        mainHandler.post {
            recordingListener?.onRecordingError(CameraType.USB, "Detection error: $error")
            recordingListener?.onRecordingError(CameraType.INTERNAL, "Detection error: $error")
        }
    }
    
    /**
     * Process detection results and handle recording logic
     */
    private fun processDetectionResults(
        results: List<ObjectDetection>,
        inferenceTime: Long,
        imageWidth: Int,
        imageHeight: Int
    ) {
        val hasDetection = results.isNotEmpty()
        val objectCount = results.size
        
        Log.d(TAG, "Detection results: ${results.size} objects found in ${inferenceTime}ms")
        
        // Log detected objects for debugging
        if (hasDetection) {
            val detectedLabels = results.map { "${it.category.label}(${String.format("%.2f", it.category.confidence)})" }
            Log.d(TAG, "Detected objects: ${detectedLabels.joinToString(", ")}")
        }
        
        // Handle detection results for both cameras
        // Note: This is simplified - we're applying same logic to both cameras
        // In production, you might want to track each camera separately
        handleDetectionForCamera(CameraType.USB, hasDetection, objectCount)
        handleDetectionForCamera(CameraType.INTERNAL, hasDetection, objectCount)
    }
    
    /**
     * Handle detection results for a specific camera
     */
    private fun handleDetectionForCamera(cameraType: CameraType, hasDetection: Boolean, objectCount: Int) {
        val camera = when (cameraType) {
            CameraType.USB -> usbCamera
            CameraType.INTERNAL -> internalCamera
        }
        
        if (camera == null || !camera.isAvailable()) return
        
        val consecutiveNonDetections = when (cameraType) {
            CameraType.USB -> usbConsecutiveNonDetections
            CameraType.INTERNAL -> internalConsecutiveNonDetections
        }
        
        if (hasDetection) {
            // Reset non-detection counter
            consecutiveNonDetections.set(0)
            
            // Start recording if not already recording
            if (!camera.isRecording()) {
                startRecordingForCamera(camera, cameraType)
            }
            
            // Notify listener
            recordingListener?.onDetectionStateChanged(cameraType, true, objectCount)
            
        } else {
            // Increment non-detection counter
            val nonDetectionCount = consecutiveNonDetections.incrementAndGet()
            
            Log.v(TAG, "$cameraType camera: ${nonDetectionCount} consecutive non-detections")
            
            // Stop recording after configured consecutive non-detections
            if (nonDetectionCount >= DetectionConfig.CONSECUTIVE_NON_DETECTIONS_TO_STOP) {
                if (camera.isRecording()) {
                    stopRecordingForCamera(camera, cameraType)
                }
                
                // Reset counter after stopping
                consecutiveNonDetections.set(0)
            }
            
            // Notify listener
            recordingListener?.onDetectionStateChanged(cameraType, false, 0)
        }
    }
    
    /**
     * Start recording for a specific camera
     */
    private fun startRecordingForCamera(camera: ICamera, cameraType: CameraType) {
        try {
            val timestamp = System.currentTimeMillis()
            val outputPath = generateOutputPath(cameraType, timestamp)
            
            camera.startRecording(outputPath, object : RecordingCallback {
                override fun onRecordingStarted(outputPath: String) {
                    Log.d(TAG, "$cameraType recording started: $outputPath")
                    recordingListener?.onRecordingStarted(cameraType, outputPath)
                }
                
                override fun onRecordingStopped(outputPath: String) {
                    Log.d(TAG, "$cameraType recording stopped: $outputPath")
                    recordingListener?.onRecordingStopped(cameraType, outputPath)
                }
                
                override fun onRecordingError(error: String) {
                    Log.e(TAG, "$cameraType recording error: $error")
                    recordingListener?.onRecordingError(cameraType, error)
                }
            })
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording for $cameraType camera", e)
            recordingListener?.onRecordingError(cameraType, "Failed to start recording: ${e.message}")
        }
    }
    
    /**
     * Stop recording for a specific camera
     */
    private fun stopRecordingForCamera(camera: ICamera, cameraType: CameraType) {
        try {
            camera.stopRecording()
            Log.d(TAG, "$cameraType recording stopped due to no detections")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording for $cameraType camera", e)
            recordingListener?.onRecordingError(cameraType, "Failed to stop recording: ${e.message}")
        }
    }
    
    /**
     * Generate output path for recording
     */
    private fun generateOutputPath(cameraType: CameraType, timestamp: Long): String {
        val cameraPrefix = when (cameraType) {
            CameraType.USB -> "usb"
            CameraType.INTERNAL -> "internal"
        }
        
        val dateString = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
        
        return "${android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)}/MergedApp/detection_${cameraPrefix}_${dateString}.mp4"
    }
    
    /**
     * Get current detection statistics
     */
    fun getDetectionStats(): DetectionStats {
        return DetectionStats(
            usbFramesProcessed = usbFrameCounter.get(),
            internalFramesProcessed = internalFrameCounter.get(),
            usbConsecutiveNonDetections = usbConsecutiveNonDetections.get(),
            internalConsecutiveNonDetections = internalConsecutiveNonDetections.get(),
            isUSBRecording = usbCamera?.isRecording() ?: false,
            isInternalRecording = internalCamera?.isRecording() ?: false
        )
    }
}

/**
 * Data class for detection statistics
 */
data class DetectionStats(
    val usbFramesProcessed: Long,
    val internalFramesProcessed: Long,
    val usbConsecutiveNonDetections: Int,
    val internalConsecutiveNonDetections: Int,
    val isUSBRecording: Boolean,
    val isInternalRecording: Boolean
)
