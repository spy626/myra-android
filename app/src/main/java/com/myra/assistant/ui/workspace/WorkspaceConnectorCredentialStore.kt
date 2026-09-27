package com.myra.assistant.ui.workspace

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secret connector credentials are isolated from model-provider API keys.
 * GitHub OAuth tokens and temporary PKCE verifier state are encrypted at rest and never exposed
 * through public preferences.
 */
internal class WorkspaceConnectorCredentialStore(context: Context) {
    data class GitHubConnection(
        val login: String,
        val repository: String,
        val branch: String,
        val token: String,
        val refreshToken: String? = null,
        val tokenExpiresAtMs: Long? = null,
        val refreshTokenExpiresAtMs: Long? = null,
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
            val refreshToken = secure.getString(KEY_GITHUB_REFRESH_TOKEN, null)
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let(WorkspaceGitHubConnector::requireToken)
            GitHubConnection(
                login = login,
                repository = binding.repository,
                branch = binding.branch,
                token = WorkspaceGitHubConnector.requireToken(token),
                refreshToken = refreshToken,
                tokenExpiresAtMs = secure.getLong(KEY_GITHUB_TOKEN_EXPIRES_AT, 0L)
                    .takeIf { it > 0L },
                refreshTokenExpiresAtMs = secure.getLong(KEY_GITHUB_REFRESH_EXPIRES_AT, 0L)
                    .takeIf { it > 0L },
            )
        }.getOrNull()
    }

    fun saveGitHub(
        token: String,
        login: String,
        repository: String,
        branch: String,
        refreshToken: String? = null,
        tokenExpiresAtMs: Long? = null,
        refreshTokenExpiresAtMs: Long? = null,
    ) {
        val cleanToken = WorkspaceGitHubConnector.requireToken(token)
        val cleanRefresh = refreshToken
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(WorkspaceGitHubConnector::requireToken)
        val binding = WorkspaceConnectorPolicy.binding(repository, branch)
        val cleanLogin = login.trim()
        require(cleanLogin.length in 1..100 && cleanLogin.none(Char::isISOControl)) {
            "GitHub login is invalid"
        }
        require(tokenExpiresAtMs == null || tokenExpiresAtMs > 0L) {
            "GitHub token expiry is invalid"
        }
        require(refreshTokenExpiresAtMs == null || refreshTokenExpiresAtMs > 0L) {
            "GitHub refresh token expiry is invalid"
        }

        val edit = secure.edit()
            .putString(KEY_GITHUB_TOKEN, cleanToken)
            .putString(KEY_GITHUB_LOGIN, cleanLogin)
            .putString(KEY_GITHUB_REPOSITORY, binding.repository)
            .putString(KEY_GITHUB_BRANCH, binding.branch)

        if (cleanRefresh == null) edit.remove(KEY_GITHUB_REFRESH_TOKEN)
        else edit.putString(KEY_GITHUB_REFRESH_TOKEN, cleanRefresh)

        if (tokenExpiresAtMs == null) edit.remove(KEY_GITHUB_TOKEN_EXPIRES_AT)
        else edit.putLong(KEY_GITHUB_TOKEN_EXPIRES_AT, tokenExpiresAtMs)

        if (refreshTokenExpiresAtMs == null) edit.remove(KEY_GITHUB_REFRESH_EXPIRES_AT)
        else edit.putLong(KEY_GITHUB_REFRESH_EXPIRES_AT, refreshTokenExpiresAtMs)

        check(edit.commit()) { "GitHub connector could not be saved securely" }
    }

    fun saveGitHubOAuthPending(pending: WorkspaceGitHubOAuthSession.Pending) {
        val checked = WorkspaceGitHubOAuthSession.restore(
            pending.state,
            pending.verifier,
            pending.challenge,
            pending.createdAtMs,
        )
        check(
            secure.edit()
                .putString(KEY_GITHUB_OAUTH_STATE, checked.state)
                .putString(KEY_GITHUB_OAUTH_VERIFIER, checked.verifier)
                .putString(KEY_GITHUB_OAUTH_CHALLENGE, checked.challenge)
                .putLong(KEY_GITHUB_OAUTH_CREATED_AT, checked.createdAtMs)
                .commit()
        ) { "GitHub authorization state could not be saved securely" }
    }

    fun loadGitHubOAuthPending(): WorkspaceGitHubOAuthSession.Pending? {
        val state = secure.getString(KEY_GITHUB_OAUTH_STATE, null)?.trim().orEmpty()
        val verifier = secure.getString(KEY_GITHUB_OAUTH_VERIFIER, null)?.trim().orEmpty()
        val challenge = secure.getString(KEY_GITHUB_OAUTH_CHALLENGE, null)?.trim().orEmpty()
        val createdAt = secure.getLong(KEY_GITHUB_OAUTH_CREATED_AT, -1L)
        if (state.isBlank() || verifier.isBlank() || challenge.isBlank() || createdAt < 0L) {
            return null
        }
        return runCatching {
            WorkspaceGitHubOAuthSession.restore(state, verifier, challenge, createdAt)
        }.getOrNull()
    }

    fun clearGitHubOAuthPending() {
        check(
            secure.edit()
                .remove(KEY_GITHUB_OAUTH_STATE)
                .remove(KEY_GITHUB_OAUTH_VERIFIER)
                .remove(KEY_GITHUB_OAUTH_CHALLENGE)
                .remove(KEY_GITHUB_OAUTH_CREATED_AT)
                .commit()
        ) { "GitHub authorization state could not be cleared securely" }
    }

    fun disconnectGitHub() {
        check(
            secure.edit()
                .remove(KEY_GITHUB_TOKEN)
                .remove(KEY_GITHUB_REFRESH_TOKEN)
                .remove(KEY_GITHUB_TOKEN_EXPIRES_AT)
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
        const val KEY_GITHUB_REFRESH_TOKEN = "github_refresh_token"
        const val KEY_GITHUB_TOKEN_EXPIRES_AT = "github_token_expires_at"
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
