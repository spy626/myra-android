package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.json.JSONTokener

/** Bounded, on-device source/DOM checks, not an AI vision model or a visual pass claim.
 * No network access, extra files, new model request, automatic project-wide edits or JS bridge.
 */
internal object WorkspaceWebsiteVisualQuality {
    data class SourceReview(val files: Map<String, String>, val removedImages: Int,
                            val removedEmptyMedia: Int, val viewportAdded: Boolean,
                            val repairedExploreAction: Boolean = false,
                            val completedRequestedSection: Boolean = false,
                            val rebuiltCardGroup: Boolean = false) {
        fun chatNote(): String = (when {
            removedImages + removedEmptyMedia > 0 ->
                "\nLayout safeguard: omitted $removedImages unverified image(s) and " +
                    "$removedEmptyMedia empty image slot(s). Cards retain their text. " +
                    "Inspect Preview on your phone; source checks cannot judge visual appearance."
            viewportAdded -> "\nMobile viewport added. Inspect Preview on your phone."
            else -> ""
        }) + (if (repairedExploreAction)
            "\nExplore Minicoy now links to Things to Explore; tap it to verify in Preview."
        else "") + (if (completedRequestedSection)
            if (rebuiltCardGroup) "\nThe free model's card labels were not usable headings. " +
                "LYRA rebuilt only the new project's requested three-card group locally. " +
                "Check its design and text in Preview before Keep."
            else "\nCompleted the explicitly requested Things to Explore section locally. " +
                "Review cards and appearance in Preview before Keep."
        else "")
    }

    // Only new empty media-specific div/figure nodes. Never remove general empty elements,
    // text/card content, existing markup, or deliberate gradient artwork.
    private val emptyMedia = Regex("""(?is)<(div|figure)\b([^>]*)>\s*</\1\s*>""")
    private val className = Regex("""(?is)\bclass\s*=\s*(["'])(.*?)\1""")
    private val mediaWord = Regex("(?i)(image|photo|picture|thumbnail|thumb)")
    private val gradient = Regex("""(?i)(?:linear|radial|conic)-gradient\s*\(""")
    private val viewport = Regex("""(?is)<meta\b[^>]*\bname\s*=\s*['"]?viewport\b""")
    private val headTag = Regex("""(?is)<head\b[^>]*>""")
    private val htmlTag = Regex("""(?is)<html\b[^>]*>""")
    private const val viewportTag =
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"

    private fun decorated(attributes: String, classes: String, css: String): Boolean {
        if (gradient.containsMatchIn(attributes)) return true
        return classes.split(Regex("""\s+""")).take(8).any { token ->
            token.length in 1..64 && Regex("""(?is)\.${Regex.escape(token)}\s*\{[^}]{0,1200}(?:linear|radial|conic)-gradient\s*\(""")
                .containsMatchIn(css)
        }
    }

    fun review(snapshot: WorkspaceWebsiteGeneration.Snapshot,
               generated: Map<String, String>): SourceReview {
        require(generated.keys == WorkspaceWebsiteGeneration.PATHS.toSet()) {
            "Incomplete website result; nothing changed"
        }
        val baseline = snapshot.original["index.html"].orEmpty()
        val withoutImages = WorkspaceWebsiteGeneration.omitUnverifiedImages(snapshot, generated)
        var html = withoutImages.getValue("index.html")
        val css = generated.getValue("style.css")
        val removedImages = Regex("""(?is)<img\b[^>]*>""").findAll(generated.getValue("index.html")).count() -
            Regex("""(?is)<img\b[^>]*>""").findAll(html).count()
        var removedEmpty = 0
        // Inner empty image slots first, then outer wrappers. Bounded; no recursive HTML parser.
        repeat(4) {
            html = emptyMedia.replace(html) { match ->
                val attributes = match.groupValues[2]
                val classes = className.find(attributes)?.groupValues?.get(2).orEmpty()
                val oldOpeningTag = match.value.substringBefore('>') + ">"
                if (mediaWord.containsMatchIn(classes) && !baseline.contains(oldOpeningTag) &&
                    !decorated(attributes, classes, css)) {
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
        val completed = WorkspaceWebsiteRequestedSectionRepair.repair(snapshot,
            withoutImages + ("index.html" to html))
        val action = WorkspaceWebsiteActionQuality.review(snapshot, completed.files)
        // Do not expose generated HTML, project contents, keys or API responses on failure.
        // The bounded diagnostic says WHICH local repair gate blocked, not WHAT it read.
        val files = try {
            WorkspaceWebsiteConsistency.verify(snapshot, action.files)
        } catch (issue: IllegalArgumentException) {
            throw IllegalArgumentException("${issue.message} [local repair: ${completed.diagnostic}]", issue)
        }
        require(files.values.sumOf { it.length } <= 30_000 &&
            files.values.all { it.length <= 15_000 } &&
            !WorkspaceSourceContext.containsPossibleSecret(files.getValue("index.html"))) {
            "Visual safeguard exceeded approved file limits; original files unchanged"
        }
        return SourceReview(files, removedImages, removedEmpty, viewportAdded,
            action.repaired, completed.completed, completed.rebuiltCards)
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
            else -> "Preview loaded · basic layout checks clear; inspect appearance on phone. Button actions need a real tap test."
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
