package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceCustomProviderStoreTest {
    @Test fun metadataRoundTripContainsNoSecretAndRevalidates() {
        val profile = WorkspaceCustomProviderProfile.validate(
            WorkspaceCustomProviderProfile.userDraft(
                id = WorkspaceCustomProviderStore.DEFAULT_PROFILE_ID,
                displayName = "My Gateway",
                baseUrl = "https://api.example.com/openai/v1",
                modelId = "vendor/model",
            )
        )
        val raw = WorkspaceCustomProviderStore.encode(profile)
        assertFalse(raw.contains("api_key", ignoreCase = true))
        assertFalse(raw.contains("secret", ignoreCase = true))
        val decoded = requireNotNull(WorkspaceCustomProviderStore.decode(raw))
        assertEquals(profile.id, decoded.id)
        assertEquals(profile.baseUrl, decoded.baseUrl)
        assertEquals(profile.chatCompletionsUrl, decoded.chatCompletionsUrl)
        assertEquals(profile.modelId, decoded.modelId)
        assertFalse(decoded.automaticRouting)
        assertEquals(WorkspaceCustomProviderProfile.CostState.UNVERIFIED_COST,
            decoded.costState)
    }

    @Test fun corruptUnknownOrUnsafeStoredProfilesFailClosed() {
        assertNull(WorkspaceCustomProviderStore.decode(""))
        assertNull(WorkspaceCustomProviderStore.decode("""{"v":2}"""))
        val safe = WorkspaceCustomProviderStore.encode(
            WorkspaceCustomProviderProfile.validate(
                WorkspaceCustomProviderProfile.userDraft(
                    WorkspaceCustomProviderStore.DEFAULT_PROFILE_ID,
                    "Safe", "https://example.com/v1", "model")))
        assertNull(WorkspaceCustomProviderStore.decode(
            safe.replace("https://example.com/v1", "http://example.com/v1")))
    }
}
