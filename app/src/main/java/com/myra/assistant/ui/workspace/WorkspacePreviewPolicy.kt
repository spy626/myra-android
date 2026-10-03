package com.myra.assistant.ui.workspace

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** A deliberately small, read-only website preview surface. Never serves .lyra metadata. */
internal object WorkspacePreviewPolicy {
    private val allowedTypes = mapOf(
        "html" to "text/html; charset=utf-8", "htm" to "text/html; charset=utf-8",
        "css" to "text/css; charset=utf-8", "js" to "application/javascript; charset=utf-8",
        "mjs" to "application/javascript; charset=utf-8", "svg" to "image/svg+xml",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "ico" to "image/x-icon",
        "woff" to "font/woff", "woff2" to "font/woff2",
    )

    fun route(requestTarget: String): String? {
        if (!requestTarget.startsWith('/') || requestTarget.startsWith("//") ||
            requestTarget.length > 1024 || requestTarget.any { it.isISOControl() }) return null
        val raw = requestTarget.substringBefore('?').substringBefore('#')
        val decoded = runCatching {
            URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrNull() ?: return null
        if (!decoded.startsWith('/') || decoded.contains("//") || '\\' in decoded ||
            ':' in decoded || '%' in decoded || decoded.any { it.isISOControl() }) return null
        val parts = decoded.removePrefix("/").split('/').filter { it.isNotEmpty() }
        if (parts.size > 12 || parts.any {
                it == "." || it == ".." || it == ".lyra" ||
                    it.startsWith(".lyra-write-") || it.length > 100
            }) return null
        val path = parts.joinToString("/") + if (decoded.endsWith('/') || parts.isEmpty()) {
            if (parts.isEmpty()) "index.html" else "/index.html"
        } else ""
        return path.takeIf { mime(it) != null }
    }

    fun mime(path: String): String? = allowedTypes[path.substringAfterLast('.', "").lowercase()]

    fun validHost(host: String?, port: Int): Boolean = host == "127.0.0.1:$port"
}
