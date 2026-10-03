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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import com.myra.assistant.databinding.ActivityWorkspaceEditorBinding
import java.io.File

/** Phone-first Workspace editor. Project files, not personal AIRI memory, own its state. */
class WorkspaceEditorActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_PROJECT_ID = "workspace_project_id"
        fun intent(context: Context, projectId: String): Intent =
            Intent(context, WorkspaceEditorActivity::class.java).putExtra(EXTRA_PROJECT_ID, projectId)
    }

    private lateinit var binding: ActivityWorkspaceEditorBinding
    private val projects by lazy { WorkspaceProjectStore(File(filesDir, "workspace/projects")) }
    private val files by lazy { WorkspaceFileStore(projects) }
    private lateinit var id: String
    private var entries = emptyList<WorkspaceFileStore.Entry>()
    private val collapsed = mutableSetOf<String>()
    private var pickerDialog: AlertDialog? = null
    private var pickerRows: LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentPath: String? = null
    private var dirty = false
    private var loading = false
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
        id = intent.getStringExtra(EXTRA_PROJECT_ID).orEmpty()
        val project = projects.getProject(id)
        if (project == null) {
            toast("Workspace project is unavailable")
            finish()
            return
        }
        binding.editorHeading.text = project.name
        // The old inline tree is deliberately never shown. All project files are tabs above code.
        binding.fileTreeScroll.visibility = View.GONE
        binding.filesButton.text = "☷  Files"
        binding.filesButton.contentDescription = "Browse project folders"
        binding.backButton.setOnClickListener { attemptClose() }
        binding.filesButton.setOnClickListener { showFilesPicker() }
        binding.newFileButton.setOnClickListener { withSavedChanges { showPathDialog(false) } }
        binding.newFolderButton.setOnClickListener { withSavedChanges { showPathDialog(true) } }
        binding.saveButton.setOnClickListener { saveCurrent() }
        binding.starterButton.setOnClickListener { withSavedChanges { createWebsiteStarter() } }
        binding.codeEditor.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            binding.lineNumbers.scrollTo(0, scrollY)
        }
        binding.codeEditor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (loading || currentPath == null) return
                val firstChange = !dirty
                dirty = true
                renderStatus()
                if (firstChange) renderTabs()
                handler.removeCallbacks(recolor)
                handler.postDelayed(recolor, 180)
            }
        })
        refreshFileUi()
        val remembered = project.activeFilePath
        val first = WorkspaceEditorTabs.files(entries).firstOrNull()
        if (remembered != null) openFile(remembered)
        else if (first != null) openFile(first)
        else renderStatus()
    }

    override fun onPause() {
        // Save user code before Android backgrounds or kills the editor; failed saves remain dirty.
        if (dirty) saveCurrent()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(recolor)
        super.onDestroy()
    }

    @Deprecated("Back callback for existing AppCompat navigation")
    override fun onBackPressed() { attemptClose() }

    private fun attemptClose() = withSavedChanges { finish() }

    private fun discardCurrent(): Boolean {
        val path = currentPath ?: return true
        return try {
            val saved = files.readFile(id, path)
            loading = true
            binding.codeEditor.setText(saved)
            loading = false
            dirty = false
            renderStatus()
            renderTabs()
            handler.removeCallbacks(recolor)
            handler.post(recolor)
            true
        } catch (error: Exception) {
            loading = false
            toast(error.message ?: "Could not restore saved code")
            false
        }
    }

    private fun withSavedChanges(next: () -> Unit) {
        if (!dirty) { next(); return }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Unsaved changes")
            .setMessage("Save changes to ${currentPath ?: "this file"} before continuing?")
            .setPositiveButton("Save", null)
            .setNeutralButton("Discard", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            styleDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (saveCurrent()) { dialog.dismiss(); next() }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (discardCurrent()) { dialog.dismiss(); next() }
            }
        }
        dialog.show()
    }

    private fun openFile(path: String) {
        if (path == currentPath) return
        try {
            val text = files.readFile(id, path)
            files.rememberActive(id, path)
            currentPath = path
            loading = true
            spans.clear()
            binding.codeEditor.setText(text)
            binding.codeEditor.setSelection(0)
            binding.codeEditor.isEnabled = true
            loading = false
            dirty = false
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
            renderTabs()
            true
        } catch (error: Exception) {
            toast(error.message ?: "Could not save file")
            false
        }
    }

    private fun refreshFileUi() {
        entries = runCatching { files.list(id) }.getOrElse {
            toast(it.message ?: "Could not load project files")
            emptyList()
        }
        binding.starterButton.visibility = if (entries.isEmpty() &&
            projects.getProject(id)?.type == WorkspaceProjectType.WEBSITE) View.VISIBLE else View.GONE
        renderTabs()
        pickerRows?.let { renderPickerTree(it) }
    }

    private fun renderTabs() {
        binding.tabsContainer.removeAllViews()
        val paths = (WorkspaceEditorTabs.files(entries) + listOfNotNull(currentPath)).distinct()
        if (paths.isEmpty()) {
            binding.tabsContainer.addView(TextView(this).apply {
                text = "No files yet · + File or create website starter"
                textSize = 12f
                setTextColor(Color.parseColor("#83AA8C"))
                gravity = Gravity.CENTER_VERTICAL
            })
            return
        }
        paths.forEach { path ->
            val selected = path == currentPath
            val chip = TextView(this).apply {
                text = WorkspaceEditorTabs.label(path, paths) + if (selected && dirty) " •" else ""
                textSize = 12f
                maxWidth = dp(230)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Color.parseColor(if (selected) "#F1FFF3" else "#92B99B"))
                setPadding(dp(13), dp(10), dp(13), dp(10))
                setBackgroundResource(if (selected) R.drawable.bg_field else R.drawable.bg_workspace_dialog_input)
                minimumHeight = dp(40)
                isClickable = true
                isFocusable = true
                contentDescription = "Open $path; long press for Rename or Delete"
                setOnClickListener { requestOpen(path) }
                setOnLongClickListener {
                    val entry = entries.firstOrNull { !it.folder && it.path == path }
                    if (entry != null) withSavedChanges { showEntryActions(entry) }
                    true
                }
            }
            val params = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(7) }
            binding.tabsContainer.addView(chip, params)
            if (selected) (binding.tabsContainer.parent as? HorizontalScrollView)?.post {
                (binding.tabsContainer.parent as? HorizontalScrollView)?.smoothScrollTo(chip.left - dp(12), 0)
            }
        }
    }

    private fun renderStatus() {
        val path = currentPath
        binding.editorFileName.text = if (path == null) "No file open" else path + if (dirty) "  •  Unsaved" else "  •  Saved"
        binding.saveButton.isEnabled = path != null
        binding.codeEditor.isEnabled = path != null
        // Empty, already-open files must look like empty code, not like an unopened editor.
        binding.codeEditor.hint = if (path == null) "Open or create a file to start coding" else ""
        val text = binding.codeEditor.text.toString()
        val cursor = binding.codeEditor.selectionStart.coerceIn(0, text.length)
        val prefix = text.take(cursor)
        val line = prefix.count { it == '\n' } + 1
        val column = prefix.length - prefix.lastIndexOf('\n')
        binding.lineNumbers.text = if (path == null) "" else (1..(text.count { it == '\n' } + 1).coerceAtMost(2500)).joinToString("\n")
        binding.editorStatus.text = if (path == null) "UTF-8  •  App-private Workspace" else "Ln $line, Col $column  •  UTF-8  •  ${if (dirty) "Unsaved" else "Saved"}"
    }

    private fun showFilesPicker() {
        if (pickerDialog?.isShowing == true) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(binding.codeEditor.windowToken, 0)
        binding.filesButton.requestFocus()
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(rows, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1210"))
            setPadding(dp(10), dp(3), dp(10), dp(10))
            addView(scroll, LinearLayout.LayoutParams(-1, dp(390)))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Project files")
            .setView(frame)
            .setNegativeButton("Close", null)
            .create()
        pickerRows = rows
        pickerDialog = dialog
        dialog.setOnShowListener { styleDialog(dialog) }
        dialog.setOnDismissListener {
            if (pickerDialog === dialog) { pickerDialog = null; pickerRows = null }
        }
        renderPickerTree(rows)
        dialog.show()
    }

    private fun renderPickerTree(container: LinearLayout) {
        container.removeAllViews()
        if (entries.isEmpty()) {
            container.addView(makeRow("No files yet. Use + File to create one.", false, 0))
            if (projects.getProject(id)?.type == WorkspaceProjectType.WEBSITE) {
                container.addView(makeRow("＋ Create website starter files", false, 0).apply {
                    setOnClickListener {
                        withSavedChanges {
                            pickerDialog?.dismiss()
                            createWebsiteStarter()
                        }
                    }
                })
            }
            return
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
                    renderPickerTree(container)
                } else {
                    pickerDialog?.dismiss()
                    requestOpen(entry.path)
                }
            }
            name.setOnLongClickListener { withSavedChanges { showEntryActions(entry) }; true }
            row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = "⋮"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#B6F3C1"))
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                isClickable = true
                isFocusable = true
                contentDescription = "More actions for ${entry.path}"
                setOnClickListener { withSavedChanges { showEntryActions(entry) } }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            container.addView(row)
        }
    }

    private fun makeRow(label: String, active: Boolean, depth: Int): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(Color.parseColor(if (active) "#E8FFEA" else "#A8D3B0"))
        setPadding(dp(12 + depth * 14), dp(10), dp(8), dp(10))
        minimumHeight = dp(48)
        isClickable = true
        isFocusable = true
    }

    private fun createWebsiteStarter() {
        try {
            files.addWebsiteStarter(id)
            refreshFileUi()
            openFile("index.html")
        } catch (error: Exception) { toast(error.message ?: "Starter could not be created") }
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
                    refreshFileUi()
                    if (folder) showFilesPicker() else openFile(path)
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
                    val previous = currentPath
                    val remapped = WorkspaceEditorTabs.remap(previous, entry.path, path)
                    dialog.dismiss()
                    refreshFileUi()
                    if (remapped != previous) {
                        currentPath = null
                        if (remapped != null) openFile(remapped) else renderStatus()
                    } else renderStatus()
                } catch (error: Exception) { input.error = error.message ?: "Rename failed" }
            }
        }
        dialog.show()
    }

    private fun referenceWarning(entry: WorkspaceFileStore.Entry): String {
        // Advisory, bounded scan of common text sources; unrecognized references may still exist.
        val referencers = entries.asSequence()
            .filter { candidate ->
                !candidate.folder && !WorkspaceEditorTabs.removed(candidate.path, entry.path) &&
                    listOf(".html", ".htm", ".css", ".js", ".mjs", ".jsx", ".ts", ".tsx")
                        .any { candidate.path.endsWith(it, ignoreCase = true) }
            }
            .sortedWith(compareBy<WorkspaceFileStore.Entry> {
                if (it.path == "index.html") 0 else if (it.path.endsWith(".html", true)) 1 else 2
            }.thenBy { it.path })
            .take(40)
            .mapNotNull { candidate ->
                val source = runCatching { files.readFile(id, candidate.path) }.getOrNull()
                if (source != null && WorkspaceDeleteReferencePolicy.referencesTarget(candidate.path, source, entry.path))
                    candidate.path else null
            }
            .take(3)
            .toList()
        return if (referencers.isEmpty()) {
            "Other files may reference it. References are not automatically updated."
        } else {
            "Referenced by: ${referencers.joinToString(", ")}. Deleting may break your project. References are not automatically updated."
        }
    }

    private fun confirmDelete(entry: WorkspaceFileStore.Entry) {
        val warning = referenceWarning(entry)
        val dialog = AlertDialog.Builder(this).setTitle("Delete ${entry.path}?")
            .setMessage("This permanently deletes the selected ${if (entry.folder) "folder and all its files" else "file"}.\n\n$warning\n\nPersonal memory is unaffected.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                try {
                    files.delete(id, entry.path)
                    val deletedCurrent = WorkspaceEditorTabs.removed(currentPath, entry.path)
                    if (deletedCurrent) {
                        currentPath = null
                        loading = true
                        binding.codeEditor.setText("")
                        loading = false
                        dirty = false
                    }
                    refreshFileUi()
                    if (deletedCurrent) {
                        val next = WorkspaceEditorTabs.files(entries).firstOrNull()
                        if (next != null) openFile(next) else renderStatus()
                    } else renderStatus()
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
