package com.myra.assistant.ui.settings

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
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
import com.myra.assistant.data.memory.LyraMemoryDatabase
import com.myra.assistant.data.memory.ManualMemoryPolicy
import com.myra.assistant.data.memory.MemoryCategory
import com.myra.assistant.data.memory.MemoryEntity
import com.myra.assistant.data.memory.MemoryRepository
import com.myra.assistant.data.memory.MemoryWriteResult
import com.myra.assistant.databinding.ActivityMemorySettingsBinding
import kotlinx.coroutines.launch

/** User-controlled view of the same Room memory store used by LYRA voice. */
class MemorySettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMemorySettingsBinding
    private val repository by lazy { MemoryRepository(LyraMemoryDatabase.get(this).memoryDao()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemorySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }
        binding.addMemoryButton.setOnClickListener { showMemoryEditor() }
        binding.deleteAllButton.setOnClickListener { confirmDeleteAll() }
    }

    override fun onResume() {
        super.onResume()
        refreshMemories()
    }

    private fun refreshMemories() {
        lifecycleScope.launch {
            val memories = repository.allActive()
            binding.memoryList.removeAllViews()
            binding.emptyText.visibility = if (memories.isEmpty()) View.VISIBLE else View.GONE
            binding.deleteAllButton.isEnabled = memories.isNotEmpty()
            memories.forEach { binding.memoryList.addView(memoryCard(it)) }
        }
    }

    private fun memoryCard(memory: MemoryEntity): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            setBackgroundResource(com.myra.assistant.R.drawable.bg_field)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        card.addView(TextView(this).apply {
            text = memory.fact
            setTextColor(Color.rgb(238, 238, 238))
            textSize = 15f
        })
        card.addView(TextView(this).apply {
            text = "${memory.category.lowercase().replaceFirstChar { it.uppercase() }} · " +
                when (memory.source) {
                    ManualMemoryPolicy.SOURCE -> "Added in Settings"
                    "screen_observation" -> "Observed on screen"
                    else -> "Learned by LYRA"
                }
            setTextColor(Color.rgb(119, 112, 119))
            textSize = 11f
            setPadding(0, dp(6), 0, dp(6))
        })
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            if (memory.source == ManualMemoryPolicy.SOURCE && memory.stableKey.startsWith("manual:")) {
                addView(actionButton("EDIT") { showMemoryEditor(memory) })
            }
            addView(actionButton("DELETE") { confirmDelete(memory) })
        })
        return card
    }

    private fun actionButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(Color.rgb(45, 72, 58))
        layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) }
        setOnClickListener { click() }
    }

    private fun showMemoryEditor(existing: MemoryEntity? = null) {
        val categories = MemoryCategory.entries
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val input = EditText(this).apply {
            hint = "Example: Zopy likes horror movies"
            setText(existing?.fact.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MemorySettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                categories.map { it.name.lowercase().replaceFirstChar { char -> char.uppercase() } }
            )
            val existingCategory = existing?.category?.let { runCatching { MemoryCategory.valueOf(it) }.getOrNull() }
            setSelection(categories.indexOf(existingCategory ?: MemoryCategory.PREFERENCE).coerceAtLeast(0))
        }
        container.addView(input)
        container.addView(spinner)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add memory" else "Edit memory")
            .setMessage("Do not save passwords, OTPs or financial details.")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val fact = input.text.toString()
                val category = categories[spinner.selectedItemPosition]
                lifecycleScope.launch {
                    val result = if (existing == null) {
                        repository.saveManualFact(fact, category)
                    } else {
                        repository.updateManualFact(existing.id, fact, category)
                    }
                    when (result) {
                        is MemoryWriteResult.Saved -> {
                            dialog.dismiss()
                            Toast.makeText(this@MemorySettingsActivity, "Memory saved", Toast.LENGTH_SHORT).show()
                            refreshMemories()
                        }
                        is MemoryWriteResult.Rejected -> Toast.makeText(
                            this@MemorySettingsActivity,
                            result.reason,
                            Toast.LENGTH_LONG
                        ).show()
                        MemoryWriteResult.NeedsPermission -> Toast.makeText(
                            this@MemorySettingsActivity,
                            "Memory could not be saved",
                            Toast.LENGTH_LONG
                        ).show()
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
                    val deleted = repository.forgetFromSettings(memory)
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
            .setMessage("This permanently removes every saved LYRA memory from this phone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete all") { _, _ ->
                lifecycleScope.launch {
                    repository.clearAll()
                    Toast.makeText(this@MemorySettingsActivity, "All memories deleted", Toast.LENGTH_SHORT).show()
                    refreshMemories()
                }
            }
            .show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
