package com.myra.assistant.ui.workspace

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secret connector credentials are isolated from model-provider API keys.
 *
 * GitHub installation tokens and the device pairing key are encrypted at rest and never exposed
 * through public preferences or model prompts. The pairing key can mint replacement one-hour
 * installation tokens through the Cloudflare broker without repeated GitHub sign-in.
 */
internal class WorkspaceConnectorCredentialStore(context: Context) {
    data class GitHubConnection(
        val login: String,
        val repository: String,
        val branch: String,
        val token: String,
        val pairingSecret: String?,
        val tokenExpiresAtMs: Long?,
    )

    private val appContext = context.applicationContext
    private val secure by lazy {
        val master = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "workspace_connectors_secure",
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun loadGitHub(): GitHubConnection? {
        val token = secure.getString(KEY_GITHUB_TOKEN, null)?.trim().orEmpty()
        val login = secure.getString(KEY_GITHUB_LOGIN, null)?.trim().orEmpty()
        val repository = secure.getString(KEY_GITHUB_REPOSITORY, null)?.trim().orEmpty()
        val branch = secure.getString(KEY_GITHUB_BRANCH, null)?.trim().orEmpty()
        if (token.isBlank() || login.isBlank() || repository.isBlank() || branch.isBlank()) {
            return null
        }
        return runCatching {
            val binding = WorkspaceConnectorPolicy.binding(repository, branch)
            require(login.length in 1..100 && login.none(Char::isISOControl)) {
                "Saved GitHub login is invalid"
            }
            val pairing = secure.getString(KEY_GITHUB_PAIRING_SECRET, null)
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let(WorkspaceGitHubConnector::requirePairingSecret)
            GitHubConnection(
                login = login,
                repository = binding.repository,
                branch = binding.branch,
                token = WorkspaceGitHubConnector.requireToken(token),
                pairingSecret = pairing,
                tokenExpiresAtMs = secure.getLong(KEY_GITHUB_TOKEN_EXPIRES_AT, 0L)
                    .takeIf { it > 0L },
            )
        }.getOrNull()
    }

    fun getOrCreateGitHubPairingSecret(): String {
        secure.getString(KEY_GITHUB_PAIRING_SECRET, null)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { existing ->
                return WorkspaceGitHubConnector.requirePairingSecret(existing)
            }

        val generated = WorkspaceGitHubConnector.newPairingSecret()
        check(
            secure.edit()
                .putString(KEY_GITHUB_PAIRING_SECRET, generated)
                .commit()
        ) { "LYRA pairing key could not be saved securely" }
        return generated
    }

    fun saveGitHub(
        token: String,
        pairingSecret: String,
        login: String,
        repository: String,
        branch: String,
        tokenExpiresAtMs: Long,
    ) {
        val cleanToken = WorkspaceGitHubConnector.requireToken(token)
        val cleanPairing = WorkspaceGitHubConnector.requirePairingSecret(pairingSecret)
        val binding = WorkspaceConnectorPolicy.binding(repository, branch)
        val cleanLogin = login.trim()
        require(cleanLogin.length in 1..100 && cleanLogin.none(Char::isISOControl)) {
            "GitHub login is invalid"
        }
        require(tokenExpiresAtMs > 0L) { "GitHub token expiry is invalid" }

        check(
            secure.edit()
                .putString(KEY_GITHUB_TOKEN, cleanToken)
                .putString(KEY_GITHUB_PAIRING_SECRET, cleanPairing)
                .putString(KEY_GITHUB_LOGIN, cleanLogin)
                .putString(KEY_GITHUB_REPOSITORY, binding.repository)
                .putString(KEY_GITHUB_BRANCH, binding.branch)
                .putLong(KEY_GITHUB_TOKEN_EXPIRES_AT, tokenExpiresAtMs)
                .remove(KEY_GITHUB_REFRESH_TOKEN)
                .remove(KEY_GITHUB_REFRESH_EXPIRES_AT)
                .remove(KEY_GITHUB_OAUTH_STATE)
                .remove(KEY_GITHUB_OAUTH_VERIFIER)
                .remove(KEY_GITHUB_OAUTH_CHALLENGE)
                .remove(KEY_GITHUB_OAUTH_CREATED_AT)
                .commit()
        ) { "GitHub connector could not be saved securely" }
    }

    fun disconnectGitHub() {
        check(
            secure.edit()
                .remove(KEY_GITHUB_TOKEN)
                .remove(KEY_GITHUB_PAIRING_SECRET)
                .remove(KEY_GITHUB_TOKEN_EXPIRES_AT)
                .remove(KEY_GITHUB_REFRESH_TOKEN)
                .remove(KEY_GITHUB_REFRESH_EXPIRES_AT)
                .remove(KEY_GITHUB_LOGIN)
                .remove(KEY_GITHUB_REPOSITORY)
                .remove(KEY_GITHUB_BRANCH)
                .remove(KEY_GITHUB_OAUTH_STATE)
                .remove(KEY_GITHUB_OAUTH_VERIFIER)
                .remove(KEY_GITHUB_OAUTH_CHALLENGE)
                .remove(KEY_GITHUB_OAUTH_CREATED_AT)
                .commit()
        ) { "GitHub connector could not be removed" }
    }

    private companion object {
        const val KEY_GITHUB_TOKEN = "github_token"
        const val KEY_GITHUB_PAIRING_SECRET = "github_pairing_secret"
        const val KEY_GITHUB_TOKEN_EXPIRES_AT = "github_token_expires_at"

        // Legacy OAuth keys are removed when installation authentication succeeds or disconnects.
        const val KEY_GITHUB_REFRESH_TOKEN = "github_refresh_token"
        const val KEY_GITHUB_REFRESH_EXPIRES_AT = "github_refresh_expires_at"
        const val KEY_GITHUB_LOGIN = "github_login"
        const val KEY_GITHUB_REPOSITORY = "github_repository"
        const val KEY_GITHUB_BRANCH = "github_branch"
        const val KEY_GITHUB_OAUTH_STATE = "github_oauth_state"
        const val KEY_GITHUB_OAUTH_VERIFIER = "github_oauth_verifier"
        const val KEY_GITHUB_OAUTH_CHALLENGE = "github_oauth_challenge"
        const val KEY_GITHUB_OAUTH_CREATED_AT = "github_oauth_created_at"
    }
}
