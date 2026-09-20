#!/usr/bin/env python3
"""One-time exact-anchor patch for a visibly inert generated Explore Minicoy CTA.
The native preview stays read-only; this touches only generated website source.
"""
from pathlib import Path

ROOT = Path('app/src/main/java/com/myra/assistant/ui/workspace')
TEST = Path('app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceWebsiteActionQualityTest.kt')
ACTION = ROOT / 'WorkspaceWebsiteActionQuality.kt'

def replace_once(path, before, after):
    text = path.read_text(encoding='utf-8')
    assert text.count(before) == 1, f'Unexpected source revision: {path}'
    path.write_text(text.replace(before, after, 1), encoding='utf-8')

assert not ACTION.exists() and not TEST.exists(), 'Action-quality files already exist'
ACTION.write_text(r'''package com.myra.assistant.ui.workspace

/** Narrow, deterministic navigation repair for the explicitly requested Explore Minicoy CTA.
 * A visual layout check alone cannot show whether a generated control has an action.
 * Native in-page links work without a JavaScript listener, remote asset or Android bridge.
 * No other button, destination or user-specified alternate action is silently changed.
 */
internal object WorkspaceWebsiteActionQuality {
    data class Review(val files: Map<String, String>, val repaired: Boolean) {
        fun chatNote(): String = if (repaired)
            "\nExplore Minicoy now links to Things to Explore with visible target feedback. " +
                "Tap it in Preview to confirm on your phone."
        else ""
    }

    private val heading = Regex("""(?is)<h([1-6])\b([^>]*)>(.*?)</h\1\s*>""")
    private val control = Regex("""(?is)<(button|a)\b([^>]*)>(.*?)</\1\s*>""")
    private val id = Regex("""(?is)\bid\s*=\s*(["'])([a-zA-Z][\w:.-]{0,63})\1""")
    private val href = Regex("""(?is)\bhref\s*=\s*(["'])(#[a-zA-Z][\w:.-]{0,63})\1""")
    private val classes = Regex("""(?is)\bclass\s*=\s*(["'])(.*?)\1""")
    private val style = Regex("""(?is)\bstyle\s*=\s*(["'])(.*?)\1""")
    private val tags = Regex("""(?s)<[^>]*>""")
    private val spaces = Regex("""\s+""")
    private const val TARGET_CLASS = "lyra-explore-target"
    private const val FEEDBACK = "\n/* LYRA Explore target feedback */\n" +
        ".lyra-explore-target:target { outline: 2px solid #0f766e; " +
        "outline-offset: 6px; scroll-margin-top: 24px; border-radius: 6px; }\n"

    private fun text(html: String): String = spaces.replace(
        tags.replace(html, "").replace("&nbsp;", " ").trim(), " ")

    private fun hasDifferentRequestedAction(goal: String): Boolean =
        // Explicitly requesting an alternate action (e.g. show Welcome on click)
        // is authoritative. Do not swap that action for default section navigation.
        Regex("""(?is)(?:explore\s+minicoy|explore\s+button).{0,100}\b(?:click|tap|press|dabao|dabane)\b.{0,100}\bwelcome\s+to\s+minicoy\b""")
            .containsMatchIn(goal) ||
        Regex("""(?is)\b(?:click|tap|press)\b.{0,100}\b(?:alert|modal|new\s+page|external\s+link)\b""")
            .containsMatchIn(goal)

    fun review(snapshot: WorkspaceWebsiteGeneration.Snapshot, files: Map<String, String>): Review {
        val goal = snapshot.goal
        if (!Regex("""(?i)\bexplore\s+minicoy\b""").containsMatchIn(goal) ||
            hasDifferentRequestedAction(goal)) return Review(files, false)
        val html = files["index.html"] ?: return Review(files, false)
        val css = files["style.css"] ?: return Review(files, false)
        val section = heading.findAll(html).firstOrNull {
            text(it.groupValues[3]).equals("Things to Explore", ignoreCase = true)
        } ?: return Review(files, false)
        val cta = control.findAll(html).firstOrNull {
            text(it.groupValues[3]).equals("Explore Minicoy", ignoreCase = true)
        } ?: return Review(files, false)

        val headingId = id.find(section.groupValues[2])?.groupValues?.get(2)
        // A stable, unique internal anchor: do not steal any pre-existing element ID.
        val target = headingId ?: (0..8).map { if (it == 0) "lyra-explore-section" else "lyra-explore-section-$it" }
            .firstOrNull { candidate ->
                !Regex("""(?is)\bid\s*=\s*(["'])${Regex.escape(candidate)}\1""")
                    .containsMatchIn(html)
            } ?: return Review(files, false)
        val existingHref = href.find(cta.groupValues[2])?.groupValues?.get(2)
        val alreadyLinked = cta.groupValues[1].equals("a", ignoreCase = true) &&
            existingHref == "#$target"

        var fixedHtml = html
        if (!alreadyLinked) {
            // Preserve visual classes/styles, not onclick, disabled or stale JS binding IDs.
            // Avoid binding script.js to the replacement link through the previous button ID.
            val attributes = cta.groupValues[2]
            val visualAttrs = listOfNotNull(classes.find(attributes)?.value,
                style.find(attributes)?.value).joinToString(" ")
            val extra = if (visualAttrs.isBlank()) "" else " $visualAttrs"
            val replacement = "<a href=\"#$target\"$extra>${cta.groupValues[3]}</a>"
            fixedHtml = fixedHtml.replaceRange(cta.range, replacement)
        }
        // Re-find after changing the CTA because it may precede the heading.
        val freshHeading = heading.findAll(fixedHtml).firstOrNull {
            text(it.groupValues[3]).equals("Things to Explore", ignoreCase = true)
        } ?: return Review(files, false)
        var opening = freshHeading.value.substringBefore('>')
        if (headingId == null) opening += " id=\"$target\""
        val currentClass = classes.find(opening)
        opening = if (currentClass == null) "$opening class=\"$TARGET_CLASS\"" else {
            val classList = currentClass.groupValues[2]
            if (classList.split(spaces).contains(TARGET_CLASS)) opening else opening.replaceRange(
                currentClass.range,
                "class=\"${classList.trim()} $TARGET_CLASS\"")
        }
        fixedHtml = fixedHtml.replaceRange(freshHeading.range,
            opening + ">" + freshHeading.value.substringAfter('>'))
        val fixedCss = if (css.contains("/* LYRA Explore target feedback */")) css else css + FEEDBACK
        require(fixedHtml.length <= 15_000 && fixedCss.length <= 15_000) {
            "Explore link repair exceeds website file limit; no files changed"
        }
        val updated = files + ("index.html" to fixedHtml) + ("style.css" to fixedCss)
        return Review(updated, updated != files)
    }
}
''', encoding='utf-8')

visual = ROOT / 'WorkspaceWebsiteVisualQuality.kt'
replace_once(visual,
    '''        val files = withoutImages + ("index.html" to html)
        require(files.values.sumOf { it.length } <= 30_000 &&''',
    '''        val action = WorkspaceWebsiteActionQuality.review(snapshot,
            withoutImages + ("index.html" to html))
        val files = action.files
        require(files.values.sumOf { it.length } <= 30_000 &&''')
replace_once(visual,
    '''    data class SourceReview(val files: Map<String, String>, val removedImages: Int,
                            val removedEmptyMedia: Int, val viewportAdded: Boolean) {''',
    '''    data class SourceReview(val files: Map<String, String>, val removedImages: Int,
                            val removedEmptyMedia: Int, val viewportAdded: Boolean,
                            val repairedExploreAction: Boolean = false) {''')
replace_once(visual,
    '''        fun chatNote(): String = when {
            removedImages + removedEmptyMedia > 0 ->''',
    '''        fun chatNote(): String = (when {
            removedImages + removedEmptyMedia > 0 ->''')
replace_once(visual,
    '''            else -> ""
        }
    }

    // Only new empty media-specific''',
    '''            else -> ""
        }) + if (repairedExploreAction)
            "\\nExplore Minicoy now links to Things to Explore; tap it to verify in Preview."
        else ""
    }

    // Only new empty media-specific''')
replace_once(visual,
    '''        return SourceReview(files, removedImages, removedEmpty, viewportAdded)
''',
    '''        return SourceReview(files, removedImages, removedEmpty, viewportAdded,
            action.repaired)
''')

generation = ROOT / 'WorkspaceWebsiteGeneration.kt'
replace_once(generation,
    '''            "Make every visible button do what the goal asks; no placeholder alert unless explicitly requested. " +''',
    '''            "For an Explore Minicoy CTA and a Things to Explore section, make a real in-page " +
            "anchor link to an existing section ID, with visible focus/target feedback; " +
            "a bare button or nonfunctional click listener is not complete. " +
            "For different requested button behavior, implement that exact action. " +
            "Make every visible button do what the goal asks; no placeholder alert unless explicitly requested. " +''')

TEST.write_text(r'''package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteActionQualityTest {
    private fun source(goal: String) = WorkspaceWebsiteGeneration.Snapshot(
        "site", "task", "approved", goal,
        mapOf("index.html" to null, "style.css" to null, "script.js" to null))
    private fun files(html: String, css: String = "") = mapOf(
        "index.html" to html, "style.css" to css, "script.js" to "")

    @Test fun inertExploreButtonGetsNativeAnchorWithTargetAndVisibleFeedback() {
        val html = "<html><body><button class='cta' id='missing-listener'>Explore Minicoy</button>" +
            "<section><h2>Things to Explore</h2><p>Beaches</p></section></body></html>"
        val fixed = WorkspaceWebsiteActionQuality.review(
            source("Build Explore Minicoy button and Things to Explore section"), files(html))
        assertTrue(fixed.repaired)
        assertTrue(fixed.files.getValue("index.html").contains(
            "<a href=\"#lyra-explore-section\" class='cta'>Explore Minicoy</a>"))
        assertTrue(fixed.files.getValue("index.html").contains(
            "id=\"lyra-explore-section\" class=\"lyra-explore-target\""))
        assertTrue(fixed.files.getValue("style.css").contains(".lyra-explore-target:target"))
        assertFalse(fixed.files.getValue("index.html").contains("missing-listener"))
    }

    @Test fun existingAnchorAndTargetRemainStableAcrossSubsequentEdits() {
        val html = "<html><body><a href='#island'>Explore Minicoy</a>" +
            "<h2 id='island'>Things to Explore</h2></body></html>"
        val snapshot = source("Create Explore Minicoy CTA and Things to Explore")
        val first = WorkspaceWebsiteActionQuality.review(snapshot, files(html))
        assertEquals(1, Regex("""href=['\"]#island['\"]""").findAll(first.files.getValue("index.html")).count())
        assertEquals(1, Regex("""id=['\"]island['\"]""").findAll(first.files.getValue("index.html")).count())
        val second = WorkspaceWebsiteActionQuality.review(snapshot, first.files)
        assertFalse(second.repaired)
        assertEquals(first.files, second.files)
    }

    @Test fun explicitOtherClickActionAndUnrelatedButtonsAreNotOverwritten() {
        val html = "<html><body><button onclick='showWelcome()'>Explore Minicoy</button>" +
            "<h2>Things to Explore</h2><button>Book now</button></body></html>"
        val explicit = WorkspaceWebsiteActionQuality.review(source(
            "Explore Minicoy button click should show Welcome to Minicoy"), files(html))
        assertFalse(explicit.repaired)
        assertEquals(html, explicit.files.getValue("index.html"))
        val unrelated = WorkspaceWebsiteActionQuality.review(source("Add a booking button"), files(html))
        assertFalse(unrelated.repaired)
        assertEquals(html, unrelated.files.getValue("index.html"))
    }

    @Test fun intentionalSectionIdCollisionUsesNewIdWithoutChangingOtherElement() {
        val html = "<html><body><div id='lyra-explore-section'>Keep this</div>" +
            "<button class='cta'>Explore Minicoy</button><h2>Things to Explore</h2></body></html>"
        val fixed = WorkspaceWebsiteActionQuality.review(
            source("Explore Minicoy Things to Explore"), files(html))
        assertTrue(fixed.files.getValue("index.html").contains("href=\"#lyra-explore-section-1\""))
        assertTrue(fixed.files.getValue("index.html").contains("id='lyra-explore-section'"))
    }

    @Test fun preserveWholeThreeFileSnapshotAndVisualQualityReviewIntegration() {
        val html = "<html><head></head><body><button class='cta'>Explore Minicoy</button>" +
            "<h2>Things to Explore</h2></body></html>"
        val snapshot = source("Build an Explore Minicoy button and Things to Explore section")
        val original = files(html, ".cta{padding:1rem;}") + ("script.js" to "console.log('old')")
        val result = WorkspaceWebsiteVisualQuality.review(snapshot, original)
        assertTrue(result.repairedExploreAction)
        assertTrue(result.files.getValue("index.html").contains("href=\"#lyra-explore-section\""))
        assertEquals("console.log('old')", result.files.getValue("script.js"))
        assertTrue(result.chatNote().contains("tap it to verify"))
    }
}
''', encoding='utf-8')
print('Staged narrow generated Explore CTA normalization, feedback and five regression tests.')
