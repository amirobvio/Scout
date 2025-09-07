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
    private var detectionFrameListener: DetectionFrameListener? = null
    private var recordingStateListener: RecordingStateListener? = null
    private var cameraStateListener: CameraStateListener? = null
    private var currentConfig: CameraConfig? = null
    
    // Recording state
    private var isCurrentlyRecording = false
    private var currentRecording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var detectionBitmapBuffer: Bitmap     // Optimized buffer for detection

    override fun startCamera(config: CameraConfig) {
        Log.d(TAG, "🚀 Starting internal camera with config: $config")
        
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
            detectionFrameListener = null
            currentConfig = null
            
            Log.d(TAG, "Internal camera stopped successfully")
            cameraStateListener?.onCameraClosed()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping internal camera", e)
            cameraStateListener?.onCameraError("Failed to stop camera: ${e.message}")
        }
    }

    override fun startRecording(outputPath: String, callback: RecordingStateListener) {
        if (videoCapture == null) {
            callback.onRecordingError("Video capture not initialized")
            return
        }
        if (isCurrentlyRecording) {
            callback.onRecordingError("Already recording")
            return
        }
        this.recordingStateListener = callback
        
        try {
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()
            val outputOptions = FileOutputOptions.Builder(outputFile).build()
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
        if (!isCurrentlyRecording) {
            Log.w(TAG, "Not currently recording")
            return
        }
        
        try {
            currentRecording?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
            recordingStateListener?.onRecordingError("Error stopping recording: ${e.message}")
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
    
    override fun setDetectionFrameCallback(callback: DetectionFrameListener?) {
        this.detectionFrameListener = callback
        Log.d(TAG, "Detection frame callback set: ${callback != null}")
    }

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

    private fun setupUseCases() {
        val config = currentConfig ?: CameraConfig()
        
        // Preview use case
        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()
        
        // Image analysis for frame callbacks (if enabled)
        if (config.enableDetectionFrames) {
            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { analyzer ->
                    analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                        processDetectionFrame(imageProxy)
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

                val rawDataBuffer = ByteArray(detectionBitmapBuffer.byteCount)
                val byteBuffer = java.nio.ByteBuffer.allocate(detectionBitmapBuffer.byteCount)
                detectionBitmapBuffer.copyPixelsToBuffer(byteBuffer)
                byteBuffer.rewind()
                byteBuffer.get(rawDataBuffer)
                
                // Call raw frame callback to match USB camera pipeline
                detectionFrameListener?.onFrameAvailable(
                    data = rawDataBuffer,
                    width = proxy.width,
                    height = proxy.height,
                    format = FrameFormat.RGBA,
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
                recordingStateListener?.onRecordingStarted(outputPath)
            }
            is VideoRecordEvent.Finalize -> {
                Log.d(TAG, "Recording finalized")
                isCurrentlyRecording = false
                currentRecording = null
                
                if (recordEvent.hasError()) {
                    Log.e(TAG, "Recording failed: ${recordEvent.error}")
                    recordingStateListener?.onRecordingError("Recording failed: ${recordEvent.error}")
                } else {
                    Log.d(TAG, "Recording completed successfully: $outputPath")
                    recordingStateListener?.onRecordingStopped(outputPath)
                }
            }
            is VideoRecordEvent.Status -> {
                // Optional: Handle status updates
                Log.d(TAG, "Recording status: ${recordEvent.recordingStats}")
            }
        }
    }
}
