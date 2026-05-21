package com.mickey.phonemirror

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            statusTextView.text = "Audio permission granted. Requesting screen projection..."
        } else {
            statusTextView.text = "Audio permission denied. Screen projection will start without audio..."
        }
        launchProjection()
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startMirrorService(result.resultCode, result.data!!)
        } else {
            statusTextView.text = "Permission Denied: Screen capture is required to mirror your device."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Simple programmatic UI to avoid layout XML configuration issues
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xFF1E1E2E.toInt()) // Sleek dark slate
            setPadding(64, 64, 64, 64)
        }

        val titleView = TextView(this).apply {
            text = "Phone Mirror Server"
            textSize = 24f
            setTextColor(0xFF89B4FA.toInt()) // Lavender blue
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        statusTextView = TextView(this).apply {
            text = "Initializing projection permission request..."
            textSize = 16f
            setTextColor(0xFFCDD6F4.toInt()) // Light grey text
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 64)
        }

        val instructionView = TextView(this).apply {
            text = "Keep this app open.\nConnect via USB and launch the desktop client."
            textSize = 14f
            setTextColor(0xFFBAC2DE.toInt()) // Muted purple-grey
            gravity = android.view.Gravity.CENTER
        }

        container.addView(titleView)
        container.addView(statusTextView)
        container.addView(instructionView)
        setContentView(container)

        requestProjectionPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Update activity intent to read latest quality extras
        requestProjectionPermission()
    }

    private fun requestProjectionPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) 
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            launchProjection()
        } else {
            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchProjection() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startMirrorService(resultCode: Int, data: Intent) {
        statusTextView.text = "Permission Granted! Starting capture service..."
        
        // Extract configuration extras passed from ADB shell start command
        val bitrate = intent?.getIntExtra("bitrate", 12000000) ?: 12000000
        val fps = intent?.getIntExtra("fps", 60) ?: 60
        val maxRes = intent?.getIntExtra("max_res", 0) ?: 0

        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_DATA, data)
            putExtra("bitrate", bitrate)
            putExtra("fps", fps)
            putExtra("max_res", maxRes)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }
}
