package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.json.JSONTokener

/**
 * Bounded browser-verification evidence for local Workspace preview.
 *
 * This is not a browser executor and cannot create phone/visual acceptance. It only combines
 * DOM/accessibility counts with console/network counters already observed by the existing WebView.
 */
internal object WorkspaceBrowserVerificationEvidence {
    enum class Assessment { ISSUES_FOUND, BOUNDED_CLEAR, UNKNOWN }

    enum class Plane {
        DOM,
        ACCESSIBILITY,
        CONSOLE,
        NETWORK,
    }

    data class DomSignals(
        val overflow: Boolean,
        val brokenImages: Int,
        val emptyMedia: Int,
        val interactiveControls: Int,
        val unlabeledControls: Int,
    )

    data class RuntimeSignals(
        val consoleErrors: Int = 0,
        val consoleWarnings: Int = 0,
        val networkFailures: Int = 0,
        val httpErrors: Int = 0,
    ) {
        init {
            require(consoleErrors in 0..500 && consoleWarnings in 0..500 &&
                networkFailures in 0..500 && httpErrors in 0..500) {
                "Browser runtime evidence is out of bounds"
            }
        }
    }

    data class Snapshot(
        val pageUrl: String,
        val capturedAtMs: Long,
        val dom: DomSignals?,
        val runtime: RuntimeSignals,
    ) {
        init {
            require(pageUrl.length in 1..2_048) { "Browser evidence URL is invalid" }
            require(capturedAtMs >= 0L) { "Browser evidence timestamp is invalid" }
        }
    }

    data class Result(
        val assessment: Assessment,
        val findings: List<String>,
        val planes: Set<Plane>,
    ) {
        fun statusText(): String = when (assessment) {
            Assessment.ISSUES_FOUND ->
                "Preview needs review: ${findings.joinToString(", ")}. Fix or inspect before Keep."
            Assessment.BOUNDED_CLEAR ->
                "Browser DOM/accessibility/console/network checks are clear. " +
                    "Appearance and real tap behavior still need phone/visual verification."
            Assessment.UNKNOWN ->
                "Browser verification is incomplete. Re-observe Preview; do not treat this as PASS."
        }
    }

    fun assess(snapshot: Snapshot): Result {
        val findings = mutableListOf<String>()
        val planes = linkedSetOf(Plane.CONSOLE, Plane.NETWORK)
        val dom = snapshot.dom
        if (dom != null) {
            planes += Plane.DOM
            planes += Plane.ACCESSIBILITY
            if (dom.overflow) findings += "horizontal overflow"
            if (dom.brokenImages > 0) findings += "${dom.brokenImages} broken image(s)"
            if (dom.emptyMedia > 0) findings += "${dom.emptyMedia} empty media slot(s)"
            if (dom.unlabeledControls > 0) {
                findings += "${dom.unlabeledControls} unlabeled interactive control(s)"
            }
        }
        if (snapshot.runtime.consoleErrors > 0) {
            findings += "${snapshot.runtime.consoleErrors} console error(s)"
        }
        if (snapshot.runtime.networkFailures > 0) {
            findings += "${snapshot.runtime.networkFailures} network load failure(s)"
        }
        if (snapshot.runtime.httpErrors > 0) {
            findings += "${snapshot.runtime.httpErrors} HTTP error response(s)"
        }

        val assessment = when {
            findings.isNotEmpty() -> Assessment.ISSUES_FOUND
            dom == null -> Assessment.UNKNOWN
            else -> Assessment.BOUNDED_CLEAR
        }
        return Result(assessment, findings.toList(), planes)
    }

    /** evaluateJavascript returns a JSON-encoded string; accept counts only, never page text. */
    fun decodeDomResult(encoded: String?): DomSignals? = runCatching {
        require(!encoded.isNullOrBlank() && encoded.length <= 2_048)
        val raw = JSONTokener(encoded).nextValue()
        require(raw is String && raw.length <= 1_024)
        val obj = JSONObject(raw)
        require(obj.keys().asSequence().toSet() == setOf(
            "overflow", "brokenImages", "emptyMedia", "interactiveControls", "unlabeledControls"))
        val overflow = obj.get("overflow")
        require(overflow is Boolean)
        val broken = obj.getInt("brokenImages")
        val empty = obj.getInt("emptyMedia")
        val interactive = obj.getInt("interactiveControls")
        val unlabeled = obj.getInt("unlabeledControls")
        require(broken in 0..200 && empty in 0..200 &&
            interactive in 0..300 && unlabeled in 0..300 &&
            unlabeled <= interactive)
        DomSignals(overflow, broken, empty, interactive, unlabeled)
    }.getOrNull()

    /**
     * Fixed local audit: returns only counts/booleans. It never returns page text, storage, cookies,
     * tokens, form values or arbitrary DOM content.
     */
    val DOM_AUDIT_SCRIPT: String = """(function(){
        'use strict';
        var width = Math.max(window.innerWidth || 0, document.documentElement.clientWidth || 0);
        var overflow = document.documentElement.scrollWidth > width + 8;
        var images = Array.prototype.slice.call(document.images, 0, 200);
        var broken = images.filter(function(i){return i.complete && i.naturalWidth === 0;}).length;
        var media = Array.prototype.slice.call(document.querySelectorAll('[class]'), 0, 450);
        var empty = media.filter(function(el){
          if (!/(image|photo|picture|thumbnail|thumb)/i.test(String(el.className))) return false;
          if (!el.getBoundingClientRect || el.getBoundingClientRect().height < 120) return false;
          if ((el.textContent || '').trim() || el.querySelector('img,svg,canvas,video')) return false;
          var style = window.getComputedStyle(el);
          return style.backgroundImage === 'none';
        }).length;
        var controls = Array.prototype.slice.call(
          document.querySelectorAll('button,a[href],input:not([type="hidden"]),select,textarea'), 0, 300);
        function named(el){
          if ((el.getAttribute('aria-label') || '').trim()) return true;
          if ((el.getAttribute('aria-labelledby') || '').trim()) return true;
          if ((el.getAttribute('title') || '').trim()) return true;
          if (el.id && document.querySelector('label[for="' + CSS.escape(el.id) + '"]')) return true;
          if (el.closest && el.closest('label')) return true;
          if (el.tagName === 'INPUT' && (el.getAttribute('placeholder') || '').trim()) return true;
          return (el.textContent || '').trim().length > 0;
        }
        var unlabeled = controls.filter(function(el){return !named(el);}).length;
        return JSON.stringify({
          overflow:overflow,
          brokenImages:broken,
          emptyMedia:empty,
          interactiveControls:controls.length,
          unlabeledControls:unlabeled
        });
      })()"""
}
