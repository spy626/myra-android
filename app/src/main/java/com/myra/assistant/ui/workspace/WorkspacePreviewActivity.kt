package com.myra.assistant.ui.workspace

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.databinding.ActivityWorkspacePreviewBinding
import java.io.File

/** Preview is a read-only view of saved Workspace website files, not an AI/browser agent. */
class WorkspacePreviewActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_PROJECT_ID = "workspace_project_id"
        fun intent(context: Context, projectId: String): Intent =
            Intent(context, WorkspacePreviewActivity::class.java).putExtra(EXTRA_PROJECT_ID, projectId)
    }

    private lateinit var binding: ActivityWorkspacePreviewBinding
    private val projects by lazy { WorkspaceProjectStore(File(filesDir, "workspace/projects")) }
    private lateinit var projectId: String
    private var server: WorkspacePreviewServer? = null
    private var failed = false
    private var browserEvidenceGeneration = 0L
    private var browserDomSignals: WorkspaceBrowserVerificationEvidence.DomSignals? = null
    private var browserConsoleErrors = 0
    private var browserConsoleWarnings = 0
    private var browserNetworkFailures = 0
    private var browserHttpErrors = 0

    private fun resetBrowserEvidence() {
        browserEvidenceGeneration++
        browserDomSignals = null
        browserConsoleErrors = 0
        browserConsoleWarnings = 0
        browserNetworkFailures = 0
        browserHttpErrors = 0
    }

    private fun incrementBounded(value: Int): Int = (value + 1).coerceAtMost(500)

    private fun browserEvidenceResult(url: String): WorkspaceBrowserVerificationEvidence.Result =
        WorkspaceBrowserVerificationEvidence.assess(
            WorkspaceBrowserVerificationEvidence.Snapshot(
                pageUrl = url,
                capturedAtMs = System.currentTimeMillis(),
                dom = browserDomSignals,
                runtime = WorkspaceBrowserVerificationEvidence.RuntimeSignals(
                    consoleErrors = browserConsoleErrors,
                    consoleWarnings = browserConsoleWarnings,
                    networkFailures = browserNetworkFailures,
                    httpErrors = browserHttpErrors,
                ),
            )
        )

    private fun refreshBrowserEvidenceStatus(url: String?) {
        if (failed || url.isNullOrBlank() || browserDomSignals == null) return
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (!isLocalPreviewUrl(parsed) || binding.previewWebView.url != url) return
        binding.previewStatus.text = browserEvidenceResult(url).statusText()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkspacePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        projectId = intent.getStringExtra(EXTRA_PROJECT_ID).orEmpty()
        val project = projects.getProject(projectId)
        if (project?.type != WorkspaceProjectType.WEBSITE) {
            toast("Website project is unavailable")
            finish()
            return
        }
        binding.previewHeading.text = project.name
        binding.previewBack.setOnClickListener { finish() }
        binding.previewRefresh.setOnClickListener { reload() }
        binding.previewChrome.setOnClickListener { openChrome() }
        binding.previewEditor.setOnClickListener {
            startActivity(WorkspaceEditorActivity.intent(this, projectId))
        }
        binding.previewWebView.apply {
            // A website without its own background uses the browser's white canvas,
            // not LYRA's dark chrome. Keep the website's own CSS colours authoritative.
            setBackgroundColor(android.graphics.Color.WHITE)
            settings.disableAutomaticDarkening()
            settings.javaScriptEnabled = true // User's local website JavaScript, never an Android bridge.
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.setSupportMultipleWindows(false)
            settings.setGeolocationEnabled(false)
            settings.safeBrowsingEnabled = true
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    when (consoleMessage.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR ->
                            browserConsoleErrors = incrementBounded(browserConsoleErrors)
                        ConsoleMessage.MessageLevel.WARNING ->
                            browserConsoleWarnings = incrementBounded(browserConsoleWarnings)
                        else -> Unit
                    }
                    // Never store raw console text: it may contain page data or secrets.
                    refreshBrowserEvidenceStatus(binding.previewWebView.url)
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !isLocalPreviewUrl(request.url)

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (isLocalPreviewUrl(Uri.parse(url))) {
                        failed = false
                        resetBrowserEvidence()
                        binding.previewStatus.text = "Loading saved website · collecting browser evidence…"
                    }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest,
                                             error: WebResourceError) {
                    browserNetworkFailures = incrementBounded(browserNetworkFailures)
                    if (request.isForMainFrame) {
                        failed = true
                        binding.previewStatus.text = "Preview failed: ${error.description}"
                    } else {
                        refreshBrowserEvidenceStatus(view.url)
                    }
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest,
                                                 errorResponse: WebResourceResponse) {
                    if (errorResponse.statusCode >= 400) {
                        browserHttpErrors = incrementBounded(browserHttpErrors)
                        if (request.isForMainFrame) {
                            failed = true
                            binding.previewStatus.text = "Preview HTTP ${errorResponse.statusCode}"
                        } else {
                            refreshBrowserEvidenceStatus(view.url)
                        }
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (failed || !isLocalPreviewUrl(Uri.parse(url))) return
                    val observationGeneration = browserEvidenceGeneration
                    binding.previewStatus.text = "Preview loaded · checking browser evidence…"
                    // Fixed, count-only DOM/accessibility metrics. No JS bridge, page text, storage,
                    // console bodies, request URLs or provider transfer.
                    view.evaluateJavascript(
                        WorkspaceBrowserVerificationEvidence.DOM_AUDIT_SCRIPT
                    ) { encoded ->
                        if (!failed && !isFinishing && !isDestroyed &&
                            observationGeneration == browserEvidenceGeneration &&
                            binding.previewWebView.url == url &&
                            isLocalPreviewUrl(Uri.parse(url))) {
                            browserDomSignals =
                                WorkspaceBrowserVerificationEvidence.decodeDomResult(encoded)
                            binding.previewStatus.text = browserEvidenceResult(url).statusText()
                        }
                    }
                }
            }
        }
        try {
            val preview = WorkspacePreviewServer(projects, projectId)
            preview.start()
            server = preview
            reload()
        } catch (error: Exception) {
            binding.previewStatus.text = "Preview unavailable: ${error.message ?: "Cannot start"}"
            binding.previewChrome.isEnabled = false
            binding.previewRefresh.isEnabled = false
        }
    }

    /** Android 13+ uses algorithmic darkening; Android 10–12 use the legacy force-dark setting. */
    @Suppress("DEPRECATION")
    private fun WebSettings.disableAutomaticDarkening() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setAlgorithmicDarkeningAllowed(false)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setForceDark(WebSettings.FORCE_DARK_OFF)
        }
    }

    private fun isLocalPreviewUrl(uri: Uri): Boolean {
        val active = server ?: return false
        val expected = Uri.parse(active.url)
        return uri.scheme == "http" && uri.host == "127.0.0.1" &&
            uri.port == expected.port && uri.encodedPath?.startsWith(expected.encodedPath.orEmpty()) == true
    }

    private fun reload() {
        val active = server ?: return
        val available = runCatching {
            WorkspaceFileStore(projects).list(projectId).any { !it.folder && it.path == "index.html" }
        }.getOrDefault(false)
        if (!available) {
            binding.previewStatus.text = "Create index.html in Files to preview this website"
            binding.previewChrome.isEnabled = false
            binding.previewWebView.loadData("", "text/html", "utf-8")
            return
        }
        binding.previewChrome.isEnabled = true
        failed = false
        binding.previewStatus.text = "Loading saved website…"
        binding.previewWebView.loadUrl(active.url + "index.html?refresh=" + System.currentTimeMillis())
    }

    private fun openChrome() {
        val active = server ?: return
        if (!binding.previewChrome.isEnabled) return
        val url = Uri.parse(active.url + "index.html")
        val chrome = Intent(Intent.ACTION_VIEW, url).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            setPackage("com.android.chrome")
        }
        try {
            startActivity(chrome)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: ActivityNotFoundException) { toast("No browser is available") }
        }
    }

    @Deprecated("Back callback for existing AppCompat navigation")
    override fun onBackPressed() {
        if (binding.previewWebView.canGoBack()) binding.previewWebView.goBack() else finish()
    }

    override fun onDestroy() {
        server?.close()
        server = null
        binding.previewWebView.stopLoading()
        binding.previewWebView.destroy()
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
