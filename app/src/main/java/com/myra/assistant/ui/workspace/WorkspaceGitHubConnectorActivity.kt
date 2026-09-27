package com.myra.assistant.ui.workspace

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/**
 * GitHub Connector C1: verify identity + exact repository + safe feature branch.
 *
 * C1 intentionally does not execute GitHub writes. The token is persisted only after all three
 * remote checks pass and is stored in encrypted connector-only preferences.
 */
class WorkspaceGitHubConnectorActivity : AppCompatActivity() {
    private val store by lazy { WorkspaceConnectorCredentialStore(this) }
    private var activeCall: Call? = null
    private var root: LinearLayout? = null
    private var status: TextView? = null
    private var connectButton: TextView? = null
    private var tokenField: EditText? = null
    private var repositoryField: EditText? = null
    private var branchField: EditText? = null

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun text(
        value: String,
        size: Float = 14f,
        color: Int = Color.rgb(224, 230, 226),
    ) = TextView(this).apply {
        this.text = value
        textSize = size
        setTextColor(color)
    }

    private fun sectionTitle(value: String) = text(
        value.uppercase(),
        12f,
        Color.rgb(128, 139, 132),
    ).apply {
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(22), 0, dp(8))
    }

    private fun field(hint: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(121, 134, 126))
        textSize = 14f
        maxLines = 1
        isSingleLine = true
        inputType = if (password) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        background = rounded(
            Color.rgb(22, 28, 25),
            14,
            Color.rgb(56, 68, 61),
        )
        setPadding(dp(14), 0, dp(14), 0)
    }

    private fun button(
        label: String,
        destructive: Boolean = false,
        action: () -> Unit,
    ) = text(label, 14f, Color.WHITE).apply {
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(
            if (destructive) Color.rgb(126, 28, 44) else Color.rgb(41, 76, 53),
            22,
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onDestroy() {
        activeCall?.cancel()
        activeCall = null
        super.onDestroy()
    }

    private fun render() {
        val connection = runCatching { store.loadGitHub() }.getOrNull()
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(3, 7, 6))
            isFillViewport = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(26), dp(24), dp(32))
        }
        root = column
        scroll.addView(column, android.widget.FrameLayout.LayoutParams(-1, -2))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text("‹", 34f).apply {
            gravity = Gravity.CENTER
            contentDescription = "Back"
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(46), dp(52)))
        header.addView(text("GitHub", 25f, Color.WHITE).apply {
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(header, LinearLayout.LayoutParams(-1, dp(58)))

        column.addView(text(
            "Connect one GitHub account and bind LYRA to one exact repository + feature branch.",
            14f,
            Color.rgb(174, 186, 178),
        ).apply { setPadding(0, dp(8), 0, dp(4)) })

        if (connection == null) renderDisconnected(column) else renderConnected(column, connection)
        setContentView(scroll)
    }

    private fun renderDisconnected(column: LinearLayout) {
        column.addView(sectionTitle("Connect GitHub"))

        tokenField = field("Fine-grained GitHub token", password = true)
        repositoryField = field("owner/repository").apply { setText("spy626/myra-android") }
        branchField = field("feature branch").apply { setText("agent/myra-phase-1") }

        column.addView(tokenField, LinearLayout.LayoutParams(-1, dp(54)))
        column.addView(repositoryField, LinearLayout.LayoutParams(-1, dp(54)).apply {
            topMargin = dp(10)
        })
        column.addView(branchField, LinearLayout.LayoutParams(-1, dp(54)).apply {
            topMargin = dp(10)
        })

        column.addView(text(
            "Use a fine-grained token with only the repository access you intend to give LYRA. " +
                "The token is encrypted on this phone and sent only to api.github.com. " +
                "It is never added to AI prompts. C1 verifies read access only; GitHub writes stay blocked.",
            12.5f,
            Color.rgb(145, 156, 149),
        ).apply { setPadding(0, dp(12), 0, dp(12)) })

        status = text("", 13f, Color.rgb(175, 205, 183)).apply {
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.rgb(17, 31, 23), 12)
        }
        column.addView(status, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })

        connectButton = button("VERIFY & CONNECT") { verifyAndConnect() }
        column.addView(connectButton, LinearLayout.LayoutParams(-1, dp(52)))

        column.addView(sectionTitle("C1 permissions"))
        permissionRows(column)
        column.addView(text(
            "OAuth/Device Flow and custom MCP sign-in are not active in this slice. " +
                "No hidden server or connector is contacted from this screen.",
            12.5f,
            Color.rgb(125, 136, 130),
        ).apply { setPadding(0, dp(14), 0, 0) })
    }

    private fun renderConnected(
        column: LinearLayout,
        connection: WorkspaceConnectorCredentialStore.GitHubConnection,
    ) {
        column.addView(sectionTitle("Connected"))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = rounded(
                Color.rgb(15, 29, 21),
                18,
                Color.rgb(93, 158, 111),
            )
        }
        card.addView(text("GitHub · @" + connection.login, 17f, Color.WHITE).apply {
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(text(connection.repository, 14f, Color.rgb(204, 216, 208)).apply {
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(text(connection.branch, 13f, Color.rgb(120, 203, 148)).apply {
            setPadding(0, dp(5), 0, 0)
        })
        card.addView(text("Verified connection", 12f, Color.rgb(120, 203, 148)).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, 0)
        })
        column.addView(card, LinearLayout.LayoutParams(-1, -2))

        column.addView(sectionTitle("Permissions"))
        permissionRows(column)

        column.addView(text(
            "LYRA cannot modify main/master, force-push, change GitHub secrets, delete repositories, " +
                "or delete branches through this connector policy.",
            12.5f,
            Color.rgb(159, 168, 163),
        ).apply { setPadding(0, dp(12), 0, dp(18)) })

        column.addView(button("DISCONNECT GITHUB", destructive = true) {
            AlertDialog.Builder(this)
                .setTitle("Disconnect GitHub?")
                .setMessage(
                    "Remove the encrypted GitHub token and repository binding from LYRA? " +
                        "Installed Skills and chats are not changed."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Disconnect") { _, _ ->
                    runCatching { store.disconnectGitHub() }
                        .onSuccess { render() }
                        .onFailure { showStatus(it.message ?: "Could not disconnect GitHub", true) }
                }
                .show()
        }, LinearLayout.LayoutParams(-1, dp(52)))
    }

    private fun permissionRows(column: LinearLayout) {
        val rows = listOf(
            "✓ Read repository" to true,
            "✓ Read commits / Actions" to true,
            "○ Create feature branch · next write phase" to false,
            "○ Create/update files · next write phase" to false,
            "○ Open/update PR · next write phase" to false,
            "✕ Modify main/master" to false,
            "✕ Force push / secrets / destructive deletes" to false,
        )
        rows.forEach { (label, allowed) ->
            column.addView(text(
                label,
                13.5f,
                if (allowed) Color.rgb(128, 205, 151) else Color.rgb(158, 164, 160),
            ).apply { setPadding(dp(4), dp(7), dp(4), dp(7)) })
        }
    }

    private fun showStatus(message: String, error: Boolean = false) {
        val view = status ?: return
        view.text = message
        view.setTextColor(
            if (error) Color.rgb(246, 153, 164) else Color.rgb(175, 205, 183)
        )
        view.visibility = View.VISIBLE
    }

    private fun setBusy(busy: Boolean) {
        connectButton?.isEnabled = !busy
        tokenField?.isEnabled = !busy
        repositoryField?.isEnabled = !busy
        branchField?.isEnabled = !busy
        connectButton?.alpha = if (busy) .55f else 1f
    }

    private fun verifyAndConnect() {
        if (activeCall != null) return
        val token = tokenField?.text?.toString().orEmpty()
        val repository = repositoryField?.text?.toString().orEmpty()
        val branch = branchField?.text?.toString().orEmpty()
        val input = runCatching {
            Triple(
                WorkspaceGitHubConnector.requireToken(token),
                WorkspaceConnectorPolicy.requireRepository(repository),
                WorkspaceConnectorPolicy.requireFeatureBranch(branch),
            )
        }.getOrElse {
            showStatus(it.message ?: "GitHub connector details are invalid", true)
            return
        }

        setBusy(true)
        showStatus("Checking GitHub account…")
        val call = WorkspaceGitHubConnector.client.newCall(
            WorkspaceGitHubConnector.userRequest(input.first)
        )
        activeCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fail(call, "GitHub connection failed. Check network and try again.")
            }

            override fun onResponse(call: Call, response: Response) {
                val account = runCatching { WorkspaceGitHubConnector.readAccount(response) }
                    .getOrElse {
                        fail(call, it.message ?: "GitHub account verification failed")
                        return
                    }
                verifyRepository(call, input.first, input.second, input.third, account)
            }
        })
    }

    private fun verifyRepository(
        previous: Call,
        token: String,
        repository: String,
        branch: String,
        account: WorkspaceGitHubConnector.Account,
    ) {
        runOnUiThread { showStatus("Checking repository access…") }
        if (activeCall !== previous) return
        val call = WorkspaceGitHubConnector.client.newCall(
            WorkspaceGitHubConnector.repositoryRequest(token, repository)
        )
        activeCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fail(call, "GitHub repository check failed. No connection was saved.")
            }

            override fun onResponse(call: Call, response: Response) {
                val verifiedRepo = runCatching {
                    WorkspaceGitHubConnector.readRepository(response, repository)
                }.getOrElse {
                    fail(call, it.message ?: "GitHub repository verification failed")
                    return
                }
                verifyBranch(call, token, verifiedRepo.fullName, branch, account)
            }
        })
    }

    private fun verifyBranch(
        previous: Call,
        token: String,
        repository: String,
        branch: String,
        account: WorkspaceGitHubConnector.Account,
    ) {
        runOnUiThread { showStatus("Checking protected feature branch…") }
        if (activeCall !== previous) return
        val call = WorkspaceGitHubConnector.client.newCall(
            WorkspaceGitHubConnector.branchRequest(token, repository, branch)
        )
        activeCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fail(call, "GitHub branch check failed. No connection was saved.")
            }

            override fun onResponse(call: Call, response: Response) {
                val verifiedBranch = runCatching {
                    WorkspaceGitHubConnector.readBranch(response, branch)
                }.getOrElse {
                    fail(call, it.message ?: "GitHub branch verification failed")
                    return
                }
                runOnUiThread {
                    if (activeCall !== call || isFinishing || isDestroyed) return@runOnUiThread
                    val saved = runCatching {
                        store.saveGitHub(
                            token = token,
                            login = account.login,
                            repository = repository,
                            branch = verifiedBranch.name,
                        )
                    }
                    activeCall = null
                    saved.onSuccess { render() }
                        .onFailure {
                            setBusy(false)
                            showStatus(
                                it.message ?: "GitHub connection could not be saved securely",
                                true,
                            )
                        }
                }
            }
        })
    }

    private fun fail(call: Call, message: String) {
        runOnUiThread {
            if (activeCall !== call || isFinishing || isDestroyed) return@runOnUiThread
            activeCall = null
            setBusy(false)
            showStatus(message, true)
        }
    }
}
