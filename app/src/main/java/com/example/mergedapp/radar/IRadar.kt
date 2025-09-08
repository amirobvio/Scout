package com.example.mergedapp.radar

/**
 * Radar interface for different radar sensor types
 * Provides unified API for radar operations
 */
interface IRadar {
    val radarType: RadarType
    
    fun startReading()
    fun stopReading()
    fun isReading(): Boolean
    fun isAvailable(): Boolean
    fun setRadarDataListener(listener: RadarDataListener?)
    fun setRadarStateListener(listener: RadarStateListener?)
}

enum class RadarType {
    ONE_DIMENSIONAL_SPEED
}

/**
 * Listener for radar data updates
 */
interface RadarDataListener {
    fun onRadarDataReceived(value: Float, unit: String, timestamp: Long)
}

/**
 * Listener for radar state changes
 */
interface RadarStateListener {
    fun onRadarConnected()
    fun onRadarDisconnected()
    fun onRadarError(error: String)
}

/**
 * Data class for radar readings
 */
data class RadarReading(
    val value: Float,
    val unit: String,
    val timestamp: Long,
    val formattedTime: String
)
