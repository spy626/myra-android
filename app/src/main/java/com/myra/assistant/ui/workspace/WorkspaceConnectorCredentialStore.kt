package com.myra.assistant.ui.workspace

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secret connector credentials are isolated from model-provider API keys.
 * GitHub tokens are encrypted at rest and never exposed through public preferences.
 */
internal class WorkspaceConnectorCredentialStore(context: Context) {
    data class GitHubConnection(
        val login: String,
        val repository: String,
        val branch: String,
        val token: String,
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
            GitHubConnection(login, binding.repository, binding.branch, token)
        }.getOrNull()
    }

    fun saveGitHub(
        token: String,
        login: String,
        repository: String,
        branch: String,
    ) {
        val cleanToken = WorkspaceGitHubConnector.requireToken(token)
        val binding = WorkspaceConnectorPolicy.binding(repository, branch)
        val cleanLogin = login.trim()
        require(cleanLogin.length in 1..100 && cleanLogin.none(Char::isISOControl)) {
            "GitHub login is invalid"
        }
        check(
            secure.edit()
                .putString(KEY_GITHUB_TOKEN, cleanToken)
                .putString(KEY_GITHUB_LOGIN, cleanLogin)
                .putString(KEY_GITHUB_REPOSITORY, binding.repository)
                .putString(KEY_GITHUB_BRANCH, binding.branch)
                .commit()
        ) { "GitHub connector could not be saved securely" }
    }

    fun disconnectGitHub() {
        check(
            secure.edit()
                .remove(KEY_GITHUB_TOKEN)
                .remove(KEY_GITHUB_LOGIN)
                .remove(KEY_GITHUB_REPOSITORY)
                .remove(KEY_GITHUB_BRANCH)
                .commit()
        ) { "GitHub connector could not be removed" }
    }

    private companion object {
        const val KEY_GITHUB_TOKEN = "github_token"
        const val KEY_GITHUB_LOGIN = "github_login"
        const val KEY_GITHUB_REPOSITORY = "github_repository"
        const val KEY_GITHUB_BRANCH = "github_branch"
    }
}
