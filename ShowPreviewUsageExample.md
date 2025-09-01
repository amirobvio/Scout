# ShowPreview Toggle Implementation

## Overview

The `showPreview` toggle has been implemented to allow efficient USB camera recording without UI preview. This reduces computational costs when you only need recording functionality.

## Key Features

1. **Offscreen Rendering**: When `showPreview = false`, the camera operates in true offscreen mode using OpenGL's PBuffer surface
2. **Runtime Toggle**: You can also toggle preview visibility at runtime
3. **Performance Optimized**: Offscreen mode eliminates UI rendering overhead while maintaining full recording capability

## Usage Examples

### 1. Recording-Only Mode (No Preview)

```kotlin
// Create camera configuration with preview disabled
val config = CameraConfig(
    width = 1920,
    height = 1080,
    showPreview = false,  // Enable offscreen mode
    enableFrameCallback = false,  // Only needed if you want frame data
    enableDetectionFrames = false  // Only for detection use cases
)

// Start camera in offscreen mode
usbCamera.startCamera(config)

// Start recording - no UI will be shown
usbCamera.startRecording("/path/to/video.mp4") { success, error ->
    if (success) {
        Log.d("Recording", "Started recording in offscreen mode")
    }
}
```

### 2. Normal Preview Mode (With UI)

```kotlin
// Create camera configuration with preview enabled
val config = CameraConfig(
    width = 1920,
    height = 1080,
    showPreview = true,  // Normal preview mode
    enableFrameCallback = false,
    enableDetectionFrames = false
)

// Start camera with preview
usbCamera.startCamera(config)

// Start recording with preview visible
usbCamera.startRecording("/path/to/video.mp4") { success, error ->
    if (success) {
        Log.d("Recording", "Started recording with preview visible")
    }
}
```

### 3. Runtime Preview Toggle

```kotlin
// Start with preview enabled
val config = CameraConfig(showPreview = true)
usbCamera.startCamera(config)

// Check if preview is enabled
if (usbCamera.isPreviewEnabled()) {
    // Hide preview at runtime (but keep camera running)
    usbCamera.setPreviewVisibility(false)
    
    // Or use convenience methods
    usbCamera.hidePreview()
    
    // Show again later
    usbCamera.showPreview()
}
```

## Performance Benefits

### Offscreen Mode (`showPreview = false`)
- **No OpenGL-to-screen rendering**: Saves GPU cycles
- **No UI updates**: Eliminates main thread UI work
- **Minimal memory footprint**: No display buffers allocated
- **Better for headless recording**: Ideal for background recording applications

### Visible Preview Mode (`showPreview = true`)
- Full preview functionality
- Real-time visual feedback
- Normal UI interaction capability

## Technical Implementation

### AUSBC Integration
- When `getCameraView()` returns `null`, AUSBC automatically switches to offscreen rendering
- Uses OpenGL PBuffer surface instead of window surface
- Frame data callbacks still work normally
- Recording functionality is unchanged

### Key Methods
- `CameraConfig.showPreview`: Configure preview mode at camera start
- `USBCameraImpl.setPreviewVisibility(visible)`: Toggle preview visibility at runtime
- `USBCameraImpl.isPreviewEnabled()`: Check current preview configuration
- `USBCameraImpl.showPreview()/hidePreview()`: Convenience methods

## Best Practices

1. **Use offscreen mode for recording-only applications** to maximize performance
2. **Use runtime toggle** when you need to switch between preview and recording-only modes
3. **Keep frame callbacks disabled** unless you specifically need frame data
4. **Monitor battery usage** - offscreen mode should provide better battery life

## Example: Surveillance Application

```kotlin
class SurveillanceRecorder {
    private val usbCamera = USBCameraImpl(context, usbDevice, activity)
    
    fun startSilentRecording() {
        val config = CameraConfig(
            width = 1920,
            height = 1080,
            showPreview = false,  // Silent operation
            enableFrameCallback = false
        )
        
        usbCamera.startCamera(config)
        usbCamera.startRecording(generateOutputPath()) { success, error ->
            if (success) {
                Log.d("Surveillance", "Silent recording started")
            }
        }
    }
    
    fun startMonitoringWithPreview() {
        val config = CameraConfig(
            width = 1280,
            height = 720,
            showPreview = true,  // Show preview for monitoring
            enableFrameCallback = false
        )
        
        usbCamera.startCamera(config)
        // Preview will be visible for monitoring
    }
}
```

This implementation provides maximum flexibility while maintaining optimal performance for both use cases.
