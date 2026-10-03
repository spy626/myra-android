package com.myra.assistant.ui.workspace

/**
 * One metadata/health contract for Workspace providers.
 *
 * This is NOT a second router, executor, provider client or persistence layer. Existing Chat/Work
 * owners remain authoritative; this object only centralizes capabilities, conservative budgets,
 * free-route assurance and definite-vs-uncertain failure semantics.
 */
internal object WorkspaceProviderRegistry {
    enum class Id {
        OPENROUTER_FREE,
        GROQ_FREE,
        LLM7_FREE,
        XKIRO_FREE,
        ZAI_FREE,
    }

    enum class TaskKind {
        CHAT_TEXT,
        CHAT_ATTACHMENT,
        CODE_EDIT,
        WEBSITE_BUILD,
    }

    enum class FreeAssurance {
        /** API request itself enforces a literal zero-price ceiling. */
        API_ZERO_PRICE_CEILING,
        /** User must keep the external account on its Free/ZDR configuration. */
        USER_ATTESTED_FREE_ACCOUNT,
        /** Every source-bearing request is preceded by zero-price + remaining-free-quota checks. */
        PREFLIGHT_ZERO_PRICE_AND_QUOTA,
        /** Adapter has no paid fallback, but account-level free entitlement is not proven here. */
        NO_PAID_FALLBACK_ONLY,
    }

    enum class FallbackRule {
        NONE,
        USER_CONSENTED_DEFINITE_HTTP_ONLY,
    }

    data class Budget(
        val maxPromptChars: Int?,
        val maxOutputTokens: Int?,
    )

    data class Capability(
        val id: Id,
        val displayName: String,
        val tasks: Set<TaskKind>,
        val sourceAllowed: Boolean,
        val attachmentsAllowed: Boolean,
        val freeAssurance: FreeAssurance,
        val fallbackRule: FallbackRule,
        val budgets: Map<TaskKind, Budget>,
        val definitiveFallbackStatuses: Set<Int> = emptySet(),
    )

    enum class HealthState {
        HEALTHY,
        COOLDOWN,
        ACCESS_REFUSED,
        PAYMENT_REQUIRED,
        REQUEST_TOO_LARGE,
        TEMPORARILY_UNAVAILABLE,
        REQUEST_REJECTED,
        UNCERTAIN_OUTCOME,
    }

    data class Health(
        val state: HealthState,
        val definitiveHttp: Boolean,
        val retryAfterMillis: Long? = null,
    )

    private val all: Map<Id, Capability> = listOf(
        Capability(
            id = Id.OPENROUTER_FREE,
            displayName = "OpenRouter Free",
            tasks = setOf(TaskKind.CHAT_TEXT, TaskKind.CHAT_ATTACHMENT,
                TaskKind.CODE_EDIT, TaskKind.WEBSITE_BUILD),
            sourceAllowed = true,
            attachmentsAllowed = true,
            freeAssurance = FreeAssurance.API_ZERO_PRICE_CEILING,
            fallbackRule = FallbackRule.USER_CONSENTED_DEFINITE_HTTP_ONLY,
            budgets = mapOf(
                TaskKind.CHAT_TEXT to Budget(WorkspaceLongInputPolicy.MAX_REQUEST_CHARS,
                    WorkspaceFreeAiSuggestion.MAX_OUTPUT_TOKENS),
                TaskKind.CHAT_ATTACHMENT to Budget(WorkspaceLongInputPolicy.MAX_REQUEST_CHARS,
                    WorkspaceFreeAiSuggestion.MAX_OUTPUT_TOKENS),
                TaskKind.CODE_EDIT to Budget(12_000, WorkspaceFreeAiSuggestion.MAX_OUTPUT_TOKENS),
                TaskKind.WEBSITE_BUILD to Budget(null, null),
            ),
            definitiveFallbackStatuses = setOf(404, 429, 502, 503, 504),
        ),
        Capability(
            id = Id.GROQ_FREE,
            displayName = "Groq Free",
            tasks = setOf(TaskKind.CHAT_TEXT, TaskKind.CODE_EDIT, TaskKind.WEBSITE_BUILD),
            sourceAllowed = true,
            attachmentsAllowed = false,
            freeAssurance = FreeAssurance.USER_ATTESTED_FREE_ACCOUNT,
            fallbackRule = FallbackRule.USER_CONSENTED_DEFINITE_HTTP_ONLY,
            budgets = mapOf(
                TaskKind.CHAT_TEXT to Budget(WorkspaceGroqFree.MAX_PROMPT_CHARS, 2_048),
                TaskKind.CODE_EDIT to Budget(WorkspaceGroqFree.MAX_PROMPT_CHARS, 2_048),
                TaskKind.WEBSITE_BUILD to Budget(WorkspaceLongInputPolicy.MAX_REQUEST_CHARS, 4_500),
            ),
            definitiveFallbackStatuses = setOf(429, 502, 503, 504),
        ),
        Capability(
            id = Id.LLM7_FREE,
            displayName = "LLM7 Free",
            tasks = setOf(TaskKind.CHAT_TEXT),
            sourceAllowed = false,
            attachmentsAllowed = false,
            freeAssurance = FreeAssurance.NO_PAID_FALLBACK_ONLY,
            fallbackRule = FallbackRule.NONE,
            budgets = mapOf(
                TaskKind.CHAT_TEXT to Budget(WorkspaceLlm7Free.MAX_PROMPT_CHARS, 2_048),
            ),
        ),
        Capability(
            id = Id.XKIRO_FREE,
            displayName = "xKiro Free",
            tasks = setOf(TaskKind.CODE_EDIT, TaskKind.WEBSITE_BUILD),
            sourceAllowed = true,
            attachmentsAllowed = false,
            freeAssurance = FreeAssurance.PREFLIGHT_ZERO_PRICE_AND_QUOTA,
            fallbackRule = FallbackRule.USER_CONSENTED_DEFINITE_HTTP_ONLY,
            budgets = mapOf(
                TaskKind.CODE_EDIT to Budget(null, 3_500),
                TaskKind.WEBSITE_BUILD to Budget(null, 7_000),
            ),
            definitiveFallbackStatuses = setOf(429, 502, 503, 504),
        ),
        Capability(
            id = Id.ZAI_FREE,
            displayName = "Z.ai GLM-4.7-Flash",
            tasks = setOf(TaskKind.CODE_EDIT, TaskKind.WEBSITE_BUILD),
            sourceAllowed = true,
            attachmentsAllowed = false,
            freeAssurance = FreeAssurance.NO_PAID_FALLBACK_ONLY,
            fallbackRule = FallbackRule.NONE,
            budgets = mapOf(
                TaskKind.CODE_EDIT to Budget(12_000, WorkspaceFreeAiSuggestion.MAX_OUTPUT_TOKENS),
                TaskKind.WEBSITE_BUILD to Budget(null, null),
            ),
        ),
    ).associateBy { it.id }

    fun capability(id: Id): Capability = requireNotNull(all[id])

    fun supports(id: Id, task: TaskKind): Boolean = task in capability(id).tasks

    fun allowsAttachments(id: Id): Boolean = capability(id).attachmentsAllowed

    fun allowsSource(id: Id): Boolean = capability(id).sourceAllowed

    fun budget(id: Id, task: TaskKind): Budget? = capability(id).budgets[task]

    fun definitiveFallbackAllowed(id: Id, httpCode: Int): Boolean {
        val capability = capability(id)
        return capability.fallbackRule == FallbackRule.USER_CONSENTED_DEFINITE_HTTP_ONLY &&
            httpCode in capability.definitiveFallbackStatuses
    }

    /** Network/timeout failures never enter this method; they remain UNCERTAIN_OUTCOME upstream. */
    fun classifyHttp(id: Id, httpCode: Int, retryAfterSeconds: Long? = null): Health {
        if (httpCode in 200..299) return Health(HealthState.HEALTHY, definitiveHttp = true)
        val retryAfter = retryAfterSeconds?.takeIf { it in 1..86_400 }?.times(1_000L)
        return when (httpCode) {
            401, 403 -> Health(HealthState.ACCESS_REFUSED, definitiveHttp = true)
            402 -> Health(HealthState.PAYMENT_REQUIRED, definitiveHttp = true)
            408 -> Health(HealthState.UNCERTAIN_OUTCOME, definitiveHttp = false)
            413 -> Health(HealthState.REQUEST_TOO_LARGE, definitiveHttp = true)
            429 -> Health(HealthState.COOLDOWN, definitiveHttp = true, retryAfterMillis = retryAfter)
            502, 503, 504 -> Health(HealthState.TEMPORARILY_UNAVAILABLE, definitiveHttp = true)
            else -> Health(HealthState.REQUEST_REJECTED, definitiveHttp = true)
        }
    }

    fun uncertainNetworkFailure(): Health =
        Health(HealthState.UNCERTAIN_OUTCOME, definitiveHttp = false)

    fun id(provider: WorkspaceChatGateway.Provider): Id = when (provider) {
        WorkspaceChatGateway.Provider.OPENROUTER_FREE -> Id.OPENROUTER_FREE
        WorkspaceChatGateway.Provider.GROQ_FREE -> Id.GROQ_FREE
        WorkspaceChatGateway.Provider.LLM7_FREE -> Id.LLM7_FREE
    }

    fun id(provider: WorkspaceWebsiteRoute.Provider): Id = when (provider) {
        WorkspaceWebsiteRoute.Provider.OPENROUTER -> Id.OPENROUTER_FREE
        WorkspaceWebsiteRoute.Provider.GROQ -> Id.GROQ_FREE
        WorkspaceWebsiteRoute.Provider.XKIRO -> Id.XKIRO_FREE
        WorkspaceWebsiteRoute.Provider.ZAI -> Id.ZAI_FREE
    }

    fun idForEndpoint(url: String): Id? = when (url) {
        WorkspaceFreeAiSuggestion.ENDPOINT -> Id.OPENROUTER_FREE
        WorkspaceGroqFree.ENDPOINT -> Id.GROQ_FREE
        WorkspaceLlm7Free.ENDPOINT -> Id.LLM7_FREE
        WorkspaceXKiroFree.ENDPOINT -> Id.XKIRO_FREE
        WorkspaceZaiFree.ENDPOINT -> Id.ZAI_FREE
        else -> null
    }
}
