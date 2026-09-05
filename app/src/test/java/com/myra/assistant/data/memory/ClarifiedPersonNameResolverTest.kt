package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class ClarifiedPersonNameResolverTest {
    @Test fun bareClarificationResolves() {
        assertEquals(ClarifiedNameResult.Accepted("Kareem"), ClarifiedPersonNameResolver.resolve("Kareem."))
    }

    @Test fun letterByLetterClarificationResolves() {
        assertEquals(ClarifiedNameResult.Accepted("Kareem"), ClarifiedPersonNameResolver.resolve("K-A-R-E-E-M"))
        assertEquals(ClarifiedNameResult.Accepted("Kareem"), ClarifiedPersonNameResolver.resolve("K A R E E M"))
    }

    @Test fun incompleteSpellingRequiresExplicitConfirmationAndNeverBecomesAreen() {
        assertEquals(
            ClarifiedNameResult.NeedsConfirmation("A-R-E-E-N", "Kareem"),
            ClarifiedPersonNameResolver.resolve("A-R-E-E-N")
        )
        assertEquals(ClarifiedNameResult.Unclear, ClarifiedPersonNameResolver.resolve("haan"))
    }
}
