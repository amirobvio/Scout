package com.example.mergedapp.radar

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.example.mergedapp.utils.FilePermissionManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

/**
 * OneDimensionalRadarImpl - Concrete implementation of IRadar for speed/distance radar sensors
 * 
 * Based on radar_demo implementation for OPS7243A (IFX CDC) radar sensor
 * VID=1419 (0x058B), PID=88 (0x0058)
 */

 
class OneDimensionalRadarImpl(
    private val context: Context,
    private val device: UsbDevice
) : IRadar {
    
    companion object {
        private const val TAG = "OneDimensionalRadar"
        private const val READINGS_PER_HOUR = 3600 * 4 // 4 readings per second for 1 hour
        
        // Helper function for consistent logging format
        private fun logFormat(functionName: String, message: String): String {
            return "OneDimensionalRadarImpl.$functionName: $message"
        }
    }
    
    override val radarType: RadarType = RadarType.ONE_DIMENSIONAL_SPEED
    
    // USB Serial communication
    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null
    private val radarScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readJob: Job? = null
    
    // Data parsing
    private val valueRegex = Pattern.compile(
        "([-+]?\\d*\\.?\\d+)(?:\\s*(cm|mm|m|m/s|mph|kph|hz|db))?",
        Pattern.CASE_INSENSITIVE
    )
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    
    // Listeners
    private var dataListener: RadarDataListener? = null
    private var stateListener: RadarStateListener? = null
    
    // State tracking
    private var isConnected = false
    private var isCurrentlyReading = false
    
    // Data saving
    private var isDataSavingEnabled = false
    private var readingCounter = 0
    private var currentDataArray = JSONArray()
    private var customSavePath: String? = null
    private val fileTimeFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    
    init {
        Log.d(TAG, logFormat("init", "Radar instance created for device: ${device.productName ?: device.deviceName}"))
        // Connection will be initialized after listeners are set
    }
    
    private fun initializeConnection() {

        Log.d(TAG, logFormat("initializeConnection", "Device: VID=${device.vendorId}, PID=${device.productId}"))
        
        try {
            val connection = usbManager.openDevice(device) ?: run {
                val errorMsg = "Failed to open USB connection - check permissions"
                Log.e(TAG, logFormat("initializeConnection", errorMsg))
                stateListener?.onRadarError(errorMsg)
                return
            }
            Log.d(TAG, logFormat("initializeConnection", "USB connection opened successfully"))
            
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: run {
                val errorMsg = "No serial driver found for device VID=${device.vendorId}, PID=${device.productId}"
                Log.e(TAG, logFormat("initializeConnection", errorMsg))
                stateListener?.onRadarError(errorMsg)
                connection.close()
                return
            }
            Log.d(TAG, logFormat("initializeConnection", "Serial driver found: ${driver.javaClass.simpleName}"))
            
            val port = driver.ports.firstOrNull() ?: run {
                val errorMsg = "No serial ports found on device"
                Log.e(TAG, logFormat("initializeConnection", errorMsg))
                stateListener?.onRadarError(errorMsg)
                connection.close()
                return
            }
            Log.d(TAG, logFormat("initializeConnection", "Serial port found, attempting to open"))
            
            serialPort = port
            
            // Configure serial port
            port.open(connection)
            //TODO: Check these params
            port.setParameters(57600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true
            
            isConnected = true
            Log.d(TAG, logFormat("initializeConnection", "✅ Serial port configured successfully - radar connected!"))
            stateListener?.onRadarConnected()
            
        } catch (e: Exception) {
            val errorMsg = "Connection error: ${e.message}"
            Log.e(TAG, logFormat("initializeConnection", errorMsg), e)
            try { 
                serialPort?.close() 
            } catch (_: Exception) {}
            serialPort = null
            isConnected = false
            stateListener?.onRadarError(errorMsg)
        }
    }
    
    override fun startReading() {
        if (!isConnected) {
            Log.w(TAG, logFormat("startReading", "Radar not connected"))
            stateListener?.onRadarError("Radar not connected")
            return
        }
        
        if (isCurrentlyReading) {
            Log.w(TAG, logFormat("startReading", "Already reading"))
            return
        }
        
        val port = serialPort ?: run {
            Log.e(TAG, logFormat("startReading", "Serial port not available"))
            stateListener?.onRadarError("Serial port not available")
            return
        }
        
        isCurrentlyReading = true
        Log.d(TAG, logFormat("startReading", "Starting radar reading"))
        
        readJob = radarScope.launch {
            val buf = ByteArray(1024)
            val ascii = Charset.forName("US-ASCII")
            
            while (isActive && isCurrentlyReading) {
                try {
                    val n = port.read(buf, 250) // 250ms timeout for responsive cancellation
                    
                    if (n > 0) {
                        val text = String(buf, 0, n, ascii)
                        val timestamp = System.currentTimeMillis()
                        val formattedTime = timeFmt.format(Date(timestamp))
                        
                        // Process each line
                        text.trimEnd()
                            .split('\n')
                            .map { it.trimEnd('\r') }
                            .filter { it.isNotBlank() }
                            .forEach { line ->
                                Log.v(TAG, logFormat("startReading", "Raw data: [$formattedTime] $line"))
                                processRadarLine(line, timestamp)
                            }
                    }
                } catch (e: Exception) {
                    if (isActive && isCurrentlyReading) {
                        Log.e(TAG, logFormat("startReading", "Read error: ${e.message}"))
                        withContext(Dispatchers.Main) {
                            stateListener?.onRadarError("Read error: ${e.message}")
                        }
                        break
                    }
                }
            }
            
            Log.d(TAG, logFormat("startReading", "Reading loop ended"))
        }
    }
    
    override fun stopReading() {
        if (!isCurrentlyReading) return
        
        Log.d(TAG, logFormat("stopReading", "Stopping radar reading"))
        isCurrentlyReading = false
        readJob?.cancel()
        readJob = null
    }
    
    override fun isReading(): Boolean = isCurrentlyReading
    
    override fun isAvailable(): Boolean = isConnected && serialPort != null
    
    override fun setRadarDataListener(listener: RadarDataListener?) {
        this.dataListener = listener
        // Initialize connection when both listeners are potentially set
        initializeConnectionIfReady()
    }
    
    override fun setRadarStateListener(listener: RadarStateListener?) {
        this.stateListener = listener
        // Initialize connection when both listeners are potentially set
        initializeConnectionIfReady()
    }
    
    /**
     * Initialize connection if state listener is set and not already connected
     */
    private fun initializeConnectionIfReady() {
        if (stateListener != null && !isConnected) {
            Log.d(TAG, logFormat("initializeConnectionIfReady", "Listeners ready, initializing connection"))
            initializeConnection()
        }
    }
    
    private suspend fun processRadarLine(line: String, timestamp: Long) {
        val matcher = valueRegex.matcher(line)
        if (!matcher.find()) {
            // No numeric value found, skip
            return
        }
        
        val valueStr = matcher.group(1) ?: return
        val unitStr = (matcher.group(2) ?: "").lowercase()
        
        val value = valueStr.toFloatOrNull() ?: return
        
        Log.d(TAG, logFormat("processRadarLine", "Parsed radar data: $value $unitStr"))
        
        // Save data if enabled
        if (isDataSavingEnabled) {
            saveRadarData(value, timestamp)
        }
        
        // Notify listener on main thread
        withContext(Dispatchers.Main) {
            dataListener?.onRadarDataReceived(value, unitStr, timestamp)
        }
    }
    
    /**
     * Send command to radar (if needed for configuration)
     */
    fun writeCommand(cmd: String) {
        val port = serialPort ?: return
        try {
            port.write(cmd.toByteArray(Charset.forName("US-ASCII")), 200)
            Log.d(TAG, logFormat("writeCommand", ">> $cmd"))
        } catch (e: Exception) {
            Log.e(TAG, logFormat("writeCommand", "Write error: ${e.message}"))
            stateListener?.onRadarError("Write error: ${e.message}")
        }
    }
    
    // Data saving implementation
    override fun startDataSaving(customFilePath: String?) {
        if (isDataSavingEnabled) {
            Log.w(TAG, logFormat("startDataSaving", "Data saving already enabled"))
            return
        }
        
        // Use FilePermissionManager to get the best available directory
        val saveDirectory = FilePermissionManager.getBestSaveDirectory(context, customFilePath)
        customSavePath = saveDirectory.absolutePath
        
        // Check if we have storage permissions
        if (!FilePermissionManager.hasStoragePermissions(context) && customFilePath != null) {
            Log.w(TAG, logFormat("startDataSaving", "Storage permissions not granted. Custom path may not be accessible."))
            Log.w(TAG, logFormat("startDataSaving", "Consider requesting permissions using FilePermissionManager.requestStoragePermissions()"))
        }
        
        isDataSavingEnabled = true
        readingCounter = 0
        currentDataArray = JSONArray()
        
        Log.d(TAG, logFormat("startDataSaving", "Data saving started, saving to: ${saveDirectory.absolutePath}"))
        Log.d(TAG, logFormat("startDataSaving", "Storage permissions granted: ${FilePermissionManager.hasStoragePermissions(context)}"))
    }
    
    override fun stopDataSaving() {
        if (!isDataSavingEnabled) {
            Log.w(TAG, logFormat("stopDataSaving", "Data saving already disabled"))
            return
        }
        
        // Save any remaining data before stopping
        if (currentDataArray.length() > 0) {
            saveCurrentDataToFile()
        }
        
        isDataSavingEnabled = false
        readingCounter = 0
        currentDataArray = JSONArray()
        Log.d(TAG, logFormat("stopDataSaving", "Data saving stopped"))
    }
    
    override fun isDataSavingEnabled(): Boolean = isDataSavingEnabled
    
    private fun saveRadarData(value: Float, timestamp: Long) {
        try {
            val dataPoint = JSONObject().apply {
                put("timestamp", timestamp)
                put("radar_reading", value)
            }
            
            currentDataArray.put(dataPoint)
            readingCounter++
            
            // Save file when we reach the limit (1 hour worth of data)
            if (readingCounter >= READINGS_PER_HOUR) {
                saveCurrentDataToFile()
                // Reset for next hour
                currentDataArray = JSONArray()
                readingCounter = 0
            }
            
        } catch (e: Exception) {
            Log.e(TAG, logFormat("saveRadarData", "Error saving data: ${e.message}"), e)
        }
    }
    
    private fun saveCurrentDataToFile() {
        try {
            val timestamp = System.currentTimeMillis()
            val filename = "radar_data_${fileTimeFmt.format(Date(timestamp))}.json"
            
            // Use the directory set in startDataSaving
            val saveDirectory = File(customSavePath ?: context.filesDir.absolutePath)
            val file = File(saveDirectory, filename)
            
            // Ensure directory exists
            if (!saveDirectory.exists()) {
                saveDirectory.mkdirs()
            }
            
            FileWriter(file).use { writer ->
                writer.write(currentDataArray.toString(2)) // Pretty print with 2-space indentation
            }
            
            Log.d(TAG, logFormat("saveCurrentDataToFile", "Saved ${currentDataArray.length()} readings to ${file.absolutePath}"))
            
        } catch (e: Exception) {
            Log.e(TAG, logFormat("saveCurrentDataToFile", "Error saving file: ${e.message}"), e)
            
            // Try fallback to internal storage if external fails
            try {
                val timestamp = System.currentTimeMillis()
                val filename = "radar_data_${fileTimeFmt.format(Date(timestamp))}.json"
                val fallbackFile = File(context.filesDir, filename)
                
                FileWriter(fallbackFile).use { writer ->
                    writer.write(currentDataArray.toString(2))
                }
                
                Log.d(TAG, logFormat("saveCurrentDataToFile", "Fallback save successful to ${fallbackFile.absolutePath}"))
                
            } catch (fallbackException: Exception) {
                Log.e(TAG, logFormat("saveCurrentDataToFile", "Fallback save also failed: ${fallbackException.message}"), fallbackException)
            }
        }
    }

    /**
     * Cleanup resources
     */
    fun shutdown() {
        Log.d(TAG, logFormat("shutdown", "Shutting down radar"))
        
        stopReading()
        stopDataSaving()
        
        try {
            serialPort?.close()
        } catch (e: Exception) {
            Log.w(TAG, logFormat("shutdown", "Error closing serial port: ${e.message}"))
        }
        
        serialPort = null
        isConnected = false
        
        radarScope.cancel()
        
        stateListener?.onRadarDisconnected()
        Log.d(TAG, logFormat("shutdown", "Radar shutdown completed"))
    }
}
