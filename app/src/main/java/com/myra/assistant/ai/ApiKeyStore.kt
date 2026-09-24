package com.myra.assistant.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class ApiKeyStore(context: Context) {
    private val appContext=context.applicationContext
    private val legacy=appContext.getSharedPreferences("myra",Context.MODE_PRIVATE)
    private val secure:SharedPreferences by lazy { val master=MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();EncryptedSharedPreferences.create(appContext,"myra_secure",master,EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM) }
    fun get(name:String):String { val saved=secure.getString(name,null);if(saved!=null)return saved;val old=if(name==GEMINI)legacy.getString("api_key","").orEmpty() else "";if(old.isNotBlank())put(name,old);return old }
    fun put(name:String,value:String){secure.edit().putString(name,value.trim()).apply();if(name==GEMINI)legacy.edit().remove("api_key").apply()}
    fun remove(name: String) { secure.edit().remove(name).apply() }
    companion object {
        const val GEMINI="gemini_api_key";const val OPENROUTER="openrouter_api_key";const val GROQ="groq_api_key";const val LLM7="llm7_api_key";const val XKIRO="xkiro_api_key";const val ZAI="zai_api_key";const val DEEPSEEK="deepseek_api_key";const val TAVILY="tavily_api_key"
        private const val CUSTOM_PROVIDER_PREFIX = "custom_provider_api_key_"
        private val CUSTOM_PROVIDER_ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")
        fun customProviderSlot(id: String): String {
            val clean = id.trim().lowercase()
            require(CUSTOM_PROVIDER_ID.matches(clean)) { "Invalid custom provider ID" }
            return CUSTOM_PROVIDER_PREFIX + clean
        }
    }
}
