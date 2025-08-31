package com.example.mergedapp.camera.bridge

import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.util.Log
import android.view.*       
import android.widget.FrameLayout
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import com.example.mergedapp.camera.CameraConfig

/**
 * Internal AUSBC Bridge Fragment
 * 
 * This fragment extends AUSBC's CameraFragment and acts as a bridge between
 * our clean ICamera interface and AUSBC's framework requirements.
 * 
 * It's designed to be invisible or minimal - just providing the necessary
 * framework compliance for AUSBC to work properly.
 */
internal class AUSBCBridgeFragment : CameraFragment() {
    
    companion object {
        private const val TAG = "AUSBCBridgeFragment"
        
        fun newInstance(usbDevice: UsbDevice, config: CameraConfig): AUSBCBridgeFragment {
            return AUSBCBridgeFragment().apply {
                this.targetUsbDevice = usbDevice
                this.cameraConfig = config
            }
        }
    }
    
    // Configuration passed from USBCameraImpl
    private lateinit var targetUsbDevice: UsbDevice
    private lateinit var cameraConfig: CameraConfig
    
    // Preview surface for AUSBC
    private var previewSurface: AspectRatioTextureView? = null
    
    // Callbacks to communicate back to USBCameraImpl
    var bridgeCallback: BridgeCallback? = null
    
    interface BridgeCallback {
        fun onCameraOpened()
        fun onCameraClosed() 
        fun onCameraError(error: String)
        fun onFrameAvailable(data: ByteArray, width: Int, height: Int)
        fun onRecordingStarted(path: String)
        fun onRecordingStopped(path: String)
        fun onRecordingError(error: String)
    }
    
    /**
     * BaseFragment requires this method - called by AUSBC framework
     */
    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View? {
        Log.d(TAG, "AUSBCBridgeFragment.getRootView: Creating AUSBC bridge fragment root view")
        
        // Create minimal layout with preview surface
        val rootLayout = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            
            // Make visible for AUSBC to work properly - AUSBC might not initialize hidden cameras
            visibility = View.VISIBLE
            setBackgroundColor(0x80000000.toInt()) // Semi-transparent background for debugging
        }
        
        // Create preview surface that AUSBC needs
        previewSurface = AspectRatioTextureView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                cameraConfig.width,
                cameraConfig.height
            ).apply {
                gravity = Gravity.CENTER
            }
        }
        
        rootLayout.addView(previewSurface)
        
        Log.d(TAG, "AUSBCBridgeFragment.getRootView: Bridge fragment root view created with preview surface")
        return rootLayout
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        Log.d(TAG, "AUSBCBridgeFragment.onViewCreated: 🎬 Bridge fragment onViewCreated called")
        Log.d(TAG, "AUSBCBridgeFragment.onViewCreated:   Device: ${targetUsbDevice.deviceName}")
        Log.d(TAG, "AUSBCBridgeFragment.onViewCreated:   View: ${view != null}")
        Log.d(TAG, "AUSBCBridgeFragment.onViewCreated:   Preview surface: ${previewSurface != null}")
        
        // Enable AUSBC debugging
        try {
            Log.d(TAG, "AUSBCBridgeFragment.onViewCreated: 🐛 Enabling AUSBC internal debugging...")
            // Enable debug mode for better logging
            com.jiangdg.ausbc.utils.Utils.debugCamera = true
        } catch (e: Exception) {
            Log.w(TAG, "AUSBCBridgeFragment.onViewCreated: Could not enable AUSBC camera debugging: ${e.message}")
        }
        
        // Initialize camera with our target USB device
        initializeCameraForDevice()
        
        // Test if postDelayed works
        view.postDelayed({
            Log.d(TAG, "AUSBCBridgeFragment.onViewCreated: ⏰ PostDelayed callback executed - view is working")
            
            // Also try to make the preview surface visible
            previewSurface?.visibility = View.VISIBLE
            Log.d(TAG, "AUSBCBridgeFragment.onViewCreated: 🖼️ Made preview surface visible")
        }, 500)
    }
    
    /**
     * Override initView to ensure AUSBC camera registration happens
     */
    override fun initView() {
        Log.d(TAG, "AUSBCBridgeFragment.initView: 🔄 AUSBC initView called - checking camera setup...")
        
        // Add detailed logging before calling super
        Log.d(TAG, "AUSBCBridgeFragment.initView:   Preview surface: ${previewSurface != null}")
        Log.d(TAG, "AUSBCBridgeFragment.initView:   Target device: ${targetUsbDevice.deviceName}")
        Log.d(TAG, "AUSBCBridgeFragment.initView:   Config: ${cameraConfig}")
        
        super.initView()
        
        Log.d(TAG, "AUSBCBridgeFragment.initView: ✅ AUSBC initView completed - parent registration should be done")
        
        // Immediate debugging - don't rely on postDelayed
        Log.d(TAG, "AUSBCBridgeFragment.initView: 🔍 Immediate camera status check...")
        
        try {
            val currentCamera = getCurrentCamera()
            Log.d(TAG, "AUSBCBridgeFragment.initView:   Current camera: ${currentCamera != null}")
            
            val deviceList = getDeviceList()
            Log.d(TAG, "AUSBCBridgeFragment.initView:   Available devices: ${deviceList?.size ?: 0}")
            deviceList?.forEach { device ->
                Log.d(TAG, "AUSBCBridgeFragment.initView:     - ${device.deviceName} (${device.productName})")
            }
            
            if (currentCamera == null) {
                Log.w(TAG, "AUSBCBridgeFragment.initView:   ❌ No current camera found immediately after initView")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "AUSBCBridgeFragment.initView: ❌ Error in immediate camera check", e)
        }
        
        // Also add delayed check to see if anything changes
        view?.postDelayed({
            Log.d(TAG, "AUSBCBridgeFragment.initView: ⏰ Delayed check (2s after initView)...")
            
            try {
                val currentCamera = getCurrentCamera()
                Log.d(TAG, "AUSBCBridgeFragment.initView:   Current camera after delay: ${currentCamera != null}")
                
                if (currentCamera != null) {
                    Log.d(TAG, "AUSBCBridgeFragment.initView:   Camera opened: ${currentCamera.isCameraOpened()}")
                    Log.d(TAG, "AUSBCBridgeFragment.initView:   Camera device: ${currentCamera.getUsbDevice()?.deviceName}")
                } else {
                    Log.w(TAG, "AUSBCBridgeFragment.initView:   ❌ Still no camera found after 2 seconds!")
                    
                    // Try manual camera opening as last resort
                    Log.d(TAG, "AUSBCBridgeFragment.initView:   🔧 Attempting manual camera opening...")
                    openCamera(previewSurface)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "AUSBCBridgeFragment.initView: ❌ Error in delayed camera check", e)
                bridgeCallback?.onCameraError("Camera check failed: ${e.message}")
            }
        }, 2000)
    }
    
    /**
     * Initialize camera for our specific USB device
     */
    private fun initializeCameraForDevice() {
        try {
            // Set up preview data callback if frame callback is enabled
            if (cameraConfig.enableFrameCallback) {
                addPreviewDataCallBack(
                    
                object : IPreviewDataCallBack {

                    override fun onPreviewData(data: ByteArray?, width: Int, height: Int, format: IPreviewDataCallBack.DataFormat) {
                        if (data != null) {
                            bridgeCallback?.onFrameAvailable(data, width, height)
                        }
                    }

                }
                
                )
            }
            
            Log.d(TAG, "AUSBCBridgeFragment.initializeCameraForDevice: Preview data callback set up")
            
        } catch (e: Exception) {
            Log.e(TAG, "AUSBCBridgeFragment.initializeCameraForDevice: Failed to initialize camera", e)
            bridgeCallback?.onCameraError("Failed to initialize: ${e.message}")
        }
    }
    
    /**
     * Handle camera state changes and forward to our callback
     */
    private fun handleCameraState(code: ICameraStateCallBack.State, msg: String?) {
        Log.d(TAG, "AUSBCBridgeFragment.handleCameraState: Camera state changed: $code, message: $msg")
        
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                Log.i(TAG, "AUSBCBridgeFragment.handleCameraState: AUSBC camera opened successfully")
                bridgeCallback?.onCameraOpened()
            }
            ICameraStateCallBack.State.CLOSED -> {
                Log.i(TAG, "AUSBCBridgeFragment.handleCameraState: AUSBC camera closed")
                bridgeCallback?.onCameraClosed()
            }
            ICameraStateCallBack.State.ERROR -> {
                Log.e(TAG, "AUSBCBridgeFragment.handleCameraState: AUSBC camera error: $msg")
                bridgeCallback?.onCameraError(msg ?: "Unknown camera error")
            }
        }
    }
    
    /**
     * Get the camera view that AUSBC expects
     * This is called by AUSBC framework
     */
    override fun getCameraView(): IAspectRatio? {
        return previewSurface
    }
    
    /**
     * Get camera view container that AUSBC expects
     * This is called by AUSBC framework
     */
    override fun getCameraViewContainer(): ViewGroup? {
        return view as? ViewGroup
    }
    
    /**
     * Get camera request configuration
     * This is called by AUSBC framework  
     */
    override fun getCameraRequest(): CameraRequest {
        Log.d(TAG, "AUSBCBridgeFragment.getCameraRequest: AUSBC requesting camera config")
        
        return CameraRequest.Builder()
            .setPreviewWidth(cameraConfig.width)
            .setPreviewHeight(cameraConfig.height)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.SOURCE_AUTO) // to mute the audio
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
            .setAspectRatioShow(true)
            .setCaptureRawImage(false) // TODO: Check the source code of ausbc to understand what this does
            .setRawPreviewData(cameraConfig.enableFrameCallback)
            .create()
    }
    
    /**
     * Tell AUSBC which USB device we want to use
     * This is crucial - without this, AUSBC doesn't know which camera to open
     */
    override fun getDefaultCamera(): UsbDevice? {
        Log.d(TAG, "AUSBCBridgeFragment.getDefaultCamera: 🎯 AUSBC requesting default camera - returning: ${targetUsbDevice.deviceName}")
        Log.d(TAG, "AUSBCBridgeFragment.getDefaultCamera:   Device ID: ${targetUsbDevice.deviceId}")
        Log.d(TAG, "AUSBCBridgeFragment.getDefaultCamera:   Product name: ${targetUsbDevice.productName}")
        Log.d(TAG, "AUSBCBridgeFragment.getDefaultCamera:   Vendor ID: ${targetUsbDevice.vendorId}")
        Log.d(TAG, "AUSBCBridgeFragment.getDefaultCamera:   Product ID: ${targetUsbDevice.productId}")
        return targetUsbDevice
    }

    /**
     * Override camera state callback to forward to our bridge callback
     */
    override fun onCameraState(self: com.jiangdg.ausbc.MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?) {
        handleCameraState(code, msg)
    }
    
    /**
     * Start video recording through AUSBC
     * for now, we let AUSBC use default path to avoid MediaStore issues
     */
    fun startVideoRecording(outputPath: String, callback: (Boolean, String?) -> Unit) {
        try {
            val camera = getCurrentCamera()
            if (camera == null) {
                callback(false, "Camera not available")
                return
            }
            
            // DON'T pass custom path - let AUSBC use default path
            camera.captureVideoStart(
                
            object : com.jiangdg.ausbc.callback.ICaptureCallBack {
                override fun onBegin() {
                    Log.d(TAG, "AUSBCBridgeFragment.startVideoRecording: ✅ Video recording started (AUSBC default path)")
                    bridgeCallback?.onRecordingStarted("AUSBC_default_path") // AUSBC will choose path
                    callback(true, null)
                }
                
                override fun onError(error: String?) {
                    Log.e(TAG, "AUSBCBridgeFragment.startVideoRecording: ❌ Video recording error: $error")
                    bridgeCallback?.onRecordingError(error ?: "Recording failed")
                    callback(false, error)
                }
                
                override fun onComplete(path: String?) {
                    Log.d(TAG, "AUSBCBridgeFragment.startVideoRecording: ✅ Video recording completed to AUSBC default path: $path")
                    // Use the actual path AUSBC saved to
                    bridgeCallback?.onRecordingStopped(path ?: "unknown_path")
                }
            }
            

            ) // NO path parameter 
            
        } catch (e: Exception) {
            Log.e(TAG, "AUSBCBridgeFragment.startVideoRecording: Failed to start recording", e)
            callback(false, e.message)
        }
    }
    
    /**
     * Stop video recording through AUSBC
     */
    fun stopVideoRecording() {
        try {
            getCurrentCamera()?.captureVideoStop()
            Log.d(TAG, "AUSBCBridgeFragment.stopVideoRecording: Video recording stop requested")
        } catch (e: Exception) {
            Log.e(TAG, "AUSBCBridgeFragment.stopVideoRecording: Failed to stop recording", e)
            bridgeCallback?.onRecordingError("Failed to stop recording: ${e.message}")
        }
    }
    
    /**
     * Check if currently recording
     */
    fun isRecording(): Boolean {
        return getCurrentCamera()?.isRecording() == true
    }
    
    /**
     * Make the preview surface visible (for debugging)
     */
    fun showPreview() {
        view?.visibility = View.VISIBLE
    }
    
    /**
     * Hide the preview surface
     */
    fun hidePreview() {
        view?.visibility = View.GONE
    }
    
    /**
     * Add logging to all fragment lifecycle methods
     */
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "AUSBCBridgeFragment.onResume: 🔄 Bridge fragment onResume called")
    }
    
    override fun onPause() {
        Log.d(TAG, "AUSBCBridgeFragment.onPause: ⏸️ Bridge fragment onPause called")
        super.onPause()
    }
    
    override fun onStart() {
        super.onStart()
        Log.d(TAG, "AUSBCBridgeFragment.onStart: ▶️ Bridge fragment onStart called")
    }
    
    override fun onStop() {
        Log.d(TAG, "AUSBCBridgeFragment.onStop: ⏹️ Bridge fragment onStop called")
        super.onStop()
    }
    
    override fun onDestroyView() {
        Log.d(TAG, "AUSBCBridgeFragment.onDestroyView: 💥 Destroying bridge fragment view")
        previewSurface = null
        bridgeCallback = null
        super.onDestroyView()
    }
    
    /**
     * Add logging to key AUSBC methods for debugging
     */
    override fun initData() {
        super.initData()
        Log.d(TAG, "AUSBCBridgeFragment.initData: 📊 AUSBC initData called")
    }
    
    override fun clear() {
        Log.d(TAG, "AUSBCBridgeFragment.clear: 🧹 AUSBC clear called")
        super.clear()
    }
}
