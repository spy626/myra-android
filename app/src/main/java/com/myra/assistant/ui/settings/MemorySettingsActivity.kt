package com.myra.assistant.ui.settings

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.myra.assistant.data.memory.MemoryBrainCoordinator
import com.myra.assistant.data.memory.MemoryCategory
import com.myra.assistant.data.memory.MemoryCoreManualActions
import com.myra.assistant.data.memory.MemoryEntity
import com.myra.assistant.data.memory.MemoryPrivacyPreferences
import com.myra.assistant.data.memory.MemoryWriteResult
import com.myra.assistant.databinding.ActivityMemorySettingsBinding
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** User-controlled view of the same JARVIS Room owner used by LYRA voice. */
class MemorySettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMemorySettingsBinding
    private val memoryOwner by lazy { MemoryBrainCoordinator.get(this) }
    private val privacyPreferences by lazy { MemoryPrivacyPreferences(this) }
    private var activeFilter = "ALL"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemorySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }
        binding.addMemoryButton.setOnClickListener { showMemoryEditor() }
        binding.deleteAllButton.setOnClickListener { confirmDeleteAll() }
        setupPrivacyControls()
        setupFilters()
    }

    override fun onResume() {
        super.onResume()
        refreshMemories()
    }

    private fun setupPrivacyControls() {
        binding.passiveAppLearningSwitch.isChecked = privacyPreferences.passiveAppLearningEnabled
        binding.passiveContentLearningSwitch.isChecked = privacyPreferences.passiveContentLearningEnabled
        binding.passiveAppLearningSwitch.setOnCheckedChangeListener { _, checked ->
            privacyPreferences.passiveAppLearningEnabled = checked
        }
        binding.passiveContentLearningSwitch.setOnCheckedChangeListener { _, checked ->
            privacyPreferences.passiveContentLearningEnabled = checked
        }
    }

    private fun refreshMemories() {
        lifecycleScope.launch {
            val memories = memoryOwner.activeCards().filter(::matchesFilter)
            binding.memoryList.removeAllViews()
            binding.emptyText.visibility = if (memories.isEmpty()) View.VISIBLE else View.GONE
            binding.deleteAllButton.isEnabled = memories.isNotEmpty()
            memories.forEach { binding.memoryList.addView(memoryCard(it)) }
        }
    }

    private fun setupFilters() {
        listOf("ALL", "PEOPLE", "RELATIONSHIPS", "PREFERENCES", "PROJECTS", "GOALS", "IDEAS").forEach { filter ->
            binding.memoryFilters.addView(Button(this).apply {
                text = filter
                textSize = 10f
                setOnClickListener {
                    activeFilter = filter
                    refreshMemories()
                }
            })
        }
    }

    private fun matchesFilter(memory: MemoryEntity): Boolean = when (activeFilter) {
        "ALL" -> true
        "PEOPLE", "RELATIONSHIPS" -> memory.kind == "RELATIONSHIP"
        "PREFERENCES" -> memory.category in setOf(MemoryCategory.PREFERENCE.name, MemoryCategory.COMMUNICATION_STYLE.name)
        "PROJECTS" -> memory.category == MemoryCategory.PROJECT.name
        "GOALS" -> memory.category == MemoryCategory.GOAL.name
        "IDEAS" -> memory.category == MemoryCategory.IDEA.name
        else -> true
    }

    private fun memoryCard(memory: MemoryEntity): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(16), dp(14))
            setBackgroundResource(com.myra.assistant.R.drawable.bg_field)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val accent = Color.rgb(108, 194, 145)
        card.addView(android.widget.ImageView(this).apply {
            setImageResource(categoryIconResource(memory.category))
            setColorFilter(accent)
            contentDescription = "${memory.category.replace('_', ' ')} memory"
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(13).toFloat()
                setColor(Color.rgb(12, 17, 15))
                setStroke(dp(1), Color.rgb(45, 72, 58))
            }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
            }
        }
        content.addView(TextView(this).apply {
            text = if (memory.kind == "RELATIONSHIP") "RELATIONSHIP" else memory.category.replace('_', ' ')
            setTextColor(accent)
            textSize = 11f
        })
        content.addView(TextView(this).apply {
            text = memory.fact
            setTextColor(Color.rgb(238, 238, 238))
            textSize = 15f
            setPadding(0, dp(4), 0, 0)
        })
        content.addView(TextView(this).apply {
            text = buildString {
                append("Source: ")
                val sourceChannel = memory.sourceKind?.takeIf { it.isNotBlank() } ?: memory.provenance
                append(sourceChannel.replace('_', ' '))
                if (memory.sourceTurnId > 0L) append(" · turn ").append(memory.sourceTurnId)
            }
            setTextColor(Color.rgb(119, 112, 119))
            textSize = 11f
            setPadding(0, dp(7), 0, 0)
        })
        card.addView(content)
        card.setOnClickListener { showMemoryDetails(memory) }
        return card
    }

    internal fun categoryIconResource(category: String): Int = when (category) {
        MemoryCategory.IDENTITY.name -> android.R.drawable.ic_menu_info_details
        MemoryCategory.PERSON.name -> android.R.drawable.ic_menu_myplaces
        MemoryCategory.PREFERENCE.name, MemoryCategory.COMMUNICATION_STYLE.name -> android.R.drawable.btn_star
        MemoryCategory.PROJECT.name, MemoryCategory.IDEA.name, MemoryCategory.WORKFLOW.name, MemoryCategory.SOLUTION.name -> android.R.drawable.ic_menu_agenda
        MemoryCategory.GOAL.name -> android.R.drawable.ic_menu_compass
        else -> com.myra.assistant.R.drawable.ic_lyra_sparkle
    }

    private fun showMemoryDetails(memory: MemoryEntity) {
        val sourceChannel = memory.sourceKind?.takeIf { it.isNotBlank() } ?: memory.provenance
        val sourceText = memory.sourceText?.takeIf { it.isNotBlank() } ?: "Not available for migrated legacy data"
        val sourceSession = memory.sourceSessionId?.takeIf { it.isNotBlank() } ?: "Not available"
        val sourceUtterance = memory.sourceUtteranceId?.takeIf { it.isNotBlank() } ?: "Not available"
        val details = buildString {
            append(memory.fact).append("\n\n")
            append("Category: ").append(if (memory.kind == "RELATIONSHIP") "Relationship" else memory.category.replace('_', ' ')).append('\n')
            append("Created: ").append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(memory.createdAt))).append('\n')
            append("Updated: ").append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(memory.updatedAt))).append('\n')
            append("Last recalled: ").append(
                if (memory.lastRecalledAt > 0L) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(memory.lastRecalledAt))
                else "Never"
            ).append("\n\n")
            append("Source channel: ").append(sourceChannel.replace('_', ' ')).append('\n')
            append("Original source: ").append(sourceText).append('\n')
            append("Source session: ").append(sourceSession).append('\n')
            append("Source turn: ").append(if (memory.sourceTurnId > 0L) memory.sourceTurnId else "Not available").append('\n')
            append("Source utterance: ").append(sourceUtterance)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("Memory details")
            .setMessage(details)
            .setNegativeButton("Close", null)
            .setNeutralButton("Delete") { _, _ -> confirmDelete(memory) }
        if (memory.kind == "RELATIONSHIP" && !memory.entityName.isNullOrBlank()) {
            builder.setPositiveButton("Rename person") { _, _ -> showPersonRename(memory) }
        }
        builder.show()
    }

    private fun showPersonRename(memory: MemoryEntity) {
        val oldName = memory.entityName
        if (oldName.isNullOrBlank()) {
            Toast.makeText(this, "This relationship has no editable person name", Toast.LENGTH_LONG).show()
            return
        }
        val input = EditText(this).apply {
            setText(oldName)
            selectAll()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Rename person")
            .setMessage("The relationship stays the same; only this person's name changes.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                lifecycleScope.launch {
                    val renamed = memory.entityId?.let { memoryOwner.renameFromManualUi(it, input.text.toString()) } == true
                    if (renamed) {
                        dialog.dismiss()
                        refreshMemories()
                    } else {
                        Toast.makeText(this@MemorySettingsActivity, "Rename was not verified", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showMemoryEditor() {
        // Relationship rows are learned from an explicit statement such as "Kareem is my friend".
        // The generic manual editor is intentionally limited to fact-like categories so it cannot
        // manufacture a relationship without a person/type contract.
        val categories = listOf(
            MemoryCategory.IDENTITY,
            MemoryCategory.PREFERENCE,
            MemoryCategory.PROJECT,
            MemoryCategory.GOAL,
            MemoryCategory.IDEA
        )
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val input = EditText(this).apply {
            hint = "Example: I prefer short answers"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MemorySettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                categories.map { it.name.lowercase().replaceFirstChar(Char::uppercase) }
            )
            setSelection(1)
        }
        container.addView(input)
        container.addView(spinner)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Add memory")
            .setMessage("Passwords, OTPs, API keys and financial identifiers are never accepted as memory.")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val fact = input.text.toString()
                val category = categories[spinner.selectedItemPosition]
                lifecycleScope.launch {
                    when (val result = MemoryCoreManualActions.add(memoryOwner, fact, category)) {
                        is MemoryWriteResult.Saved -> {
                            dialog.dismiss()
                            Toast.makeText(this@MemorySettingsActivity, "Memory saved", Toast.LENGTH_SHORT).show()
                            refreshMemories()
                        }
                        is MemoryWriteResult.Rejected -> Toast.makeText(this@MemorySettingsActivity, result.reason, Toast.LENGTH_LONG).show()
                        MemoryWriteResult.NeedsPermission -> Toast.makeText(this@MemorySettingsActivity, "Memory could not be saved", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(memory: MemoryEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete this memory?")
            .setMessage(memory.fact)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val deleted = memoryOwner.deleteMemory(memory)
                    Toast.makeText(
                        this@MemorySettingsActivity,
                        if (deleted) "Memory deleted" else "Memory was already removed",
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshMemories()
                }
            }
            .show()
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this)
            .setTitle("Delete all memories?")
            .setMessage("This removes saved JARVIS long-term memories. Chat and command history are separate.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete all") { _, _ ->
                lifecycleScope.launch {
                    val cleared = memoryOwner.clearMemories()
                    Toast.makeText(
                        this@MemorySettingsActivity,
                        if (cleared) "All memories deleted" else "Memory deletion was not verified",
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshMemories()
                }
            }
            .show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
