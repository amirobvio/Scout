package com.example.mergedapp.camera

/**
 * Camera interface for both USB and Internal cameras
 * Provides unified API for camera operations
 */
interface ICamera {
    val cameraType: CameraType

    fun startCamera(config: CameraConfig)
    fun stopCamera()
    fun startRecording(outputPath: String, callback: RecordingStateListener)
    fun stopRecording()
    fun isRecording(): Boolean
    fun isAvailable(): Boolean
    fun setCameraStateListener(listener: CameraStateListener?)
    fun setDetectionFrameCallback(callback: DetectionFrameListener?)
}

enum class CameraType {
    USB, INTERNAL
}

data class CameraConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val enableDetectionFrames: Boolean = false,  // Enable optimized detection frame delivery
    val showPreview: Boolean = false  // Control preview rendering - false enables offscreen mode for recording-only
)


enum class FrameFormat {
    NV21, RGBA
}


interface DetectionFrameListener {
    fun onFrameAvailable(data: ByteArray, width: Int, height: Int, format: FrameFormat, rotation: Int, timestamp: Long, source: CameraType)
}

interface RecordingStateListener {
    fun onRecordingStarted(outputPath: String)
    fun onRecordingStopped(outputPath: String)
    fun onRecordingError(error: String)
}


interface CameraStateListener {
    fun onCameraOpened()
    fun onCameraClosed()
    fun onCameraError(error: String)
}
