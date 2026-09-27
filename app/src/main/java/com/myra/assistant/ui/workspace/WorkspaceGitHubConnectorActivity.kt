package com.myra.assistant.ui.workspace

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
 * GitHub Connector C1.1: browser OAuth + exact repository + safe feature branch verification.
 *
 * Model output never receives OAuth codes/tokens. The broker holds the GitHub client secret;
 * Android keeps PKCE state and returned user tokens in encrypted connector-only preferences.
 * GitHub writes remain blocked in this slice.
 */
class WorkspaceGitHubConnectorActivity : AppCompatActivity() {
    private val store by lazy { WorkspaceConnectorCredentialStore(this) }
    private var activeCall: Call? = null
    private var status: TextView? = null
    private var connectButton: TextView? = null

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
        handleOAuthCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
        handleOAuthCallback(intent)
    }

    override fun onResume() {
        super.onResume()
        if (activeCall == null) setBusy(false)
    }

    override fun onDestroy() {
        activeCall?.cancel()
        activeCall = null
        super.onDestroy()
    }

    private fun render() {
        status = null
        connectButton = null
        val connection = runCatching { store.loadGitHub() }.getOrNull()
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(3, 7, 6))
            isFillViewport = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(26), dp(24), dp(32))
        }
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
            "Connect GitHub securely. LYRA is locked to one repository and one feature branch.",
            14f,
            Color.rgb(174, 186, 178),
        ).apply { setPadding(0, dp(8), 0, dp(4)) })

        if (connection == null) renderDisconnected(column) else renderConnected(column, connection)
        setContentView(scroll)
    }

    private fun renderDisconnected(column: LinearLayout) {
        column.addView(sectionTitle("Connect GitHub"))

        column.addView(text(
            "Sign in with the official GitHub browser flow. No PAT, GitHub password, client secret, " +
                "or repository token is typed into LYRA.",
            14f,
            Color.rgb(188, 202, 193),
        ).apply { setPadding(0, dp(4), 0, dp(14)) })

        val binding = WorkspaceConnectorPolicy.binding(EXPECTED_REPOSITORY, EXPECTED_BRANCH)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(
                Color.rgb(16, 24, 20),
                16,
                Color.rgb(52, 66, 58),
            )
        }
        card.addView(text("Repository", 11.5f, Color.rgb(128, 139, 132)))
        card.addView(text(binding.repository, 14f, Color.WHITE).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, dp(9))
        })
        card.addView(text("Protected self-edit branch", 11.5f, Color.rgb(128, 139, 132)))
        card.addView(text(binding.branch, 13.5f, Color.rgb(134, 210, 157)).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, 0)
        })
        column.addView(card, LinearLayout.LayoutParams(-1, -2))

        status = text("", 13f, Color.rgb(175, 205, 183)).apply {
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.rgb(17, 31, 23), 12)
        }
        column.addView(status, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(14)
            bottomMargin = dp(12)
        })

        connectButton = button("CONNECT GITHUB") { beginOAuth() }
        column.addView(connectButton, LinearLayout.LayoutParams(-1, dp(52)))

        column.addView(sectionTitle("C1 permissions"))
        permissionRows(column)
        column.addView(text(
            "GitHub opens outside LYRA. OAuth state + PKCE are generated on this phone, the client " +
                "secret stays in the Cloudflare broker, and the returned token is encrypted locally. " +
                "C1 still performs read verification only.",
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
        card.addView(text("OAuth verified connection", 12f, Color.rgb(120, 203, 148)).apply {
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
                    "Remove the encrypted GitHub OAuth tokens and repository binding from LYRA? " +
                        "Installed Skills and chats are not changed."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Disconnect") { _, _ ->
                    runCatching { store.disconnectGitHub() }
                        .onSuccess { render() }
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
        connectButton?.alpha = if (busy) .55f else 1f
    }

    private fun beginOAuth() {
        if (activeCall != null) return
        val pending = runCatching { WorkspaceGitHubOAuthSession.create() }
            .getOrElse {
                showStatus("Could not create a secure GitHub authorization session.", true)
                return
            }
        val stored = runCatching { store.saveGitHubOAuthPending(pending) }
        if (stored.isFailure) {
            showStatus("Could not save the secure GitHub authorization state.", true)
            return
        }
        val url = runCatching {
            WorkspaceGitHubOAuthSession.connectUrl(
                WorkspaceGitHubConnector.OAUTH_BROKER,
                pending,
            )
        }.getOrElse {
            runCatching { store.clearGitHubOAuthPending() }
            showStatus(it.message ?: "GitHub authorization URL is invalid", true)
            return
        }

        showStatus("Opening official GitHub sign-in…")
        val browser = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        runCatching { startActivity(browser) }
            .onSuccess {
                setBusy(false)
                showStatus("Finish GitHub authorization in the browser.")
            }
            .onFailure {
                runCatching { store.clearGitHubOAuthPending() }
                showStatus("No browser could open the GitHub authorization page.", true)
            }
    }

    private fun handleOAuthCallback(incoming: Intent?) {
        val callbackUri = incoming?.data ?: return
        if (callbackUri.scheme != "lyra" ||
            callbackUri.host != "github" ||
            callbackUri.path != "/callback") return

        setIntent(Intent(incoming).setData(null))
        if (runCatching { store.loadGitHub() }.getOrNull() != null) {
            runCatching { store.clearGitHubOAuthPending() }
            return
        }

        val pending = store.loadGitHubOAuthPending()
        if (pending == null) {
            showStatus("GitHub authorization session is missing. Tap Connect GitHub again.", true)
            return
        }
        val parsed = runCatching {
            WorkspaceGitHubOAuthSession.parseCallback(callbackUri.toString(), pending)
        }.getOrElse {
            runCatching { store.clearGitHubOAuthPending() }
            showStatus(it.message ?: "GitHub callback was rejected", true)
            return
        }

        when (parsed) {
            is WorkspaceGitHubOAuthSession.Callback.Denied -> {
                runCatching { store.clearGitHubOAuthPending() }
                showStatus("GitHub authorization was cancelled.", true)
            }
            is WorkspaceGitHubOAuthSession.Callback.Success ->
                exchangeOAuthCode(parsed.code, pending)
        }
    }

    private fun exchangeOAuthCode(
        code: String,
        pending: WorkspaceGitHubOAuthSession.Pending,
    ) {
        if (activeCall != null) return
        setBusy(true)
        showStatus("Finishing secure GitHub sign-in…")
        val request = runCatching {
            WorkspaceGitHubConnector.oauthExchangeRequest(
                code = code,
                state = pending.state,
                verifier = pending.verifier,
            )
        }.getOrElse {
            runCatching { store.clearGitHubOAuthPending() }
            setBusy(false)
            showStatus(it.message ?: "GitHub token exchange request is invalid", true)
            return
        }

        val call = WorkspaceGitHubConnector.client.newCall(request)
        activeCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fail(call, "GitHub sign-in could not finish. Check network and connect again.")
            }

            override fun onResponse(call: Call, response: Response) {
                val tokens = runCatching { WorkspaceGitHubConnector.readOAuthTokens(response) }
                    .getOrElse {
                        runCatching { store.clearGitHubOAuthPending() }
                        fail(call, it.message ?: "GitHub token exchange failed")
                        return
                    }
                val cleared = runCatching { store.clearGitHubOAuthPending() }
                if (cleared.isFailure) {
                    fail(call, "GitHub authorization state could not be cleared securely.")
                    return
                }
                verifyAccount(call, tokens)
            }
        })
    }

    private fun verifyAccount(
        previous: Call,
        tokens: WorkspaceGitHubConnector.OAuthTokens,
    ) {
        runOnUiThread { showStatus("Checking GitHub account…") }
        if (activeCall !== previous) return
        val call = WorkspaceGitHubConnector.client.newCall(
            WorkspaceGitHubConnector.userRequest(tokens.accessToken)
        )
        activeCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fail(call, "GitHub account verification failed. Connect again.")
            }

            override fun onResponse(call: Call, response: Response) {
                val account = runCatching { WorkspaceGitHubConnector.readAccount(response) }
                    .getOrElse {
                        fail(call, it.message ?: "GitHub account verification failed")
                        return
                    }
                verifyRepository(call, tokens, account)
            }
        })
    }

    private fun verifyRepository(
        previous: Call,
        tokens: WorkspaceGitHubConnector.OAuthTokens,
        account: WorkspaceGitHubConnector.Account,
    ) {
        runOnUiThread { showStatus("Checking selected repository access…") }
        if (activeCall !== previous) return
        val call = WorkspaceGitHubConnector.client.newCall(
            WorkspaceGitHubConnector.repositoryRequest(tokens.accessToken, EXPECTED_REPOSITORY)
        )
        activeCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fail(call, "GitHub repository check failed. No connection was saved.")
            }

            override fun onResponse(call: Call, response: Response) {
                val verifiedRepo = runCatching {
                    WorkspaceGitHubConnector.readRepository(response, EXPECTED_REPOSITORY)
                }.getOrElse {
                    fail(
                        call,
                        "LYRA GitHub App does not have access to $EXPECTED_REPOSITORY. " +
                            "No connection was saved."
                    )
                    return
                }
                verifyBranch(call, tokens, verifiedRepo.fullName, account)
            }
        })
    }

    private fun verifyBranch(
        previous: Call,
        tokens: WorkspaceGitHubConnector.OAuthTokens,
        repository: String,
        account: WorkspaceGitHubConnector.Account,
    ) {
        runOnUiThread { showStatus("Checking protected feature branch…") }
        if (activeCall !== previous) return
        val call = WorkspaceGitHubConnector.client.newCall(
            WorkspaceGitHubConnector.branchRequest(
                tokens.accessToken,
                repository,
                EXPECTED_BRANCH,
            )
        )
        activeCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fail(call, "GitHub branch check failed. No connection was saved.")
            }

            override fun onResponse(call: Call, response: Response) {
                val verifiedBranch = runCatching {
                    WorkspaceGitHubConnector.readBranch(response, EXPECTED_BRANCH)
                }.getOrElse {
                    fail(call, it.message ?: "GitHub branch verification failed")
                    return
                }
                runOnUiThread {
                    if (activeCall !== call || isFinishing || isDestroyed) return@runOnUiThread
                    val now = System.currentTimeMillis()
                    val saved = runCatching {
                        store.saveGitHub(
                            token = tokens.accessToken,
                            refreshToken = tokens.refreshToken,
                            tokenExpiresAtMs = expiryAt(now, tokens.expiresInSeconds),
                            refreshTokenExpiresAtMs = expiryAt(
                                now,
                                tokens.refreshTokenExpiresInSeconds,
                            ),
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

    private fun expiryAt(nowMs: Long, seconds: Long?): Long? {
        if (seconds == null) return null
        return runCatching {
            Math.addExact(nowMs, Math.multiplyExact(seconds, 1_000L))
        }.getOrNull()
    }

    private fun fail(call: Call, message: String) {
        runOnUiThread {
            if (activeCall !== call || isFinishing || isDestroyed) return@runOnUiThread
            activeCall = null
            setBusy(false)
            showStatus(message, true)
        }
    }

    private companion object {
        const val EXPECTED_REPOSITORY = "spy626/myra-android"
        const val EXPECTED_BRANCH = "agent/myra-phase-1"
    }
}
