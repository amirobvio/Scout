package com.example.mergedapp.detection

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.mergedapp.camera.*
import org.tensorflow.lite.examples.objectdetection.config.DetectionConfig
import org.tensorflow.lite.examples.objectdetection.detectors.ObjectDetection
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
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
) : DetectionFrameCallback, DetectionModule.DetectionListener, CameraStateListener {
    
    // Alternative constructor for USB device management
    constructor(
        context: Context,
        activityContext: AppCompatActivity? = null
    ) : this(context, null, null) {
        this.activityContext = activityContext
    }
    
    companion object {
        private const val TAG = "DetectionBasedRecorder"
        
        // Helper function for consistent logging format
        private fun logFormat(functionName: String, message: String): String {
            return "DetectionBasedRecorder.$functionName: $message"
        }
    }
    
    // Detection module for object detection
    private val detectionModule = DetectionModule(context, this)
    
    // Threading
    private val detectionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // State management
    private val isInitialized = AtomicBoolean(false)
    
    // Frame counting for detection interval (same as detection_test)
    private val usbFrameCounter = AtomicLong(0)
    private val internalFrameCounter = AtomicLong(0)
    
    // Detection state tracking
    private val usbConsecutiveNonDetections = AtomicInteger(0)
    private val internalConsecutiveNonDetections = AtomicInteger(0)
    
    // USB device management
    private var activityContext: AppCompatActivity? = null
    private var managedUSBCamera: USBCameraImpl? = null
    private var managedInternalCamera: InternalCameraImpl? = null
    private var currentUsbDevice: UsbDevice? = null
    
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
            Log.w(TAG, logFormat("initialize", "DetectionBasedRecorder already initialized"))
            return
        }
        
        try {
            // Initialize detection module
            detectionModule.initialize()
            
            // Set detection frame callbacks on cameras
            getActiveUSBCamera()?.setDetectionFrameCallback(this)
            getActiveInternalCamera()?.setDetectionFrameCallback(this)
            
            isInitialized.set(true)
            Log.d(TAG, logFormat("initialize", "DetectionBasedRecorder initialized successfully"))
            
        } catch (e: Exception) {
            Log.e(TAG, logFormat("initialize", "Failed to initialize DetectionBasedRecorder: ${e.message}"), e)
        }
    }
    
    /**
     * Initialize USB camera with device
     */
    fun initializeUSBCamera(device: UsbDevice) {
        if (activityContext == null) {
            Log.e(TAG, logFormat("initializeUSBCamera", "Activity context required for USB camera management"))
            return
        }
        
        try {
            Log.d(TAG, logFormat("initializeUSBCamera", "Initializing USB camera with device: ${device.productName ?: device.deviceName}"))
            currentUsbDevice = device
            
            // Create managed USB camera
            managedUSBCamera = USBCameraImpl(activityContext!!, device, activityContext!!)
            managedUSBCamera?.setCameraStateListener(this)
            
            // Start camera with detection configuration
            val config = CameraConfig(
                width = 1280,
                height = 720,
                enableFrameCallback = false,
                enableDetectionFrames = true,
                showPreview = false
            )
            
            managedUSBCamera?.startCamera(config)
            
        } catch (e: Exception) {
            Log.e(TAG, logFormat("initializeUSBCamera", "Failed to initialize USB camera: ${e.message}"), e)
            recordingListener?.onRecordingError(CameraType.USB, "Failed to initialize USB camera: ${e.message}")
        }
    }
    
    /**
     * Initialize internal camera
     */
    fun initializeInternalCamera() {
        if (activityContext == null) {
            Log.e(TAG, logFormat("initializeInternalCamera", "Activity context required for internal camera management"))
            return
        }
        
        try {
            Log.d(TAG, logFormat("initializeInternalCamera", "Initializing internal camera"))
            
            // Create managed internal camera
            managedInternalCamera = InternalCameraImpl(activityContext!!, activityContext!!)
            managedInternalCamera?.setCameraStateListener(this)
            
            // Start camera with detection configuration
            val config = CameraConfig(
                width = 1280,
                height = 720,
                enableFrameCallback = false,
                enableDetectionFrames = true,
                showPreview = false
            )
            
            managedInternalCamera?.startCamera(config)
            
        } catch (e: Exception) {
            Log.e(TAG, logFormat("initializeInternalCamera", "Failed to initialize internal camera: ${e.message}"), e)
            recordingListener?.onRecordingError(CameraType.INTERNAL, "Failed to initialize internal camera: ${e.message}")
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
            Log.w(TAG, logFormat("startMonitoring", "DetectionBasedRecorder not initialized. Call initialize() first."))
            return
        }
        
        Log.d(TAG, logFormat("startMonitoring", "Starting detection-based recording monitoring"))
        
        // Set detection frame callbacks on active cameras
        Log.d(TAG, logFormat("startMonitoring", "Setting detection frame callbacks on cameras"))
        getActiveUSBCamera()?.setDetectionFrameCallback(this)
        getActiveInternalCamera()?.setDetectionFrameCallback(this)
        Log.d(TAG, logFormat("startMonitoring", "Detection frame callbacks set - USB: ${getActiveUSBCamera() != null}, Internal: ${getActiveInternalCamera() != null}"))
        
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
        Log.d(TAG, logFormat("stopMonitoring", "Stopping detection-based recording monitoring"))
        
        // Stop any active recordings
        getActiveUSBCamera()?.let { camera ->
            if (camera.isRecording()) {
                camera.stopRecording()
            }
        }
        
        getActiveInternalCamera()?.let { camera ->
            if (camera.isRecording()) {
                camera.stopRecording()
            }
        }
    }
    
    /**
     * Shutdown the detection-based recorder
     */
    fun shutdown() {
        try {
            stopMonitoring()
            isInitialized.set(false)
            
            // Stop and cleanup managed cameras
            managedUSBCamera?.stopCamera()
            managedInternalCamera?.stopCamera()
            
            // Clear detection callbacks
            getActiveUSBCamera()?.setDetectionFrameCallback(null)
            getActiveInternalCamera()?.setDetectionFrameCallback(null)
            
            // Cleanup managed cameras
            managedUSBCamera = null
            managedInternalCamera = null
            currentUsbDevice = null
            
            // Shutdown detection module
            detectionModule.shutdown()
            
            // Properly shutdown executor with timeout
            detectionExecutor.shutdown()
            try {
                if (!detectionExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    detectionExecutor.shutdownNow()
                    if (!detectionExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                        Log.w(TAG, "Detection executor did not terminate gracefully")
                    }
                }
            } catch (e: InterruptedException) {
                detectionExecutor.shutdownNow()
                Thread.currentThread().interrupt()
            }
            
            Log.d(TAG, logFormat("shutdown", "DetectionBasedRecorder shutdown completed"))
            
        } catch (e: Exception) {
            Log.e(TAG, logFormat("shutdown", "Error during shutdown: ${e.message}"), e)
        }
    }
    
    // DetectionFrameCallback implementation
    override fun onDetectionFrameAvailable(bitmap: Bitmap, rotation: Int, timestamp: Long, source: CameraType) {
        Log.d(TAG, logFormat("onDetectionFrameAvailable", "🎯 FRAME_RECEIVED - ${source} camera: ${bitmap.width}x${bitmap.height}"))
        
        if (!isInitialized.get()) {
            Log.w(TAG, logFormat("onDetectionFrameAvailable", "⚠️ FRAME_DROPPED - Recorder not initialized"))
            return
        }

        Log.d(TAG, logFormat("onDetectionFrameAvailable", "Received detection frame from $source camera"))
        
        // Apply frame interval logic (same as detection_test)
        val shouldProcess = when (source) {
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
            Log.d(TAG, logFormat("onDetectionFrameAvailable", "🔄 PROCESSING_FRAME - Frame #${when(source) { CameraType.USB -> usbFrameCounter.get(); CameraType.INTERNAL -> internalFrameCounter.get() }} from $source camera"))
            
            // Process frame asynchronously (non-blocking)
            detectionExecutor.submit {
                try {
                    processDetectionFrame(bitmap, rotation, source, timestamp)
                } catch (e: Exception) {
                    Log.e(TAG, logFormat("onDetectionFrameAvailable", "💥 FRAME_PROCESSING_ERROR - ${e.message}"), e)
                }
            }
        } else {
            Log.v(TAG, logFormat("onDetectionFrameAvailable", "⏭️ FRAME_SKIPPED - Frame #${when(source) { CameraType.USB -> usbFrameCounter.get(); CameraType.INTERNAL -> internalFrameCounter.get() }} from $source camera (interval)"))
        }
    }
    
    /**
     * Process detection frame on background thread
     */
    @Suppress("UNUSED_PARAMETER")
    private fun processDetectionFrame(bitmap: Bitmap, rotation: Int, cameraType: CameraType, timestamp: Long) {
        Log.d(TAG, logFormat("processDetectionFrame", "🧠 SENDING_TO_DETECTION - ${cameraType} frame ${bitmap.width}x${bitmap.height} to DetectionModule"))
        
        detectionModule.processFrameAsync(bitmap, rotation, cameraType)
        
        Log.d(TAG, logFormat("processDetectionFrame", "✅ SENT_TO_DETECTION - Awaiting results from DetectionModule"))
        // Results will come back via onDetectionResults callback with camera type included
    }
    

    // DetectionModule.DetectionListener implementation
    override fun onDetectionResults(
        results: List<ObjectDetection>,
        inferenceTime: Long,
        imageWidth: Int,
        imageHeight: Int,
        cameraType: CameraType
    ) {
        mainHandler.post {
            handleDetectionResults(results, inferenceTime, imageWidth, imageHeight, cameraType)
        }
    }
    
    override fun onDetectionError(error: String, cameraType: CameraType) {
        Log.e(TAG, logFormat("onDetectionError", "Detection error from $cameraType camera: $error"))
        mainHandler.post {
            recordingListener?.onRecordingError(cameraType, "Detection error: $error")
        }
    }
    
    /**
     * Handle detection results and manage recording logic for a specific camera
     */
    @Suppress("UNUSED_PARAMETER")
    private fun handleDetectionResults(
        results: List<ObjectDetection>,
        inferenceTime: Long,
        imageWidth: Int,
        imageHeight: Int,
        cameraType: CameraType
    ) {
        val hasDetection = results.isNotEmpty()
        val objectCount = results.size
        
        Log.d(TAG, logFormat("handleDetectionResults", "Detection results from $cameraType camera: ${results.size} objects found in ${inferenceTime}ms"))
        
        // Log detected objects for debugging
        if (hasDetection) {
            val detectedLabels = results.map { "${it.category.label}(${String.format("%.2f", it.category.confidence)})" }
            Log.d(TAG, logFormat("handleDetectionResults", "Detected objects from $cameraType camera: ${detectedLabels.joinToString(", ")}"))
        }
        
        // Get the camera instance for this detection result
        val camera = when (cameraType) {
            CameraType.USB -> getActiveUSBCamera()
            CameraType.INTERNAL -> getActiveInternalCamera()
        }
        
        if (camera == null || !camera.isAvailable()) return
        
        val consecutiveNonDetections = when (cameraType) {
            CameraType.USB -> usbConsecutiveNonDetections
            CameraType.INTERNAL -> internalConsecutiveNonDetections
        }
        
        if (hasDetection) {
            // Reset non-detection counter
            consecutiveNonDetections.set(0)
            

            if (!camera.isRecording()) {
                startRecordingForCamera(camera, cameraType)
            }
            
            // Notify listener
            recordingListener?.onDetectionStateChanged(cameraType, true, objectCount)
            
        } else {
            // Increment non-detection counter
            val nonDetectionCount = consecutiveNonDetections.incrementAndGet()
            
            Log.v(TAG, logFormat("handleDetectionResults", "$cameraType camera: ${nonDetectionCount} consecutive non-detections"))
            
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
            
            // Ensure output directory exists
            val outputFile = File(outputPath)
            val outputDir = outputFile.parentFile
            if (outputDir != null && !outputDir.exists()) {
                if (!outputDir.mkdirs()) {
                    Log.e(TAG, logFormat("startRecordingForCamera", "Failed to create output directory: ${outputDir.absolutePath}"))
                    recordingListener?.onRecordingError(cameraType, "Failed to create output directory")
                    return
                }
            }
            
            camera.startRecording(outputPath, object : RecordingCallback {
                override fun onRecordingStarted(outputPath: String) {
                    Log.d(TAG, logFormat("startRecordingForCamera", "$cameraType recording started: $outputPath"))
                    recordingListener?.onRecordingStarted(cameraType, outputPath)
                }
                
                override fun onRecordingStopped(outputPath: String) {
                    Log.d(TAG, logFormat("startRecordingForCamera", "$cameraType recording stopped: $outputPath"))
                    recordingListener?.onRecordingStopped(cameraType, outputPath)
                }
                
                override fun onRecordingError(error: String) {
                    Log.e(TAG, logFormat("startRecordingForCamera", "$cameraType recording error: $error"))
                    recordingListener?.onRecordingError(cameraType, error)
                }
            })
            
        } catch (e: Exception) {
            Log.e(TAG, logFormat("startRecordingForCamera", "Error starting recording for $cameraType camera: ${e.message}"), e)
            recordingListener?.onRecordingError(cameraType, "Failed to start recording: ${e.message}")
        }
    }
    
    /**
     * Stop recording for a specific camera
     */
    private fun stopRecordingForCamera(camera: ICamera, cameraType: CameraType) {
        try {
            camera.stopRecording()
            Log.d(TAG, logFormat("stopRecordingForCamera", "$cameraType recording stopped due to no detections"))
            
        } catch (e: Exception) {
            Log.e(TAG, logFormat("stopRecordingForCamera", "Error stopping recording for $cameraType camera: ${e.message}"), e)
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
            isUSBRecording = getActiveUSBCamera()?.isRecording() ?: false,
            isInternalRecording = getActiveInternalCamera()?.isRecording() ?: false
        )
    }
    
    /**
     * Get the active USB camera (either injected or managed)
     */
    private fun getActiveUSBCamera(): ICamera? {
        return usbCamera ?: managedUSBCamera
    }
    
    /**
     * Get the active internal camera (either injected or managed)
     */
    private fun getActiveInternalCamera(): ICamera? {
        return internalCamera ?: managedInternalCamera
    }
    
    /**
     * Check if USB camera is available
     */
    fun isUSBCameraAvailable(): Boolean {
        return getActiveUSBCamera()?.isAvailable() == true
    }
    
    /**
     * Check if internal camera is available
     */
    fun isInternalCameraAvailable(): Boolean {
        return getActiveInternalCamera()?.isAvailable() == true
    }
    
    // CameraStateListener implementation for managed cameras
    override fun onCameraOpened() {
        Log.d(TAG, logFormat("onCameraOpened", "Managed camera opened successfully"))
        recordingListener?.let { listener ->
            // Determine which camera opened
            if (managedUSBCamera?.isAvailable() == true) {
                // USB camera is ready, can start monitoring

                if (isInitialized.get()) {
                    startMonitoring()
                }
            }
        }
    }
    
    override fun onCameraClosed() {
        Log.d(TAG, logFormat("onCameraClosed", "Managed camera closed"))
    }
    
    override fun onCameraError(error: String) {
        Log.e(TAG, logFormat("onCameraError", "Managed camera error: $error"))
        // Determine which camera had the error and notify
        if (managedUSBCamera != null) {
            recordingListener?.onRecordingError(CameraType.USB, "Camera error: $error")
        }
        if (managedInternalCamera != null) {
            recordingListener?.onRecordingError(CameraType.INTERNAL, "Camera error: $error")
        }
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
