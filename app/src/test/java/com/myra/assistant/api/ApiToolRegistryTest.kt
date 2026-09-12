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
        val safe = ApiToolDefinition("safe", "Safe", "", "Weather", setOf("weather"), "https://docs", authType = ApiAuthType.NONE, noAuthAvailable = true, httpsSupported = true, enabled = true, validationState = ApiValidationState.VALIDATED, adapterId = "safe-adapter")
        val disabled = safe.copy(id = "disabled", enabled = false)
        val insecure = safe.copy(id = "insecure", httpsSupported = false)
        val result = ApiToolRegistry(listOf(disabled, insecure, safe)).relevant("WEATHER")
        assertEquals(listOf("safe"), result.map { it.id })
    }

    @Test fun broken_provider_is_not_selected_and_health_recovers_after_success() {
        val provider = ApiToolDefinition("weather", "Weather", "", "Weather", setOf("weather"), "https://docs",
            authType = ApiAuthType.NONE, noAuthAvailable = true, httpsSupported = true, enabled = true,
            validationState = ApiValidationState.VALIDATED, adapterId = "weather-adapter")
        val registry = ApiToolRegistry(listOf(provider))
        registry.recordFailure("weather", 1)
        registry.recordFailure("weather", 2)
        registry.recordFailure("weather", 3)
        assertTrue(registry.relevant("weather").isEmpty())
        registry.recordSuccess("weather", 4)
        assertEquals(listOf("weather"), registry.relevant("weather").map { it.id })
    }

    @Test fun catalogue_metadata_is_not_executable_without_validated_adapter() {
        val metadata = ApiToolDefinition("meta", "Meta", "", "Weather", setOf("weather"), "https://docs",
            authType = ApiAuthType.NONE, noAuthAvailable = true, httpsSupported = true, enabled = true)
        assertTrue(ApiToolRegistry(listOf(metadata)).relevant("weather").isEmpty())
    }
}
