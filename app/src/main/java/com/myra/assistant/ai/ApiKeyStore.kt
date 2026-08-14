package com.myra.assistant.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Keeps provider secrets out of the app's plain-text SharedPreferences file. */
class ApiKeyStore(context: Context) {
    private val appContext = context.applicationContext
    private val legacy = appContext.getSharedPreferences("myra", Context.MODE_PRIVATE)
    private val secure: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "myra_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun get(name: String): String {
        val encrypted = secure.getString(name, null)
        if (encrypted != null) return encrypted
        val old = if (name == GEMINI) legacy.getString("api_key", "").orEmpty() else ""
        if (old.isNotBlank()) put(name, old)
        return old
    }

    fun put(name: String, value: String) {
        secure.edit().putString(name, value.trim()).apply()
        if (name == GEMINI) legacy.edit().remove("api_key").apply()
    }

    companion object {
        const val GEMINI = "gemini_api_key"
        const val GROQ = "groq_api_key"
        const val DEEPSEEK = "deepseek_api_key"
        const val TAVILY = "tavily_api_key"
    }
}
