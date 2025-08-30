package com.example.mergedapp.camera

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
}

enum class CameraType {
    USB, INTERNAL
}

data class CameraConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val enableFrameCallback: Boolean = false,
    val pushFramesToQueue: Boolean = false  // Enable frame pushing to detection queue
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
