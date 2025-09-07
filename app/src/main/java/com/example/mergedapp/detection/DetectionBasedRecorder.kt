package com.example.mergedapp.detection

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.mergedapp.camera.*
import com.example.mergedapp.camera.FrameConversionUtils
import org.tensorflow.lite.examples.objectdetection.config.DetectionConfig
import org.tensorflow.lite.examples.objectdetection.detectors.DetectionObject
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
 */


class DetectionBasedRecorder(
    private val context: Context
) : DetectionFrameListener, DetectionModule.DetectionListener, CameraStateListener {
    
    // Alternative constructor for USB device management
    constructor(
        context: Context,
        activityContext: AppCompatActivity? = null
    ) : this(context) {
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
    
    // TODO: Check if threading approach is better or worse 
    private val detectionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    
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

    
    // Recording listeners
    private var recordingListener: RecordingStateListener? = null

    // target objects 
    private val targetObjects = listOf("laptop")
    
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
            managedUSBCamera?.setDetectionFrameCallback(this)
            managedInternalCamera?.setDetectionFrameCallback(this)
            
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

            
            // Create managed USB camera
            managedUSBCamera = USBCameraImpl(activityContext!!, device, activityContext!!)
            managedUSBCamera?.setCameraStateListener(this)
            

            // TODO: Check if this resolution is for preview or recording
            val config = CameraConfig(
                width = 1280, 
                height = 720,
                enableDetectionFrames = true,
                showPreview = false
            )
            
            managedUSBCamera?.startCamera(config)
            
            // TODO: check why we are again setting this ? we are setting already in initialize
            managedUSBCamera?.setDetectionFrameCallback(this)
            
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
                enableDetectionFrames = true,
                showPreview = false
            )
            
            managedInternalCamera?.startCamera(config)
            
            // TODO: Check why again ?
            managedInternalCamera?.setDetectionFrameCallback(this)
            
        } catch (e: Exception) {
            Log.e(TAG, logFormat("initializeInternalCamera", "Failed to initialize internal camera: ${e.message}"), e)
            recordingListener?.onRecordingError(CameraType.INTERNAL, "Failed to initialize internal camera: ${e.message}")
        }
    }
    
    fun setRecordingStateListener(listener: RecordingStateListener?) {
        this.recordingListener = listener
    }


    fun stopMonitoring() {
        Log.d(TAG, logFormat("stopMonitoring", "Stopping detection-based recording monitoring"))
        
        managedUSBCamera?.let { camera ->
            if (camera.isRecording()) {
                camera.stopRecording()
            }
        }
        managedInternalCamera?.let { camera ->
            if (camera.isRecording()) {
                camera.stopRecording()
            }
        }
    }

    fun shutdown() {
        try {
            stopMonitoring()
            isInitialized.set(false)
            
            managedUSBCamera?.stopCamera()
            managedInternalCamera?.stopCamera()

            managedUSBCamera?.setDetectionFrameCallback(null)
            managedInternalCamera?.setDetectionFrameCallback(null)
            
            managedUSBCamera = null
            managedInternalCamera = null

            detectionModule.shutdown()
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
    

    override fun onFrameAvailable(data: ByteArray, width: Int, height: Int, format: FrameFormat, rotation: Int, timestamp: Long, source: CameraType) {

        Log.d(TAG, logFormat("onRawFrameAvailable", "🎯 RAW_FRAME_RECEIVED - ${source} camera: ${width}x${height}, format=$format, size=${data.size}"))
        if (!isInitialized.get()) {
            Log.w(TAG, logFormat("onRawFrameAvailable", "⚠️ RAW_FRAME_DROPPED - Recorder not initialized"))
            return
        }

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

            val methodStartTime = System.currentTimeMillis()
            Log.d(TAG, logFormat("onRawFrameAvailable", "🔄 PROCESSING_RAW_FRAME - Frame #${when(source) { CameraType.USB -> usbFrameCounter.get(); CameraType.INTERNAL -> internalFrameCounter.get() }} from $source camera"))
            
            // Convert raw data to bitmap only when processing is needed
            val conversionStartTime = System.currentTimeMillis()
            val bitmap = FrameConversionUtils.convertToBitmap(data, width, height, format)
            val conversionDuration = System.currentTimeMillis() - conversionStartTime
            Log.d(TAG, logFormat("onRawFrameAvailable", "----------------------------------- Frame conversion took ${conversionDuration}ms"))
            
            if (bitmap != null) {
                val submissionStartTime = System.currentTimeMillis()
                detectionExecutor.submit {
                    try {
                        processDetectionFrame(bitmap, rotation, source, timestamp)
                    } catch (e: Exception) {
                        Log.e(TAG, logFormat("onRawFrameAvailable", "💥 RAW_FRAME_PROCESSING_ERROR - ${e.message}"), e)
                    }
                }
                val submissionDuration = System.currentTimeMillis() - submissionStartTime
                if (source == CameraType.USB) {
                    Log.d(TAG, logFormat("onRawFrameAvailable", "----------------------------------- USB executor submission took ${submissionDuration}ms"))
                }
            } else {
                Log.e(TAG, logFormat("onRawFrameAvailable", "❌ CONVERSION_FAILED - Unable to convert raw frame to bitmap"))
            }


            // log the time it took to do detection
            val totalMethodDuration = System.currentTimeMillis() - methodStartTime
            if (source == CameraType.USB) {
                Log.d(TAG, logFormat("onRawFrameAvailable", "----------------------------------- USB raw frame total duration ${totalMethodDuration}ms"))
            }

        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun processDetectionFrame(bitmap: Bitmap, rotation: Int, cameraType: CameraType, timestamp: Long) {
        val methodStartTime = System.currentTimeMillis()
        Log.d(TAG, logFormat("processDetectionFrame", "🧠 SENDING_TO_DETECTION - ${cameraType} frame ${bitmap.width}x${bitmap.height} to DetectionModule"))
        
        val detectionModuleStartTime = System.currentTimeMillis()
        detectionModule.processFrameAsync(bitmap, rotation, cameraType)
        val detectionModuleDuration = System.currentTimeMillis() - detectionModuleStartTime
        
        if (cameraType == CameraType.USB) {
            Log.d(TAG, logFormat("processDetectionFrame", "----------------------------------- USB DetectionModule submission took ${detectionModuleDuration}ms"))
        }
        
        val totalDuration = System.currentTimeMillis() - methodStartTime
        if (cameraType == CameraType.USB) {
            Log.d(TAG, logFormat("processDetectionFrame", "----------------------------------- USB processDetectionFrame total duration ${totalDuration}ms"))
        }
        // Results will come back via onDetectionResults callback with camera type included
    }
    

    // DetectionModule.DetectionListener implementation
    override fun onDetectionResults(
        results: List<DetectionObject>,
        inferenceTime: Long,
        imageWidth: Int,
        imageHeight: Int,
        cameraType: CameraType
    ) {
        mainHandler.post {
            handleDetectionResults(results, inferenceTime, imageWidth, imageHeight, cameraType)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handleDetectionResults(
        results: List<DetectionObject>,
        inferenceTime: Long,
        imageWidth: Int,
        imageHeight: Int,
        cameraType: CameraType
    ) {
        val hasDetection = results.any { detection ->
            targetObjects.any { targetObject ->
                detection.category.label.lowercase().contains(targetObject.lowercase())
            }
        }

        
        val objectCount = results.size
        
        Log.d(TAG, logFormat("handleDetectionResults", "Detection results from $cameraType camera: ${results.size} objects found in ${inferenceTime}ms"))
        
        // Log detected objects for debugging
        // if (hasDetection) {
        //     val detectedLabels = results.map { "${it.category.label}(${String.format("%.2f", it.category.confidence)})" }
        //     Log.d(TAG, logFormat("handleDetectionResults", "Detected objects from $cameraType camera: ${detectedLabels.joinToString(", ")}"))
        // }
        
        // Get the camera instance for this detection result
        val camera = when (cameraType) {
            CameraType.USB -> managedUSBCamera
            CameraType.INTERNAL -> managedInternalCamera
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




    override fun onDetectionError(error: String, cameraType: CameraType) {
        Log.e(TAG, logFormat("onDetectionError", "Detection error from $cameraType camera: $error"))
        mainHandler.post {
            recordingListener?.onRecordingError(cameraType, "Detection error: $error")
        }
    }


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
            
            camera.startRecording(outputPath, object :
                com.example.mergedapp.camera.RecordingStateListener {
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

    private fun stopRecordingForCamera(camera: ICamera, cameraType: CameraType) {
        try {
            camera.stopRecording()
            Log.d(TAG, logFormat("stopRecordingForCamera", "$cameraType recording stopped due to no detections"))
            
        } catch (e: Exception) {
            Log.e(TAG, logFormat("stopRecordingForCamera", "Error stopping recording for $cameraType camera: ${e.message}"), e)
            recordingListener?.onRecordingError(cameraType, "Failed to stop recording: ${e.message}")
        }
    }

    private fun generateOutputPath(cameraType: CameraType, timestamp: Long): String {
        val cameraPrefix = when (cameraType) {
            CameraType.USB -> "usb"
            CameraType.INTERNAL -> "internal"
        }
        
        val dateString = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
        
        return "${android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)}/MergedApp/detection_${cameraPrefix}_${dateString}.mp4"
    }

    fun getDetectionStats(): DetectionStats {
        return DetectionStats(
            usbFramesProcessed = usbFrameCounter.get(),
            internalFramesProcessed = internalFrameCounter.get(),
            usbConsecutiveNonDetections = usbConsecutiveNonDetections.get(),
            internalConsecutiveNonDetections = internalConsecutiveNonDetections.get(),
            isUSBRecording = managedUSBCamera?.isRecording() ?: false,
            isInternalRecording = managedInternalCamera?.isRecording() ?: false
        )
    }

    // CameraStateListener implementation for managed cameras
    override fun onCameraOpened() {
        Log.d(TAG, logFormat("onCameraOpened", "Managed camera opened successfully"))
        // Detection callbacks are already set in initializeUSBCamera/initializeInternalCamera
        // Just notify that camera is ready
        Log.d(TAG, logFormat("onCameraOpened", "Camera is ready and detection callbacks are active"))
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


// TODO: Should not be defined here
data class DetectionStats(
    val usbFramesProcessed: Long,
    val internalFramesProcessed: Long,
    val usbConsecutiveNonDetections: Int,
    val internalConsecutiveNonDetections: Int,
    val isUSBRecording: Boolean,
    val isInternalRecording: Boolean
)
