package com.example.mergedapp.camera

import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import java.io.File

/**
 * USB Camera implementation that directly extends AUSBC's CameraFragment
 * 
 * This merged implementation combines the previous USBCameraImpl and AUSBCBridgeFragment
 * into a single, cleaner class that:
 * - Implements ICamera interface for compatibility
 * - Extends CameraFragment for AUSBC framework compliance
 * - Eliminates redundant callback forwarding layers
 */
class USBCameraFragment : CameraFragment(), ICamera {
    
    companion object {
        private const val TAG = "USBCameraFragment"
        
        fun newInstance(usbDevice: UsbDevice, config: CameraConfig, activity: AppCompatActivity): USBCameraFragment {
            return USBCameraFragment().apply {
                this.targetUsbDevice = usbDevice
                this.cameraConfig = config
                this.activityContext = activity
            }
        }
    }
    
    // Configuration
    private lateinit var targetUsbDevice: UsbDevice
    private lateinit var cameraConfig: CameraConfig
    private lateinit var activityContext: AppCompatActivity
    
    // ICamera implementation
    override val cameraType = CameraType.USB
    
    // Preview surface removed for offscreen mode - saves RAM
    
    // Callbacks
    private var detectionFrameCallback: DetectionFrameCallback? = null
    private var recordingCallback: RecordingCallback? = null
    private var cameraStateListener: CameraStateListener? = null
    
    // Recording state
    private var isCurrentlyRecording = false
    private var currentRecordingPath: String? = null
    
    // Frame tracking for debugging
    private var frameCount = 0L
    private var lastFrameLogTime = 0L
    
    // ICamera interface implementation
    override fun startCamera(config: CameraConfig) {
        // Camera is started automatically by AUSBC when fragment is added
        // This method exists for interface compatibility
        Log.d(TAG, "startCamera called - camera will start when fragment is ready")
    }
    
    override fun stopCamera() {
        try {
            if (isCurrentlyRecording) {
                stopRecording()
            }
            
            getCurrentCamera()?.closeCamera()
            Log.d(TAG, "USB camera stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping USB camera", e)
            cameraStateListener?.onCameraError("Failed to stop camera: ${e.message}")
        }
    }
    
    override fun startRecording(outputPath: String, callback: RecordingCallback) {
        if (getCurrentCamera() == null) {
            callback.onRecordingError("Camera not initialized")
            return
        }
        
        if (isCurrentlyRecording) {
            callback.onRecordingError("Already recording")
            return
        }
        
        this.recordingCallback = callback
        this.currentRecordingPath = outputPath
        
        // Ensure output directory exists
        val outputFile = File(outputPath)
        outputFile.parentFile?.mkdirs()
        
        // Start recording through AUSBC
        startVideoRecording(outputPath) { success, error ->
            if (!success) {
                isCurrentlyRecording = false
                currentRecordingPath = null
                callback.onRecordingError(error ?: "Failed to start recording")
            }
        }
    }
    
    override fun stopRecording() {
        Log.d(TAG, "Stopping recording")
        
        if (!isCurrentlyRecording) {
            Log.w(TAG, "Not currently recording")
            return
        }
        
        stopVideoRecording()
    }
    
    override fun isRecording(): Boolean {
        return getCurrentCamera()?.isRecording() == true
    }
    
    override fun isAvailable(): Boolean {
        return getCurrentCamera() != null
    }
    
    override fun setCameraStateListener(listener: CameraStateListener?) {
        this.cameraStateListener = listener
    }
    
    override fun setDetectionFrameCallback(callback: DetectionFrameCallback?) {
        this.detectionFrameCallback = callback
        Log.d(TAG, "Detection frame callback set: ${callback != null}")
    }
    
    // AUSBC CameraFragment required methods
    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View? {
        return if (cameraConfig.showPreview) {
            Log.d(TAG, "Creating root view with preview enabled")
            // Create container for preview surface that fits within parent constraints
            FrameLayout(requireContext()).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(0xFF000000.toInt()) // Black background
                
                // Add preview label
                val previewLabel = TextView(requireContext()).apply {
                    text = "📹 USB Camera Preview"
                    textSize = 14f
                    setTextColor(0xFF4CAF50.toInt())
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                    setPadding(0, 8, 0, 8)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP
                    )
                }
                addView(previewLabel)
            }
        } else {
            Log.d(TAG, "Creating root view for offscreen mode (no preview)")
            // Return minimal invisible view for offscreen mode
            // This ensures camera works without any preview surface, saving significant RAM
            View(requireContext()).apply {
                layoutParams = ViewGroup.LayoutParams(0, 0)
                visibility = View.GONE
            }
        }
    }
    
    override fun getCameraView(): IAspectRatio? {
        return if (cameraConfig.showPreview) {
            Log.d(TAG, "Creating camera preview surface")
            // Create preview surface for live feed that respects container bounds
            AspectRatioTextureView(requireContext()).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                ).apply {
                    topMargin = 40 // Leave space for label
                }
                setAspectRatio(cameraConfig.width, cameraConfig.height)
            }
        } else {
            Log.d(TAG, "No preview surface needed for offscreen mode")
            // Return null for offscreen mode - no preview surface needed
            null
        }
    }
    
    override fun getCameraViewContainer(): ViewGroup? {
        return if (cameraConfig.showPreview) {
            Log.d(TAG, "Providing camera view container for preview")
            // Return the root view as container when preview is enabled
            view as? ViewGroup
        } else {
            Log.d(TAG, "No container needed for offscreen mode")
            // No container needed for offscreen mode
            null
        }
    }
    
    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(cameraConfig.width)
            .setPreviewHeight(cameraConfig.height)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.SOURCE_AUTO)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
            .setAspectRatioShow(false)  // No aspect ratio needed in offscreen mode
            .setCaptureRawImage(false)
            .setRawPreviewData(true)  // TRUE = Enable raw frames for detection even without preview
            .create()
    }
    
    override fun getDefaultCamera(): UsbDevice? {
        Log.d(TAG, "Returning target USB device: ${targetUsbDevice.deviceName}")
        return targetUsbDevice
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mode = if (cameraConfig.showPreview) "PREVIEW MODE" else "OFFSCREEN MODE"
        Log.d(TAG, "onViewCreated - Device: ${targetUsbDevice.deviceName} ($mode)")
        
        // Enable AUSBC debugging
        try {
            com.jiangdg.ausbc.utils.Utils.debugCamera = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not enable AUSBC debugging: ${e.message}")
        }
        
        if (cameraConfig.showPreview) {
            Log.i(TAG, "📺 USB Camera Preview ENABLED - Live feed will be displayed")
        } else {
            Log.i(TAG, "📺 USB Camera Preview DISABLED - Running in offscreen mode (saves RAM)")
        }
    }
    
    override fun onCameraState(
        self: com.jiangdg.ausbc.MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                Log.i(TAG, "✅ USB camera opened successfully in OFFSCREEN mode (no preview)")
                Log.i(TAG, "📷 Setting up frame callback for detection...")
                setupPreviewCallback()
                cameraStateListener?.onCameraOpened()
            }
            ICameraStateCallBack.State.CLOSED -> {
                Log.i(TAG, "🔴 USB camera closed - Total frames received: $frameCount")
                frameCount = 0
                cameraStateListener?.onCameraClosed()
            }
            ICameraStateCallBack.State.ERROR -> {
                Log.e(TAG, "❌ USB camera error: $msg")
                cameraStateListener?.onCameraError(msg ?: "Unknown camera error")
            }
        }
    }
    
    private fun setupPreviewCallback() {
        if (!cameraConfig.enableDetectionFrames) {
            Log.w(TAG, "⚠️ Detection frames disabled in config")
            return
        }
        
        Log.d(TAG, "🔧 Setting up frame callback for OFFSCREEN mode (no preview surface)")
        
        val previewCallback = object : IPreviewDataCallBack {
            override fun onPreviewData(data: ByteArray?, width: Int, height: Int, format: IPreviewDataCallBack.DataFormat) {
                if (data != null && cameraConfig.enableDetectionFrames) {
                    frameCount++
                    
                    // Log every 30 frames to confirm flow
                    if (frameCount % 30 == 0L) {
                        val currentTime = System.currentTimeMillis()
                        val timeDiff = if (lastFrameLogTime > 0) currentTime - lastFrameLogTime else 0
                        val fps = if (timeDiff > 0) (30 * 1000.0 / timeDiff) else 0.0
                        
                        Log.d(TAG, "📊 USB Frame Flow [OFFSCREEN]: #$frameCount | ${width}x${height} | " +
                              "Format: $format | FPS: %.1f".format(fps))
                        lastFrameLogTime = currentTime
                    }
                    
                    processDetectionFrame(data, width, height, format)
                } else if (data == null) {
                    Log.w(TAG, "⚠️ Received null frame data")
                }
            }
        }
        
        addPreviewDataCallBack(previewCallback)
        Log.i(TAG, "✅ Frame callback registered successfully for OFFSCREEN mode")
    }
    
    private fun processDetectionFrame(data: ByteArray, width: Int, height: Int, format: IPreviewDataCallBack.DataFormat) {
        try {
            val frameFormat = when (format) {
                IPreviewDataCallBack.DataFormat.RGBA -> FrameFormat.RGBA
                IPreviewDataCallBack.DataFormat.NV21 -> FrameFormat.NV21
            }
            
            // First frame log
            if (frameCount == 1L) {
                Log.i(TAG, "🎉 First USB frame received in OFFSCREEN mode! Format: $frameFormat, Size: ${width}x${height}")
            }
            
            // Direct callback to detection system - no intermediate forwarding
            detectionFrameCallback?.onRawFrameAvailable(
                data = data,
                width = width,
                height = height,
                format = frameFormat,
                rotation = 0,
                timestamp = System.currentTimeMillis(),
                source = CameraType.USB
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing detection frame: ${e.message}", e)
        }
    }
    
    // Video recording helpers
    private fun startVideoRecording(outputPath: String, callback: (Boolean, String?) -> Unit) {
        try {
            val camera = getCurrentCamera()
            if (camera == null) {
                callback(false, "Camera not available")
                return
            }
            
            Log.d(TAG, "Starting USB recording (will move to custom path after completion): $outputPath")
            
            // AUSBC doesn't support custom output path directly, so we'll move the file after recording
            camera.captureVideoStart(
                object : com.jiangdg.ausbc.callback.ICaptureCallBack {
                    override fun onBegin() {
                        Log.d(TAG, "USB video recording started (AUSBC default path)")
                        isCurrentlyRecording = true
                        recordingCallback?.onRecordingStarted(outputPath) // Report our intended path
                        callback(true, null)
                    }
                    
                    override fun onError(error: String?) {
                        Log.e(TAG, "USB video recording error: $error")
                        isCurrentlyRecording = false
                        recordingCallback?.onRecordingError(error ?: "Recording failed")
                        callback(false, error)
                    }
                    
                    override fun onComplete(path: String?) {
                        Log.d(TAG, "USB video recording completed at AUSBC path: $path")
                        isCurrentlyRecording = false
                        currentRecordingPath = null
                        
                        // Move file from AUSBC path to our organized path
                        moveRecordedFile(path, outputPath)
                    }
                }
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start USB recording", e)
            callback(false, e.message)
        }
    }
    
    private fun moveRecordedFile(sourceAUSBCPath: String?, targetPath: String) {
        try {
            if (sourceAUSBCPath.isNullOrEmpty()) {
                Log.e(TAG, "AUSBC source path is null or empty, cannot move file")
                recordingCallback?.onRecordingStopped(targetPath) // Still report our target path
                return
            }
            
            val sourceFile = File(sourceAUSBCPath)
            if (!sourceFile.exists()) {
                Log.e(TAG, "AUSBC recorded file doesn't exist: $sourceAUSBCPath")
                recordingCallback?.onRecordingStopped(targetPath) // Still report our target path
                return
            }
            
            // Ensure target directory exists
            val targetFile = File(targetPath)
            targetFile.parentFile?.mkdirs()
            
            // Move file from AUSBC location to our organized location
            if (sourceFile.renameTo(targetFile)) {
                Log.d(TAG, "Successfully moved USB recording from $sourceAUSBCPath to $targetPath")
                recordingCallback?.onRecordingStopped(targetPath)
            } else {
                Log.e(TAG, "Failed to move USB recording from $sourceAUSBCPath to $targetPath")
                // Fallback: copy file if move fails
                try {
                    sourceFile.copyTo(targetFile, overwrite = true)
                    sourceFile.delete() // Delete original after successful copy
                    Log.d(TAG, "Successfully copied USB recording from $sourceAUSBCPath to $targetPath")
                    recordingCallback?.onRecordingStopped(targetPath)
                } catch (copyException: Exception) {
                    Log.e(TAG, "Failed to copy USB recording: ${copyException.message}")
                    recordingCallback?.onRecordingStopped(sourceAUSBCPath) // Use original path as fallback
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error moving USB recorded file: ${e.message}", e)
            recordingCallback?.onRecordingStopped(sourceAUSBCPath ?: targetPath)
        }
    }
    
    private fun stopVideoRecording() {
        try {
            getCurrentCamera()?.captureVideoStop()
            Log.d(TAG, "Video recording stop requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
            recordingCallback?.onRecordingError("Failed to stop recording: ${e.message}")
        }
    }
    
    override fun onDestroyView() {
        Log.d(TAG, "onDestroyView - cleaning up")
        detectionFrameCallback = null
        recordingCallback = null
        cameraStateListener = null
        super.onDestroyView()
    }
}
