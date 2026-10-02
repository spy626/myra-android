package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspacePlanningBriefTest {
    @Test fun currentTurnSeparatesDeviceFromDeliveryPlatform() {
        val shape = WorkspacePlanningBrief.parse(
            "bro mere paas sirf Android phone hai aur mujhe free mein ek simple grocery app " +
                "banana hai. Sabse pehle kya karna chahiye? 3 practical steps batao, " +
                "abhi coding start mat karna 😂"
        )
        assertEquals(3, shape.stepCount)
        assertTrue(shape.phoneOnly)
        assertTrue(shape.freeOnly)
        assertEquals(WorkspacePlanningBrief.Platform.UNSPECIFIED, shape.platform)
        assertTrue(shape.digitalProject)
        assertTrue(shape.adviceOnly)
        assertTrue(WorkspacePlanningBrief.parse(
            "sirf advice do, abhi coding mat start karna"
        ).adviceOnly)
        assertTrue(WorkspacePlanningBrief.parse(
            "don't code yet; give three doable steps"
        ).adviceOnly)
        val projected = WorkspacePlanningBrief.instructions(
            "I only have an Android phone. What 3 steps should I plan for a free booking app? Don't code yet."
        )
        assertTrue(projected.contains("UNSPECIFIED"))
        assertTrue(projected.contains("step count: 3"))
        assertTrue(projected.contains("Phone-only resource explicitly stated: true"))
    }

    @Test fun userNamedWebOrNativePlatformIsNotOverridden() {
        assertEquals(WorkspacePlanningBrief.Platform.WEB,
            WorkspacePlanningBrief.parse(
                "I only have a phone. Please plan a web app in three steps."
            ).platform)
        assertEquals(WorkspacePlanningBrief.Platform.NATIVE,
            WorkspacePlanningBrief.parse(
                "Please plan a native Android app. Give 3 practical steps."
            ).platform)
    }

    @Test fun broadPlansAndCasualConversationDoNotInventRestrictions() {
        val study = WorkspacePlanningBrief.parse(
            "How should I start learning guitar? Give three simple steps."
        )
        assertEquals(3, study.stepCount)
        assertFalse(study.phoneOnly)
        assertFalse(study.freeOnly)
        assertFalse(study.digitalProject)
        assertEquals(WorkspacePlanningBrief.Platform.UNSPECIFIED, study.platform)
        assertNull(WorkspacePlanningBrief.parse("hello bro").stepCount)
    }
}
