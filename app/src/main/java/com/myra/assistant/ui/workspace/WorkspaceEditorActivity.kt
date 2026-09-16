package com.myra.assistant.ui.workspace

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import com.myra.assistant.databinding.ActivityWorkspaceEditorBinding
import java.io.File

/** Native, phone-first Workspace editor; no model, browser, preview or personal memory dependency. */
class WorkspaceEditorActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_PROJECT_ID = "workspace_project_id"
        private const val STATE_TABS = "workspace_editor_tabs"
        fun intent(context: Context, projectId: String): Intent =
            Intent(context, WorkspaceEditorActivity::class.java).putExtra(EXTRA_PROJECT_ID, projectId)
    }

    private lateinit var binding: ActivityWorkspaceEditorBinding
    private val projects by lazy { WorkspaceProjectStore(File(filesDir, "workspace/projects")) }
    private val files by lazy { WorkspaceFileStore(projects) }
    private lateinit var id: String
    private val tabs = linkedSetOf<String>()
    private val collapsed = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var currentPath: String? = null
    private var dirty = false
    private var loading = false
    private var treeOpen = false
    private val spans = mutableListOf<ForegroundColorSpan>()
    private val keywords = Regex("\\b(?:fun|class|val|var|const|let|function|return|if|else|for|while|import|package|public|private|true|false|null|new|async|await|interface|object|override)\\b")
    private val tags = Regex("</?[A-Za-z][^>]*>")
    private val strings = Regex("\"[^\"\\n]*\"|'[^'\\n]*'")
    private val comments = Regex("//[^\\n]*|<!--[\\s\\S]*?-->")
    private val recolor = Runnable { applySyntaxColors() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkspaceEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // Three or four rows are enough while browsing. The editor owns the remaining height.
        binding.fileTreeScroll.layoutParams = binding.fileTreeScroll.layoutParams.apply { height = dp(136) }
        id = intent.getStringExtra(EXTRA_PROJECT_ID).orEmpty()
        val project = projects.getProject(id)
        if (project == null) {
            toast("Workspace project is unavailable")
            finish()
            return
        }
        binding.editorHeading.text = project.name
        binding.backButton.setOnClickListener { attemptClose() }
        binding.filesButton.setOnClickListener {
            val nextOpen = WorkspaceEditorPanelPolicy.afterFilesButton(treeOpen)
            if (nextOpen) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(binding.codeEditor.windowToken, 0)
                binding.filesButton.requestFocus()
            }
            setTreeOpen(nextOpen)
        }
        binding.newFileButton.setOnClickListener { withSavedChanges { showPathDialog(false) } }
        binding.newFolderButton.setOnClickListener { withSavedChanges { showPathDialog(true) } }
        binding.saveButton.setOnClickListener { saveCurrent() }
        binding.starterButton.setOnClickListener {
            withSavedChanges {
                try {
                    files.addWebsiteStarter(id)
                    renderTree()
                    openFile("index.html")
                } catch (error: Exception) { toast(error.message ?: "Starter could not be created") }
            }
        }
        binding.codeEditor.setOnFocusChangeListener { _, focused ->
            if (focused && treeOpen) setTreeOpen(WorkspaceEditorPanelPolicy.afterEditorFocus(treeOpen))
        }
        binding.codeEditor.setOnClickListener {
            if (treeOpen) setTreeOpen(WorkspaceEditorPanelPolicy.afterEditorFocus(treeOpen))
        }
        binding.codeEditor.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            binding.lineNumbers.scrollTo(0, scrollY)
        }
        binding.codeEditor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (loading || currentPath == null) return
                dirty = true
                renderStatus()
                handler.removeCallbacks(recolor)
                handler.postDelayed(recolor, 180)
            }
        })
        savedInstanceState?.getStringArrayList(STATE_TABS)?.forEach { tabs.add(it) }
        setTreeOpen(false)
        val restored = project.activeFilePath
        if (restored != null) openFile(restored)
        else renderStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(STATE_TABS, ArrayList(tabs))
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        // The current on-disk file is the recoverable session truth if Android backgrounds/kills us.
        if (dirty) saveCurrent()
        super.onPause()
    }

    @Deprecated("Back callback for existing AppCompat navigation")
    override fun onBackPressed() { attemptClose() }

    private fun attemptClose() = withSavedChanges { finish() }

    private fun setTreeOpen(open: Boolean) {
        treeOpen = open
        binding.fileTreeScroll.visibility = if (open) View.VISIBLE else View.GONE
        binding.filesButton.text = if (open) "▾  Files" else "▸  Files"
        binding.filesButton.contentDescription = if (open) "Hide project files" else "Show project files"
        if (open) renderTree() else binding.starterButton.visibility = View.GONE
    }

    private fun withSavedChanges(next: () -> Unit) {
        if (!dirty) { next(); return }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Unsaved changes")
            .setMessage("Save changes to ${currentPath ?: "this file"} before continuing?")
            .setPositiveButton("Save", null)
            .setNeutralButton("Discard") { _, _ -> dirty = false; next() }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            styleDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (saveCurrent()) { dialog.dismiss(); next() }
            }
        }
        dialog.show()
    }

    private fun openFile(path: String) {
        if (path == currentPath) {
            setTreeOpen(WorkspaceEditorPanelPolicy.afterFileSelected(treeOpen))
            return
        }
        try {
            val text = files.readFile(id, path)
            files.rememberActive(id, path)
            currentPath = path
            tabs.add(path)
            while (tabs.size > 8) tabs.remove(tabs.first())
            loading = true
            spans.clear()
            binding.codeEditor.setText(text)
            binding.codeEditor.setSelection(0)
            binding.codeEditor.isEnabled = true
            loading = false
            dirty = false
            setTreeOpen(WorkspaceEditorPanelPolicy.afterFileSelected(treeOpen))
            renderStatus()
            renderTabs()
            handler.removeCallbacks(recolor)
            handler.post(recolor)
        } catch (error: Exception) {
            loading = false
            toast(error.message ?: "Could not open file")
        }
    }

    private fun requestOpen(path: String) = withSavedChanges { openFile(path) }

    private fun saveCurrent(): Boolean {
        val path = currentPath ?: return false
        if (!dirty) { renderStatus(); return true }
        return try {
            files.saveFile(id, path, binding.codeEditor.text.toString())
            dirty = false
            renderStatus()
            true
        } catch (error: Exception) {
            toast(error.message ?: "Could not save file")
            false
        }
    }

    private fun renderTree() {
        val entries = runCatching { files.list(id) }.getOrElse {
            toast(it.message ?: "Could not load files"); return
        }
        binding.fileTreeContainer.removeAllViews()
        binding.starterButton.visibility = if (treeOpen && entries.isEmpty() &&
            projects.getProject(id)?.type == WorkspaceProjectType.WEBSITE) View.VISIBLE else View.GONE
        if (entries.isEmpty()) {
            binding.fileTreeContainer.addView(makeRow("No files yet · create a file or use website starter", false, 0))
        }
        entries.filter { entry -> collapsed.none { entry.path.startsWith("$it/") } }.forEach { entry ->
            val label = (if (entry.folder) if (entry.path in collapsed) "▸  " else "▾  " else "◇  ") + entry.path.substringAfterLast('/')
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                if (entry.path == currentPath) setBackgroundResource(R.drawable.bg_workspace_dialog_input)
            }
            val name = makeRow(label, entry.path == currentPath, entry.depth)
            name.contentDescription = (if (entry.folder) "Folder " else "Open file ") + entry.path
            name.setOnClickListener {
                if (entry.folder) {
                    if (!collapsed.add(entry.path)) collapsed.remove(entry.path)
                    renderTree()
                } else requestOpen(entry.path)
            }
            name.setOnLongClickListener { withSavedChanges { showEntryActions(entry) }; true }
            row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val actions = TextView(this).apply {
                text = "⋮"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#B6F3C1"))
                minWidth = dp(48)
                minHeight = dp(48)
                isClickable = true
                isFocusable = true
                contentDescription = "More actions for ${entry.path}"
                setOnClickListener { withSavedChanges { showEntryActions(entry) } }
            }
            row.addView(actions, LinearLayout.LayoutParams(dp(48), dp(48)))
            binding.fileTreeContainer.addView(row)
        }
    }

    private fun makeRow(label: String, active: Boolean, depth: Int): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(Color.parseColor(if (active) "#E8FFEA" else "#A8D3B0"))
        setPadding(dp(12 + depth * 14), dp(10), dp(8), dp(10))
        minHeight = dp(48)
        isClickable = true
        isFocusable = true
    }

    private fun renderTabs() {
        binding.tabsContainer.removeAllViews()
        tabs.forEach { path ->
            val chip = TextView(this).apply {
                text = path.substringAfterLast('/') + if (path == currentPath && dirty) " •" else ""
                textSize = 12f
                setTextColor(Color.parseColor(if (path == currentPath) "#F1FFF3" else "#92B99B"))
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setBackgroundResource(if (path == currentPath) R.drawable.bg_field else R.drawable.bg_workspace_dialog_input)
                setOnClickListener { requestOpen(path) }
                contentDescription = "Open tab $path"
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.marginEnd = dp(6)
            binding.tabsContainer.addView(chip, params)
        }
    }

    private fun renderStatus() {
        val path = currentPath
        binding.editorFileName.text = if (path == null) "No file open" else path + if (dirty) "  •  Unsaved" else "  •  Saved"
        binding.saveButton.isEnabled = path != null
        binding.codeEditor.isEnabled = path != null
        val text = binding.codeEditor.text.toString()
        val cursor = binding.codeEditor.selectionStart.coerceIn(0, text.length)
        val prefix = text.take(cursor)
        val line = prefix.count { it == '\n' } + 1
        val column = prefix.length - prefix.lastIndexOf('\n')
        binding.lineNumbers.text = if (path == null) "" else (1..(text.count { it == '\n' } + 1).coerceAtMost(2500)).joinToString("\n")
        binding.editorStatus.text = if (path == null) "UTF-8  •  App-private Workspace" else "Ln $line, Col $column  •  UTF-8  •  ${if (dirty) "Unsaved" else "Saved"}"
        renderTabs()
    }

    private fun showPathDialog(folder: Boolean) {
        val input = EditText(this).apply {
            hint = if (folder) "e.g. src/components" else "e.g. src/main.js"
            setSingleLine(true)
            setTextColor(Color.parseColor("#E8FFEC"))
            setHintTextColor(Color.parseColor("#7A9F83"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundResource(R.drawable.bg_workspace_dialog_input)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            addView(input, LinearLayout.LayoutParams(-1, dp(56)))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (folder) "New folder" else "New file")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create", null)
            .create()
        dialog.setOnShowListener {
            styleDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val path = input.text.toString().trim()
                try {
                    if (folder) files.createFolder(id, path) else files.createFile(id, path)
                    dialog.dismiss()
                    setTreeOpen(true)
                    if (!folder) openFile(path)
                } catch (error: Exception) { input.error = error.message ?: "Invalid path" }
            }
        }
        dialog.show()
    }

    private fun showEntryActions(entry: WorkspaceFileStore.Entry) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(entry.path)
            .setItems(arrayOf("Rename / move", "Delete")) { _, which ->
                if (which == 0) showRename(entry) else confirmDelete(entry)
            }.setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { styleDialog(dialog) }
        dialog.show()
    }

    private fun showRename(entry: WorkspaceFileStore.Entry) {
        val input = EditText(this).apply {
            setText(entry.path)
            selectAll()
            setSingleLine(true)
            setTextColor(Color.parseColor("#E8FFEC"))
            setPadding(dp(15), dp(12), dp(15), dp(12))
            setBackgroundResource(R.drawable.bg_workspace_dialog_input)
        }
        val content = LinearLayout(this).apply {
            setPadding(dp(18), dp(12), dp(18), dp(12))
            addView(input, LinearLayout.LayoutParams(-1, dp(54)))
        }
        val dialog = AlertDialog.Builder(this).setTitle("Rename / move")
            .setView(content).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            styleDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val path = files.rename(id, entry.path, input.text.toString().trim())
                    val old = entry.path
                    val renamed = tabs.map { if (it == old || it.startsWith("$old/")) path + it.removePrefix(old) else it }
                    tabs.clear(); tabs.addAll(renamed)
                    if (currentPath == old || currentPath?.startsWith("$old/") == true) {
                        currentPath = null
                        openFile(projects.getProject(id)?.activeFilePath ?: path)
                    }
                    dialog.dismiss(); renderTree(); renderTabs()
                } catch (error: Exception) { input.error = error.message ?: "Rename failed" }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(entry: WorkspaceFileStore.Entry) {
        val dialog = AlertDialog.Builder(this).setTitle("Delete ${entry.path}?")
            .setMessage("This permanently deletes the selected ${if (entry.folder) "folder and all its files" else "file"}. Personal memory is unaffected.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                try {
                    files.delete(id, entry.path)
                    tabs.removeAll { it == entry.path || it.startsWith("${entry.path}/") }
                    if (currentPath == entry.path || currentPath?.startsWith("${entry.path}/") == true) {
                        currentPath = null
                        loading = true
                        binding.codeEditor.setText("")
                        loading = false
                        dirty = false
                        files.rememberActive(id, null)
                    }
                    renderTree(); renderStatus()
                } catch (error: Exception) { toast(error.message ?: "Delete failed") }
            }.create()
        dialog.setOnShowListener { styleDialog(dialog) }
        dialog.show()
    }

    private fun styleDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_workspace_dialog)
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { button ->
            dialog.getButton(button)?.setTextColor(Color.parseColor("#A8EFB4"))
        }
    }

    private fun applySyntaxColors() {
        val edit = binding.codeEditor.text ?: return
        spans.forEach { edit.removeSpan(it) }
        spans.clear()
        if (currentPath == null || edit.length > 32_000) return
        val source = edit.toString()
        fun color(regex: Regex, hex: String) {
            var count = 0
            for (match in regex.findAll(source)) {
                if (++count > 1200) break
                val span = ForegroundColorSpan(Color.parseColor(hex))
                edit.setSpan(span, match.range.first, match.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                spans.add(span)
            }
        }
        color(keywords, "#9BD8FF")
        color(tags, "#A7E9AB")
        color(strings, "#EFD09A")
        color(comments, "#7B9C83")
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value + .5f).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
