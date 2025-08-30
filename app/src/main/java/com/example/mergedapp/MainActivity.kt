package com.example.mergedapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.mergedapp.test.USBCameraTestActivity
import com.example.mergedapp.test.InternalCameraTestActivity

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
            text = "📷 Merged Camera App - Phase 1"
            textSize = 20f
            setPadding(0, 0, 0, 40)
        }
        
        // Description
        val descText = TextView(this).apply {
            text = "Phase 2: Camera Implementation Complete\n\n" +
                    "✅ USB Camera Interface (AUSBC)\n" +
                    "✅ Internal Camera Interface (CameraX)\n" +
                    "✅ USB Permission Management\n" +
                    "✅ Frame Callback System\n" +
                    "✅ Recording System\n" +
                    "🎯 Ready for Detection Integration"
            textSize = 14f
            setPadding(0, 0, 0, 40)
        }
        
        // USB Camera Test Button
        val usbTestButton = Button(this).apply {
            text = "🔌 Test USB Camera"
            textSize = 16f
            setPadding(20, 20, 20, 20)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, USBCameraTestActivity::class.java))
            }
        }
        
        // Internal Camera Test Button
        val internalTestButton = Button(this).apply {
            text = "📱 Test Internal Camera"
            textSize = 16f
            setPadding(20, 20, 20, 20)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, InternalCameraTestActivity::class.java))
            }
        }
        
        // Instructions
        val instructionsText = TextView(this).apply {
            text = "\n📋 Instructions:\n" +
                    "1. Connect a USB camera to your device\n" +
                    "2. Tap 'Test USB Camera' button\n" +
                    "3. Grant USB permissions when prompted\n" +
                    "4. Test recording and frame callbacks\n\n" +
                    "⚠️ Make sure your USB camera is UVC compatible"
            textSize = 12f
            setPadding(0, 20, 0, 0)
        }
        
        // Add all views
        layout.addView(titleText)
        layout.addView(descText)
        layout.addView(usbTestButton)
        layout.addView(internalTestButton)
        layout.addView(instructionsText)
        
        setContentView(layout)
    }
}