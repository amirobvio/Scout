# AUSBC Library Documentation

## Overview
AUSBC (Android USB Camera) is a comprehensive library for integrating USB UVC cameras into Android applications. This documentation covers the key components and APIs relevant to our multi-camera object detection project.

## Core Components

### 1. CameraFragment (Base Class)
**File**: `com.jiangdg.ausbc.base.CameraFragment`

The fundamental base class that all camera implementations must extend. This is the entry point for USB camera functionality.

#### Key Abstract Methods (Must be implemented by subclasses):
```kotlin
abstract fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View?
abstract fun getCameraView(): IAspectRatio?  
abstract fun getCameraViewContainer(): ViewGroup?
abstract fun getCameraRequest(): CameraRequest
abstract fun getDefaultCamera(): UsbDevice?
```

#### Core Lifecycle Methods:
- `onCameraState(self: ICamera, code: ICameraStateCallBack.State, msg: String?)` - Camera state changes
- `onPreviewDataUpdate(data: ByteArray?)` - Raw preview frame data callback

#### Key Public Methods:
```kotlin
// Camera control
fun openCamera(device: UsbDevice? = null)
fun closeCamera()
fun switchCamera(device: UsbDevice?)

// Recording control  
fun startRecording()
fun stopRecording()
fun isRecording(): Boolean

// Preview control
fun startPreview()
fun stopPreview()

// Capture
fun captureImage(callBack: ICaptureCallBack? = null, savePath: String? = null)
```

---

### 2. CameraRequest (Configuration)
**File**: `com.jiangdg.ausbc.camera.bean.CameraRequest`

The configuration class that defines camera parameters. Built using Builder pattern.

#### Available Builder Methods:
```kotlin
// Basic settings
.setPreviewWidth(int) / .setPreviewHeight(int)
.setRenderMode(RenderMode.OPENGL | RenderMode.NATIVE_WINDOW)
.setDefaultRotateType(RotateType.ANGLE_0|90|180|270)
.setPreviewFormat(PreviewFormat.FORMAT_MJPEG | FORMAT_YUYV)

// Audio settings
.setAudioSource(AudioSource.SOURCE_AUTO | SOURCE_MIC | SOURCE_CAMCORDER)

// Frame data settings  
.setRawPreviewData(boolean)  // Enable frame callbacks
.setCaptureRawImage(boolean) // Enable raw image capture
.setAspectRatioShow(boolean) // Show aspect ratio

// Effects
.setDefaultEffect(AbstractEffect)
```

#### Common Usage Pattern:
```kotlin
val request = CameraRequest.Builder()
    .setPreviewWidth(1280)
    .setPreviewHeight(720)
    .setRenderMode(CameraRequest.RenderMode.OPENGL)
    .setDefaultRotateType(RotateType.ANGLE_0)
    .setAudioSource(CameraRequest.AudioSource.SOURCE_AUTO)
    .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
    .setAspectRatioShow(true)
    .setCaptureRawImage(false)
    .setRawPreviewData(true)  // Essential for frame callbacks
    .create()
```

#### Enums Available:
```kotlin
// Render modes
enum class RenderMode { OPENGL, NATIVE_WINDOW }

// Preview formats  
enum class PreviewFormat { FORMAT_MJPEG, FORMAT_YUYV }

// Audio sources
enum class AudioSource { SOURCE_AUTO, SOURCE_MIC, SOURCE_CAMCORDER }

// Rotation types
enum class RotateType { ANGLE_0, ANGLE_90, ANGLE_180, ANGLE_270 }
```

---

### 3. Callbacks and Interfaces

#### ICameraStateCallBack.State
Camera state enumeration for monitoring camera lifecycle:
```kotlin
enum class State {
    OPENED,    // Camera successfully opened
    CLOSED,    // Camera closed  
    ERROR      // Camera error occurred
}
```

#### IPreviewDataCallBack
Interface for receiving raw preview frame data:
```kotlin
interface IPreviewDataCallBack {
    fun onPreviewDataUpdate(data: ByteArray?)
}
```

#### ICaptureCallBack  
Interface for image capture events:
```kotlin
interface ICaptureCallBack {
    fun onCaptureResult(path: String?)
}
```

---

### 4. Recording Implementation

#### Key Methods for Video Recording:
From CameraFragment base class:
```kotlin
// Start recording with default path (recommended)
fun startRecording()

// Stop current recording
fun stopRecording() 

// Check recording status
fun isRecording(): Boolean
```

#### Recording Path Handling:
- **Recommended**: Let AUSBC handle the recording path automatically
- AUSBC uses internal MediaStore integration for proper Android file management
- Custom paths may require additional MediaStore handling

#### Recording Implementation Example:
```kotlin
// Start recording
if (!isRecording()) {
    startRecording()  // Let AUSBC handle the path
}

// Stop recording
if (isRecording()) {
    stopRecording()
}
```

---

### 5. Preview Surface (AspectRatioTextureView)

#### IAspectRatio Interface
The preview surface that AUSBC expects for rendering camera preview:
```kotlin
// Key method that must be implemented in getCameraView()
abstract fun getCameraView(): IAspectRatio?
```

#### AspectRatioTextureView
AUSBC's provided implementation of IAspectRatio:
```kotlin
// Common usage in fragment layout
val textureView = AspectRatioTextureView(context).apply {
    setAspectRatio(width, height)
}
```

#### Container Requirements
```kotlin
// Must provide container for AUSBC framework
abstract fun getCameraViewContainer(): ViewGroup?
```

---

### 6. Bridge Pattern Implementation (Our Project)

#### Our AUSBCBridgeFragment Pattern
Based on our working implementation in MergedApp:

```kotlin
internal class AUSBCBridgeFragment : CameraFragment() {
    
    // Configuration from USBCameraImpl
    private lateinit var targetUsbDevice: UsbDevice
    private lateinit var cameraConfig: CameraConfig
    
    // Preview surface
    private var previewSurface: AspectRatioTextureView? = null
    
    // Bridge callback to communicate back to USBCameraImpl
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
}
```

#### Key Implementation Details:
1. **Extends CameraFragment**: Satisfies AUSBC framework requirements
2. **Bridge Callbacks**: Clean interface between AUSBC and our ICamera system
3. **Device Selection**: `getDefaultCamera()` returns the specific USB device
4. **Configuration**: `getCameraRequest()` converts our config to AUSBC format
5. **Frame Callbacks**: `onPreviewDataUpdate()` forwards to our callback system

---

## Working Integration Patterns

### Pattern 1: Basic USB Camera Setup
```kotlin
// 1. Create bridge fragment
val bridgeFragment = AUSBCBridgeFragment.newInstance(usbDevice, config)

// 2. Set callback handler  
bridgeFragment.bridgeCallback = this

// 3. Add to activity
fragmentManager.beginTransaction()
    .add(android.R.id.content, bridgeFragment, "ausbc_bridge")
    .commit()
```

### Pattern 2: Frame Callback Integration
```kotlin
// Enable in CameraRequest
.setRawPreviewData(true)

// Handle in bridge
override fun onPreviewDataUpdate(data: ByteArray?) {
    data?.let { frameData ->
        bridgeCallback?.onFrameAvailable(frameData, width, height)
    }
}
```

### Pattern 3: Recording Integration
```kotlin
// Start recording through bridge
fun startVideoRecording(outputPath: String, callback: (Boolean, String?) -> Unit) {
    try {
        if (!isRecording()) {
            startRecording()  // AUSBC handles the path
        }
    } catch (e: Exception) {
        callback(false, e.message)
    }
}
```

