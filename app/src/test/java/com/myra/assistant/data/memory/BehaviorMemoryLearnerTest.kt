package com.myra.assistant.data.memory

import com.myra.assistant.agent.CurrentActivityContext
import com.myra.assistant.agent.SemanticElement
import com.myra.assistant.agent.SemanticRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BehaviorMemoryLearnerTest {
    @Test fun oneObservationNeverBecomesDurablePattern() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val learner = BehaviorMemoryLearner(repository)
        assertNull(learner.observe(BehaviorSignal(BehaviorObservationKind.APP_USAGE, "YouTube", "s1", 0)))
        assertTrue(repository.allActive().isEmpty())
    }

    @Test fun repeatedAppUsagePromotesOnlyAfterCrossSessionCrossDayEvidence() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao()); val learner = BehaviorMemoryLearner(repository)
        repeat(6) { index -> learner.observe(BehaviorSignal(BehaviorObservationKind.APP_USAGE,
            "YouTube", "s$index", index * BehaviorMemoryLearner.DAY_MS)) }
        val frequent = repository.allActive().single { it.stableKey == "behavior:app_usage:youtube" }
        assertEquals(MemoryCategory.APP_USAGE.name, frequent.category)
        assertEquals(MemoryProvenance.BEHAVIOR_PATTERN.name, frequent.provenance)
        assertEquals("Uses YouTube frequently", frequent.fact)
    }

    @Test fun repeatedCrossSessionCrossDayEvidencePromotesConservativePattern() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao()); val learner = BehaviorMemoryLearner(repository)
        repeat(6) { index -> learner.observe(BehaviorSignal(BehaviorObservationKind.YOUTUBE_CHANNEL,
            "Jonathan Gaming", "s${index % 3}", index * BehaviorMemoryLearner.DAY_MS)) }
        val row = repository.allActive().single()
        assertEquals(MemoryCategory.CONTENT_INTEREST.name, row.category)
        assertEquals("Frequently watches Jonathan Gaming", row.fact)
        assertFalse(row.fact.contains("favorite", true))
    }

    @Test fun repeatedTopicsBecomeCurrentInterestAndFollowLifecycleDecay() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao()); val learner = BehaviorMemoryLearner(repository)
        repeat(6) { index -> learner.observe(BehaviorSignal(BehaviorObservationKind.CONTENT_TOPIC,
            "AI", "s$index", index * BehaviorMemoryLearner.DAY_MS)) }
        assertEquals(MemoryCategory.CURRENT_INTEREST.name, repository.allActive().single().category)

        learner.decay(40 * BehaviorMemoryLearner.DAY_MS)
        assertEquals(MemoryLifecycleStatus.WEAKENING.name, repository.allActive().single().lifecycleStatus)

        learner.decay(55 * BehaviorMemoryLearner.DAY_MS)
        assertEquals(MemoryLifecycleStatus.HISTORICAL.name, repository.allActive().single().lifecycleStatus)

        learner.decay(66 * BehaviorMemoryLearner.DAY_MS)
        assertTrue(repository.allActive().isEmpty())
    }

    @Test fun staleObservationEvidenceResetsBeforePatternCanReturn() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val learner = BehaviorMemoryLearner(repository)
        repeat(6) { index ->
            learner.observe(BehaviorSignal(
                BehaviorObservationKind.CONTENT_TOPIC,
                "AI",
                "old-$index",
                index * BehaviorMemoryLearner.DAY_MS
            ))
        }
        assertTrue(repository.allActive().any { it.stableKey == "behavior:content_topic:ai" })

        learner.observe(BehaviorSignal(
            BehaviorObservationKind.CONTENT_TOPIC,
            "AI",
            "fresh-session",
            66 * BehaviorMemoryLearner.DAY_MS
        ))

        assertFalse(repository.allActive().any { it.stableKey == "behavior:content_topic:ai" })
        val observation = repository.behavior("behavior:content_topic:ai")!!
        assertEquals(1, observation.observationCount)
        assertEquals(1, observation.sessionCount)
        assertEquals(1, observation.dayCount)
    }

    @Test fun decayCoversMoreThanOneHundredBehaviorKeys() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val learner = BehaviorMemoryLearner(repository)
        repeat(130) { index ->
            val key = "behavior:content_topic:topic_$index"
            repository.recordBehavior(BehaviorObservationEntity(
                id = "b-$index",
                stableKey = key,
                kind = BehaviorObservationKind.CONTENT_TOPIC.name,
                label = "Topic $index",
                observationCount = 6,
                sessionCount = 3,
                dayCount = 2,
                firstObservedAt = 0L,
                lastObservedAt = 0L,
                lastSessionId = "s-$index",
                lastDayBucket = 0L
            ))
            repository.saveGrounded(MemoryCandidate(
                MemoryCategory.CURRENT_INTEREST,
                "Recently follows Topic $index-related content",
                key,
                MemorySensitivity.LOW,
                .86,
                provenance = MemoryProvenance.BEHAVIOR_PATTERN
            ))
        }
        assertEquals(130, repository.allActive().size)
        learner.decay(61 * BehaviorMemoryLearner.DAY_MS)
        assertTrue(repository.allActive().isEmpty())
    }

    @Test fun contentTopicExtractorDiscoversNonHardcodedTopics() {
        val topics = ContentTopicExtractor.extract(listOf(
            "Kubernetes deployment tutorial for beginners",
            "Kubernetes operators explained",
            "Building reliable Kubernetes clusters"
        ))
        assertTrue(topics.any { it.equals("Kubernetes", ignoreCase = true) })
    }

    @Test fun mostUsedAppRequiresClearComparativeLead() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val learner = BehaviorMemoryLearner(repository)

        repeat(10) { index ->
            learner.observe(BehaviorSignal(
                BehaviorObservationKind.APP_USAGE,
                "YouTube",
                "yt-$index",
                index * BehaviorMemoryLearner.DAY_MS
            ))
        }

        repeat(5) { index ->
            learner.observe(BehaviorSignal(
                BehaviorObservationKind.APP_USAGE,
                "Chrome",
                "ch-$index",
                (index + 10L) * BehaviorMemoryLearner.DAY_MS
            ))
        }

        val mostUsed = repository.allActive().single { it.stableKey == BehaviorMemoryLearner.MOST_USED_APP_KEY }
        assertEquals("Usually uses YouTube the most", mostUsed.fact)
        assertEquals(MemoryCategory.APP_USAGE.name, mostUsed.category)
        assertEquals(MemoryProvenance.BEHAVIOR_PATTERN.name, mostUsed.provenance)
    }

    @Test fun staleMostUsedSummaryIsRetiredWhenAnotherAppCatchesUp() = runBlocking {
        val repository = MemoryRepository(FakeMemoryDao())
        val learner = BehaviorMemoryLearner(repository)

        repeat(10) { index ->
            learner.observe(BehaviorSignal(
                BehaviorObservationKind.APP_USAGE,
                "YouTube",
                "yt-$index",
                index * BehaviorMemoryLearner.DAY_MS
            ))
        }
        assertTrue(repository.allActive().any {
            it.stableKey == BehaviorMemoryLearner.MOST_USED_APP_KEY && it.fact.contains("YouTube")
        })

        repeat(10) { index ->
            learner.observe(BehaviorSignal(
                BehaviorObservationKind.APP_USAGE,
                "Chrome",
                "ch-$index",
                (index + 10L) * BehaviorMemoryLearner.DAY_MS
            ))
        }
        assertFalse(repository.allActive().any { it.stableKey == BehaviorMemoryLearner.MOST_USED_APP_KEY })
    }

    @Test fun passivePrivacyBlocksSensitiveAppsAndPrivateBrowsing() {
        assertTrue(PassiveMemoryPrivacyPolicy.blocksApp("com.example.mobilebanking", "My Bank"))
        assertTrue(PassiveMemoryPrivacyPolicy.blocksApp("com.example.authenticator", "Authenticator"))
        assertFalse(PassiveMemoryPrivacyPolicy.blocksApp("com.google.android.youtube", "YouTube"))
        assertTrue(PassiveMemoryPrivacyPolicy.privateContext(listOf("New incognito tab")))
        assertTrue(PassiveMemoryPrivacyPolicy.privateContext(listOf("Private browsing")))
    }

    @Test fun youtubeContentLearningRequiresActiveVideoEvidence() {
        val home = activity(
            screenType = "LIST",
            elements = listOf(
                element(SemanticRole.VIDEO_CARD, "AI agents explained"),
                element(SemanticRole.CHANNEL_NAME, "Jonathan Gaming")
            )
        )
        assertFalse(PassiveMemoryObserver.isActiveYouTubeVideoContext(home))

        val player = activity(
            screenType = "VIDEO",
            elements = listOf(
                element(SemanticRole.VIDEO, "AI agents explained"),
                element(SemanticRole.CHANNEL_NAME, "Jonathan Gaming"),
                element(SemanticRole.BUTTON, "Pause")
            )
        )
        assertTrue(PassiveMemoryObserver.isActiveYouTubeVideoContext(player))
    }

    @Test fun watchedTopicUsesCurrentVideoAndExcludesRecommendations() {
        val player = activity(
            screenType = "VIDEO",
            elements = listOf(
                element(SemanticRole.VIDEO, "Gemini AI Agents Tutorial"),
                element(SemanticRole.VIDEO_CARD, "Best Chicken Curry"),
                element(SemanticRole.LIST_ITEM, "Football Highlights"),
                element(SemanticRole.CHANNEL_NAME, "Jonathan Gaming"),
                element(SemanticRole.BUTTON, "Pause")
            )
        )

        val titles = PassiveMemoryObserver.currentVideoTopicLabels(player)
        val topics = ContentTopicExtractor.extract(titles)

        assertEquals(listOf("Gemini AI Agents Tutorial"), titles)
        assertTrue(topics.any { it.equals("AI", true) || it.contains("Gemini", true) })
        assertFalse(topics.any { it.contains("Chicken", true) || it.contains("Football", true) })
    }

    @Test fun recommendationCardsWithoutCurrentVideoProduceNoTopicEvidence() {
        val feed = activity(
            screenType = "LIST",
            elements = listOf(
                element(SemanticRole.VIDEO_CARD, "AI Agents"),
                element(SemanticRole.LIST_ITEM, "Android News"),
                element(SemanticRole.CHANNEL_NAME, "Suggested Channel")
            )
        )
        assertTrue(PassiveMemoryObserver.currentVideoTopicLabels(feed).isEmpty())
    }

    @Test fun channelAndTitleEvidenceRequireVerifiedActivePlayer() {
        val player = activity(
            screenType = "VIDEO",
            elements = listOf(
                element(SemanticRole.VIDEO, "Android AI Tutorial"),
                element(SemanticRole.CHANNEL_NAME, "Creator Channel"),
                element(SemanticRole.BUTTON, "Pause")
            )
        )
        assertTrue(PassiveMemoryObserver.isActiveYouTubeVideoContext(player))
        assertEquals(listOf("Android AI Tutorial"), PassiveMemoryObserver.currentVideoTopicLabels(player))
    }

    private fun activity(screenType: String, elements: List<SemanticElement>) = CurrentActivityContext(
        packageName = "com.google.android.youtube",
        appLabel = "YouTube",
        screenType = screenType,
        windowId = 1,
        generation = 1L,
        visibleElements = elements,
        confidence = .9,
        timestamp = 1L
    )

    private fun element(role: SemanticRole, label: String) = SemanticElement(
        id = label,
        role = role,
        label = label,
        left = 0,
        top = 0,
        right = 100,
        bottom = 100,
        actionable = true
    )
}
