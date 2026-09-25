package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceSkillContractTest {
    private val c = WorkspaceSkillContract

    @Test fun minimalSkillParsesAsImmutableInstructionOnlyPackage() {
        val md = """---
name: code-explainer
description: Explain code in plain language.
license: MIT
---
# Workflow
1. Read the code.
2. Explain it.

## Verification
Confirm every referenced symbol exists.
"""
        val skill = c.parse(md)
        assertEquals("code-explainer", skill.name)
        assertEquals("Explain code in plain language.", skill.description)
        assertEquals("MIT", skill.declaredLicense)
        assertEquals(64, skill.contentSha256.length)
        assertTrue(skill.hasVerificationGate)
        assertTrue(skill.manifest.allowedTools.isEmpty())
        assertFalse(skill.manifest.modelInvocable)
        assertEquals(md, skill.originalSkillMd)
    }

    @Test fun blockDescriptionAndFrontmatterToolsAreSupportedConservatively() {
        val md = """---
name: review-code
description: >
  Review one bounded change
  and report concrete issues.
allowed-tools: [read_file, search_code]
---
Follow the task criteria.
"""
        val skill = c.parse(md)
        assertEquals("Review one bounded change and report concrete issues.",
            skill.description)
        assertEquals(setOf("read_file", "search_code"), skill.manifest.allowedTools)
        assertFalse(skill.hasVerificationGate)
        assertTrue(skill.permissionPreview.warnings.any {
            it.contains("verification", ignoreCase = true)
        })
    }

    @Test fun manifestProducesPermissionPreviewWithoutGrantingAnything() {
        val md = """---
name: github-review
description: Review public GitHub evidence.
---
## Verification
Require pinned provenance.
"""
        val json = """{
          "version":"1.0.0",
          "author":"Example",
          "allowedTools":["github_read"],
          "requiredCapabilities":["network:read"],
          "networkDomains":["api.github.com"],
          "sourceSharing":"BOUNDED",
          "memoryAccess":"READ",
          "userInvocable":true,
          "modelInvocable":true,
          "dependencySkills":["code-explainer"],
          "maxNestingDepth":1
        }"""
        val skill = c.parse(md, json, packagePaths = listOf("SKILL.md", "skill.json"))
        assertEquals(listOf("api.github.com"), skill.permissionPreview.networkDomains)
        assertEquals(WorkspaceSkillContract.SourceSharing.BOUNDED, skill.permissionPreview.sourceSharing)
        assertEquals(WorkspaceSkillContract.MemoryAccess.READ, skill.permissionPreview.memoryAccess)
        assertTrue(skill.permissionPreview.modelInvocable)
        assertTrue(skill.permissionPreview.warnings.any {
            it.contains("explicit approval", ignoreCase = true)
        })
    }

    @Test fun executableAndTraversalPackageAssetsFailClosed() {
        val md = """---
name: safe-skill
description: Safe skill.
---
Instructions.
"""
        listOf(
            listOf("SKILL.md", "scripts/install.sh"),
            listOf("SKILL.md", "../evil.txt"),
            listOf("SKILL.md", "assets/plugin.apk"),
            listOf("SKILL.md", "unknown/readme.md"),
        ).forEach { paths ->
            assertTrue("Unsafe package accepted: $paths", runCatching {
                c.parse(md, packagePaths = paths)
            }.isFailure)
        }
    }

    @Test fun manifestPathAndManifestBytesMustMatchExactly() {
        val md = """---
name: safe-skill
description: Safe skill.
---
Instructions.
"""
        assertTrue(runCatching {
            c.parse(md, """{"version":"1.0.0"}""")
        }.isFailure)
        assertTrue(runCatching {
            c.parse(md, null, packagePaths = listOf("SKILL.md", "skill.json"))
        }.isFailure)
        assertTrue(runCatching {
            c.parse(md, "", packagePaths = listOf("SKILL.md", "skill.json"))
        }.isFailure)

        val parsed = c.parse(
            md,
            """{"version":"1.0.0"}""",
            packagePaths = listOf("SKILL.md", "skill.json"),
        )
        assertEquals("1.0.0", parsed.manifest.version)
    }

    @Test fun executableOrUnknownManifestFieldsFailClosed() {
        val md = """---
name: safe-skill
description: Safe skill.
---
Instructions.
"""
        listOf(
            """{"scripts":["install.sh"]}""",
            """{"entrypoint":"run"}""",
            """{"unknownPermission":true}"""
        ).forEach { json ->
            assertTrue(runCatching { c.parse(md, json,
                packagePaths = listOf("SKILL.md", "skill.json")) }.isFailure)
        }
    }

    @Test fun githubOriginRequiresPublicGithubUrlAndImmutableCommit() {
        val md = """---
name: pinned-skill
description: Pinned skill.
---
Instructions.
"""
        val pinned = WorkspaceSkillContract.Provenance(
            WorkspaceSkillContract.Origin.GITHUB_PINNED,
            "https://github.com/example/repo/blob/main/SKILL.md",
            "1234567890abcdef1234567890abcdef12345678",
        )
        assertEquals(WorkspaceSkillContract.Origin.GITHUB_PINNED, c.parse(md, provenance = pinned).provenance.origin)

        assertTrue(runCatching {
            c.parse(md, provenance = pinned.copy(pinnedRevision = "main"))
        }.isFailure)
        assertTrue(runCatching {
            c.parse(md, provenance = pinned.copy(sourceUrl = "https://example.com/SKILL.md"))
        }.isFailure)
    }

    @Test fun frontmatterAndDomainsAreFailClosed() {
        val badDocs = listOf(
            "name: missing-frontmatter",
            """---
description: no name
---
Body""",
            """---
name: Bad Name
description: bad
---
Body""",
            """---
name: x
name: y
description: duplicate
---
Body"""
        )
        badDocs.forEach { assertTrue(runCatching { c.parse(it) }.isFailure) }

        val md = """---
name: network-skill
description: Network skill.
---
Body
"""
        listOf("localhost", "127.0.0.1", "*.example.com", "https://example.com", "api.internal")
            .forEach { domain ->
                val json = """{"networkDomains":["$domain"]}"""
                assertTrue("Unsafe domain accepted: $domain", runCatching {
                    c.parse(md, json, packagePaths = listOf("SKILL.md", "skill.json"))
                }.isFailure)
            }
    }
}
