package com.myra.assistant.security

import android.content.Context
import com.myra.assistant.ai.ApiKeyStore

class SecureKeyStorage(context: Context) {
    private val store = ApiKeyStore(context)
    fun read(key: String): String = store.get(key)
    fun write(key: String, value: String) = store.put(key, value)
}
