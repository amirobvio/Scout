package com.example.mergedapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.mergedapp.test.DetectionBasedRecordingTestActivity

/**
 * Main launcher activity for the Merged Camera App
 * Provides access to different camera test modes
 */
class MainActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        createUI()
    }
    
    private fun createUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
        }
        
        // Title
        val titleText = TextView(this).apply {
            text = "📷 Merged Camera App - Phase 3"
            textSize = 20f
            setPadding(0, 0, 0, 40)
        }
        
        // Description
        val descText = TextView(this).apply {
            text = "Phase 3: Detection-Based Recording Complete\n\n" +
                    "✅ USB Camera Interface (AUSBC)\n" +
                    "✅ Internal Camera Interface (CameraX)\n" +
                    "✅ USB Permission Management\n" +
                    "✅ Frame Callback System\n" +
                    "✅ Recording System\n" +
                    "✅ TensorFlow Lite Object Detection\n" +
                    "✅ Dual Camera Detection-Based Recording\n" +
                    "🎯 Production Ready"
            textSize = 14f
            setPadding(0, 0, 0, 40)
        }
        
        // Detection-Based Recording Test Button
        val detectionTestButton = Button(this).apply {
            text = "🎯 Start Detection-Based Recording"
            textSize = 16f
            setPadding(20, 20, 20, 20)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, DetectionBasedRecordingTestActivity::class.java))
            }
        }
        
        // Instructions
        val instructionsText = TextView(this).apply {
            text = "\n📋 Instructions:\n" +
                    "1. Connect a USB camera to your device (optional)\n" +
                    "2. Tap 'Start Detection-Based Recording' button\n" +
                    "3. Grant camera and USB permissions when prompted\n" +
                    "4. Place target objects (laptop) in camera view\n" +
                    "5. Recording starts automatically when objects detected\n\n" +
                    "🎯 Target Object: laptop\n" +
                    "📷 Works with USB + Internal cameras simultaneously\n" +
                    "⚠️ USB camera should be UVC compatible"
            textSize = 12f
            setPadding(0, 20, 0, 0)
        }
        
        // Add all views
        layout.addView(titleText)
        layout.addView(descText)
        layout.addView(detectionTestButton)
        layout.addView(instructionsText)
        
        setContentView(layout)
    }
}