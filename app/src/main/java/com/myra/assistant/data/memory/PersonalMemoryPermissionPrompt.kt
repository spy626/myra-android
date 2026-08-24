package com.myra.assistant.data.memory

object PersonalMemoryPermissionPrompt {
    fun format(candidate: MemoryCandidate): String {
        val understood = when {
            candidate.stableKey == "identity:age" -> {
                val age = Regex("\\b(\\d{1,3})\\b").find(candidate.fact)?.groupValues?.get(1)
                age?.let { "tum ${it} saal ke ho" }
            }
            candidate.stableKey == "person:best_friend" -> {
                candidate.fact.substringAfter("best friend is ", "").trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { "${it} tumhari best friend hai" }
            }
            candidate.category == MemoryCategory.GOAL ->
                candidate.fact.removePrefix("Zopy's goal is ").takeIf { it.isNotBlank() }
                    ?.let { "tumhara goal ${it} hai" }
            candidate.category == MemoryCategory.PROJECT ->
                candidate.fact.removePrefix("Zopy is working on ").takeIf { it.isNotBlank() }
                    ?.let { "tum ${it} par kaam kar rahe ho" }
            candidate.category == MemoryCategory.HABIT ->
                candidate.fact.removePrefix("Zopy ").takeIf { it.isNotBlank() }
                    ?.let { "tum ${it}" }
            else -> null
        }
        return if (understood == null) {
            "Ye personal memory hai. Kya main ise yaad rakhun?"
        } else {
            "Maine samjha ${understood}. Kya main ise yaad rakhun?"
        }
    }
}
