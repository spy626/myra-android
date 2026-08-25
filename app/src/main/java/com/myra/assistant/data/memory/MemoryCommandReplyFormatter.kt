package com.myra.assistant.data.memory

object MemoryCommandReplyFormatter {
    fun rememberSaved(): String = "Theek hai, yaad rakhungi."

    fun rememberRejected(): String =
        "Passwords, security codes ya unsafe private details save nahi kar sakti."

    fun forgotten(found: Boolean): String = if (found) {
        "Theek hai, woh memory delete kar di."
    } else {
        "Woh memory saved nahi mili."
    }
}
