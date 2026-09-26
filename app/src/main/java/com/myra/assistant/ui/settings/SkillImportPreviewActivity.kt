package com.myra.assistant.ui.settings

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import com.myra.assistant.databinding.ActivitySkillImportPreviewBinding
import com.myra.assistant.ui.workspace.WorkspaceSkillImportPreview
import java.io.ByteArrayOutputStream

/**
 * H6 local import preview only.
 *
 * Selected document bytes are held only in this Activity process state and are never copied to the
 * skill store. H7 will own any future explicit install approval/persistence path.
 */
class SkillImportPreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySkillImportPreviewBinding
    private var skillMdBytes: ByteArray? = null
    private var skillJsonBytes: ByteArray? = null

    private val skillMdPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            selectFile(uri, "SKILL.md") { bytes ->
                skillMdBytes = bytes
                binding.skillMdState.text = "SKILL.md selected · " + bytes.size + " bytes"
                renderPreview()
            }
        }

    private val skillJsonPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            selectFile(uri, "skill.json") { bytes ->
                skillJsonBytes = bytes
                binding.skillJsonState.text = "skill.json selected · " + bytes.size + " bytes"
                binding.clearManifestButton.visibility = View.VISIBLE
                renderPreview()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkillImportPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.chooseSkillMdButton.setOnClickListener {
            skillMdPicker.launch(arrayOf("text/*", "application/octet-stream"))
        }
        binding.chooseSkillJsonButton.setOnClickListener {
            skillJsonPicker.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
        }
        binding.clearManifestButton.setOnClickListener {
            skillJsonBytes = null
            binding.skillJsonState.text = "No skill.json selected"
            binding.clearManifestButton.visibility = View.GONE
            renderPreview()
        }
    }

    private fun selectFile(
        uri: Uri,
        expectedName: String,
        onSuccess: (ByteArray) -> Unit,
    ) {
        val result = runCatching {
            val actualName = displayName(uri)
            require(actualName == expectedName) {
                "Choose the exact " + expectedName + " file"
            }
            readBounded(uri)
        }
        result.onSuccess(onSuccess).onFailure { error ->
            Toast.makeText(
                this,
                error.message ?: "Selected file could not be inspected",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            .use { cursor ->
                require(cursor != null && cursor.moveToFirst()) {
                    "Selected document name is unavailable"
                }
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                require(index >= 0) { "Selected document name is unavailable" }
                return cursor.getString(index).orEmpty()
            }
    }

    private fun readBounded(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Selected document could not be opened")
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                require(total <= WorkspaceSkillImportPreview.MAX_FILE_BYTES) {
                    "Selected file exceeds the local skill import bound"
                }
                output.write(buffer, 0, read)
            }
            require(total > 0) { "Selected file is empty" }
            return output.toByteArray()
        }
    }

    private fun renderPreview() {
        binding.previewRows.removeAllViews()
        binding.warningRows.removeAllViews()
        val md = skillMdBytes
        if (md == null) {
            binding.previewStatus.text = "SELECT SKILL.MD"
            binding.previewStatus.setTextColor(Color.rgb(145, 137, 145))
            binding.previewName.text = "No skill preview yet"
            binding.previewDescription.text =
                "Choose SKILL.md first. skill.json is optional."
            binding.previewCard.visibility = View.GONE
            return
        }

        val result = runCatching {
            WorkspaceSkillImportPreview.inspect(md, skillJsonBytes)
        }
        result.onFailure { error ->
            binding.previewStatus.text = "PREVIEW BLOCKED"
            binding.previewStatus.setTextColor(Color.rgb(255, 80, 110))
            binding.previewName.text = "Invalid local skill"
            binding.previewDescription.text =
                error.message ?: "Selected skill could not be parsed safely."
            binding.previewCard.visibility = View.VISIBLE
            return
        }

        val preview = result.getOrThrow()
        binding.previewCard.visibility = View.VISIBLE
        binding.previewName.text = preview.name
        binding.previewDescription.text = preview.description
        binding.previewStatus.text = when (preview.status) {
            WorkspaceSkillImportPreview.Status.READY_FOR_INSTALL_REVIEW ->
                "PREVIEW READY"
            WorkspaceSkillImportPreview.Status.BLOCKED_SECRET ->
                "BLOCKED · POSSIBLE SECRET"
        }
        binding.previewStatus.setTextColor(
            if (preview.status == WorkspaceSkillImportPreview.Status.READY_FOR_INSTALL_REVIEW)
                Color.rgb(108, 194, 145)
            else Color.rgb(255, 80, 110)
        )
        binding.previewResult.text = preview.statusDetail
        preview.rows.forEach { binding.previewRows.addView(row(it.label, it.value)) }
        preview.warnings.forEach { warning ->
            binding.warningRows.addView(row("WARNING", warning, warning = true))
        }
    }

    private fun row(label: String, value: String, warning: Boolean = false): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.bg_field)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            addView(TextView(this@SkillImportPreviewActivity).apply {
                text = label.uppercase()
                setTextColor(
                    if (warning) Color.rgb(255, 180, 90)
                    else Color.rgb(108, 194, 145)
                )
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@SkillImportPreviewActivity).apply {
                text = value
                setTextColor(Color.rgb(224, 222, 224))
                textSize = 13f
                setPadding(0, dp(4), 0, 0)
                setTextIsSelectable(true)
            })
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
