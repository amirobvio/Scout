package com.example.mergedapp.camera

import androidx.camera.core.ImageProxy

/**
 * Sealed class representing different types of frame data
 * Allows efficient handling of both ImageProxy (from internal camera) and raw bytes (from USB camera)
 */
sealed class FrameData {
    abstract val width: Int
    abstract val height: Int
    abstract val rotation: Int
    abstract val timestamp: Long
    abstract val source: CameraType
    
    /**
     * Frame data from internal camera as ImageProxy
     * This is the most efficient format as it avoids unnecessary conversions
     */
    data class ImageProxyFrame(
        val imageProxy: ImageProxy,
        override val rotation: Int,
        override val source: CameraType = CameraType.INTERNAL,
        override val timestamp: Long = System.currentTimeMillis()
    ) : FrameData() {
        override val width: Int = imageProxy.width
        override val height: Int = imageProxy.height
    }
    
    /**
     * Frame data from USB camera as raw bytes
     * Required because USB camera API provides data in this format
     */
    data class RawBytesFrame(
        val data: ByteArray,
        override val width: Int,
        override val height: Int,
        val format: FrameFormat,
        override val rotation: Int,
        override val source: CameraType = CameraType.USB,
        override val timestamp: Long = System.currentTimeMillis()
    ) : FrameData() {
        
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            
            other as RawBytesFrame
            
            if (!data.contentEquals(other.data)) return false
            if (width != other.width) return false
            if (height != other.height) return false
            if (format != other.format) return false
            if (rotation != other.rotation) return false
            if (source != other.source) return false
            if (timestamp != other.timestamp) return false
            
            return true
        }
        
        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + format.hashCode()
            result = 31 * result + rotation
            result = 31 * result + source.hashCode()
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }
}
