package com.myra.assistant.ui.workspace

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Workspace-only navigation: chats are conversations, never a permission to delete project files. */
internal object WorkspaceNavigationDrawer {
    fun show(
        activity: AppCompatActivity,
        projects: List<WorkspaceProject>,
        chatProjectIds: Set<String>,
        selectedProjectId: String?,
        pinnedChatIds: Set<String>,
        titleFor: (WorkspaceProject) -> String,
        onNewChat: () -> Unit,
        onPlugins: () -> Unit,
        onApiSettings: () -> Unit,
        onSelectProject: (String) -> Unit,
        onTogglePin: (String) -> Unit,
        onRenameChat: (String, String) -> Unit,
        onDeleteChat: (String) -> Unit
    ) {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density + .5f).toInt()
        val dialog = Dialog(activity)
        val frame = FrameLayout(activity).apply { setBackgroundColor(Color.TRANSPARENT) }
        frame.addView(View(activity).apply {
            contentDescription = "Close Workspace navigation"
            setOnClickListener { dialog.dismiss() }
        }, FrameLayout.LayoutParams(-1, -1))
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(32, 32, 32))
            setPadding(dp(16), dp(18), dp(12), dp(16))
            isClickable = true
        }
        val width = (activity.resources.displayMetrics.widthPixels - dp(48))
            .coerceAtMost(dp(360)).coerceAtLeast(dp(220))
        frame.addView(panel, FrameLayout.LayoutParams(width, -1, Gravity.START))

        fun item(text: String, description: String = text, action: () -> Unit): TextView =
            TextView(activity).apply {
                this.text = text
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(12), 0)
                minHeight = dp(55)
                contentDescription = description
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(12).toFloat()
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog.dismiss(); action() }
            }
        fun section(title: String) {
            panel.addView(TextView(activity).apply {
                text = title
                textSize = 12f
                setTextColor(Color.rgb(175, 175, 175))
                setPadding(dp(16), dp(8), 0, dp(8))
            })
        }
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        header.addView(TextView(activity).apply {
            text = "LYRA Workspace"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), 0, dp(8))
        }, LinearLayout.LayoutParams(0, dp(64), 1f))
        header.addView(item("×", "Close navigation") {}, LinearLayout.LayoutParams(dp(48), dp(55)))
        panel.addView(header)
        panel.addView(item("＋   New Chat", action = onNewChat))
        panel.addView(item("◉   Plugins", action = onPlugins))
        panel.addView(item("⚙   API & Cloud Settings", action = onApiSettings))
        panel.addView(View(activity).apply {
            setBackgroundColor(Color.rgb(68, 68, 68))
        }, LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(18)
            bottomMargin = dp(12)
        })
        val projectList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(projectList)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        fun showChatActions(project: WorkspaceProject) {
            val pinned = project.projectId in pinnedChatIds
            val options = arrayOf(if (pinned) "Unpin" else "Pin", "Rename", "Delete")
            AlertDialog.Builder(activity)
                .setTitle(titleFor(project))
                .setItems(options) { _, index ->
                    when (index) {
                        0 -> { dialog.dismiss(); onTogglePin(project.projectId) }
                        1 -> {
                            val editor = EditText(activity).apply {
                                setSingleLine(true)
                                setText(titleFor(project))
                                setSelection(text.length)
                                filters = arrayOf(android.text.InputFilter.LengthFilter(80))
                                setPadding(dp(18), dp(16), dp(18), dp(16))
                            }
                            AlertDialog.Builder(activity).setTitle("Rename Chat")
                                .setView(editor)
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Rename") { _, _ ->
                                    dialog.dismiss()
                                    onRenameChat(project.projectId, editor.text.toString())
                                }.show()
                        }
                        2 -> { dialog.dismiss(); onDeleteChat(project.projectId) }
                    }
                }.show()
        }
        fun projectRow(project: WorkspaceProject, chat: Boolean) {
            val row = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }
            val pinned = project.projectId in pinnedChatIds
            val title = if (chat) titleFor(project) else project.name
            val entry = item(
                "${if (pinned && chat) "📌   " else if (project.projectId == selectedProjectId) "✓   " else "▢   "}$title",
                "Open ${if (chat) "chat" else "project"} $title"
            ) { onSelectProject(project.projectId) }
            if (chat) {
                entry.isLongClickable = true
                entry.setOnLongClickListener { showChatActions(project); true }
            }
            row.addView(entry, LinearLayout.LayoutParams(0, dp(55), 1f))
            if (chat) row.addView(item("⋯", "Options for chat $title") {
                // The item helper dismisses the drawer; the menu is opened on a new dialog.
                showChatActions(project)
            }, LinearLayout.LayoutParams(dp(48), dp(55)))
            projectList.addView(row)
        }
        val chats = projects.filter { it.projectId in chatProjectIds }
            .sortedWith(compareByDescending<WorkspaceProject> { it.projectId in pinnedChatIds }
                .thenByDescending { it.lastOpenedAtMs })
        projectList.addView(TextView(activity).apply {
            text = "CHATS"
            textSize = 12f
            setTextColor(Color.rgb(175, 175, 175))
            setPadding(dp(16), dp(8), 0, dp(8))
        })
        chats.forEach { projectRow(it, chat = true) }
        if (chats.isEmpty()) projectList.addView(TextView(activity).apply {
            text = "Your chats will appear here after the first message."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(dp(16), dp(14), dp(12), dp(14))
        })
        projectList.addView(View(activity).apply { setBackgroundColor(Color.rgb(68, 68, 68)) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(14); bottomMargin = dp(8) })
        projectList.addView(TextView(activity).apply {
            text = "PROJECTS"
            textSize = 12f
            setTextColor(Color.rgb(175, 175, 175))
            setPadding(dp(16), dp(8), 0, dp(8))
        })
        projects.filterNot { it.projectId in chatProjectIds }.forEach { projectRow(it, chat = false) }

        dialog.setContentView(frame)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
            setGravity(Gravity.START or Gravity.TOP)
        }
        dialog.show()
        dialog.window?.setLayout(-1, -1)
    }
}
