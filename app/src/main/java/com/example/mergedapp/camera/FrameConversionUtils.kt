package com.example.mergedapp.camera

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Utility class for frame format conversions
 * Handles conversion between different frame formats for detection processing
 */
object FrameConversionUtils {
    
    private const val TAG = "FrameConversionUtils"
    
    /**
     * Convert raw frame data to Bitmap based on format
     * @param data Raw frame data
     * @param width Frame width
     * @param height Frame height  
     * @param format Frame format
     * @return Converted Bitmap or null if conversion fails
     */
    fun convertToBitmap(data: ByteArray, width: Int, height: Int, format: FrameFormat): Bitmap? {
        return when (format) {
            FrameFormat.RGBA -> convertRGBAToBitmap(data, width, height)
            FrameFormat.NV21 -> convertNV21ToBitmap(data, width, height)
        }
    }
    
    /**
     * Convert RGBA byte array to Bitmap (efficient for detection)
     * Based on AUSBCBridgeFragment implementation
     */
    fun convertRGBAToBitmap(data: ByteArray, width: Int, height: Int): Bitmap? {
        val startTime = System.currentTimeMillis()
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val buffer = java.nio.ByteBuffer.wrap(data)
            bitmap.copyPixelsFromBuffer(buffer)
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "RGBA conversion took ${duration}ms for ${width}x${height}")
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error converting RGBA to Bitmap", e)
            null
        }
    }
    
    /**
     * Convert NV21 (YUV420) byte array to RGB Bitmap
     * This is a fallback when RGBA is not available
     */
    fun convertNV21ToBitmap(data: ByteArray, width: Int, height: Int): Bitmap? {
        val startTime = System.currentTimeMillis()
        return try {
            val yuvImageStartTime = System.currentTimeMillis()
            val yuvImage = YuvImage(
                data, 
                ImageFormat.NV21, 
                width, 
                height, 
                null
            )
            val yuvImageDuration = System.currentTimeMillis() - yuvImageStartTime
            Log.d(TAG, "YuvImage creation took ${yuvImageDuration}ms")
            
            val compressionStartTime = System.currentTimeMillis()
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
            val imageBytes = out.toByteArray()
            val compressionDuration = System.currentTimeMillis() - compressionStartTime
            Log.d(TAG, "JPEG compression took ${compressionDuration}ms")
            
            val decodingStartTime = System.currentTimeMillis()
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            val decodingDuration = System.currentTimeMillis() - decodingStartTime
            Log.d(TAG, "JPEG decoding took ${decodingDuration}ms")
            
            val totalDuration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Total NV21 conversion took ${totalDuration}ms for ${width}x${height}")
            
            bitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Error converting NV21 to Bitmap", e)
            null
        }
    }
    
    /**
     * Get expected frame data size for validation
     */
    fun getExpectedDataSize(width: Int, height: Int, format: FrameFormat): Int {
        return when (format) {
            FrameFormat.RGBA -> width * height * 4  // 4 bytes per pixel
            FrameFormat.NV21 -> (width * height * 3) / 2  // YUV420 format
        }
    }
    
    /**
     * Validate frame data size matches format expectations
     */
    fun validateFrameData(data: ByteArray, width: Int, height: Int, format: FrameFormat): Boolean {
        val expectedSize = getExpectedDataSize(width, height, format)
        val actualSize = data.size
        
        if (actualSize != expectedSize) {
            Log.w(TAG, "Frame data size mismatch - expected: $expectedSize, actual: $actualSize, format: $format, dimensions: ${width}x${height}")
            return false
        }
        
        return true
    }
}
