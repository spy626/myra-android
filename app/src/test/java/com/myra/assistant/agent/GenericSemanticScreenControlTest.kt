package com.myra.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericSemanticScreenControlTest {
    @Test fun privacyOpenRoutesToSemanticTap() {
        val decision = UnifiedTurnInterpreter.interpret("Privacy kholo", WorkingTaskContext())
        val structured = UnifiedLyraAgent().toStructuredIntent("Privacy kholo", decision)
        assertEquals(TurnIntent.ACTION_REQUEST, decision.intent)
        assertEquals("privacy", structured.textHint)
        assertTrue(ToolCapability.ACCESSIBILITY_CLICK in structured.requiredCapabilities)
    }

    @Test fun uniqueVisibleSettingsButtonResolves() {
        val target = element("settings", SemanticRole.SETTINGS, "Settings", 800, 40)
        val result = resolver.resolve(SemanticTargetRequest("open settings", roleHint = SemanticRole.SETTINGS), scene(listOf(target)))
        assertEquals("settings", (result as SemanticTargetResolution.Unique).element.id)
    }

    @Test fun topRightGearDescriptionBeatsOtherControl() {
        val gear = element("gear", SemanticRole.SETTINGS, "Gear settings", 900, 20)
        val other = element("menu", SemanticRole.MENU, "Menu", 50, 20)
        val result = resolver.resolve(SemanticTargetRequest("upper right settings icon", SemanticRole.SETTINGS,
            spatialHint = SpatialHint.TOP_RIGHT), scene(listOf(other, gear)))
        assertEquals("gear", (result as SemanticTargetResolution.Unique).element.id)
    }

    @Test fun twoEquivalentSettingsTargetsRequireClarification() {
        val result = resolver.resolve(SemanticTargetRequest("settings", SemanticRole.SETTINGS), scene(listOf(
            element("a", SemanticRole.SETTINGS, "Settings", 100, 10),
            element("b", SemanticRole.SETTINGS, "Settings", 800, 10)
        )))
        assertTrue(result is SemanticTargetResolution.Ambiguous)
    }

    @Test fun secondResultUsesGenericOrdinalOrdering() {
        val request = SemanticTargetRequestParser.parse("Second result kholo")
        val result = resolver.resolve(request, scene(listOf(
            element("one", SemanticRole.LINK, "First article", 0, 100),
            element("two", SemanticRole.LINK, "Second article", 0, 300)
        )))
        assertEquals("two", (result as SemanticTargetResolution.Unique).element.id)
    }

    @Test fun secondResultExcludesHomeTabsAndNavigationLinks() {
        val request = SemanticTargetRequestParser.parse("Second result kholo")
        val result = resolver.resolve(request, scene(listOf(
            element("home", SemanticRole.LINK, "Home", 0, 20),
            element("tab", SemanticRole.TAB, "News", 200, 20),
            element("one", SemanticRole.LINK, "First AI result", 0, 180),
            element("two", SemanticRole.LINK, "Second AI result", 0, 380)
        )))
        assertEquals("two", (result as SemanticTargetResolution.Unique).element.id)
    }

    @Test fun secondButtonUsesOnlyButtonFamily() {
        val request = SemanticTargetRequestParser.parse("Second button kholo")
        val result = resolver.resolve(request, scene(listOf(
            element("link", SemanticRole.LINK, "First article", 0, 30),
            element("button-one", SemanticRole.BUTTON, "Open", 0, 150),
            element("button-two", SemanticRole.BUTTON, "Continue", 0, 350)
        )))
        assertEquals("button-two", (result as SemanticTargetResolution.Unique).element.id)
    }

    @Test fun deicticTargetWithoutReferenceIsAmbiguous() {
        val request = SemanticTargetRequestParser.parse("Ye wala")
        val result = resolver.resolve(request, scene(listOf(element("a"), element("b", left = 400))))
        assertTrue(result is SemanticTargetResolution.Ambiguous)
    }

    @Test fun deicticTargetUsesUniqueActiveReference() {
        val working = WorkingTaskContext(currentReference = "b")
        val result = resolver.resolve(SemanticTargetRequestParser.parse("Ye wala", working),
            scene(listOf(element("a"), element("b", left = 400))))
        assertEquals("b", (result as SemanticTargetResolution.Unique).element.id)
    }

    @Test fun rejectedFingerprintCannotBeSelectedAgain() {
        val first = element("old", label = "Settings")
        val refreshed = element("new", label = "Settings")
        val rejected = setOf(SemanticTargetFingerprint.of(first))
        assertTrue(resolver.resolve(SemanticTargetRequest("Settings", textHint = "Settings"), scene(listOf(refreshed)), rejected) is SemanticTargetResolution.NotFound)
    }

    @Test fun clickPriorityPrefersNodeThenAncestorThenFreshGesture() {
        assertEquals(SemanticClickMethod.ACCESSIBILITY_CLICK, SemanticClickPolicy.choose(true, true, true))
        assertEquals(SemanticClickMethod.ACCESSIBILITY_ANCESTOR_CLICK, SemanticClickPolicy.choose(false, true, true))
        assertEquals(SemanticClickMethod.GESTURE_LAST_RESORT, SemanticClickPolicy.choose(false, false, true))
        assertEquals(SemanticClickMethod.REJECT_STALE, SemanticClickPolicy.choose(false, false, false))
    }

    @Test fun staleSceneBindingRejectsPhysicalAction() {
        val current = scene(listOf(element("x"))).copy(generation = 5)
        val target = ResolvedActionTarget("x", "unknown.app", 1, 4, listOf(0, 0, 100, 100))
        assertFalse(GeneralActionSafety.validate(target, PerceptionSnapshot(current, "task", 5)))
    }

    @Test fun acceptedTapWithoutExpectedChangeIsFailureAndRejectsOneTarget() {
        val runtime = GeneralAgentRuntime(now = { 2 })
        val intent = StructuredAgentIntent(TurnIntent.ACTION_REQUEST, "Settings kholo", "tap", true,
            targetDescription = "Settings", requiredCapabilities = setOf(ToolCapability.ACCESSIBILITY_CLICK), confidence = .9)
        val task = runtime.start(1, intent)!!
        val before = PerceptionSnapshot(scene(listOf(element("settings", label = "Settings"))), task.id, 1)
        val step = (runtime.next(before) as PlannerResult.Next).step
        runtime.recordAction(step, GeneralActionResult(true, "settings", metadata = mapOf("fingerprint" to "SETTINGS|settings|CENTER|TOP|")), before)
        val (verification, recovery) = runtime.verify(before.copy(capturedAt = 2))
        assertEquals(GeneralVerificationStatus.UNKNOWN, verification.status)
        assertTrue(recovery is RecoveryDecision.Clarify)
        assertEquals(0, runtime.activeTask()?.recoveryCount)
    }

    @Test fun rejectedFirstTargetAllowsOneFreshAlternative() {
        val first = element("first", SemanticRole.SETTINGS, "Settings", 100, 20)
        val alternate = element("alternate", SemanticRole.SETTINGS, "Settings", 800, 20)
        val request = SemanticTargetRequest("settings", SemanticRole.SETTINGS)
        val initial = resolver.resolve(request.copy(spatialHint = SpatialHint.LEFT), scene(listOf(first, alternate))) as SemanticTargetResolution.Unique
        val rejected = setOf(SemanticTargetFingerprint.of(initial.element))
        val recovered = resolver.resolve(request, scene(listOf(first, alternate)), rejected) as SemanticTargetResolution.Unique
        assertEquals("alternate", recovered.element.id)
    }

    @Test fun majorNewScreenVerifiesTap() {
        val verifier = GeneralVerifier()
        val before = PerceptionSnapshot(scene((1..5).map { element("old$it", label = "Old $it", top = it * 100) }), "t", 1)
        val after = PerceptionSnapshot(scene((1..5).map { element("new$it", label = "New $it", top = it * 100) }).copy(generation = 2), "t", 2)
        val step = GeneralPlanStep(taskId = "t", category = ActionCategory.PHONE_CONTROL,
            capability = ToolCapability.ACCESSIBILITY_CLICK,
            expectedOutcome = ExpectedOutcome(ExpectedOutcomeType.TARGET_STATE_CHANGED, "new screen"))
        assertEquals(GeneralVerificationStatus.SUCCESS, verifier.verify(step, before, after).status)
    }

    @Test fun permissionModalCannotAutoProceed() {
        val protected = scene(listOf(element("allow", label = "Allow"))).copy(modal = ModalKind.PERMISSION)
        assertTrue(ModalSafetyPolicy.requiresAuthorization(protected))
    }

    @Test fun harmlessAppDialogCanBeResolvedButPermissionCannot() {
        val runtime = GeneralAgentRuntime(now = { 1 })
        val intent = StructuredAgentIntent(TurnIntent.ACTION_REQUEST, "Close dialog", "tap", true,
            requiredCapabilities = setOf(ToolCapability.ACCESSIBILITY_CLICK), confidence = .9)
        val task = runtime.start(1, intent)!!
        val harmless = PerceptionSnapshot(scene(listOf(element("close", SemanticRole.CLOSE, "Close"))).copy(modal = ModalKind.APP_DIALOG), task.id, 1)
        assertTrue(runtime.next(harmless) is PlannerResult.Next)
    }

    @Test fun systemOverlayPreservesUnderlyingExternalApp() {
        val context = CurrentActivityContext("com.android.systemui", screenType = "DIALOG", windowId = 9,
            generation = 2, visibleElements = listOf(element("cancel", SemanticRole.CLOSE, "Cancel")),
            confidence = .9, timestamp = 2)
        assertEquals("com.android.chrome", ScreenSceneFactory.from(context, "com.android.chrome").externalForegroundPackage)
    }

    @Test fun genericBackImperativeRoutesToBackCapability() {
        val agent = UnifiedLyraAgent()
        val decision = UnifiedTurnInterpreter.interpret("Peeche jao", WorkingTaskContext())
        assertTrue(decision.authorizesPhoneActions)
        assertTrue(ToolCapability.BACK in agent.toStructuredIntent("Peeche jao", decision).requiredCapabilities)
    }

    @Test fun settingsDiscussionRemainsConversationAndRunsNoTool() {
        val decision = UnifiedTurnInterpreter.interpret("Settings button ka system aur smart bana sakte hain", null)
        assertEquals(TurnIntent.CONVERSATION, decision.intent)
        assertFalse(decision.authorizesPhoneActions)
    }

    @Test fun privacyTranscriptArtifactUsesUniqueFreshVisibleTarget() {
        val privacy = element("privacy", SemanticRole.LINK, "Privacy", 0, 180)
        val context = CurrentActivityContext("com.android.settings", windowId = 1, generation = 1,
            visibleElements = listOf(privacy), confidence = .9, timestamp = 1)
        val decision = UnifiedTurnInterpreter.interpret("privacy {colon}", WorkingTaskContext(), context)
        assertEquals(TurnIntent.ACTION_REQUEST, decision.intent)
        val structured = UnifiedLyraAgent().toStructuredIntent("privacy {colon}", decision)
        assertEquals("privacy", structured.textHint)
        assertTrue(ToolCapability.ACCESSIBILITY_CLICK in structured.requiredCapabilities)
    }

    @Test fun transcriptArtifactWithoutUniqueVisibleTargetCannotAuthorize() {
        val context = CurrentActivityContext("unknown.app", windowId = 1, generation = 1,
            visibleElements = listOf(element("a", label = "Privacy"), element("b", label = "Privacy", left = 400)),
            confidence = .9, timestamp = 1)
        assertEquals(TurnIntent.CONVERSATION,
            UnifiedTurnInterpreter.interpret("privacy {colon}", WorkingTaskContext(), context).intent)
    }

    @Test fun spatialTargetModifierRoutesTapWhileMovementPredicateRoutesScroll() {
        val target = UnifiedTurnInterpreter.interpret("niche wala Privacy kholo", WorkingTaskContext())
        val movement = UnifiedTurnInterpreter.interpret("niche jao", WorkingTaskContext())
        assertEquals(SemanticPredicate.OPEN_TARGET, SemanticCapabilityParser.parse("niche wala Privacy kholo").predicate)
        assertTrue(ToolCapability.ACCESSIBILITY_CLICK in UnifiedLyraAgent().toStructuredIntent("niche wala Privacy kholo", target).requiredCapabilities)
        assertEquals(SemanticPredicate.MOVE_VIEWPORT, SemanticCapabilityParser.parse("niche jao").predicate)
        assertTrue(ToolCapability.ACCESSIBILITY_SCROLL in UnifiedLyraAgent().toStructuredIntent("niche jao", movement).requiredCapabilities)
    }

    @Test fun recoveryPreservesResultFamilyAndOrdinalWhileExcludingRejectedFingerprint() {
        val request = SemanticTargetRequestParser.parse("Second result kholo")
        val firstScene = scene(listOf(
            element("r1", SemanticRole.LINK, "AI result one", 0, 100),
            element("r2", SemanticRole.LINK, "AI result two", 0, 300),
            element("home", SemanticRole.LINK, "Home", 0, 500)
        ))
        val selected = (resolver.resolve(request, firstScene) as SemanticTargetResolution.Unique).element
        val rejected = setOf(SemanticTargetFingerprint.of(selected))
        val refreshed = scene(listOf(
            element("new-r1", SemanticRole.LINK, "AI result one", 0, 100),
            element("new-r2", SemanticRole.LINK, "Different second result", 0, 300),
            element("home-new", SemanticRole.LINK, "Home", 0, 500)
        ))
        val recovered = resolver.resolve(request, refreshed, rejected) as SemanticTargetResolution.Unique
        assertEquals("new-r2", recovered.element.id)
        assertFalse(recovered.element.label.equals("Home", true))
    }

    @Test fun ordinaryModelPhysicalClaimRequiresGroundedRuntimeAction() {
        assertTrue(GroundedActionClaimPolicy.shouldSuppress("Privacy par tap kar diya", GroundedActionResultState.NOT_DISPATCHED))
        assertFalse(GroundedActionClaimPolicy.shouldSuppress("Privacy par tap kar diya", GroundedActionResultState.VERIFIED_SUCCESS))
        assertFalse(GroundedActionClaimPolicy.shouldSuppress("Tap system ko improve karna hai", GroundedActionResultState.NOT_DISPATCHED))
    }

    @Test fun semanticVerificationResamplesWithoutAuthorizingAnotherTap() {
        assertTrue(SemanticVerificationResamplePolicy.shouldResample(true, GeneralVerificationStatus.UNKNOWN, 0))
        assertFalse(SemanticVerificationResamplePolicy.shouldResample(true, GeneralVerificationStatus.UNKNOWN, 1))
        assertFalse(SemanticVerificationResamplePolicy.shouldResample(true, GeneralVerificationStatus.SUCCESS, 0))
    }

    private val resolver = GeneralSemanticTargetResolver()
    private fun scene(elements: List<SemanticElement>) = ScreenScene("unknown.app", "unknown.app", windowId = 1,
        generation = 1, screenType = "UNKNOWN", semanticElements = elements, screenshotReference = null,
        observedAt = 1, confidence = .9)
    private fun element(id: String, role: SemanticRole = SemanticRole.BUTTON, label: String = id,
        left: Int = 0, top: Int = 0) = SemanticElement(id, role, label, left, top, left + 100, top + 80, true,
        packageName = "unknown.app", windowId = 1, screenGeneration = 1,
        horizontalPosition = if (left > 600) RelativeHorizontalPosition.RIGHT else RelativeHorizontalPosition.LEFT,
        verticalPosition = if (top < 300) RelativeVerticalPosition.TOP else RelativeVerticalPosition.MIDDLE)
}
