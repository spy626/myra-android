package com.myra.assistant.api

import java.util.Locale

enum class ApiAuthType { NONE, API_KEY, OAUTH, OTHER, UNKNOWN }
enum class ApiPrivacyLevel { PUBLIC, USER_DATA, SENSITIVE }

data class ApiToolDefinition(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val capabilityTags: Set<String>,
    val documentationUrl: String,
    val baseUrl: String? = null,
    val authType: ApiAuthType = ApiAuthType.UNKNOWN,
    val requiresUserKey: Boolean = false,
    val requiresOAuth: Boolean = false,
    val noAuthAvailable: Boolean = false,
    val httpsSupported: Boolean = false,
    val corsSupport: String = "unknown",
    val freeTierKnown: Boolean = false,
    val rateLimitKnown: Boolean = false,
    val privacyLevel: ApiPrivacyLevel = ApiPrivacyLevel.PUBLIC,
    val reliabilityScore: Double = 0.5,
    val enabled: Boolean = false,
    val providerPriority: Int = 100,
    val lastValidatedAt: Long = 0L
)

class ApiToolRegistry(initial: List<ApiToolDefinition> = emptyList()) {
    private val definitions = linkedMapOf<String, ApiToolDefinition>().apply { initial.forEach { put(it.id, it) } }

    @Synchronized fun replaceCatalogue(items: List<ApiToolDefinition>) {
        definitions.clear(); items.forEach { definitions[it.id] = it }
    }

    @Synchronized fun relevant(capability: String, usableOnly: Boolean = true): List<ApiToolDefinition> {
        val tag = capability.lowercase(Locale.ROOT)
        return definitions.values.filter { item ->
            (!usableOnly || item.enabled) && item.httpsSupported && item.capabilityTags.any { it == tag }
        }.sortedWith(compareBy<ApiToolDefinition> { it.providerPriority }
            .thenByDescending { it.noAuthAvailable }.thenByDescending { it.reliabilityScore })
    }
}

object PublicApisCatalogueImporter {
    /** Parses public-apis markdown rows without treating catalogue entries as trusted/enabled providers. */
    fun parse(markdown: String, categoryHint: String? = null): List<ApiToolDefinition> {
        var category = categoryHint.orEmpty()
        return buildList {
            markdown.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.startsWith("## ")) category = line.removePrefix("## ").trim()
                if (line.startsWith("### ")) category = line.removePrefix("### ").trim()
                if (!line.startsWith("| [") || line.contains("API | Description")) return@forEach
                val cells = line.split('|').map(String::trim)
                if (cells.size < 7) return@forEach
                val match = Regex("\\[([^]]+)]\\((https?://[^)]+)\\)").find(cells[1]) ?: return@forEach
                val name = match.groupValues[1].trim()
                val url = match.groupValues[2].trim()
                val authRaw = cells.getOrElse(3) { "" }.trim('`', ' ')
                val https = cells.getOrElse(4) { "" }.equals("Yes", true)
                val cors = cells.getOrElse(5) { "unknown" }.lowercase(Locale.ROOT)
                val auth = when {
                    authRaw.isBlank() || authRaw.equals("No", true) -> ApiAuthType.NONE
                    authRaw.contains("OAuth", true) -> ApiAuthType.OAUTH
                    authRaw.contains("apiKey", true) -> ApiAuthType.API_KEY
                    else -> ApiAuthType.OTHER
                }
                val normalizedCategory = category.ifBlank { "Uncategorized" }
                add(ApiToolDefinition(
                    id = (normalizedCategory + "-" + name).lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-'),
                    name = name, description = cells[2], category = normalizedCategory,
                    capabilityTags = setOf(normalizedCategory.lowercase(Locale.ROOT), name.lowercase(Locale.ROOT)),
                    documentationUrl = url, authType = auth,
                    requiresUserKey = auth == ApiAuthType.API_KEY, requiresOAuth = auth == ApiAuthType.OAUTH,
                    noAuthAvailable = auth == ApiAuthType.NONE, httpsSupported = https,
                    corsSupport = cors, enabled = false
                ))
            }
        }
    }
}
