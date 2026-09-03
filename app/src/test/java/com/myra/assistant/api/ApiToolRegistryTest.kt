package com.myra.assistant.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiToolRegistryTest {
    @Test fun importer_preserves_auth_and_keeps_catalogue_entries_disabled() {
        val markdown = """
            ## Weather
            | API | Description | Auth | HTTPS | CORS |
            | --- | --- | --- | --- | --- |
            | [Open Weather](https://example.com/docs) | Forecast | `apiKey` | Yes | Yes |
            | [No Key Weather](https://example.org/docs) | Public forecast | No | Yes | Unknown |
        """.trimIndent()
        val imported = PublicApisCatalogueImporter.parse(markdown)
        assertEquals(2, imported.size)
        assertEquals(ApiAuthType.API_KEY, imported.first().authType)
        assertTrue(imported.first().requiresUserKey)
        assertTrue(imported[1].noAuthAvailable)
        assertFalse(imported.any { it.enabled })
    }

    @Test fun registry_returns_only_enabled_https_relevant_providers() {
        val safe = ApiToolDefinition("safe", "Safe", "", "Weather", setOf("weather"), "https://docs", authType = ApiAuthType.NONE, noAuthAvailable = true, httpsSupported = true, enabled = true)
        val disabled = safe.copy(id = "disabled", enabled = false)
        val insecure = safe.copy(id = "insecure", httpsSupported = false)
        val result = ApiToolRegistry(listOf(disabled, insecure, safe)).relevant("WEATHER")
        assertEquals(listOf("safe"), result.map { it.id })
    }
}
