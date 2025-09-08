package com.example.mergedapp.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * FilePermissionManager - Handles file permissions for Android 14+ compatibility
 * Manages storage permissions across different Android versions
 */
class FilePermissionManager {
    
    companion object {
        private const val TAG = "FilePermissionManager"
        const val STORAGE_PERMISSION_REQUEST_CODE = 1001
        const val MANAGE_EXTERNAL_STORAGE_REQUEST_CODE = 1002
        
        /**
         * Check if the app has the necessary storage permissions
         */
        fun hasStoragePermissions(context: Context): Boolean {
            return when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    // Android 11+ (API 30+) - Check for MANAGE_EXTERNAL_STORAGE
                    Environment.isExternalStorageManager()
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    // Android 6+ (API 23+) - Check for legacy storage permissions
                    ContextCompat.checkSelfPermission(
                        context, 
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                }
                else -> {
                    // Below Android 6 - permissions are granted at install time
                    true
                }
            }
        }
        
        /**
         * Request storage permissions based on Android version
         */
        fun requestStoragePermissions(activity: Activity) {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    // Android 11+ (API 30+) - Request MANAGE_EXTERNAL_STORAGE
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        }
                        activity.startActivityForResult(intent, MANAGE_EXTERNAL_STORAGE_REQUEST_CODE)
                        Log.d(TAG, "Requesting MANAGE_EXTERNAL_STORAGE permission")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to request MANAGE_EXTERNAL_STORAGE: ${e.message}")
                        // Fallback to general settings
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        activity.startActivityForResult(intent, MANAGE_EXTERNAL_STORAGE_REQUEST_CODE)
                    }
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    // Android 13+ (API 33+) - Request granular media permissions
                    val permissions = arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO
                    )
                    ActivityCompat.requestPermissions(activity, permissions, STORAGE_PERMISSION_REQUEST_CODE)
                    Log.d(TAG, "Requesting granular media permissions for Android 13+")
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    // Android 6-12 (API 23-32) - Request legacy storage permissions
                    val permissions = arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                    ActivityCompat.requestPermissions(activity, permissions, STORAGE_PERMISSION_REQUEST_CODE)
                    Log.d(TAG, "Requesting legacy storage permissions")
                }
            }
        }
        
        /**
         * Get the best available directory for saving files
         */
        fun getBestSaveDirectory(context: Context, customPath: String? = null): File {
            return when {
                customPath != null -> {
                    val customDir = File(customPath)
                    if (customDir.exists() || customDir.mkdirs()) {
                        Log.d(TAG, "Using custom directory: $customPath")
                        customDir
                    } else {
                        Log.w(TAG, "Custom directory not accessible, falling back to app directory")
                        getAppDirectory(context)
                    }
                }
                hasStoragePermissions(context) -> {
                    // Try external storage first if we have permissions
                    val externalDir = context.getExternalFilesDir(null)
                    if (externalDir != null && (externalDir.exists() || externalDir.mkdirs())) {
                        Log.d(TAG, "Using external app directory: ${externalDir.absolutePath}")
                        externalDir
                    } else {
                        Log.w(TAG, "External directory not accessible, using internal storage")
                        getAppDirectory(context)
                    }
                }
                else -> {
                    Log.d(TAG, "No storage permissions, using internal app directory")
                    getAppDirectory(context)
                }
            }
        }
        
        /**
         * Get the internal app directory (always accessible)
         */
        private fun getAppDirectory(context: Context): File {
            return context.filesDir
        }
        
        /**
         * Check if a specific path is writable
         */
        fun isPathWritable(path: String): Boolean {
            return try {
                val file = File(path)
                file.exists() && file.canWrite() || file.mkdirs()
            } catch (e: Exception) {
                Log.e(TAG, "Error checking path writability: ${e.message}")
                false
            }
        }
        
        /**
         * Handle permission request results
         */
        fun handlePermissionResult(
            requestCode: Int,
            @Suppress("UNUSED_PARAMETER") permissions: Array<out String>,
            grantResults: IntArray,
            onPermissionGranted: () -> Unit,
            onPermissionDenied: () -> Unit
        ) {
            when (requestCode) {
                STORAGE_PERMISSION_REQUEST_CODE -> {
                    if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                        Log.d(TAG, "Storage permissions granted")
                        onPermissionGranted()
                    } else {
                        Log.w(TAG, "Storage permissions denied")
                        onPermissionDenied()
                    }
                }
                MANAGE_EXTERNAL_STORAGE_REQUEST_CODE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        if (Environment.isExternalStorageManager()) {
                            Log.d(TAG, "MANAGE_EXTERNAL_STORAGE permission granted")
                            onPermissionGranted()
                        } else {
                            Log.w(TAG, "MANAGE_EXTERNAL_STORAGE permission denied")
                            onPermissionDenied()
                        }
                    }
                }
            }
        }
    }
}
