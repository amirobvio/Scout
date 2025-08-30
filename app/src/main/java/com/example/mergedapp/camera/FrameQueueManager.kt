package com.example.mergedapp.camera

import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Frame Queue Manager for handling camera frames for detection processing
 * 
 * This system allows cameras to push frames to queues and detection systems
 * to pull frames for processing. Supports multiple camera sources and 
 * configurable queue behavior.
 */
class FrameQueueManager private constructor() {
    
    companion object {
        private const val TAG = "FrameQueueManager"
        private const val MAX_QUEUE_SIZE = 10  // Maximum frames in queue before dropping oldest
        
        @Volatile
        private var INSTANCE: FrameQueueManager? = null
        
        fun getInstance(): FrameQueueManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FrameQueueManager().also { INSTANCE = it }
            }
        }
    }
    
    // Frame queues for different camera types
    private val usbCameraQueue = ConcurrentLinkedQueue<QueuedFrame>()
    private val internalCameraQueue = ConcurrentLinkedQueue<QueuedFrame>()
    
    // Queue state management
    private val usbQueueEnabled = AtomicBoolean(false)
    private val internalQueueEnabled = AtomicBoolean(false)
    
    // Frame counting for statistics
    private val usbFramesPushed = AtomicLong(0)
    private val internalFramesPushed = AtomicLong(0)
    private val usbFramesDropped = AtomicLong(0)
    private val internalFramesDropped = AtomicLong(0)
    
    // Listeners for frame availability
    private var frameAvailableListener: FrameAvailableListener? = null
    
    /**
     * Data class for queued frames with metadata
     */
    data class QueuedFrame(
        val frame: CameraFrame,
        val sourceCamera: CameraType,
        val queueTimestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Listener interface for frame availability notifications
     */
    interface FrameAvailableListener {
        fun onFrameAvailable(sourceCamera: CameraType, queueSize: Int)
    }
    
    /**
     * Enable frame pushing for a specific camera type
     */
    fun enableFramePushing(cameraType: CameraType, enabled: Boolean) {
        Log.d(TAG, "Frame pushing ${if (enabled) "ENABLED" else "DISABLED"} for $cameraType camera")
        
        when (cameraType) {
            CameraType.USB -> {
                usbQueueEnabled.set(enabled)
                if (!enabled) {
                    clearQueue(cameraType)
                }
            }
            CameraType.INTERNAL -> {
                internalQueueEnabled.set(enabled)
                if (!enabled) {
                    clearQueue(cameraType)
                }
            }
        }
    }
    
    /**
     * Push a frame to the appropriate queue
     */
    fun pushFrame(frame: CameraFrame, sourceCamera: CameraType) {
        val isEnabled = when (sourceCamera) {
            CameraType.USB -> usbQueueEnabled.get()
            CameraType.INTERNAL -> internalQueueEnabled.get()
        }
        
        if (!isEnabled) {
            return  // Silently ignore if queue is disabled
        }
        
        val queue = getQueue(sourceCamera)
        val framesPushedCounter = getFramesPushedCounter(sourceCamera)
        val framesDroppedCounter = getFramesDroppedCounter(sourceCamera)
        
        // Create queued frame
        val queuedFrame = QueuedFrame(frame, sourceCamera)
        
        // Manage queue size - drop oldest frames if queue is full
        while (queue.size >= MAX_QUEUE_SIZE) {
            val droppedFrame = queue.poll()
            if (droppedFrame != null) {
                framesDroppedCounter.incrementAndGet()
                Log.v(TAG, "Dropped old frame from $sourceCamera queue (queue was full)")
            }
        }
        
        // Add new frame
        queue.offer(queuedFrame)
        framesPushedCounter.incrementAndGet()
        
        // Notify listener if set
        frameAvailableListener?.onFrameAvailable(sourceCamera, queue.size)
        
        // Log occasionally for debugging
        val totalPushed = framesPushedCounter.get()
        if (totalPushed % 100 == 0L) {
            Log.d(TAG, "$sourceCamera queue: ${queue.size} frames, total pushed: $totalPushed, dropped: ${framesDroppedCounter.get()}")
        }
    }
    
    /**
     * Pull the next frame from a specific camera queue
     */
    fun pullFrame(sourceCamera: CameraType): QueuedFrame? {
        val queue = getQueue(sourceCamera)
        return queue.poll()
    }
    
    /**
     * Pull the next frame from any available queue (prioritizes USB then Internal)
     */
    fun pullNextAvailableFrame(): QueuedFrame? {
        // Try USB first, then internal
        return pullFrame(CameraType.USB) ?: pullFrame(CameraType.INTERNAL)
    }
    
    /**
     * Pull the most recent frame from a specific camera queue, discarding older frames
     */
    fun pullLatestFrame(sourceCamera: CameraType): QueuedFrame? {
        val queue = getQueue(sourceCamera)
        var latestFrame: QueuedFrame? = null
        
        // Keep pulling until queue is empty, keeping only the last frame
        while (!queue.isEmpty()) {
            latestFrame = queue.poll()
        }
        
        return latestFrame
    }
    
    /**
     * Get queue size for a specific camera
     */
    fun getQueueSize(sourceCamera: CameraType): Int {
        return getQueue(sourceCamera).size
    }
    
    /**
     * Check if any frames are available for processing
     */
    fun hasFramesAvailable(): Boolean {
        return !usbCameraQueue.isEmpty() || !internalCameraQueue.isEmpty()
    }
    
    /**
     * Check if a specific camera has frames available
     */
    fun hasFramesAvailable(sourceCamera: CameraType): Boolean {
        return !getQueue(sourceCamera).isEmpty()
    }
    
    /**
     * Clear all frames from a specific queue
     */
    fun clearQueue(sourceCamera: CameraType) {
        val queue = getQueue(sourceCamera)
        val clearedCount = queue.size
        queue.clear()
        
        if (clearedCount > 0) {
            Log.d(TAG, "Cleared $clearedCount frames from $sourceCamera queue")
        }
    }
    
    /**
     * Clear all queues
     */
    fun clearAllQueues() {
        clearQueue(CameraType.USB)
        clearQueue(CameraType.INTERNAL)
        Log.d(TAG, "All frame queues cleared")
    }
    
    /**
     * Get statistics for debugging
     */
    fun getQueueStatistics(): QueueStatistics {
        return QueueStatistics(
            usbQueueSize = usbCameraQueue.size,
            internalQueueSize = internalCameraQueue.size,
            usbQueueEnabled = usbQueueEnabled.get(),
            internalQueueEnabled = internalQueueEnabled.get(),
            usbFramesPushed = usbFramesPushed.get(),
            internalFramesPushed = internalFramesPushed.get(),
            usbFramesDropped = usbFramesDropped.get(),
            internalFramesDropped = internalFramesDropped.get()
        )
    }
    
    /**
     * Set frame available listener
     */
    fun setFrameAvailableListener(listener: FrameAvailableListener?) {
        this.frameAvailableListener = listener
    }
    
    /**
     * Reset all statistics
     */
    fun resetStatistics() {
        usbFramesPushed.set(0)
        internalFramesPushed.set(0)
        usbFramesDropped.set(0)
        internalFramesDropped.set(0)
        Log.d(TAG, "Queue statistics reset")
    }
    
    // Private helper methods
    
    private fun getQueue(sourceCamera: CameraType): ConcurrentLinkedQueue<QueuedFrame> {
        return when (sourceCamera) {
            CameraType.USB -> usbCameraQueue
            CameraType.INTERNAL -> internalCameraQueue
        }
    }
    
    private fun getFramesPushedCounter(sourceCamera: CameraType): AtomicLong {
        return when (sourceCamera) {
            CameraType.USB -> usbFramesPushed
            CameraType.INTERNAL -> internalFramesPushed
        }
    }
    
    private fun getFramesDroppedCounter(sourceCamera: CameraType): AtomicLong {
        return when (sourceCamera) {
            CameraType.USB -> usbFramesDropped
            CameraType.INTERNAL -> internalFramesDropped
        }
    }
}

/**
 * Statistics data class for queue monitoring
 */
data class QueueStatistics(
    val usbQueueSize: Int,
    val internalQueueSize: Int,
    val usbQueueEnabled: Boolean,
    val internalQueueEnabled: Boolean,
    val usbFramesPushed: Long,
    val internalFramesPushed: Long,
    val usbFramesDropped: Long,
    val internalFramesDropped: Long
) {
    override fun toString(): String {
        return """
            |FrameQueue Statistics:
            |  USB: ${if (usbQueueEnabled) "ENABLED" else "DISABLED"} | Queue: $usbQueueSize | Pushed: $usbFramesPushed | Dropped: $usbFramesDropped
            |  Internal: ${if (internalQueueEnabled) "ENABLED" else "DISABLED"} | Queue: $internalQueueSize | Pushed: $internalFramesPushed | Dropped: $internalFramesDropped
        """.trimMargin()
    }
}
