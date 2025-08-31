package com.example.mergedapp.detection

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.examples.objectdetection.detectors.ObjectDetection
import org.tensorflow.lite.examples.objectdetection.detectors.ObjectDetector
import org.tensorflow.lite.examples.objectdetection.detectors.TaskVisionDetector
import org.tensorflow.lite.examples.objectdetection.detectors.YoloDetector
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector.ObjectDetectorOptions
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DetectionModule - Unified object detection system for MergedApp
 * 
 * This class combines the functionality of ObjectDetectorWrapper and DetectionModule
 * into a single, efficient implementation that provides:
 * 
 * - Direct TensorFlow Lite model management
 * - Async frame processing for non-blocking camera operations
 * - Multiple model support (YOLO, EfficientDet, MobileNet)
 * - GPU acceleration and configurable hardware delegates
 * - Thread-safe operations with proper state management
 * - Configurable detection settings via metadata.yaml
 */
class DetectionModule(
    private val context: Context,
    private val detectionListener: DetectionListener? = null
) {
    
    companion object {
        private const val TAG = "DetectionModule"
        
        // Hardware delegates
        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1
        const val DELEGATE_NNAPI = 2
        
        // Detection models
        const val MODEL_MOBILENETV1 = 0
        const val MODEL_EFFICIENTDETV0 = 1
        const val MODEL_EFFICIENTDETV1 = 2
        const val MODEL_EFFICIENTDETV2 = 3
        const val MODEL_YOLO = 4
    }
    
    // Threading and state management
    private val detectionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isInitialized = AtomicBoolean(false)
    
    // Core detection components
    private var objectDetector: ObjectDetector? = null
    
    // Configuration parameters
    private var threshold: Float = 0.5f
    private var numThreads: Int = 2
    private var maxResults: Int = 3
    private var currentDelegate: Int = DELEGATE_CPU
    private var currentModel: Int = MODEL_YOLO // Default to YOLO which uses metadata.yaml config
    
    /**
     * Interface for receiving detection results
     */
    interface DetectionListener {
        /**
         * Called when objects are detected in a frame
         * @param results List of detected objects
         * @param inferenceTime Time taken for detection in milliseconds
         * @param imageWidth Width of the processed image
         * @param imageHeight Height of the processed image
         */
        fun onDetectionResults(
            results: List<ObjectDetection>,
            inferenceTime: Long,
            imageWidth: Int,
            imageHeight: Int
        )
        
        /**
         * Called when detection processing encounters an error
         * @param error Error message
         */
        fun onDetectionError(error: String)
    }
    
    /**
     * Initialize the detection system
     * This should be called before processing any frames
     */
    fun initialize() {
        if (isInitialized.get()) {
            Log.w(TAG, "DetectionModule already initialized")
            return
        }
        
        try {
            setupObjectDetector()
            isInitialized.set(true)
            Log.d(TAG, "DetectionModule initialized successfully with model: $currentModel")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DetectionModule", e)
            detectionListener?.onDetectionError("Failed to initialize detection: ${e.message}")
        }
    }
    
    /**
     * Set up the object detector based on current configuration
     */
    private fun setupObjectDetector() {
        try {
            if (currentModel == MODEL_YOLO) {
                // YoloDetector reads all settings from metadata.yaml
                objectDetector = YoloDetector(context = context)
                Log.d(TAG, "Initialized YOLO detector with YAML configuration")
                
            } else {
                // Create the base options for other TensorFlow Lite models
                val optionsBuilder = ObjectDetectorOptions.builder()
                    .setScoreThreshold(threshold)
                    .setMaxResults(maxResults)

                // Set general detection options, including number of used threads
                val baseOptionsBuilder = BaseOptions.builder().setNumThreads(numThreads)

                // Configure hardware delegate
                when (currentDelegate) {
                    DELEGATE_CPU -> {
                        // Default - no additional configuration needed
                        Log.d(TAG, "Using CPU delegate")
                    }
                    DELEGATE_GPU -> {
                        baseOptionsBuilder.useGpu()
                        Log.d(TAG, "Using GPU delegate")
                    }
                    DELEGATE_NNAPI -> {
                        baseOptionsBuilder.useNnapi()
                        Log.d(TAG, "Using NNAPI delegate")
                    }
                }

                optionsBuilder.setBaseOptions(baseOptionsBuilder.build())
                val options = optionsBuilder.build()

                objectDetector = TaskVisionDetector(
                    options = options,
                    currentModel = currentModel,
                    context = context
                )
                
                Log.d(TAG, "Initialized TaskVision detector with model: $currentModel")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up object detector", e)
            throw e
        }
    }
    
    /**
     * Process a frame for object detection (Async - Non-blocking)
     * This method returns immediately and processes the frame on a background thread
     * 
     * @param bitmap The frame to process as a Bitmap
     * @param rotation Image rotation in degrees (0, 90, 180, 270)
     */
    fun processFrameAsync(bitmap: Bitmap, rotation: Int = 0) {
        if (!isInitialized.get()) {
            Log.w(TAG, "DetectionModule not initialized. Call initialize() first.")
            return
        }
        
        // Submit frame processing to background thread - returns immediately
        detectionExecutor.submit {
            try {
                performDetection(bitmap, rotation)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame", e)
                mainHandler.post {
                    detectionListener?.onDetectionError("Frame processing error: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Process a frame for object detection (Sync - Blocking)
     * Use this only when you need immediate results and can handle blocking
     * 
     * @param bitmap The frame to process as a Bitmap
     * @param rotation Image rotation in degrees (0, 90, 180, 270)
     * @return DetectionResults containing all detection information
     */
    fun processFrameSync(bitmap: Bitmap, rotation: Int = 0): DetectionResults? {
        if (!isInitialized.get()) {
            Log.w(TAG, "DetectionModule not initialized. Call initialize() first.")
            return null
        }
        
        return try {
            var detectionResults: DetectionResults? = null
            val syncLock = Object()
            
            // Create temporary listener for sync operation
            val tempListener = object : DetectionListener {
                override fun onDetectionResults(
                    results: List<ObjectDetection>,
                    inferenceTime: Long,
                    imageWidth: Int,
                    imageHeight: Int
                ) {
                    synchronized(syncLock) {
                        detectionResults = DetectionResults(
                            detections = results,
                            hasDetection = results.isNotEmpty(),
                            inferenceTime = inferenceTime,
                            imageWidth = imageWidth,
                            imageHeight = imageHeight,
                            timestamp = System.currentTimeMillis()
                        )
                        syncLock.notify()
                    }
                }
                
                override fun onDetectionError(error: String) {
                    synchronized(syncLock) {
                        Log.e(TAG, "Sync detection error: $error")
                        syncLock.notify()
                    }
                }
            }
            
            // Perform detection with temporary listener
            val originalListener = detectionListener
            performDetectionWithListener(bitmap, rotation, tempListener)
            
            // Wait for result
            synchronized(syncLock) {
                syncLock.wait(5000) // 5 second timeout
            }
            
            detectionResults
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in sync frame processing", e)
            null
        }
    }
    
    /**
     * Core detection logic that processes the bitmap and calls listener
     */
    private fun performDetection(bitmap: Bitmap, rotation: Int) {
        performDetectionWithListener(bitmap, rotation, detectionListener)
    }
    
    /**
     * Core detection logic with specific listener
     */
    private fun performDetectionWithListener(
        bitmap: Bitmap, 
        rotation: Int, 
        listener: DetectionListener?
    ) {
        if (objectDetector == null) {
            setupObjectDetector()
        }
        
        try {
            // Create preprocessor for the image
            val imageProcessor = ImageProcessor.Builder()
                .add(Rot90Op(-rotation / 90))
                .build()

            // Preprocess the image and convert it into a TensorImage for detection
            val tensorImage = imageProcessor.process(TensorImage.fromBitmap(bitmap))

            // Measure inference time
            var inferenceTime = SystemClock.uptimeMillis()
            
            // Perform detection
            val results = objectDetector?.detect(tensorImage, rotation)
            
            inferenceTime = SystemClock.uptimeMillis() - inferenceTime

            // Process results
            if (results != null) {
                Log.d(TAG, "Detection completed: ${results.detections.size} objects found in ${inferenceTime}ms")
                
                // Call listener on main thread
                mainHandler.post {
                    listener?.onDetectionResults(
                        results = results.detections,
                        inferenceTime = inferenceTime,
                        imageWidth = results.image.width,
                        imageHeight = results.image.height
                    )
                }
            } else {
                Log.w(TAG, "Detection returned null results")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during detection", e)
            mainHandler.post {
                listener?.onDetectionError("Detection error: ${e.message}")
            }
        }
    }
    
    /**
     * Update detection configuration
     * Note: Changes will take effect on next initialization
     */
    fun updateConfiguration(
        threshold: Float? = null,
        numThreads: Int? = null,
        maxResults: Int? = null,
        delegate: Int? = null,
        model: Int? = null
    ) {
        threshold?.let { this.threshold = it }
        numThreads?.let { this.numThreads = it }
        maxResults?.let { this.maxResults = it }
        delegate?.let { this.currentDelegate = it }
        model?.let { this.currentModel = it }
        
        Log.d(TAG, "Configuration updated - threshold: $threshold, model: $currentModel, delegate: $currentDelegate")
        
        // If already initialized, reinitialize with new settings
        if (isInitialized.get()) {
            shutdown()
            initialize()
        }
    }
    
    /**
     * Get current configuration as a readable string
     */
    fun getConfigurationInfo(): String {
        val modelName = when (currentModel) {
            MODEL_YOLO -> "YOLO"
            MODEL_MOBILENETV1 -> "MobileNetV1"
            MODEL_EFFICIENTDETV0 -> "EfficientDet-Lite0"
            MODEL_EFFICIENTDETV1 -> "EfficientDet-Lite1"
            MODEL_EFFICIENTDETV2 -> "EfficientDet-Lite2"
            else -> "Unknown"
        }
        
        val delegateName = when (currentDelegate) {
            DELEGATE_CPU -> "CPU"
            DELEGATE_GPU -> "GPU"
            DELEGATE_NNAPI -> "NNAPI"
            else -> "Unknown"
        }
        
        return "Model: $modelName, Delegate: $delegateName, Threshold: $threshold, Threads: $numThreads, MaxResults: $maxResults"
    }
    
    /**
     * Check if detection system is ready to process frames
     */
    fun isReady(): Boolean = isInitialized.get()
    
    /**
     * Clear the current detector and force reinitialization on next use
     */
    fun clearDetector() {
        objectDetector = null
        Log.d(TAG, "Object detector cleared")
    }
    
    /**
     * Shutdown the detection system and cleanup resources
     */
    fun shutdown() {
        try {
            isInitialized.set(false)
            objectDetector = null
            detectionExecutor.shutdown()
            Log.d(TAG, "DetectionModule shutdown completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
        }
    }
}

/**
 * Data class to hold detection results
 */
data class DetectionResults(
    val detections: List<ObjectDetection>,
    val hasDetection: Boolean,
    val inferenceTime: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val timestamp: Long
)