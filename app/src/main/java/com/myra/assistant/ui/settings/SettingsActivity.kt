package com.myra.assistant.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.myra.assistant.databinding.ActivitySettingsBinding
import com.myra.assistant.diagnostics.VoicePipelineLogger

/** Stable full-screen settings hub. It cannot be dragged or swipe-dismissed. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.backButton.setOnClickListener { finish() }
        b.apiCloudCard.setOnClickListener { startActivity(Intent(this, ApiCloudSettingsActivity::class.java)) }
        b.voiceAiCard.setOnClickListener { startActivity(Intent(this, VoiceAiSettingsActivity::class.java)) }
        b.permissionsCard.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        b.notificationAccessCard.setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        b.exportVoiceLogsCard.setOnClickListener { exportVoiceLogs() }
        b.orbCard.setOnClickListener { Toast.makeText(this, "Orb customization is the next design module", Toast.LENGTH_SHORT).show() }
        b.contactsCard.setOnClickListener { Toast.makeText(this, "Prime contacts editor is being moved here next", Toast.LENGTH_SHORT).show() }
    }

    private fun exportVoiceLogs() {
        VoicePipelineLogger.createExport(this) { result ->
            result.onSuccess { file ->
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "LYRA voice pipeline logs")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "Share LYRA voice logs"))
            }.onFailure {
                Toast.makeText(this, "Could not export voice logs", Toast.LENGTH_LONG).show()
            }
        }
    }
}
