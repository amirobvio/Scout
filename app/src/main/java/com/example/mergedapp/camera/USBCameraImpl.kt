package com.example.mergedapp.camera

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.mergedapp.camera.bridge.AUSBCBridgeFragment
import java.io.File


/**
 * USB Camera implementation using AUSBC library through bridge fragment
 * 
 * This implementation uses a composition-based approach with an internal
 * AUSBC bridge fragment to maintain our clean interface while satisfying
 * AUSBC's framework requirements.
 */
class USBCameraImpl(
    private val context: Context,
    private val usbDevice: UsbDevice,
    private val activity: AppCompatActivity
) : ICamera, AUSBCBridgeFragment.BridgeCallback {

    companion object {
        private const val TAG = "USBCameraImpl"
        private const val BRIDGE_FRAGMENT_TAG = "ausbc_bridge"
    }

    override val cameraType = CameraType.USB
    
    // Bridge fragment that handles all AUSBC complexity
    private var bridgeFragment: AUSBCBridgeFragment? = null
    
    // State management
    private var detectionFrameListener: DetectionFrameListener? = null
    private var recordingStateListener: RecordingStateListener? = null
    private var cameraStateListener: CameraStateListener? = null
    private var currentConfig: CameraConfig? = null
    
    // Recording state
    private var isCurrentlyRecording = false
    private var currentRecordingPath: String? = null

    override fun startCamera(config: CameraConfig) {
        Log.d(TAG, "🚀 Starting USB camera with config: $config")
        
        this.currentConfig = config
        
        try {
            // Only stop existing camera if there's already one running
            if (bridgeFragment != null) {
                stopCamera()
            }

            bridgeFragment = AUSBCBridgeFragment.newInstance(usbDevice, config).apply {
                bridgeCallback = this@USBCameraImpl
            }
            
            // Add fragment to activity with a container
            val fragmentManager = activity.supportFragmentManager
            fragmentManager.beginTransaction()
                .add(android.R.id.content, bridgeFragment!!, BRIDGE_FRAGMENT_TAG)  // Add to content container
                .commit()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start USB camera", e)
            cameraStateListener?.onCameraError("Failed to start camera: ${e.message}")
        }
    }

    override fun stopCamera() {
        Log.d(TAG, "Stopping USB camera")
        
        try {
            // Stop recording if active
            if (isCurrentlyRecording) {
                stopRecording()
            }
            
            // Remove bridge fragment if it exists
            bridgeFragment?.let { fragment ->
                val fragmentManager = activity.supportFragmentManager
                fragmentManager.beginTransaction()
                    .remove(fragment)
                    .commitAllowingStateLoss()
            }
            
            // Clear references
            bridgeFragment = null
            detectionFrameListener = null
            currentConfig = null
            
            Log.d(TAG, "USB camera stopped successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping USB camera", e)
            cameraStateListener?.onCameraError("Failed to stop camera: ${e.message}")
        }
    }

    override fun startRecording(outputPath: String, callback: RecordingStateListener) {

        
        if (bridgeFragment == null) {
            callback.onRecordingError("Camera not initialized")
            return
        }
        
        if (isCurrentlyRecording) {
            callback.onRecordingError("Already recording")
            return
        }
        
        this.recordingStateListener = callback
        this.currentRecordingPath = outputPath
        
        // Ensure output directory exists
        val outputFile = File(outputPath)
        outputFile.parentFile?.mkdirs()
        
        // Start recording through bridge fragment
        bridgeFragment?.startVideoRecording(outputPath) { success, error ->
            if (!success) {
                isCurrentlyRecording = false
                currentRecordingPath = null
                callback.onRecordingError(error ?: "Failed to start recording")
            }
            // Success case is handled in BridgeCallback.onRecordingStarted
        }
    }

    override fun stopRecording() {
        Log.d(TAG, "Stopping recording")
        
        if (!isCurrentlyRecording) {
            Log.w(TAG, "Not currently recording")
            return
        }
        
        bridgeFragment?.stopVideoRecording()
    }

    override fun isRecording(): Boolean {
        return bridgeFragment?.isRecording() ?: false
    }

    override fun isAvailable(): Boolean {
        return bridgeFragment != null
    }

    fun setPreviewVisibility(visible: Boolean) {
        bridgeFragment?.setPreviewVisibility(visible)
    }

    override fun setCameraStateListener(listener: CameraStateListener?) {
        this.cameraStateListener = listener
    }
    override fun setDetectionFrameCallback(callback: DetectionFrameListener?) {
        Log.d(TAG, "🔗 USBCameraImpl.setDetectionFrameCallback: SETTING_CALLBACK - callback=${callback != null}")
        this.detectionFrameListener = callback
        bridgeFragment?.setDetectionFrameCallback(callback)
    }

    override fun onCameraOpened() {
        cameraStateListener?.onCameraOpened()
    }

    override fun onCameraClosed() {
        cameraStateListener?.onCameraClosed()
    }

    override fun onCameraError(error: String) {
        Log.e(TAG, "Camera error through bridge: $error")
        cameraStateListener?.onCameraError(error)
    }

    override fun onFrameAvailable(data: ByteArray, width: Int, height: Int, format: FrameFormat, rotation: Int, timestamp: Long, source: CameraType) {
//        Log.d(TAG, "📥 USBCameraImpl.onRawDetectionFrameAvailable: RAW_FRAME_RECEIVED - ${width}x${height}, format=$format, size=${data.size}")
        detectionFrameListener?.onFrameAvailable(data, width, height, format, rotation, timestamp, source)
    }

    override fun onRecordingStarted(path: String) {
        isCurrentlyRecording = true
        recordingStateListener?.onRecordingStarted(path)
    }

    override fun onRecordingStopped(path: String) {
        isCurrentlyRecording = false
        currentRecordingPath = null
        recordingStateListener?.onRecordingStopped(path)
    }

    override fun onRecordingError(error: String) {
        isCurrentlyRecording = false
        currentRecordingPath = null
        recordingStateListener?.onRecordingError(error)
    }
}