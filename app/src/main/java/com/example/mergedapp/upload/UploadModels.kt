package com.example.mergedapp.upload

import java.io.File

/**
 * Configuration for the DataUploader
 */
data class UploadConfig(
    val s3BaseUrl: String,
    val accessToken: String,
    val maxRetries: Int = 3,
    val timeoutSeconds: Int = 120
) {
    companion object {
        /**
         * Create a default config for development/testing
         */
        fun createDefault(s3BaseUrl: String, accessToken: String): UploadConfig {
            return UploadConfig(
                s3BaseUrl = s3BaseUrl,
                accessToken = accessToken,
                maxRetries = 3,
                timeoutSeconds = 120
            )
        }
    }
}

/**
 * Metadata associated with an upload
 * Can be extended to include additional context information
 */
data class UploadMetadata(
    val deviceId: String? = null,
    val sessionId: String? = null,
    val cameraType: String? = null, // "usb" or "internal" for video files
    val timestamp: Long = System.currentTimeMillis(),
    val location: String? = null,
    val customTags: Map<String, String> = emptyMap()
) {
    companion object {
        /**
         * Create metadata for video files
         */
        fun forVideo(
            deviceId: String,
            cameraType: String,
            sessionId: String? = null,
            location: String? = null,
            customTags: Map<String, String> = emptyMap()
        ): UploadMetadata {
            return UploadMetadata(
                deviceId = deviceId,
                sessionId = sessionId,
                cameraType = cameraType,
                location = location,
                customTags = customTags
            )
        }
        
        /**
         * Create metadata for radar JSON files
         */
        fun forRadar(
            deviceId: String,
            sessionId: String? = null,
            location: String? = null,
            customTags: Map<String, String> = emptyMap()
        ): UploadMetadata {
            return UploadMetadata(
                deviceId = deviceId,
                sessionId = sessionId,
                location = location,
                customTags = customTags
            )
        }
    }
}

/**
 * Represents different file types that can be uploaded
 */
enum class FileType {
    JSON,    // Radar data files
    VIDEO,   // Camera recording files
    UNKNOWN  // Unknown file types
}

/**
 * Internal class representing an upload task
 */
internal data class UploadTask(
    val file: File,
    val fileType: FileType,
    val metadata: UploadMetadata,
    val s3Key: String,
    var retryCount: Int = 0
)

/**
 * Status of the upload queue
 */
data class UploadQueueStatus(
    val queuedCount: Int,
    val activeCount: Int,
    val isProcessing: Boolean
) {
    /**
     * Total number of uploads (queued + active)
     */
    val totalCount: Int
        get() = queuedCount + activeCount
    
    /**
     * Whether the uploader is idle (no uploads in progress or queued)
     */
    val isIdle: Boolean
        get() = totalCount == 0 && !isProcessing
}
