package com.myra.assistant.ui.workspace

import java.net.URI
import java.util.Locale

/**
 * Validated metadata for a user-defined OpenAI-compatible provider.
 *
 * This is configuration only: it performs no network call, owns no router, stores no API key,
 * and never grants source/attachment permission by itself.
 */
internal object WorkspaceCustomProviderProfile {
    enum class AuthMode { BEARER, X_API_KEY }
    enum class CostState { UNVERIFIED_COST, VERIFIED_ZERO_COST }

    data class Draft(
        val id: String,
        val displayName: String,
        val baseUrl: String,
        val modelId: String,
        val authMode: AuthMode = AuthMode.BEARER,
        val localEndpoint: Boolean = false,
        val tasks: Set<WorkspaceProviderRegistry.TaskKind> = setOf(
            WorkspaceProviderRegistry.TaskKind.CHAT_TEXT),
        val maxPromptChars: Int = 12_000,
        val maxOutputTokens: Int = 2_048,
        val sourceAllowed: Boolean = false,
        val attachmentsAllowed: Boolean = false,
        val timeoutSeconds: Int = 35,
        val costState: CostState = CostState.UNVERIFIED_COST,
        val automaticRouting: Boolean = false,
    )

    data class Validated(
        val id: String,
        val displayName: String,
        val baseUrl: String,
        val chatCompletionsUrl: String,
        val modelId: String,
        val authMode: AuthMode,
        val localEndpoint: Boolean,
        val tasks: Set<WorkspaceProviderRegistry.TaskKind>,
        val maxPromptChars: Int,
        val maxOutputTokens: Int,
        val sourceAllowed: Boolean,
        val attachmentsAllowed: Boolean,
        val timeoutSeconds: Int,
        val costState: CostState,
        val automaticRouting: Boolean,
        val encryptedKeySlot: String,
    )

    private val idPattern = Regex("""[a-z0-9][a-z0-9_-]{0,63}""")
    private val ipv4 = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
    private val safeText = Regex("""[^\p{Cntrl}]{1,120}""")

    private fun cleanLabel(value: String, field: String): String {
        val clean = value.trim()
        require(safeText.matches(clean)) { "$field is missing, too long, or contains control characters" }
        return clean
    }

    private fun ipv4Bytes(host: String): List<Int>? {
        if (!ipv4.matches(host)) return null
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return null
        return parts
    }

    internal fun isLoopbackHost(hostRaw: String): Boolean {
        val host = hostRaw.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.US)
        return host == "localhost" || host.endsWith(".localhost") ||
            host == "127.0.0.1" || host == "::1"
    }

    internal fun isLocalHost(hostRaw: String): Boolean {
        val host = hostRaw.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.US)
        if (host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home")) return true
        if (host == "::1") return true
        if (host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe8") ||
            host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")) {
            if (':' in host) return true
        }
        val bytes = ipv4Bytes(host) ?: return false
        val a = bytes[0]
        val b = bytes[1]
        return a == 10 || a == 127 || (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    private fun normalizeBaseUrl(raw: String, localEndpoint: Boolean): Pair<String, String> {
        val input = raw.trim()
        require(input.length in 8..512) { "Custom API Base URL is missing or too long" }
        val uri = runCatching { URI(input) }
            .getOrElse { throw IllegalArgumentException("Custom API Base URL is invalid") }
        val scheme = uri.scheme?.lowercase(Locale.US)
            ?: throw IllegalArgumentException("Custom API Base URL needs http or https")
        require(scheme == "https" || scheme == "http") { "Only http/https Custom API URLs are supported" }
        require(uri.host != null && uri.host.isNotBlank()) { "Custom API Base URL needs a host" }
        require(uri.userInfo == null) { "Credentials are not allowed inside Custom API Base URL" }
        require(uri.rawQuery == null) { "Query parameters are not allowed in Custom API Base URL" }
        require(uri.rawFragment == null) { "URL fragments are not allowed in Custom API Base URL" }
        require(uri.port in -1..65535) { "Custom API port is invalid" }

        val decodedSegments = uri.path.orEmpty().split('/')
        require(decodedSegments.none { it == "." || it == ".." }) {
            "Custom API Base URL cannot contain dot-path traversal"
        }
        require(!uri.path.orEmpty().trimEnd('/').endsWith("/chat/completions", ignoreCase = true)) {
            "Enter the API base URL, not the full chat/completions endpoint"
        }

        val localHost = isLocalHost(uri.host)
        val loopbackHost = isLoopbackHost(uri.host)
        if (localEndpoint) {
            require(localHost) { "Local endpoint mode accepts only loopback/private-network hosts" }
            require(scheme == "https" || loopbackHost) {
                "Private-network Custom API endpoints must use HTTPS; cleartext HTTP is limited to phone loopback"
            }
        } else {
            require(scheme == "https") { "Internet Custom API endpoints must use HTTPS" }
            require(!localHost) { "Loopback/private hosts require Local endpoint mode" }
        }

        val normalized = URI(
            scheme,
            null,
            uri.host.lowercase(Locale.US),
            uri.port,
            uri.rawPath?.trimEnd('/')?.ifEmpty { null },
            null,
            null,
        ).toASCIIString().trimEnd('/')
        return normalized to "$normalized/chat/completions"
    }

    fun validate(draft: Draft): Validated {
        val id = draft.id.trim().lowercase(Locale.US)
        require(idPattern.matches(id)) { "Custom provider ID must use lowercase letters, numbers, _ or -" }
        val displayName = cleanLabel(draft.displayName, "Custom provider name")
        val model = cleanLabel(draft.modelId, "Custom model ID")
        // Keep the model ID opaque; different OpenAI-compatible providers use different syntax.
        require(draft.tasks.isNotEmpty()) { "Choose at least one Custom provider task capability" }
        require(draft.maxPromptChars in 1_000..96_000) { "Custom provider prompt budget is out of range" }
        require(draft.maxOutputTokens in 128..16_384) { "Custom provider output budget is out of range" }
        require(draft.timeoutSeconds in 5..120) { "Custom provider timeout must be 5–120 seconds" }
        require(!draft.attachmentsAllowed ||
            WorkspaceProviderRegistry.TaskKind.CHAT_ATTACHMENT in draft.tasks) {
            "Attachment permission requires the attachment task capability"
        }
        require(!draft.sourceAllowed ||
            draft.tasks.any { it == WorkspaceProviderRegistry.TaskKind.CODE_EDIT ||
                it == WorkspaceProviderRegistry.TaskKind.WEBSITE_BUILD }) {
            "Source permission requires a coding capability"
        }
        require(!draft.automaticRouting ||
            draft.costState == CostState.VERIFIED_ZERO_COST) {
            "Unverified-cost Custom providers are manual-only"
        }

        val (base, endpoint) = normalizeBaseUrl(draft.baseUrl, draft.localEndpoint)
        return Validated(
            id = id,
            displayName = displayName,
            baseUrl = base,
            chatCompletionsUrl = endpoint,
            modelId = model,
            authMode = draft.authMode,
            localEndpoint = draft.localEndpoint,
            tasks = draft.tasks.toSet(),
            maxPromptChars = draft.maxPromptChars,
            maxOutputTokens = draft.maxOutputTokens,
            sourceAllowed = draft.sourceAllowed,
            attachmentsAllowed = draft.attachmentsAllowed,
            timeoutSeconds = draft.timeoutSeconds,
            costState = draft.costState,
            automaticRouting = draft.automaticRouting,
            encryptedKeySlot = com.myra.assistant.ai.ApiKeyStore.customProviderSlot(id),
        )
    }

    /** User-created profiles always start manual-only until a separate cost verifier proves $0. */
    fun userDraft(
        id: String,
        displayName: String,
        baseUrl: String,
        modelId: String,
        localEndpoint: Boolean = false,
    ): Draft = Draft(
        id = id,
        displayName = displayName,
        baseUrl = baseUrl,
        modelId = modelId,
        localEndpoint = localEndpoint,
        costState = CostState.UNVERIFIED_COST,
        automaticRouting = false,
    )

    const val SYNTHETIC_CONNECTION_TEST_TEXT =
        "Reply with exactly OK. This is a synthetic LYRA connection test and contains no project source."
}
