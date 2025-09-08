package com.example.mergedapp.upload

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Example showing how to use the DataUploader
 * This demonstrates the clean integration approach
 */
class UploadExample(private val context: Context) {
    
    companion object {
        private const val TAG = "UploadExample"
    }
    
    // Create uploader instance
    private val uploadConfig = UploadConfig(
        s3BaseUrl = "https://your-s3-bucket.s3.amazonaws.com",
        accessToken = "your-aws-access-token",
        maxRetries = 3
    )
    
    private val dataUploader = DataUploader(context, uploadConfig)
    
    init {
        // Add upload listener to monitor upload events
        dataUploader.addUploadListener(object : UploadListener {
            override fun onUploadStarted(file: File, metadata: UploadMetadata) {
                Log.d(TAG, "Upload started: ${file.name}")
            }
            
            override fun onUploadCompleted(file: File, s3Key: String, metadata: UploadMetadata) {
                Log.d(TAG, "Upload completed: ${file.name} -> $s3Key")
                
                // Optionally delete local file after successful upload
                if (shouldDeleteAfterUpload()) {
                    file.delete()
                    Log.d(TAG, "Deleted local file: ${file.name}")
                }
            }
            
            override fun onUploadFailed(file: File, error: String, metadata: UploadMetadata) {
                Log.e(TAG, "Upload failed: ${file.name} - $error")
            }
        })
    }
    
    /**
     * Example: Upload a video file from camera
     * This would be called from your DetectionBasedRecorder
     */
    fun uploadVideoFile(videoFile: File, cameraType: String, deviceId: String) {
        val metadata = UploadMetadata.forVideo(
            deviceId = deviceId,
            cameraType = cameraType,
            sessionId = generateSessionId(),
            location = getCurrentLocation()
        )
        
        dataUploader.queueUpload(videoFile, metadata)
        Log.d(TAG, "Queued video upload: ${videoFile.name}")
    }
    
    /**
     * Example: Upload radar JSON data
     * This would be called from your OneDimensionalRadarImpl
     */
    fun uploadRadarData(jsonFile: File, deviceId: String) {
        val metadata = UploadMetadata.forRadar(
            deviceId = deviceId,
            sessionId = generateSessionId(),
            location = getCurrentLocation()
        )
        
        dataUploader.queueUpload(jsonFile, metadata)
        Log.d(TAG, "Queued radar upload: ${jsonFile.name}")
    }
    
    /**
     * Get current upload status
     */
    fun getUploadStatus(): UploadQueueStatus {
        return dataUploader.getQueueStatus()
    }
    
    /**
     * Shutdown uploader when done
     */
    fun shutdown() {
        dataUploader.shutdown()
    }
    
    // Helper methods (implement these based on your app's needs)
    private fun shouldDeleteAfterUpload(): Boolean = true // Configure as needed
    private fun generateSessionId(): String = "session_${System.currentTimeMillis()}"
    private fun getCurrentLocation(): String? = null // Implement GPS location if needed
}

/**
 * Integration points for your existing classes:
 * 
 * 1. In DetectionBasedRecorder.kt, in the RecordingCallback:
 * 
 *    override fun onRecordingStopped(outputPath: String) {
 *        recordingListener?.onRecordingStopped(cameraType, outputPath)
 *        
 *        // ADD: Notify uploader about new video file
 *        uploadExample.uploadVideoFile(
 *            videoFile = File(outputPath),
 *            cameraType = when(cameraType) { 
 *                CameraType.USB -> "usb"
 *                CameraType.INTERNAL -> "internal" 
 *            },
 *            deviceId = getDeviceId()
 *        )
 *    }
 * 
 * 2. In OneDimensionalRadarImpl.kt, in saveCurrentDataToFile():
 * 
 *    private fun saveCurrentDataToFile() {
 *        // ... existing save logic ...
 *        val savedFile = File(filePath)
 *        
 *        // ADD: Notify uploader about new radar file
 *        uploadExample.uploadRadarData(savedFile, getDeviceId())
 *    }
 */
