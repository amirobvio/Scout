package com.example.mergedapp.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Internal Camera implementation using CameraX
 * Based on detection_test patterns with modular frame handling
 */
class InternalCameraImpl(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) : ICamera {

    companion object {
        private const val TAG = "InternalCameraImpl"
    }

    override val cameraType = CameraType.INTERNAL
    
    // CameraX components
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var camera: Camera? = null
    
    // State management
    private var frameCallback: FrameCallback? = null
    private var detectionFrameCallback: DetectionFrameCallback? = null
    private var recordingCallback: RecordingCallback? = null
    private var cameraStateListener: CameraStateListener? = null
    private var currentConfig: CameraConfig? = null
    
    // Recording state
    private var isCurrentlyRecording = false
    private var currentRecording: Recording? = null
    
    // Camera executor for background operations
    private lateinit var cameraExecutor: ExecutorService
    
    // Frame processing
    private lateinit var bitmapBuffer: Bitmap              // For legacy frame callback
    private lateinit var detectionBitmapBuffer: Bitmap     // Optimized buffer for detection

    override fun startCamera(config: CameraConfig, frameCallback: FrameCallback?) {
        Log.d(TAG, "🚀 Starting internal camera with config: $config")
        
        this.frameCallback = frameCallback
        this.currentConfig = config
        
        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        try {
            setupCameraProvider()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start internal camera", e)
            cameraStateListener?.onCameraError("Failed to start camera: ${e.message}")
        }
    }

    override fun stopCamera() {
        Log.d(TAG, "Stopping internal camera")
        
        try {
            // Stop recording if active
            if (isCurrentlyRecording) {
                stopRecording()
            }
            
            // Unbind all use cases
            cameraProvider?.unbindAll()
            
            // Shut down executor
            if (::cameraExecutor.isInitialized) {
                cameraExecutor.shutdown()
            }
            
            // Clear references
            cameraProvider = null
            preview = null
            imageAnalyzer = null
            videoCapture = null
            camera = null
            frameCallback = null
            detectionFrameCallback = null
            currentConfig = null
            
            Log.d(TAG, "Internal camera stopped successfully")
            cameraStateListener?.onCameraClosed()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping internal camera", e)
            cameraStateListener?.onCameraError("Failed to stop camera: ${e.message}")
        }
    }

    override fun startRecording(outputPath: String, callback: RecordingCallback) {
        Log.d(TAG, "Starting recording to: $outputPath")
        
        if (videoCapture == null) {
            callback.onRecordingError("Video capture not initialized")
            return
        }
        
        if (isCurrentlyRecording) {
            callback.onRecordingError("Already recording")
            return
        }
        
        this.recordingCallback = callback
        
        try {
            // Ensure output directory exists
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()
            
            // Create FileOutputOptions
            val outputOptions = FileOutputOptions.Builder(outputFile).build()
            
            // Start recording
            currentRecording = videoCapture!!.output
                .prepareRecording(context, outputOptions)
                .start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                    handleRecordingEvent(recordEvent, outputPath)
                }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            callback.onRecordingError("Failed to start recording: ${e.message}")
        }
    }

    override fun stopRecording() {
        Log.d(TAG, "Stopping recording")
        
        if (!isCurrentlyRecording) {
            Log.w(TAG, "Not currently recording")
            return
        }
        
        try {
            currentRecording?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
            recordingCallback?.onRecordingError("Error stopping recording: ${e.message}")
        }
    }

    override fun isRecording(): Boolean {
        return isCurrentlyRecording
    }

    override fun isAvailable(): Boolean {
        return cameraProvider != null
    }

    override fun setCameraStateListener(listener: CameraStateListener?) {
        this.cameraStateListener = listener
    }
    
    override fun setDetectionFrameCallback(callback: DetectionFrameCallback?) {
        this.detectionFrameCallback = callback
        Log.d(TAG, "Detection frame callback set: ${callback != null}")
    }
    
    /**
     * Get preview use case for external surface provider
     */
    fun getPreview(): Preview? = preview
    
    /**
     * Set up CameraX provider and bind use cases
     */
    private fun setupCameraProvider() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                setupUseCases()
                bindUseCases()
                
                Log.d(TAG, "CameraX provider set up successfully")
                cameraStateListener?.onCameraOpened()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider", e)
                cameraStateListener?.onCameraError("Camera provider failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }
    
    /**
     * Set up all CameraX use cases
     */
    private fun setupUseCases() {
        val config = currentConfig ?: CameraConfig()
        
        // Preview use case
        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()
        
        // Image analysis for frame callbacks (if enabled)
        if (config.enableFrameCallback || config.enableDetectionFrames) {
            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { analyzer ->
                    analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (config.enableDetectionFrames) {
                            processDetectionFrame(imageProxy)
                        } else {
                            processFrame(imageProxy)
                        }
                    }
                }
        }
        
        // Video capture for recording
        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(Quality.HD, Quality.SD, Quality.LOWEST)
        )
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        videoCapture = VideoCapture.withOutput(recorder)
        
        Log.d(TAG, "Use cases set up: preview=${preview != null}, " +
                "imageAnalyzer=${imageAnalyzer != null}, videoCapture=${videoCapture != null}")
    }
    
    /**
     * Bind use cases to camera lifecycle
     */
    private fun bindUseCases() {
        val provider = cameraProvider ?: throw IllegalStateException("Camera provider not initialized")
        
        // Camera selector (back camera by default)
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()
        
        try {
            // Unbind all use cases before rebinding
            provider.unbindAll()
            
            // Build use cases list
            val useCases = mutableListOf<UseCase>().apply {
                preview?.let { add(it) }
                imageAnalyzer?.let { add(it) }
                videoCapture?.let { add(it) }
            }
            
            // Bind use cases to camera
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                *useCases.toTypedArray()
            )
            
            Log.d(TAG, "Camera bound successfully with ${useCases.size} use cases")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera use cases", e)
            throw e
        }
    }
    
    /**
     * Process frame for callbacks (legacy CameraFrame format)
     */
    private fun processFrame(imageProxy: ImageProxy) {
        try {
            // Initialize bitmap buffer if needed
            if (!::bitmapBuffer.isInitialized) {
                bitmapBuffer = Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
            }
            
            // Copy frame data
            imageProxy.use { proxy ->
                bitmapBuffer.copyPixelsFromBuffer(proxy.planes[0].buffer)
                
                // Convert to our CameraFrame format
                val frameData = ByteArray(bitmapBuffer.byteCount)
                val buffer = java.nio.ByteBuffer.allocate(bitmapBuffer.byteCount)
                bitmapBuffer.copyPixelsToBuffer(buffer)
                buffer.rewind()
                buffer.get(frameData)
                
                val cameraFrame = CameraFrame(
                    data = frameData,
                    width = proxy.width,
                    height = proxy.height,
                    format = FrameFormat.RGBA,
                    timestamp = System.currentTimeMillis()
                )
                
                // Call frame callback
                frameCallback?.onFrameAvailable(cameraFrame)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        }
    }
    
    /**
     * Process frame for detection (optimized detection_test pattern)
     * This uses the same efficient approach as detection_test project
     */
    private fun processDetectionFrame(imageProxy: ImageProxy) {
        try {
            // Initialize detection bitmap buffer if needed (same as detection_test)
            if (!::detectionBitmapBuffer.isInitialized) {
                detectionBitmapBuffer = Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
                Log.d(TAG, "Detection bitmap buffer initialized: ${imageProxy.width}x${imageProxy.height}")
            }
            
            // Copy out RGB bits to the shared bitmap buffer (detection_test pattern)
            imageProxy.use { proxy ->
                detectionBitmapBuffer.copyPixelsFromBuffer(proxy.planes[0].buffer)
                
                // Get rotation and timestamp
                val rotation = proxy.imageInfo.rotationDegrees
                val timestamp = System.currentTimeMillis()
                
                // Call detection frame callback with ready-to-use Bitmap
                detectionFrameCallback?.onDetectionFrameAvailable(
                    bitmap = detectionBitmapBuffer,
                    rotation = rotation,
                    timestamp = timestamp,
                    source = CameraType.INTERNAL
                )
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing detection frame", e)
        }
    }
    
    /**
     * Handle recording events
     */
    private fun handleRecordingEvent(recordEvent: VideoRecordEvent, outputPath: String) {
        when (recordEvent) {
            is VideoRecordEvent.Start -> {
                Log.d(TAG, "Recording started successfully")
                isCurrentlyRecording = true
                recordingCallback?.onRecordingStarted(outputPath)
            }
            is VideoRecordEvent.Finalize -> {
                Log.d(TAG, "Recording finalized")
                isCurrentlyRecording = false
                currentRecording = null
                
                if (recordEvent.hasError()) {
                    Log.e(TAG, "Recording failed: ${recordEvent.error}")
                    recordingCallback?.onRecordingError("Recording failed: ${recordEvent.error}")
                } else {
                    Log.d(TAG, "Recording completed successfully: $outputPath")
                    recordingCallback?.onRecordingStopped(outputPath)
                }
            }
            is VideoRecordEvent.Status -> {
                // Optional: Handle status updates
                Log.d(TAG, "Recording status: ${recordEvent.recordingStats}")
            }
        }
    }
}
