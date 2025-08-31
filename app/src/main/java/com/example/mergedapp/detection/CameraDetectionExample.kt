package com.example.mergedapp.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.mergedapp.detection.DetectionModule
import com.example.mergedapp.detection.DetectionUtils
import org.tensorflow.lite.examples.objectdetection.detectors.ObjectDetection
import java.util.concurrent.atomic.AtomicLong

/**
 * Example showing how to integrate DetectionModule with camera implementations
 * 
 * This demonstrates the pattern for:
 * 1. Processing frames from USB/Internal cameras
 * 2. Handling detection results
 * 3. Implementing frame interval logic
 */
class CameraDetectionExample(
    private val context: Context
) : DetectionModule.DetectionListener {
    
    companion object {
        private const val TAG = "CameraDetectionExample"
        
        // Process every 15th frame for detection (same as original detection_test)
        private const val DETECTION_FRAME_INTERVAL = 15L
    }
    
    // Detection system
    private val detectionModule = DetectionModule(context, this)
    
    // Frame counting for detection interval
    private val frameCounter = AtomicLong(0)
    
    fun initialize() {
        Log.d(TAG, "Initializing camera detection example")
        detectionModule.initialize()
    }
    
    /**
     * This would be called from your camera implementations
     * when a new frame is available
     */
    fun onCameraFrameAvailable(bitmap: Bitmap, rotation: Int = 0) {
        // Increment frame counter
        val currentFrame = frameCounter.incrementAndGet()
        
        // Only process every Nth frame for detection
        if (currentFrame % DETECTION_FRAME_INTERVAL == 0L) {
            Log.d(TAG, "Processing frame $currentFrame for detection")
            
            // This returns immediately - processing happens asynchronously
            detectionModule.processFrameAsync(bitmap, rotation)
        }
    }
    
    /**
     * Example: USB Camera integration
     * This shows how you'd integrate with your USBCameraImpl
     */
    fun onUSBCameraFrame(frameData: ByteArray, width: Int, height: Int) {
        // Convert USB frame to Bitmap (you'll implement this conversion)
        val bitmap = convertUSBFrameToBitmap(frameData, width, height)
        onCameraFrameAvailable(bitmap, 0)
    }
    
    /**
     * Example: Internal Camera integration  
     * This shows how you'd integrate with your InternalCameraImpl
     */
    fun onInternalCameraFrame(bitmap: Bitmap, rotation: Int) {
        onCameraFrameAvailable(bitmap, rotation)
    }
    
    // DetectionModule.DetectionListener implementation
    override fun onDetectionResults(
        results: List<ObjectDetection>,
        inferenceTime: Long,
        imageWidth: Int,
        imageHeight: Int
    ) {
        Log.d(TAG, "Detection completed in ${inferenceTime}ms: ${DetectionUtils.formatDetectionResults(results)}")
        
        // Example: Check for specific objects (like laptop from original detection_test)
        if (DetectionUtils.hasObjectType(results, "laptop")) {
            Log.d(TAG, "Laptop detected! Frame: ${frameCounter.get()}")
            onLaptopDetected(results)
        }
        
        // Example: Check for person detection
        if (DetectionUtils.hasObjectType(results, "person")) {
            Log.d(TAG, "Person detected!")
            onPersonDetected(results)
        }
        
        // Example: Process high-confidence detections only
        val highConfidenceDetections = DetectionUtils.filterByConfidence(results, 0.7f)
        if (highConfidenceDetections.isNotEmpty()) {
            Log.d(TAG, "High confidence detections: ${DetectionUtils.formatDetectionResults(highConfidenceDetections)}")
        }
    }
    
    override fun onDetectionError(error: String) {
        Log.e(TAG, "Detection error: $error")
        // Handle detection errors (retry, fallback, notify user, etc.)
    }
    
    /**
     * Example handlers for specific object types
     */
    private fun onLaptopDetected(detections: List<ObjectDetection>) {
        // This is where you'd trigger recording or other actions
        Log.d(TAG, "Laptop detection handler triggered")
        
        // Example: Get the laptop detection with highest confidence
        val laptopDetections = DetectionUtils.getObjectsOfType(detections, "laptop")
        val bestLaptop = DetectionUtils.getHighestConfidenceDetection(laptopDetections)
        
        bestLaptop?.let { detection ->
            Log.d(TAG, "Best laptop detection: confidence=${detection.category.confidence}, " +
                       "box=${detection.boundingBox}")
        }
        
        // TODO: Integration with DetectionRecorder (your next phase)
        // detectionRecorder.onObjectDetected("laptop", detections)
    }
    
    private fun onPersonDetected(detections: List<ObjectDetection>) {
        Log.d(TAG, "Person detection handler triggered")
        // Similar logic for person detection
    }
    
    /**
     * Placeholder for USB frame conversion
     * You'll need to implement this based on your USB camera format
     */
    private fun convertUSBFrameToBitmap(frameData: ByteArray, width: Int, height: Int): Bitmap {
        // TODO: Implement USB frame to Bitmap conversion
        // This depends on the format of frames from your USB camera
        // Common formats: YUV420, NV21, RGB, etc.
        
        // For now, create a dummy bitmap - replace with actual conversion
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }
    
    /**
     * Get current detection configuration
     */
    fun getDetectionInfo(): String {
        return "Detection Module Ready: ${detectionModule.isReady()}, " +
               "Frames Processed: ${frameCounter.get()}, " +
               "Detection Interval: Every ${DETECTION_FRAME_INTERVAL}th frame"
    }
    
    /**
     * Update detection settings
     */
    fun updateDetectionSettings(
        confidenceThreshold: Float? = null,
        model: Int? = null,
        delegate: Int? = null
    ) {
        detectionModule.updateConfiguration(
            threshold = confidenceThreshold,
            model = model,
            delegate = delegate
        )
    }
    
    fun shutdown() {
        Log.d(TAG, "Shutting down camera detection example")
        detectionModule.shutdown()
    }
}
