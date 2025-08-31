package com.example.mergedapp.detection

import org.tensorflow.lite.examples.objectdetection.detectors.ObjectDetection

/**
 * Utility functions for detection operations
 */
object DetectionUtils {
    
    /**
     * Check if any objects of a specific type are detected
     * @param detections List of detected objects
     * @param targetLabel Label to search for (case-insensitive)
     * @return true if at least one object with the target label is found
     */
    fun hasObjectType(detections: List<ObjectDetection>, targetLabel: String): Boolean {
        return detections.any { detection ->
            detection.category.label.lowercase().contains(targetLabel.lowercase())
        }
    }
    
    /**
     * Get all detections of a specific type
     * @param detections List of detected objects
     * @param targetLabel Label to search for (case-insensitive)
     * @return List of detections matching the target label
     */
    fun getObjectsOfType(detections: List<ObjectDetection>, targetLabel: String): List<ObjectDetection> {
        return detections.filter { detection ->
            detection.category.label.lowercase().contains(targetLabel.lowercase())
        }
    }
    
    /**
     * Get the highest confidence detection
     * @param detections List of detected objects
     * @return ObjectDetection with highest confidence, or null if list is empty
     */
    fun getHighestConfidenceDetection(detections: List<ObjectDetection>): ObjectDetection? {
        return detections.maxByOrNull { it.category.confidence }
    }
    
    /**
     * Filter detections by minimum confidence threshold
     * @param detections List of detected objects
     * @param minConfidence Minimum confidence threshold (0.0 to 1.0)
     * @return List of detections above the confidence threshold
     */
    fun filterByConfidence(detections: List<ObjectDetection>, minConfidence: Float): List<ObjectDetection> {
        return detections.filter { it.category.confidence >= minConfidence }
    }
    
    /**
     * Get unique object labels from detections
     * @param detections List of detected objects
     * @return Set of unique labels
     */
    fun getUniqueLabels(detections: List<ObjectDetection>): Set<String> {
        return detections.map { it.category.label }.toSet()
    }
    
    /**
     * Format detection results for logging
     * @param detections List of detected objects
     * @return Formatted string for logging
     */
    fun formatDetectionResults(detections: List<ObjectDetection>): String {
        if (detections.isEmpty()) {
            return "No objects detected"
        }
        
        return buildString {
            append("Detected ${detections.size} objects: ")
            detections.forEachIndexed { index, detection ->
                if (index > 0) append(", ")
                append("${detection.category.label} (${String.format("%.2f", detection.category.confidence)})")
            }
        }
    }
}
