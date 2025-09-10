package com.example.mergedapp.config

import android.content.Context
import android.util.Log
import org.yaml.snakeyaml.Yaml
import java.io.InputStreamReader

/**
 * AppConfigManager - Manages application configuration from app_config.yaml
 * 
 * Reads the YAML configuration file to determine which components should be enabled:
 * - USB Camera
 * - Internal Camera  
 * - Radar Sensor
 */
class AppConfigManager private constructor() {
    
    companion object {
        private const val TAG = "AppConfigManager"
        private const val CONFIG_FILE = "app_config.yaml"
        
        @Volatile
        private var INSTANCE: AppConfigManager? = null
        
        fun getInstance(): AppConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppConfigManager().also { INSTANCE = it }
            }
        }
        
        // Helper function for consistent logging format
        private fun logFormat(functionName: String, message: String): String {
            return "AppConfigManager.$functionName: $message"
        }
    }
    
    // Configuration properties
    var isUsbCameraEnabled: Boolean = true
        private set
    
    var isInternalCameraEnabled: Boolean = true
        private set
        
    var isRadarEnabled: Boolean = true
        private set
    
    private var isConfigLoaded = false
    
    /**
     * Load configuration from app_config.yaml in assets
     */
    fun loadConfig(context: Context): Boolean {
        if (isConfigLoaded) {
            Log.d(TAG, logFormat("loadConfig", "Configuration already loaded"))
            return true
        }
        
        try {
            Log.d(TAG, logFormat("loadConfig", "Loading configuration from $CONFIG_FILE"))
            
            val inputStream = context.assets.open(CONFIG_FILE)
            val reader = InputStreamReader(inputStream)
            
            val yaml = Yaml()
            val config = yaml.load<Map<String, Any>>(reader)
            
            reader.close()
            inputStream.close()
            
            // Parse configuration values with defaults
            isUsbCameraEnabled = (config["usb_camera_enabled"] as? Boolean) ?: true
            isInternalCameraEnabled = (config["internal_camera_enabled"] as? Boolean) ?: true
            isRadarEnabled = (config["radar_enabled"] as? Boolean) ?: true
            
            isConfigLoaded = true
            
            Log.d(TAG, logFormat("loadConfig", "✅ Configuration loaded successfully:"))
            Log.d(TAG, logFormat("loadConfig", "  USB Camera: ${if (isUsbCameraEnabled) "ENABLED" else "DISABLED"}"))
            Log.d(TAG, logFormat("loadConfig", "  Internal Camera: ${if (isInternalCameraEnabled) "ENABLED" else "DISABLED"}"))
            Log.d(TAG, logFormat("loadConfig", "  Radar: ${if (isRadarEnabled) "ENABLED" else "DISABLED"}"))
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, logFormat("loadConfig", "❌ Failed to load configuration: ${e.message}"), e)
            Log.w(TAG, logFormat("loadConfig", "Using default configuration (all components enabled)"))
            
            // Use defaults if config loading fails
            isUsbCameraEnabled = true
            isInternalCameraEnabled = true
            isRadarEnabled = true
            isConfigLoaded = true
            
            return false
        }
    }
    
    /**
     * Get human-readable configuration summary
     */
    fun getConfigSummary(): String {
        return buildString {
            append("Component Configuration:\n")
            append("🔌 USB Camera: ${if (isUsbCameraEnabled) "ENABLED" else "DISABLED"}\n")
            append("📱 Internal Camera: ${if (isInternalCameraEnabled) "ENABLED" else "DISABLED"}\n")
            append("📡 Radar: ${if (isRadarEnabled) "ENABLED" else "DISABLED"}")
        }
    }
    
    /**
     * Check if any camera component is enabled
     */
    fun hasAnyCameraEnabled(): Boolean {
        return isUsbCameraEnabled || isInternalCameraEnabled
    }
    
    /**
     * Check if detection system should be initialized
     * (only needed if at least one camera is enabled)
     */
    fun shouldInitializeDetection(): Boolean {
        return hasAnyCameraEnabled()
    }
    
    /**
     * Reset configuration (for testing purposes)
     */
    fun resetConfig() {
        isConfigLoaded = false
        isUsbCameraEnabled = true
        isInternalCameraEnabled = true
        isRadarEnabled = true
        Log.d(TAG, logFormat("resetConfig", "Configuration reset to defaults"))
    }
}
