#!/usr/bin/env python3
"""One-shot, anchored button palette acceptance checks; no prompt-specific code patches."""
from pathlib import Path
root = Path('app/src/main/java/com/myra/assistant/ui/workspace')

def replace_once(path, old, new):
    value = path.read_text(encoding='utf-8')
    assert value.count(old) == 1, f'Expected exactly one anchor in {path}'
    path.write_text(value.replace(old, new, 1), encoding='utf-8')

website = root / 'WorkspaceWebsiteGeneration.kt'
anchor = '            "Treat each explicitly requested heading, named card and button behavior as acceptance criteria. " +\n'
replace_once(website, anchor, anchor +
    '            "If the goal requests a color for buttons, apply that background to EVERY visible " +\n'
    '            "button, including increment, decrement, reset and secondary actions, not just " +\n'
    '            "the most prominent action. Check button IDs/classes and CSS cascade: a later " +\n'
    '            "specific selector must not override the requested button color. Keep existing " +\n'
    '            "JavaScript handlers and accessible text contrast. Use explicit solid CSS hex " +\n'
    '            "button backgrounds (or a resolvable CSS variable) so local source review " +\n'
    '            "can check requested color coverage before saving. " +\n')
anchor = '        requireChanged(snapshot, generated)\n        val file = backupFile(projects, snapshot.projectId)\n'
replace_once(website, anchor, '        requireChanged(snapshot, generated)\n        WorkspaceWebsiteButtonPalette.verify(snapshot, generated)\n        val file = backupFile(projects, snapshot.projectId)\n')
review = root / 'WorkspaceWebsiteVisualQuality.kt'
anchor = '        WorkspaceWebsiteGeneration.requireChanged(snapshot, files)\n        return SourceReview(files, removedImages, removedEmpty, viewportAdded,\n'
replace_once(review, anchor, '        WorkspaceWebsiteGeneration.requireChanged(snapshot, files)\n        WorkspaceWebsiteButtonPalette.verify(snapshot, files)\n        return SourceReview(files, removedImages, removedEmpty, viewportAdded,\n')

(root / 'WorkspaceWebsiteButtonPalette.kt').write_text(r'''package com.myra.assistant.ui.workspace

import kotlin.math.max
import kotlin.math.min

/** Source-only gate for a user-requested single button color. No generated CSS repairs,
 * no network, no second inference, no claims about pixels or physical button taps.
 * Deliberately fails closed if a CSS background cannot be established for every <button>.
 */
internal object WorkspaceWebsiteButtonPalette {
    private val desired = Regex("""(?i)\b(amber|orange|gold|yellow|red|blue|green|teal|pink|purple|violet|indigo|cyan|lime|brown|black|white|gray|grey|charcoal|navy)\s+(?:colou?red\s+)?buttons\b""")
    private val buttonTag = Regex("""(?is)<button\b([^>]{0,800})>""")
    private val cssRule = Regex("""(?s)([^{}]+)\{([^{}]*)\}""")
    private val background = Regex("""(?i)(?:^|;)\s*background(?:-color)?\s*:\s*([^;]+)""")
    private val variables = Regex("""(--[a-zA-Z][\w-]*)\s*:\s*([^;]+)""")
    private val variableUse = Regex("""var\(\s*(--[a-zA-Z][\w-]*)\s*\)""")
    private val hex = Regex("""(?i)#([0-9a-f]{6}|[0-9a-f]{3})\b""")
    private val rgb = Regex("""(?i)rgb\(\s*(\d{1,3})[\s,]+(\d{1,3})[\s,]+(\d{1,3})\s*\)""")
    private val ids = Regex("""#([a-zA-Z_][\w-]*)""")
    private val classes = Regex("""\.([a-zA-Z_][\w-]*)""")
    private data class Button(val id: String, val classes: Set<String>, val inline: String)
    private data class Fill(val priority: Int, val order: Int, val value: String)

    private fun attribute(attrs: String, name: String): String =
        Regex("""(?is)\b${Regex.escape(name)}\s*=\s*(['"])(.*?)\1""")
            .find(attrs)?.groupValues?.get(2).orEmpty()

    private fun matches(selector: String, button: Button): Boolean {
        // Consider only ordinary, non-state rules; hover/focus styling is not a default fill.
        val simple = selector.trim().substringAfterLast(' ').substringAfterLast('>').trim()
        if (':' in simple || '[' in simple || '*' in simple || '+' in simple || '~' in simple) return false
        if (!Regex("""(?i)^(?:button)?(?:[.#][a-zA-Z_][\w-]*)*$""").matches(simple) ||
            simple.isBlank()) return false
        if (!simple.startsWith("button", true) && !simple.startsWith('.') && !simple.startsWith('#')) return false
        return ids.findAll(simple).all { it.groupValues[1] == button.id } &&
            classes.findAll(simple).all { it.groupValues[1] in button.classes }
    }

    private fun parseFill(raw: String, vars: Map<String, String>): Triple<Int, Int, Int>? {
        var fill = raw.replace(Regex("""(?i)!important"""), "").trim()
        repeat(3) {
            val key = variableUse.matchEntire(fill)?.groupValues?.get(1) ?: return@repeat
            fill = vars[key]?.trim() ?: return null
        }
        if (fill.contains("gradient(", true) || fill.contains("rgba(", true) ||
            fill.contains("transparent", true)) return null
        hex.find(fill)?.let {
            val code = it.groupValues[1].let { digits ->
                if (digits.length == 3) digits.map { c -> "$c$c" }.joinToString("") else digits
            }
            return Triple(code.substring(0, 2).toInt(16), code.substring(2, 4).toInt(16),
                code.substring(4, 6).toInt(16))
        }
        rgb.find(fill)?.let {
            val numbers = it.groupValues.drop(1).map(String::toInt)
            return if (numbers.all { n -> n in 0..255 })
                Triple(numbers[0], numbers[1], numbers[2]) else null
        }
        return mapOf("red" to Triple(255, 0, 0), "blue" to Triple(0, 0, 255),
            "green" to Triple(0, 128, 0), "teal" to Triple(0, 128, 128),
            "orange" to Triple(255, 165, 0), "gold" to Triple(255, 215, 0),
            "yellow" to Triple(255, 255, 0), "purple" to Triple(128, 0, 128),
            "pink" to Triple(255, 192, 203), "cyan" to Triple(0, 255, 255),
            "black" to Triple(0, 0, 0), "white" to Triple(255, 255, 255),
            "gray" to Triple(128, 128, 128), "grey" to Triple(128, 128, 128),
            "navy" to Triple(0, 0, 128), "brown" to Triple(165, 42, 42),
            "lime" to Triple(0, 255, 0), "indigo" to Triple(75, 0, 130),
            "violet" to Triple(238, 130, 238))[fill.lowercase()]
    }

    private fun inPalette(color: Triple<Int, Int, Int>, target: String): Boolean {
        val (r, g, b) = color
        val high = max(r, max(g, b)).toDouble()
        val low = min(r, min(g, b)).toDouble()
        val chroma = high - low
        val saturation = if (high == 0.0) 0.0 else chroma / high
        if (target in setOf("black", "charcoal")) return high <= 90.0 && saturation <= 0.35
        if (target == "white") return low >= 205.0 && chroma <= 35.0
        if (target in setOf("gray", "grey")) return chroma <= 36.0 && high in 45.0..220.0
        if (chroma < 25.0 || saturation < 0.28 || high < 55.0) return false
        val hue = (when (high) {
            r.toDouble() -> ((g - b) / chroma) % 6.0
            g.toDouble() -> (b - r) / chroma + 2.0
            else -> (r - g) / chroma + 4.0
        } * 60.0 + 360.0) % 360.0
        return when (target) {
            "red" -> hue <= 16 || hue >= 346
            "amber" -> hue in 20.0..53.0 && saturation >= 0.40
            "orange" -> hue in 12.0..48.0
            "gold", "yellow" -> hue in 37.0..72.0
            "green", "lime" -> hue in 70.0..165.0
            "teal" -> hue in 160.0..205.0
            "cyan" -> hue in 175.0..210.0
            "blue", "navy" -> hue in 205.0..265.0
            "indigo" -> hue in 235.0..285.0
            "purple", "violet" -> hue in 265.0..320.0
            "pink" -> hue in 290.0..355.0
            "brown" -> hue in 8.0..50.0 && high < 215.0
            else -> true
        }
    }

    fun verify(snapshot: WorkspaceWebsiteGeneration.Snapshot, files: Map<String, String>) {
        val target = desired.find(snapshot.goal)?.groupValues?.get(1)?.lowercase() ?: return
        val html = files["index.html"] ?: return
        val css = files["style.css"] ?: return
        val buttons = buttonTag.findAll(html).take(32).map { tag ->
            val attrs = tag.groupValues[1]
            Button(attribute(attrs, "id"), attribute(attrs, "class")
                .split(Regex("""\s+""")).filter(String::isNotEmpty).toSet(),
                attribute(attrs, "style"))
        }.toList()
        if (buttons.isEmpty()) return // Structural/button-existence review is separate.
        val vars = variables.findAll(css).associate { it.groupValues[1] to it.groupValues[2] }
        val chosen = Array<Fill?>(buttons.size) { null }
        cssRule.findAll(css).forEachIndexed { order, rule ->
            val fill = background.findAll(rule.groupValues[2]).lastOrNull()?.groupValues?.get(1)
                ?: return@forEachIndexed
            rule.groupValues[1].split(',').forEach { selector ->
                val specific = selector.trim().substringAfterLast(' ').substringAfterLast('>')
                val score = ids.findAll(specific).count() * 100 +
                    classes.findAll(specific).count() * 10 +
                    if (specific.startsWith("button", true)) 1 else 0
                buttons.forEachIndexed { index, button ->
                    if (matches(selector, button) &&
                        (chosen[index] == null || score > chosen[index]!!.priority ||
                            score == chosen[index]!!.priority && order >= chosen[index]!!.order))
                        chosen[index] = Fill(score, order, fill)
                }
            }
        }
        buttons.forEachIndexed { index, button ->
            val inline = background.findAll(button.inline).lastOrNull()?.groupValues?.get(1)
            val fill = inline ?: chosen[index]?.value
            val color = fill?.let { parseFill(it, vars) }
            require(color != null && inPalette(color, target)) {
                "Requested button color was not verified for every button; no files changed. " +
                    "Inspect CSS selectors and colors. No automatic resend."
            }
        }
    }
}
''', encoding='utf-8')

Path('app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceWebsiteButtonPaletteTest.kt').write_text(r'''package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteButtonPaletteTest {
    private val html = """<!doctype html><html><head><link rel="stylesheet" href="style.css"></head><body><button id="minus" class="counter">Minus</button><button id="plus" class="counter">Plus</button><button id="reset" class="counter">Reset</button><script src="script.js"></script></body></html>"""
    private val js = "document.querySelectorAll('button').forEach(b => b.addEventListener('click', () => {}));"
    private val darkAndAmber = "body { background: #191919 } .counter { background: #202020; color: white } #reset { background: #d97706; }"
    private fun snapshot(goal: String, css: String): WorkspaceWebsiteGeneration.Snapshot =
        WorkspaceWebsiteGeneration.Snapshot("project", "task", "spec", goal,
            mapOf("index.html" to html, "style.css" to css, "script.js" to js))
    private fun website(css: String) = mapOf("index.html" to html, "style.css" to css, "script.js" to js)

    @Test fun conflictingButtonBackgroundsRejectAnExplicitPaletteRequest() {
        val original = snapshot("Change to charcoal theme with amber buttons. Keep actions working.", "body{background:white}")
        val error = runCatching { WorkspaceWebsiteButtonPalette.verify(original, website(darkAndAmber)) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("every button"))
    }

    @Test fun allButtonColorsFromOneRuleCanPassWithoutChangingJavaScript() {
        val original = snapshot("Give the controls amber buttons", "body{background:white}")
        val updated = website("body { background: #191919 } .counter { background: #d97706; color: white; }")
        WorkspaceWebsiteButtonPalette.verify(original, updated)
        assertEquals(js, updated["script.js"])
    }

    @Test fun variableAndRgbRulesWorkForDifferentRequestedColors() {
        val original = snapshot("Set blue buttons", "body{background:white}")
        WorkspaceWebsiteButtonPalette.verify(original,
            website(":root{--action: rgb(37,99,235);} .counter{background: var(--action);}"))
        val green = snapshot("Use green buttons", "body{background:white}")
        WorkspaceWebsiteButtonPalette.verify(green, website("button{background:#16a34a}"))
    }

    @Test fun ordinaryFunctionEditsAreNotConstrainedByPaletteChecks() {
        val original = snapshot("Make Plus, Minus and Reset work with keyboard", "body{background:white}")
        WorkspaceWebsiteButtonPalette.verify(original, website(darkAndAmber))
    }

    @Test fun sourceReviewRejectsMixedPaletteBeforeAnyFilesAreSaved() {
        val original = snapshot("Change to dark background with amber buttons", "body{background:white}")
        val result = runCatching { WorkspaceWebsiteVisualQuality.review(original, website(darkAndAmber)) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("button color"))
    }
}
''', encoding='utf-8')
print('Prepared generic button palette instruction, fail-closed source gate, and five regression cases')
