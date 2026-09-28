package com.myra.assistant.ui.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/**
 * GitHub Connector C1/C2: renewable read auth plus broker-gated feature-branch writes.
 *
 * Write-capable GitHub tokens never enter Android. C2 verification and future write execution go
 * through Cloudflare, which is hard-bound to one repository and one feature branch.
 */
class WorkspaceGitHubConnectorActivity : AppCompatActivity() {
    private val store by lazy { WorkspaceConnectorCredentialStore(this) }
    private var activeCall: Call? = null
    private var status: TextView? = null
    private var connectButton: TextView? = null
    private var writeVerified = false

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
    }

    override fun onResume() {
        super.onResume()
        maybeRenewConnectedToken()
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
        if (connection == null) writeVerified = false

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
        column.addView(sectionTitle("One-time secure pairing"))
        column.addView(text(
            "LYRA uses the installed GitHub App directly. The pairing key is copied once into " +
                "Cloudflare, then one-hour read tokens can renew automatically.",
            14f,
            Color.rgb(188, 202, 193),
        ).apply { setPadding(0, dp(4), 0, dp(14)) })

        val binding = WorkspaceConnectorPolicy.binding(EXPECTED_REPOSITORY, EXPECTED_BRANCH)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.rgb(16, 24, 20), 16, Color.rgb(52, 66, 58))
        }
        card.addView(text("Repository", 11.5f, Color.rgb(128, 139, 132)))
        card.addView(text(binding.repository, 14f, Color.WHITE).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, dp(9))
        })
        card.addView(text("Protected self-edit branch", 11.5f, Color.rgb(128, 139, 132)))
        card.addView(text(binding.branch, 13.5f, Color.rgb(134, 210, 157)).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, dp(9))
        })
        card.addView(text("Pairing key", 11.5f, Color.rgb(128, 139, 132)))
        card.addView(text("Generated on this phone · hidden", 13f, Color.rgb(204, 216, 208)))
        column.addView(card, LinearLayout.LayoutParams(-1, -2))

        column.addView(button("COPY PAIRING KEY") { copyPairingKey() },
            LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(14) })

        mountStatus(column)

        connectButton = button("CONNECT INSTALLED GITHUB APP") {
            val pairing = runCatching { store.getOrCreateGitHubPairingSecret() }
                .getOrElse {
                    showStatus(it.message ?: "Could not create secure pairing key", true)
                    return@button
                }
            requestInstallationGrant(pairing)
        }
        column.addView(connectButton, LinearLayout.LayoutParams(-1, dp(52)))

        column.addView(sectionTitle("C1 permissions"))
        permissionRows(column, writeEnabled = false)
    }

    private fun renderConnected(
        column: LinearLayout,
        connection: WorkspaceConnectorCredentialStore.GitHubConnection,
    ) {
        column.addView(sectionTitle("Connected"))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = rounded(Color.rgb(15, 29, 21), 18, Color.rgb(93, 158, 111))
        }
        card.addView(text("GitHub App · @" + connection.login, 17f, Color.WHITE).apply {
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(text(connection.repository, 14f, Color.rgb(204, 216, 208)).apply {
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(text(connection.branch, 13f, Color.rgb(120, 203, 148)).apply {
            setPadding(0, dp(5), 0, 0)
        })
        card.addView(text("Installation verified · automatic read-token renewal", 12f,
            Color.rgb(120, 203, 148)).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, 0)
        })
        column.addView(card, LinearLayout.LayoutParams(-1, -2))

        mountStatus(column)

        column.addView(sectionTitle("C2 write access"))
        permissionRows(column, writeVerified)

        column.addView(text(
            if (writeVerified)
                "✓ Protected write lane ready · automatic preflight"
            else
                "○ Checking protected write lane automatically…",
            13.5f,
            if (writeVerified) Color.rgb(128, 205, 151) else Color.rgb(158, 164, 160),
        ).apply { setPadding(dp(4), dp(10), dp(4), dp(8)) })

        column.addView(text(
            "No test button is required now. Each explicit self-edit re-checks the broker automatically. " +
                "Write tokens stay inside Cloudflare. Writes are limited to bounded text commits on " +
                "agent/myra-phase-1 and draft PR create/update. main/master, force-push, Secrets, " +
                "workflow writes, merge and destructive deletes remain blocked.",
            12.5f,
            Color.rgb(159, 168, 163),
        ).apply { setPadding(0, dp(14), 0, dp(18)) })

        column.addView(button("DISCONNECT GITHUB", destructive = true) {
            AlertDialog.Builder(this)
                .setTitle("Disconnect GitHub?")
                .setMessage(
                    "Remove the encrypted GitHub read token, pairing key, and repository binding " +
                        "from LYRA? Installed Skills and chats are not changed."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Disconnect") { _, _ ->
                    runCatching { store.disconnectGitHub() }
                        .onSuccess {
                            writeVerified = false
                            render()
                        }
                }
                .show()
        }, LinearLayout.LayoutParams(-1, dp(52)))
    }

    private fun mountStatus(column: LinearLayout) {
        status = text("", 13f, Color.rgb(175, 205, 183)).apply {
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.rgb(17, 31, 23), 12)
        }
        column.addView(status, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
            bottomMargin = dp(12)
        })
    }

    private fun permissionRows(column: LinearLayout, writeEnabled: Boolean) {
        val rows = listOf(
            "✓ Read repository" to true,
            "✓ Read commits / Actions" to true,
            "✓ Fixed self-edit branch · agent/myra-phase-1" to true,
            (if (writeEnabled) "✓ Create/update bounded text files" else
                "○ Create/update files · verify C2 permission") to writeEnabled,
            (if (writeEnabled) "✓ Create non-force commits on feature branch" else
                "○ Feature-branch commits · verify C2 permission") to writeEnabled,
            (if (writeEnabled) "✓ Open/update draft PR" else
                "○ Open/update draft PR · verify C2 permission") to writeEnabled,
            "✕ Modify main/master" to false,
            "✕ Force push / Secrets / workflow writes / destructive deletes" to false,
        )
        rows.forEach { (label, allowed) ->
            column.addView(text(
                label,
                13.5f,
                if (allowed) Color.rgb(128, 205, 151) else Color.rgb(158, 164, 160),
            ).apply { setPadding(dp(4), dp(7), dp(4), dp(7)) })
        }
    }

    private fun verifyWriteAccess(
        connection: WorkspaceConnectorCredentialStore.GitHubConnection,
    ) {
        if (activeCall != null) return
        val pairing = connection.pairingSecret ?: run {
            showStatus("Reconnect GitHub once to restore the encrypted pairing key.", true)
            return
        }
        setBusy(true)
        showStatus("Checking broker-gated GitHub write permissions…")
        val call = WorkspaceGitHubConnector.client.newCall(
            WorkspaceGitHubConnector.writeAccessRequest(pairing)
        )
        activeCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fail(call, "GitHub write broker could not be reached. No retry was sent.")
            }

            override fun onResponse(call: Call, response: Response) {
                val access = runCatching {
                    WorkspaceGitHubConnector.readWriteAccess(response)
                }.getOrElse {
                    fail(call, it.message ?: "GitHub write permission verification failed")
                    return
                }
                runOnUiThread {
                    if (activeCall !== call || isFinishing || isDestroyed) return@runOnUiThread
                    activeCall = null
                    setBusy(false)
                    if (!access.repository.equals(EXPECTED_REPOSITORY, ignoreCase = true) ||
                        access.branch != EXPECTED_BRANCH ||
                        access.prBase != EXPECTED_PR_BASE) {
                        showStatus("GitHub write binding did not match LYRA policy.", true)
                        return@runOnUiThread
                    }
                    writeVerified = true
                    render()
                }
            }
        })
    }

    private fun copyPairingKey() {
        val pairing = runCatching { store.getOrCreateGitHubPairingSecret() }
            .getOrElse {
                showStatus(it.message ?: "Could not create secure pairing key", true)
                return
            }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("LYRA pairing key", pairing))
        Toast.makeText(
            this,
            "Pairing key copied. Paste it as Cloudflare secret LYRA_PAIRING_SECRET.",
            Toast.LENGTH_LONG,
        ).show()
        showStatus("Pairing key copied. Add it to Cloudflare, then tap Connect.")
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

    private fun maybeRenewConnectedToken() {
        if (activeCall != null) return
        val connection = runCatching { store.loadGitHub() }.getOrNull() ?: return
        val pairing = connection.pairingSecret ?: return
        val expiry = connection.tokenExpiresAtMs ?: 0L
        if (expiry > System.currentTimeMillis() + RENEW_BEFORE_MS) {
            if (!writeVerified) verifyWriteAccess(connection)
            return
        }
        showStatus("Renewing GitHub read token…")
        requestInstallationGrant(pairing)
    }

    private fun requestInstallationGrant(pairingSecret: String) {
        if (activeCall != null) return
        setBusy(true)
        showStatus("Requesting fresh GitHub read token…")
        val request = runCatching {
            WorkspaceGitHubConnector.installationTokenRequest(pairingSecret)
        }.getOrElse {
            setBusy(false)
            showStatus(it.message ?: "GitHub installation request is invalid", true)
            return
        }
        val call = WorkspaceGitHubConnector.client.newCall(request)
        activeCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fail(call, "GitHub broker could not be reached. Check network and try again.")
            }

            override fun onResponse(call: Call, response: Response) {
                val grant = runCatching {
                    WorkspaceGitHubConnector.readInstallationGrant(response)
                }.getOrElse {
                    fail(call, it.message ?: "GitHub installation connection failed")
                    return
                }
                verifyRepository(call, grant, pairingSecret)
            }
        })
    }

    private fun verifyRepository(
        previous: Call,
        grant: WorkspaceGitHubConnector.InstallationGrant,
        pairingSecret: String,
    ) {
        runOnUiThread { showStatus("Checking selected repository access…") }
        if (activeCall !== previous) return
        val call = WorkspaceGitHubConnector.client.newCall(
            WorkspaceGitHubConnector.repositoryRequest(grant.accessToken, EXPECTED_REPOSITORY)
        )
        activeCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fail(call, "GitHub repository check failed. No new token was saved.")
            }

            override fun onResponse(call: Call, response: Response) {
                val verifiedRepo = runCatching {
                    WorkspaceGitHubConnector.readRepository(response, EXPECTED_REPOSITORY)
                }.getOrElse {
                    fail(call, it.message ?: "GitHub repository verification failed")
                    return
                }
                val identity = runCatching {
                    requireGrantIdentity(grant, verifiedRepo.fullName)
                }
                if (identity.isFailure) {
                    fail(call, identity.exceptionOrNull()?.message ?: "GitHub installation identity failed")
                    return
                }
                verifyBranch(call, grant, pairingSecret, verifiedRepo.fullName)
            }
        })
    }

    private fun requireGrantIdentity(
        grant: WorkspaceGitHubConnector.InstallationGrant,
        repository: String,
    ) {
        require(grant.login.equals(EXPECTED_ACCOUNT, ignoreCase = true)) {
            "GitHub installation account did not match"
        }
        require(grant.repository.equals(repository, ignoreCase = true)) {
            "GitHub installation repository did not match"
        }
        require(grant.branch == EXPECTED_BRANCH) {
            "GitHub installation branch did not match"
        }
    }

    private fun verifyBranch(
        previous: Call,
        grant: WorkspaceGitHubConnector.InstallationGrant,
        pairingSecret: String,
        repository: String,
    ) {
        runOnUiThread { showStatus("Checking protected feature branch…") }
        if (activeCall !== previous) return
        val call = WorkspaceGitHubConnector.client.newCall(
            WorkspaceGitHubConnector.branchRequest(
                grant.accessToken,
                repository,
                EXPECTED_BRANCH,
            )
        )
        activeCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fail(call, "GitHub branch check failed. No new token was saved.")
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
                    val expiresAt = runCatching {
                        Math.addExact(
                            now,
                            Math.multiplyExact(grant.expiresInSeconds, 1_000L),
                        )
                    }.getOrNull()
                    if (expiresAt == null) {
                        activeCall = null
                        setBusy(false)
                        showStatus("GitHub token expiry could not be saved safely.", true)
                        return@runOnUiThread
                    }
                    val saved = runCatching {
                        store.saveGitHub(
                            token = grant.accessToken,
                            pairingSecret = pairingSecret,
                            tokenExpiresAtMs = expiresAt,
                            login = grant.login,
                            repository = repository,
                            branch = verifiedBranch.name,
                        )
                    }
                    activeCall = null
                    saved.onSuccess {
                        render()
                        store.loadGitHub()?.let(::verifyWriteAccess)
                    }.onFailure {
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

    private companion object {
        const val EXPECTED_ACCOUNT = "spy626"
        const val EXPECTED_REPOSITORY = "spy626/myra-android"
        const val EXPECTED_BRANCH = "agent/myra-phase-1"
        const val EXPECTED_PR_BASE = "main"
        const val RENEW_BEFORE_MS = 10 * 60 * 1_000L
    }
}
