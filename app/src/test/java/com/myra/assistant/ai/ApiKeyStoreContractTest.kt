package com.myra.assistant.ai

import org.junit.Assert.*
import org.junit.Test

class ApiKeyStoreContractTest {
    @Test fun customProviderSlotsAreNamespacedAndBounded() {
        assertEquals("custom_provider_api_key_my_gateway",
            ApiKeyStore.customProviderSlot("my_gateway"))
        assertEquals("custom_provider_api_key_abc-1",
            ApiKeyStore.customProviderSlot(" ABC-1 "))
        assertTrue(runCatching { ApiKeyStore.customProviderSlot("../bad") }.isFailure)
        assertTrue(runCatching { ApiKeyStore.customProviderSlot("") }.isFailure)
        assertTrue(runCatching { ApiKeyStore.customProviderSlot("x".repeat(65)) }.isFailure)
    }
}
