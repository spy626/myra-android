package com.myra.assistant.ui.settings

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.myra.assistant.databinding.ActivityScreenVisionSettingsBinding
import com.myra.assistant.screen.ScreenCaptureService
import com.myra.assistant.screen.ScreenShareState
import com.myra.assistant.screen.ScreenVisionPreferences

class ScreenVisionSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityScreenVisionSettingsBinding
    private lateinit var preferences: ScreenVisionPreferences
    private val stateListener: (ScreenShareState, ByteArray?) -> Unit = { _, _ ->
        runOnUiThread { renderState() }
    }
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            Toast.makeText(this, "Screen sharing permission was not granted", Toast.LENGTH_SHORT).show()
            renderState()
            return@registerForActivityResult
        }
        val service = Intent(this, ScreenCaptureService::class.java)
            .setAction(ScreenCaptureService.ACTION_START)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
        ContextCompat.startForegroundService(this, service)
        renderState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenVisionSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preferences = ScreenVisionPreferences(this)
        binding.backButton.setOnClickListener { finish() }
        binding.visionSwitch.isChecked = preferences.visionEnabled
        binding.learningSwitch.isChecked = preferences.automaticLearning
        binding.saveMemorySwitch.isChecked = preferences.saveScreenMemories
        binding.sensitiveSwitch.isChecked = preferences.sensitiveContentProtection
        val intervals = listOf("3 seconds", "5 seconds", "10 seconds")
        val intervalValues = listOf(3_000L, 5_000L, 10_000L)
        binding.intervalSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, intervals
        )
        binding.intervalSpinner.setSelection(
            intervalValues.indexOf(preferences.analysisIntervalMs).takeIf { it >= 0 } ?: 0
        )
        binding.visionSwitch.setOnCheckedChangeListener { _, checked -> preferences.visionEnabled = checked }
        binding.learningSwitch.setOnCheckedChangeListener { _, checked -> preferences.automaticLearning = checked }
        binding.saveMemorySwitch.setOnCheckedChangeListener { _, checked -> preferences.saveScreenMemories = checked }
        binding.sensitiveSwitch.setOnCheckedChangeListener { _, checked -> preferences.sensitiveContentProtection = checked }
        binding.intervalSpinner.setSelection(binding.intervalSpinner.selectedItemPosition)
        binding.intervalSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                preferences.analysisIntervalMs = intervalValues[position]
            }
        }
        binding.shareButton.setOnClickListener { requestCapture() }
        binding.pauseButton.setOnClickListener {
            startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_PAUSE))
        }
        binding.resumeButton.setOnClickListener {
            startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_RESUME))
        }
        binding.stopButton.setOnClickListener {
            startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
        }
    }

    override fun onStart() {
        super.onStart()
        ScreenCaptureService.listeners += stateListener
        renderState()
    }

    override fun onStop() {
        ScreenCaptureService.listeners -= stateListener
        super.onStop()
    }

    private fun requestCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        permissionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun renderState() {
        val state = ScreenCaptureService.currentState
        binding.stateText.text = "Status: ${state.name.replace('_', ' ')}"
        binding.activeIndicator.text = if (state == ScreenShareState.ACTIVE) "● SHARING ACTIVE" else "○ NOT SHARING"
        binding.shareButton.isEnabled = state !in setOf(ScreenShareState.ACTIVE, ScreenShareState.PAUSED, ScreenShareState.RESUMING)
        binding.pauseButton.isEnabled = state == ScreenShareState.ACTIVE
        binding.resumeButton.isEnabled = state == ScreenShareState.PAUSED
        binding.stopButton.isEnabled = state in setOf(ScreenShareState.ACTIVE, ScreenShareState.PAUSED, ScreenShareState.RESUMING)
        val frame = ScreenCaptureService.latestFrame
        if (frame != null) {
            android.graphics.BitmapFactory.decodeByteArray(frame, 0, frame.size)?.let(binding.preview::setImageBitmap)
        } else binding.preview.setImageDrawable(null)
    }
}

