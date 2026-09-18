package com.myra.assistant.ui.workspace

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Workspace-only navigation. Dialog back and outside taps close it without leaving the Workspace. */
internal object WorkspaceNavigationDrawer {
    fun show(
        activity: AppCompatActivity,
        projects: List<WorkspaceProject>,
        selectedProjectId: String?,
        onNewProject: () -> Unit,
        onPlugins: () -> Unit,
        onApiSettings: () -> Unit,
        onSelectProject: (String) -> Unit
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
            isClickable = true // A tap on the panel must never close the scrim.
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
        panel.addView(item("＋   New Project", action = onNewProject))
        panel.addView(item("◉   Plugins", action = onPlugins))
        panel.addView(item("⚙   API & Cloud Settings", action = onApiSettings))
        panel.addView(View(activity).apply {
            setBackgroundColor(Color.rgb(68, 68, 68))
        }, LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(18)
            bottomMargin = dp(12)
        })
        panel.addView(TextView(activity).apply {
            text = "PROJECTS"
            textSize = 12f
            setTextColor(Color.rgb(175, 175, 175))
            setPadding(dp(16), dp(8), 0, dp(8))
        })
        val projectList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val projectScroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(projectList)
        }
        panel.addView(projectScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (projects.isEmpty()) {
            projectList.addView(TextView(activity).apply {
                text = "No projects yet. Create one to get started."
                textSize = 14f
                setTextColor(Color.LTGRAY)
                setPadding(dp(16), dp(14), dp(12), dp(14))
            })
        } else {
            projects.forEach { project ->
                projectList.addView(item(
                    "${if (project.projectId == selectedProjectId) "✓   " else "▢   "}${project.name}",
                    "Open project ${project.name}"
                ) { onSelectProject(project.projectId) })
            }
        }

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
