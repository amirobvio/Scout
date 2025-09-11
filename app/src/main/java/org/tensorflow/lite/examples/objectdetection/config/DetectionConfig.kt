package org.tensorflow.lite.examples.objectdetection.config

object DetectionConfig {
    // Frame interval for detection (detect every Nth frame)
    const val DETECTION_FRAME_INTERVAL = 9
    
    // Number of consecutive non-detections needed to stop recording
    const val CONSECUTIVE_NON_DETECTIONS_TO_STOP = 10
}
