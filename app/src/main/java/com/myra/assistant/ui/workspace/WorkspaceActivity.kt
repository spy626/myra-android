package com.myra.assistant.ui.workspace

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.databinding.ActivityWorkspaceBinding

/**
 * Phase 1 Workspace shell.
 *
 * This activity intentionally owns UI/navigation only. Project persistence,
 * coding agents, model routing, and durable project knowledge are added in
 * later Workspace phases so they cannot accidentally couple to personal AIRI
 * memory or the existing voice runtime.
 */
class WorkspaceActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWorkspaceBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkspaceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.newProjectButton.setOnClickListener {
            Toast.makeText(this, "New Project foundation is next", Toast.LENGTH_SHORT).show()
        }
        binding.openProjectButton.setOnClickListener {
            Toast.makeText(this, "Open Project foundation is next", Toast.LENGTH_SHORT).show()
        }
    }
}
