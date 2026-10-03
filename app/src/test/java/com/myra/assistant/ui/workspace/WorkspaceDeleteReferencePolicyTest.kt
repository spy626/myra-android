package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDeleteReferencePolicyTest {
    @Test fun htmlStylesheetAndScriptAreDetected() {
        val html = """<link rel="stylesheet" href="style.css?v=2"><script src="script.js"></script>"""
        assertTrue(WorkspaceDeleteReferencePolicy.referencesTarget("index.html", html, "style.css"))
        assertTrue(WorkspaceDeleteReferencePolicy.referencesTarget("index.html", html, "script.js"))
        assertFalse(WorkspaceDeleteReferencePolicy.referencesTarget("index.html", html, "style.css.bak"))
    }

    @Test fun nestedRelativePathsAndFoldersResolveWithinProject() {
        val html = """<script src="../assets/app.js#v1"></script>"""
        assertTrue(WorkspaceDeleteReferencePolicy.referencesTarget("pages/home.html", html, "assets/app.js"))
        assertTrue(WorkspaceDeleteReferencePolicy.referencesTarget("pages/home.html", html, "assets"))
        assertFalse(WorkspaceDeleteReferencePolicy.referencesTarget("pages/home.html", html, "outside"))
    }

    @Test fun cssImportsAndJavaScriptModulesAreDetected() {
        assertTrue(WorkspaceDeleteReferencePolicy.referencesTarget("css/main.css", "@import './theme.css';", "css/theme.css"))
        assertTrue(WorkspaceDeleteReferencePolicy.referencesTarget("src/main.js", "import data from './data.js'", "src/data.js"))
        assertTrue(WorkspaceDeleteReferencePolicy.referencesTarget("css/main.css", "background: url('/images/bg.png')", "images/bg.png"))
    }

    @Test fun externalAndEscapingUrlsAreNotMistakenForProjectFiles() {
        val html = """<script src="https://example.com/style.css"></script><a href="../../style.css">X</a>"""
        assertFalse(WorkspaceDeleteReferencePolicy.referencesTarget("pages/a.html", html, "style.css"))
        assertFalse(WorkspaceDeleteReferencePolicy.referencesTarget("index.html", "<a href='//cdn.site/style.css'>x</a>", "style.css"))
    }
}
