package com.example.mergedapp.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DataUploader - Clean, focused uploader for JSON and video files to AWS S3
 * 
 * Features:
 * - Upload JSON and video files to AWS S3
 * - Queue management with retry logic
 * - Network-aware uploading
 * - Progress tracking and notifications
 * - Concurrent upload support
 */
class DataUploader(
    private val context: Context,
    private val config: UploadConfig
) {
    
    companion object {
        private const val TAG = "DataUploader"
        private const val MAX_CONCURRENT_UPLOADS = 3
        private const val DEFAULT_MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 5000L
        
        private fun logFormat(functionName: String, message: String): String {
            return "DataUploader.$functionName: $message"
        }
    }
    
    // Upload queue and state management
    private val uploadQueue = ConcurrentLinkedQueue<UploadTask>()
    private val activeUploads = mutableSetOf<String>()
    private val isProcessing = AtomicBoolean(false)
    
    // Coroutines
    private val uploaderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // HTTP client
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    // Listeners
    private val uploadListeners = mutableSetOf<UploadListener>()
    
    /**
     * Add an upload listener
     */
    fun addUploadListener(listener: UploadListener) {
        uploadListeners.add(listener)
    }
    
    /**
     * Remove an upload listener
     */
    fun removeUploadListener(listener: UploadListener) {
        uploadListeners.remove(listener)
    }
    
    /**
     * Queue a file for upload
     */
    fun queueUpload(file: File, metadata: UploadMetadata = UploadMetadata()) {
        if (!file.exists()) {
            Log.w(TAG, logFormat("queueUpload", "File does not exist: ${file.absolutePath}"))
            notifyUploadFailed(file, "File does not exist", metadata)
            return
        }
        
        val fileType = detectFileType(file)
        val uploadTask = UploadTask(
            file = file,
            fileType = fileType,
            metadata = metadata,
            s3Key = generateS3Key(file, fileType, metadata),
            retryCount = 0
        )
        
        uploadQueue.offer(uploadTask)
        Log.d(TAG, logFormat("queueUpload", "Queued ${fileType.name} file: ${file.name}"))
        
        // File queued for upload
        processQueue()
    }
    
    /**
     * Get current queue status
     */
    fun getQueueStatus(): UploadQueueStatus {
        return UploadQueueStatus(
            queuedCount = uploadQueue.size,
            activeCount = activeUploads.size,
            isProcessing = isProcessing.get()
        )
    }
    
    /**
     * Process the upload queue
     */
    private fun processQueue() {
        if (!isProcessing.compareAndSet(false, true)) {
            return // Already processing
        }
        
        uploaderScope.launch {
            try {
                while (uploadQueue.isNotEmpty() && activeUploads.size < MAX_CONCURRENT_UPLOADS) {
                    val task = uploadQueue.poll() ?: break
                    
                    if (isNetworkAvailable()) {
                        processUploadTask(task)
                    } else {
                        Log.w(TAG, logFormat("processQueue", "No network available, requeueing task"))
                        uploadQueue.offer(task) // Put back in queue
                        break
                    }
                }
            } finally {
                isProcessing.set(false)
                
                // Continue processing if there are more items and network is available
                if (uploadQueue.isNotEmpty() && isNetworkAvailable()) {
                    delay(1000) // Brief delay before next batch
                    processQueue()
                }
            }
        }
    }
    
    /**
     * Process a single upload task
     */
    private suspend fun processUploadTask(task: UploadTask) {
        val taskId = "${task.file.name}_${System.currentTimeMillis()}"
        activeUploads.add(taskId)
        
        try {
            Log.d(TAG, logFormat("processUploadTask", "Starting upload: ${task.file.name} -> ${task.s3Key}"))
            notifyUploadStarted(task.file, task.metadata)
            
            val success = uploadToS3(task)
            
            if (success) {
                Log.d(TAG, logFormat("processUploadTask", "Upload successful: ${task.file.name}"))
                notifyUploadCompleted(task.file, task.s3Key, task.metadata)
            } else {
                handleUploadFailure(task, "Upload failed")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, logFormat("processUploadTask", "Upload error: ${e.message}"), e)
            handleUploadFailure(task, e.message ?: "Unknown error")
        } finally {
            activeUploads.remove(taskId)
        }
    }
    
    /**
     * Upload file to AWS S3
     */
    private suspend fun uploadToS3(task: UploadTask): Boolean = withContext(Dispatchers.IO) {
        try {
            val requestBody = task.file.asRequestBody(getMediaType(task.fileType))
            
            val request = Request.Builder()
                .url("${config.s3BaseUrl}/${task.s3Key}")
                .put(requestBody)
                .header("Authorization", "Bearer ${config.accessToken}")
                .header("Content-Type", getMediaType(task.fileType).toString())
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                Log.d(TAG, logFormat("uploadToS3", "S3 upload successful: ${task.s3Key}"))
                true
            } else {
                Log.e(TAG, logFormat("uploadToS3", "S3 upload failed: ${response.code} - ${response.message}"))
                false
            }
            
        } catch (e: IOException) {
            Log.e(TAG, logFormat("uploadToS3", "Network error during upload: ${e.message}"), e)
            false
        }
    }
    
    /**
     * Handle upload failure with retry logic
     */
    private fun handleUploadFailure(task: UploadTask, error: String) {
        task.retryCount++
        
        if (task.retryCount <= config.maxRetries) {
            Log.w(TAG, logFormat("handleUploadFailure", "Upload failed, retrying (${task.retryCount}/${config.maxRetries}): ${task.file.name}"))
            
            // Requeue with delay
            uploaderScope.launch {
                delay(RETRY_DELAY_MS * task.retryCount) // Exponential backoff
                uploadQueue.offer(task)
                processQueue()
            }
        } else {
            Log.e(TAG, logFormat("handleUploadFailure", "Upload failed permanently after ${config.maxRetries} retries: ${task.file.name}"))
            notifyUploadFailed(task.file, error, task.metadata)
        }
    }
    
    /**
     * Detect file type from file extension
     */
    private fun detectFileType(file: File): FileType {
        return when (file.extension.lowercase()) {
            "json" -> FileType.JSON
            "mp4", "avi", "mov", "mkv" -> FileType.VIDEO
            else -> FileType.UNKNOWN
        }
    }
    
    /**
     * Generate S3 key for the file
     */
    private fun generateS3Key(file: File, fileType: FileType, metadata: UploadMetadata): String {
        val timestamp = System.currentTimeMillis()
        val prefix = when (fileType) {
            FileType.JSON -> "radar-data"
            FileType.VIDEO -> "video-data"
            FileType.UNKNOWN -> "unknown"
        }
        
        val deviceId = metadata.deviceId ?: "unknown-device"
        val fileName = file.nameWithoutExtension
        val extension = file.extension
        
        return "$prefix/$deviceId/$timestamp-$fileName.$extension"
    }
    
    /**
     * Get appropriate media type for file type
     */
    private fun getMediaType(fileType: FileType): MediaType {
        return when (fileType) {
            FileType.JSON -> "application/json".toMediaType()
            FileType.VIDEO -> "video/mp4".toMediaType()
            FileType.UNKNOWN -> "application/octet-stream".toMediaType()
        }
    }
    
    /**
     * Check if network is available
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    
    // Notification methods
    private fun notifyUploadStarted(file: File, metadata: UploadMetadata) {
        uploadListeners.forEach { it.onUploadStarted(file, metadata) }
    }
    
    private fun notifyUploadCompleted(file: File, s3Key: String, metadata: UploadMetadata) {
        uploadListeners.forEach { it.onUploadCompleted(file, s3Key, metadata) }
    }
    
    private fun notifyUploadFailed(file: File, error: String, metadata: UploadMetadata) {
        uploadListeners.forEach { it.onUploadFailed(file, error, metadata) }
    }
    
    /**
     * Shutdown the uploader
     */
    fun shutdown() {
        Log.d(TAG, logFormat("shutdown", "Shutting down DataUploader"))
        uploaderScope.cancel()
        httpClient.dispatcher.executorService.shutdown()
        uploadListeners.clear()
    }
}
