package com.myra.assistant.ui.workspace

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class WorkspaceAgentReachPolicyTest {
    private val p = WorkspaceAgentReachPolicy

    @Test fun classifiesSupportedPlatformsAndGithubKinds() {
        val repo = p.parse("https://github.com/browser-use/jev-ultrafast")
        assertEquals(WorkspaceAgentReachPolicy.Platform.GITHUB, repo.platform)
        assertEquals(WorkspaceAgentReachPolicy.GitHubKind.REPOSITORY, repo.githubKind)
        assertEquals("browser-use", repo.githubOwner)
        assertEquals("jev-ultrafast", repo.githubRepo)

        assertEquals(WorkspaceAgentReachPolicy.GitHubKind.BLOB,
            p.parse("https://github.com/a/b/blob/main/README.md#L10").githubKind)
        assertEquals(WorkspaceAgentReachPolicy.GitHubKind.RAW_FILE,
            p.parse("https://raw.githubusercontent.com/a/b/main/file.txt").githubKind)
        assertEquals(WorkspaceAgentReachPolicy.Platform.YOUTUBE,
            p.parse("https://youtu.be/abc").platform)
        assertEquals(WorkspaceAgentReachPolicy.Platform.REDDIT,
            p.parse("https://www.reddit.com/r/test/").platform)
        assertEquals(WorkspaceAgentReachPolicy.Platform.X,
            p.parse("https://x.com/example").platform)
        assertEquals(WorkspaceAgentReachPolicy.Platform.WEB,
            p.parse("https://example.com/docs").platform)
    }

    @Test fun publicReadUrlFailsClosedForCredentialsLocalHostsAndUnsafeSchemes() {
        listOf(
            "http://example.com",
            "https://user:pass@example.com/docs",
            "https://localhost/docs",
            "https://api.internal/docs",
            "https://127.0.0.1/docs",
            "https://0177.0.0.1/docs",
            "https://0x7f000001/docs",
            "https://169.254.169.254/latest/meta-data/",
            "https://metadata.google.internal/",
            "ftp://example.com/file",
            "https://example.com:8443/docs",
            "https://example.com/docs?access_token=secret",
            "https://example.com/docs?api%5Fkey=secret",
        ).forEach { url ->
            assertTrue("Unsafe URL accepted: $url", runCatching { p.parse(url) }.isFailure)
        }
    }

    @Test fun benignQueryIsPreservedButFragmentIsOnlyLocalProvenance() {
        val target = p.parse("https://github.com/a/b?tab=readme#section")
        assertEquals("https://github.com/a/b?tab=readme", target.canonicalUrl)
        assertEquals("section", target.fragment)
    }

    @Test fun githubRedirectMustStayInsideGithubCapabilityFamily() {
        val start = p.parse("https://github.com/a/b/blob/main/file.txt")
        val raw = p.validateRedirect(
            start, "https://raw.githubusercontent.com/a/b/main/file.txt")
        assertEquals(WorkspaceAgentReachPolicy.Platform.GITHUB, raw.platform)
        assertTrue(runCatching {
            p.validateRedirect(start, "https://example.com/file.txt")
        }.isFailure)
    }

    @Test fun dnsResultsRejectPrivateReservedAndMappedAddresses() {
        val blocked = listOf(
            "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1",
            "169.254.1.1", "100.64.0.1", "0.0.0.0", "224.0.0.1",
            "::1", "fd00::1", "2001:db8::1", "::ffff:127.0.0.1",
        )
        blocked.forEach { ip ->
            assertTrue("Expected blocked resolved IP: $ip",
                p.unsafeResolvedAddress(InetAddress.getByName(ip)))
        }
        assertFalse(p.unsafeResolvedAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(p.unsafeResolvedAddress(InetAddress.getByName("1.1.1.1")))
    }

    @Test fun resolvedAddressValidationRejectsMixedPublicPrivateAnswer() {
        assertTrue(runCatching {
            p.validateResolvedAddresses(
                "example.com",
                listOf(InetAddress.getByName("8.8.8.8"),
                    InetAddress.getByName("127.0.0.1")))
        }.isFailure)
        p.validateResolvedAddresses(
            "example.com",
            listOf(InetAddress.getByName("8.8.8.8")))
    }
}
