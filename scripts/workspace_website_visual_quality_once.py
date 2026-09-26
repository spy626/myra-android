#!/usr/bin/env python3
"""Exact-anchor one-time Workspace visual-quality patch; no second AI or file owner."""
from pathlib import Path

ROOT = Path('app/src/main/java/com/myra/assistant/ui/workspace')
TEST = Path('app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceWebsiteVisualQualityTest.kt')

def replace_once(path, old, new):
    source = path.read_text(encoding='utf-8')
    assert source.count(old) == 1, f'Unexpected revision or missing anchor: {path}'
    path.write_text(source.replace(old, new, 1), encoding='utf-8')

quality = ROOT / 'WorkspaceWebsiteVisualQuality.kt'
assert not quality.exists() and not TEST.exists(), 'Refusing to replace prior visual QA'
quality.write_text(r'''package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.json.JSONTokener

/** Bounded, on-device source/DOM checks, not an AI vision model or a visual pass claim.
 * No network access, extra files, new model request, automatic project-wide edits or JS bridge.
 */
internal object WorkspaceWebsiteVisualQuality {
    data class SourceReview(val files: Map<String, String>, val removedImages: Int,
                            val removedEmptyMedia: Int, val viewportAdded: Boolean) {
        fun chatNote(): String = when {
            removedImages + removedEmptyMedia > 0 ->
                "\nLayout safeguard: omitted $removedImages unverified image(s) and " +
                "$removedEmptyMedia empty image slot(s). Cards retain their text. " +
                "Inspect Preview on your phone; source checks cannot judge visual appearance."
            viewportAdded -> "\nMobile viewport added. Inspect Preview on your phone."
            else -> ""
        }
    }

    // Only new empty media-specific div/figure nodes. Never remove general empty elements,
    // text/card content, or an existing opening tag from the previously saved project.
    private val emptyMedia = Regex("""(?is)<(div|figure)\b([^>]*)>\s*</\1\s*>""")
    private val className = Regex("""(?is)\bclass\s*=\s*(["'])(.*?)\1""")
    private val mediaWord = Regex("(?i)(image|photo|picture|thumbnail|thumb)")
    private val viewport = Regex("""(?is)<meta\b[^>]*\bname\s*=\s*['"]?viewport\b""")
    private val headTag = Regex("""(?is)<head\b[^>]*>""")
    private val htmlTag = Regex("""(?is)<html\b[^>]*>""")
    private const val viewportTag =
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"

    fun review(snapshot: WorkspaceWebsiteGeneration.Snapshot,
               generated: Map<String, String>): SourceReview {
        require(generated.keys == WorkspaceWebsiteGeneration.PATHS.toSet()) {
            "Incomplete website result; nothing changed"
        }
        val baseline = snapshot.original["index.html"].orEmpty()
        val withoutImages = WorkspaceWebsiteGeneration.omitUnverifiedImages(snapshot, generated)
        var html = withoutImages.getValue("index.html")
        val removedImages = Regex("""(?is)<img\b[^>]*>""").findAll(generated.getValue("index.html")).count() -
            Regex("""(?is)<img\b[^>]*>""").findAll(html).count()
        var removedEmpty = 0
        // Inner empty image slots first, then outer wrappers. Bounded; no recursive HTML parser.
        repeat(4) {
            html = emptyMedia.replace(html) { match ->
                val classes = className.find(match.groupValues[2])?.groupValues?.get(2).orEmpty()
                val oldOpeningTag = match.value.substringBefore('>') + ">"
                if (mediaWord.containsMatchIn(classes) && !baseline.contains(oldOpeningTag)) {
                    removedEmpty++
                    ""
                } else match.value
            }
        }
        var viewportAdded = false
        if (!viewport.containsMatchIn(html)) {
            val head = headTag.find(html)
            val root = htmlTag.find(html)
            when {
                head != null -> {
                    html = html.replaceRange(head.range.last + 1, head.range.last + 1, viewportTag)
                    viewportAdded = true
                }
                root != null -> {
                    html = html.replaceRange(root.range.last + 1, root.range.last + 1,
                        "<head>$viewportTag</head>")
                    viewportAdded = true
                }
            }
        }
        val files = withoutImages + ("index.html" to html)
        require(files.values.sumOf { it.length } <= 30_000 &&
            files.values.all { it.length <= 15_000 } &&
            !WorkspaceSourceContext.containsPossibleSecret(html)) {
            "Visual safeguard exceeded approved file limits; original files unchanged"
        }
        return SourceReview(files, removedImages, removedEmpty, viewportAdded)
    }

    data class LayoutFindings(val overflow: Boolean, val brokenImages: Int, val emptyMedia: Int) {
        fun status(): String = when {
            overflow || brokenImages > 0 || emptyMedia > 0 -> {
                val findings = listOfNotNull(
                    if (overflow) "horizontal overflow" else null,
                    if (brokenImages > 0) "$brokenImages broken image(s)" else null,
                    if (emptyMedia > 0) "$emptyMedia empty media slot(s)" else null)
                "Preview needs layout review: ${findings.joinToString(", ")}. Check before Keep."
            }
            else -> "Preview loaded · automated layout checks clear; inspect appearance on phone."
        }
    }

    /** evaluateJavascript returns a JSON-encoded string; only accept our three bounded fields. */
    fun decodeDomResult(encoded: String?): LayoutFindings? = runCatching {
        require(!encoded.isNullOrBlank() && encoded.length <= 1024)
        val raw = JSONTokener(encoded).nextValue()
        require(raw is String && raw.length <= 512)
        val obj = JSONObject(raw)
        require(obj.keys().asSequence().toSet() == setOf("overflow", "brokenImages", "emptyMedia"))
        val overflow = obj.get("overflow")
        require(overflow is Boolean)
        val broken = obj.getInt("brokenImages")
        val empty = obj.getInt("emptyMedia")
        require(broken in 0..200 && empty in 0..200)
        LayoutFindings(overflow, broken, empty)
    }.getOrNull()

    /** Observe saved-project DOM without reading page text, keys, chat or screen pixels. */
    val DOM_AUDIT_SCRIPT: String = """(function(){
        'use strict';
        var width = Math.max(window.innerWidth || 0, document.documentElement.clientWidth || 0);
        var overflow = document.documentElement.scrollWidth > width + 8;
        var images = Array.prototype.slice.call(document.images, 0, 200);
        var broken = images.filter(function(i){return i.complete && i.naturalWidth === 0;}).length;
        var all = Array.prototype.slice.call(document.querySelectorAll('[class]'), 0, 450);
        var empty = all.filter(function(el){
          if (!/(image|photo|picture|thumbnail|thumb)/i.test(String(el.className))) return false;
          if (!el.getBoundingClientRect || el.getBoundingClientRect().height < 120) return false;
          if ((el.textContent || '').trim() || el.querySelector('img,svg,canvas,video')) return false;
          var style = window.getComputedStyle(el);
          return style.backgroundImage === 'none';
        }).length;
        return JSON.stringify({overflow:overflow,brokenImages:broken,emptyMedia:empty});
      })()"""
}
''', encoding='utf-8')

generation = ROOT / 'WorkspaceWebsiteGeneration.kt'
replace_once(generation,
    '''            "Make it mobile-friendly, functional and relevant to the goal. Use English in code and comments. " +''',
    '''            "Plan an intentional mobile-first visual hierarchy: legible contrasting text, " +
            "coherent colors, compact content-sized cards, consistent spacing, and a clear call to action. " +
            "Include the viewport meta tag; at 360px width no horizontal overflow or clipped controls. " +
            "Never reserve an empty image/photo slot or fixed image height without a real local asset. " +
            "If no real asset exists, use attractive CSS gradients/decoration and text instead; " +
            "all cards must be compact and readable, not large blank rectangles. " +
            "Make every visible button do what the goal asks; no placeholder alert unless explicitly requested. " +
            "Make it mobile-friendly, functional and relevant to the goal. Use English in code and comments. " +''')

flow = ROOT / 'WorkspaceChatCodingFlow.kt'
replace_once(flow,
    '''                val cleaned = WorkspaceWebsiteGeneration.omitUnverifiedImages(snapshot, generated)
                val omittedImages = cleaned["index.html"] != generated["index.html"]
                runCatching {
                    WorkspaceWebsiteGeneration.apply(files, tasks, projects, snapshot, cleaned)
                }.onSuccess {
                    val summary = WorkspaceCodingResult.websiteSuccess(snapshot.original, cleaned) +
                        if (omittedImages) "\\nUnverified images omitted; cards use the saved text and CSS." else ""
                    terminal(summary, "")''',
    '''                val review = runCatching { WorkspaceWebsiteVisualQuality.review(snapshot, generated) }
                    .getOrElse { issue ->
                        error("Website layout safeguard rejected this output: ${issue.message}. No files changed.")
                        return@runOnUiThread
                    }
                runCatching {
                    WorkspaceWebsiteGeneration.apply(files, tasks, projects, snapshot, review.files)
                }.onSuccess {
                    val summary = WorkspaceCodingResult.websiteSuccess(snapshot.original, review.files) +
                        review.chatNote()
                    terminal(summary, "")''')

preview = ROOT / 'WorkspacePreviewActivity.kt'
replace_once(preview,
    '''                override fun onPageFinished(view: WebView, url: String) {
                    if (!failed && isLocalPreviewUrl(Uri.parse(url)))
                        binding.previewStatus.text = "Preview ready  •  Saved project files"
                }''',
    '''                override fun onPageFinished(view: WebView, url: String) {
                    if (failed || !isLocalPreviewUrl(Uri.parse(url))) return
                    binding.previewStatus.text = "Preview loaded · checking phone layout…"
                    // Fixed, read-only DOM metrics for the saved page. No JS bridge or
                    // model transfer. A late callback cannot overwrite another page's status.
                    view.evaluateJavascript(WorkspaceWebsiteVisualQuality.DOM_AUDIT_SCRIPT) { encoded ->
                        if (!failed && !isFinishing && !isDestroyed &&
                            binding.previewWebView.url == url &&
                            isLocalPreviewUrl(Uri.parse(url))) {
                            val audit = WorkspaceWebsiteVisualQuality.decodeDomResult(encoded)
                            binding.previewStatus.text = audit?.status() ?:
                                "Preview loaded · layout check unavailable; inspect on phone."
                        }
                    }
                }''')

TEST.write_text(r'''package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteVisualQualityTest {
    private val source = WorkspaceWebsiteGeneration.Snapshot("website", "task", "approved",
        "Add three exploration cards", mapOf("index.html" to null, "style.css" to null,
            "script.js" to null))
    private fun code(html: String) = mapOf("index.html" to html, "style.css" to "", "script.js" to "")

    @Test fun removedImageDoesNotLeaveEmptyWhiteMediaWrapper() {
        val page = "<html><head></head><body><section class='cards'>" +
            "<article class='card'><div class='card-image' style='height:240px'>" +
            "<img src='missing-beach.jpg' alt='Beach'></div><h2>Beaches</h2><p>Visit Minicoy.</p></article>" +
            "<article class='card'><div class='card-photo'><img src='bad-lighthouse.png'></div>" +
            "<h2>Lighthouse</h2></article></section></body></html>"
        val review = WorkspaceWebsiteVisualQuality.review(source, code(page))
        val html = review.files.getValue("index.html")
        assertEquals(2, review.removedImages)
        assertEquals(2, review.removedEmptyMedia)
        assertFalse(html.contains("card-image"))
        assertFalse(html.contains("card-photo"))
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("<h2>Beaches</h2>"))
        assertTrue(html.contains("<h2>Lighthouse</h2>"))
        assertTrue(html.contains("width=device-width"))
        assertTrue(review.chatNote().contains("Inspect Preview"))
    }

    @Test fun existingAssetMarkupAndOtherElementsRemainUntouched() {
        val original = "<html><head><meta name='viewport' content='width=device-width'></head>" +
            "<body><div class='card-image'><img src='existing.jpg'></div>" +
            "<div class='card-image'></div><div class='card'></div></body></html>"
        val snapshot = source.copy(original = source.original + ("index.html" to original))
        val review = WorkspaceWebsiteVisualQuality.review(snapshot, code(original))
        assertEquals(original, review.files.getValue("index.html"))
        assertEquals(0, review.removedImages)
        assertEquals(0, review.removedEmptyMedia)
        assertFalse(review.viewportAdded)
    }

    @Test fun newMediaSlotRemovedButLegitimateTextAndDecorativeContainersRemain() {
        val page = "<html><body><div class=\"beach-image\"></div>" +
            "<div class=\"image-label\">Beach details</div>" +
            "<div class=\"hero\"></div><figure class=\"photo-area\"></figure>" +
            "</body></html>"
        val review = WorkspaceWebsiteVisualQuality.review(source, code(page))
        val html = review.files.getValue("index.html")
        assertEquals(2, review.removedEmptyMedia)
        assertTrue(html.contains("Beach details"))
        assertTrue(html.contains("class=\"hero\""))
    }

    @Test fun decodesBoundedReadOnlyDomMetricsWithoutClaimingVisualSuccess() {
        val encoded = JSONObject.quote("""{"overflow":true,"brokenImages":2,"emptyMedia":1}""")
        val findings = requireNotNull(WorkspaceWebsiteVisualQuality.decodeDomResult(encoded))
        assertTrue(findings.overflow)
        assertTrue(findings.status().contains("horizontal overflow"))
        assertTrue(findings.status().contains("broken image"))
        val clear = requireNotNull(WorkspaceWebsiteVisualQuality.decodeDomResult(
            JSONObject.quote("""{"overflow":false,"brokenImages":0,"emptyMedia":0}""")))
        assertTrue(clear.status().contains("inspect appearance on phone"))
        assertNull(WorkspaceWebsiteVisualQuality.decodeDomResult("{}"))
        assertNull(WorkspaceWebsiteVisualQuality.decodeDomResult(JSONObject.quote("""{"overflow":true,"brokenImages":300,"emptyMedia":0}""")))
    }
}
''', encoding='utf-8')
print('Visual source cleanup + mobile design contract + read-only WebView geometry audit staged')
