package com.example.mergedapp.camera

import android.graphics.Bitmap

/**
 * Camera interface for both USB and Internal cameras
 * Provides unified API for camera operations
 */
interface ICamera {
    val cameraType: CameraType
    
    /**
     * Start camera with configuration and optional frame callback
     */
    fun startCamera(config: CameraConfig, frameCallback: FrameCallback? = null)
    
    /**
     * Stop camera preview
     */
    fun stopCamera()
    
    /**
     * Start video recording to specified path
     */
    fun startRecording(outputPath: String, callback: RecordingCallback)
    
    /**
     * Stop video recording
     */
    fun stopRecording()
    
    /**
     * Check if currently recording
     */
    fun isRecording(): Boolean
    
    /**
     * Check if camera is available
     */
    fun isAvailable(): Boolean
    
    /**
     * Set camera state listener
     */
    fun setCameraStateListener(listener: CameraStateListener?)
    
    /**
     * Set detection frame callback for optimized frame delivery
     * This bypasses CameraFrame conversion for better performance
     */
    fun setDetectionFrameCallback(callback: DetectionFrameCallback?)
}

enum class CameraType {
    USB, INTERNAL
}

data class CameraConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val enableFrameCallback: Boolean = false,
    val enableDetectionFrames: Boolean = false,  // Enable optimized detection frame delivery
    val showPreview: Boolean = false  // Control preview rendering - false enables offscreen mode for recording-only
)

data class CameraFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val format: FrameFormat,
    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CameraFrame

        if (!data.contentEquals(other.data)) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (format != other.format) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + format.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

enum class FrameFormat {
    NV21, RGBA
}

/**
 * Callback for receiving camera frames
 */
interface FrameCallback {
    fun onFrameAvailable(frame: CameraFrame)
}

/**
 * Optimized callback for detection frames
 * Delivers frames directly as Bitmap for efficient detection processing
 */
interface DetectionFrameCallback {
    /**
     * Called when a frame is available for detection processing
     * @param bitmap Frame data as Bitmap (ready for TensorFlow Lite)
     * @param rotation Image rotation in degrees (0, 90, 180, 270)
     * @param timestamp Frame timestamp in milliseconds
     */
    fun onDetectionFrameAvailable(bitmap: Bitmap, rotation: Int, timestamp: Long, source: CameraType)
}

/**
 * Callback for recording events
 */
interface RecordingCallback {
    fun onRecordingStarted(outputPath: String)
    fun onRecordingStopped(outputPath: String)
    fun onRecordingError(error: String)
}

/**
 * Callback for camera state changes
 */
interface CameraStateListener {
    fun onCameraOpened()
    fun onCameraClosed()
    fun onCameraError(error: String)
}
