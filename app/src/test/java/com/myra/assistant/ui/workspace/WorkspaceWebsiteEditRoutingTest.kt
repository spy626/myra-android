package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteEditRoutingTest {
    private val complete = setOf("index.html", "style.css", "script.js")

    @Test fun presentationOnlyRequestsTargetStylesheet() {
        listOf(
            "Website ka background color dark navy blue karo. Baaki same rakho.",
            "Increase card spacing and border radius",
            "Font aur padding thoda compact karo",
            "Change the button colour only"
        ).forEach {
            assertEquals("style.css", WorkspaceWebsiteEditRouting.decide(it, complete).path)
        }
    }

    @Test fun contentOnlyRequestsTargetHtml() {
        listOf(
            "Heading ko Welcome to Minicoy karo",
            "Contact section ka content update karo",
            "Change the form placeholder",
            "Update the link text"
        ).forEach {
            assertEquals("index.html", WorkspaceWebsiteEditRouting.decide(it, complete).path)
        }
    }

    @Test fun behaviourOnlyRequestsTargetJavascript() {
        listOf(
            "Change the menu click behavior",
            "Add form validation",
            "Fix the modal open interaction",
            "Update the scroll behavior"
        ).forEach {
            assertEquals("script.js", WorkspaceWebsiteEditRouting.decide(it, complete).path)
        }
    }

    @Test fun explicitSingleCanonicalFileWinsWithoutGuessing() {
        assertEquals("style.css",
            WorkspaceWebsiteEditRouting.decide("In style.css make the cards slightly darker", complete).path)
        assertEquals("script.js",
            WorkspaceWebsiteEditRouting.decide("Edit script.js to change the existing handler", complete).path)
    }

    @Test fun buildsAmbiguousAndMultiFileChangesStayOnFullWebsiteRoute() {
        listOf(
            "Build a website with a dark background",
            "Ek dark website banao with a contact section",
            "Redesign the whole website",
            "Change the background and update the heading content",
            "Change style.css and script.js",
            "Make it better"
        ).forEach {
            assertNull(WorkspaceWebsiteEditRouting.decide(it, complete).path)
        }
    }

    @Test fun incompleteWebsiteNeverUsesSingleFileEditRoute() {
        assertNull(WorkspaceWebsiteEditRouting.decide(
            "Change the background color", setOf("index.html", "style.css")).path)
    }
}
