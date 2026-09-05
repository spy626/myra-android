package com.myra.assistant.data.memory

object PersonalMemoryPermissionPrompt {
    fun format(candidate: MemoryCandidate): String {
        if (MemoryRelationshipPolicy.isBestFriend(candidate)) {
            val name = MemoryRelationshipPolicy.personName(candidate.fact)?.replaceFirstChar { first ->
                if (first.isLowerCase()) first.titlecase() else first.toString()
            }
            return if (name == null) {
                "Ye strong relationship label hai—sahi? Haan bologe toh yaad rakhungi."
            } else {
                "${name} tumhari best friend hai—sahi? Haan bologe toh yaad rakhungi."
            }
        }
        val understood = when {
            candidate.stableKey == "identity:age" -> {
                val age = Regex("\\b(\\d{1,3})\\b").find(candidate.fact)?.groupValues?.get(1)
                age?.let { "tum ${it} saal ke ho" }
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
        val naturalFact = understood?.replaceFirstChar { first ->
            if (first.isLowerCase()) first.titlecase() else first.toString()
        }
        return if (naturalFact == null) {
            "Ye personal memory hai. Kya main yeh yaad rakhun?"
        } else {
            "${naturalFact}. Kya main yeh yaad rakhun?"
        }
    }
}
