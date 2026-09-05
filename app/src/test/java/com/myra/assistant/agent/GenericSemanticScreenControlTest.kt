package com.myra.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericSemanticScreenControlTest {
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
        assertEquals(GeneralVerificationStatus.FAILURE, verification.status)
        assertTrue(recovery is RecoveryDecision.Retry)
        assertEquals(1, runtime.activeTask()?.recoveryCount)
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
