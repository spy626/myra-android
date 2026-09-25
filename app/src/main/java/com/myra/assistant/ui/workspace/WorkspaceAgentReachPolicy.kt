package com.myra.assistant.ui.workspace

import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/**
 * Read-only public URL admission contract for Agent Reach.
 *
 * This does not fetch content or grant browser/action authority. Every future adapter must re-check
 * DNS results and every redirect with this policy before making the next request.
 */
internal object WorkspaceAgentReachPolicy {
    private const val MAX_URL_CHARS = 2_048
    private const val MAX_FRAGMENT_CHARS = 256

    enum class Platform { GITHUB, YOUTUBE, REDDIT, X, WEB }
    enum class GitHubKind {
        REPOSITORY, BLOB, TREE, COMMIT, PULL, ISSUE, RAW_FILE, OTHER
    }

    data class Target(
        val originalUrl: String,
        val canonicalUrl: String,
        val platform: Platform,
        val host: String,
        val fragment: String?,
        val githubKind: GitHubKind? = null,
        val githubOwner: String? = null,
        val githubRepo: String? = null,
        val readOnly: Boolean = true,
    )

    private val blockedHosts = setOf(
        "169.254.169.254",
        "metadata.google.internal",
        "metadata.google.com",
        "100.100.100.200",
    )
    private val credentialQueryNames = Regex(
        "(?i)^(?:token|access_token|api[_-]?key|key|auth|authorization|secret|password|" +
            "passwd|credential|credentials|signature|sig|code|id_token|refresh_token|" +
            "x-amz-credential|x-amz-signature|x-goog-signature)$"
    )
    private val numericHostLike = Regex(
        """(?i)^(?:0x[0-9a-f]+|[0-9]+)(?:\.(?:0x[0-9a-f]+|[0-9]+)){0,3}$"""
    )

    private fun hostMatches(host: String, root: String): Boolean =
        host == root || host.endsWith(".$root")

    private fun normalizedHost(uri: URI): String {
        val raw = uri.host ?: throw IllegalArgumentException("Agent Reach URL needs a host")
        val normalized = Normalizer.normalize(raw.trim().trimEnd('.'), Normalizer.Form.NFKC)
        val ascii = runCatching { IDN.toASCII(normalized) }
            .getOrElse { throw IllegalArgumentException("Agent Reach host is invalid") }
            .lowercase(Locale.US)
        require(ascii.isNotBlank() && ascii.length <= 253) { "Agent Reach host is invalid" }
        return ascii
    }

    internal fun isBlockedHost(hostRaw: String): Boolean {
        val host = hostRaw.trim().removePrefix("[").removeSuffix("]")
            .trimEnd('.').lowercase(Locale.US)
        if (host in blockedHosts) return true
        if (host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".lan") ||
            host.endsWith(".home") || host.endsWith(".internal")) return true
        // First public-read phase rejects every literal/non-standard numeric IP host.
        if (':' in host || numericHostLike.matches(host)) return true
        return false
    }

    private fun queryContainsCredential(rawQuery: String?): Boolean {
        if (rawQuery.isNullOrBlank()) return false
        return rawQuery.split('&').any { part ->
            val rawName = part.substringBefore('=').trim()
            val decoded = runCatching {
                URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
            }.getOrDefault(rawName)
            credentialQueryNames.matches(decoded)
        }
    }

    private fun githubInfo(host: String, path: String): Triple<GitHubKind?, String?, String?> {
        if (host == "raw.githubusercontent.com") {
            val parts = path.trim('/').split('/').filter(String::isNotBlank)
            return Triple(GitHubKind.RAW_FILE, parts.getOrNull(0), parts.getOrNull(1))
        }
        if (host != "github.com") return Triple(null, null, null)
        val parts = path.trim('/').split('/').filter(String::isNotBlank)
        val owner = parts.getOrNull(0)
        val repo = parts.getOrNull(1)?.removeSuffix(".git")
        if (owner == null || repo == null) return Triple(GitHubKind.OTHER, owner, repo)
        val kind = when (parts.getOrNull(2)) {
            null -> GitHubKind.REPOSITORY
            "blob" -> GitHubKind.BLOB
            "tree" -> GitHubKind.TREE
            "commit" -> GitHubKind.COMMIT
            "pull" -> GitHubKind.PULL
            "issues" -> GitHubKind.ISSUE
            else -> GitHubKind.OTHER
        }
        return Triple(kind, owner, repo)
    }

    fun parse(raw: String): Target {
        val input = raw.trim()
        require(input.length in 8..MAX_URL_CHARS) { "Agent Reach URL is missing or too long" }
        val uri = runCatching { URI(input) }
            .getOrElse { throw IllegalArgumentException("Agent Reach URL is invalid") }
        require(uri.scheme?.lowercase(Locale.US) == "https") {
            "Agent Reach public reads require HTTPS"
        }
        require(uri.userInfo == null) { "Credentials are not allowed inside Agent Reach URLs" }
        require(uri.port == -1 || uri.port == 443) { "Agent Reach public reads use HTTPS port 443 only" }
        require(uri.rawFragment == null || uri.rawFragment.length <= MAX_FRAGMENT_CHARS) {
            "Agent Reach URL fragment is too large"
        }
        require(!queryContainsCredential(uri.rawQuery)) {
            "Credential-like query parameters are blocked from Agent Reach"
        }

        val host = normalizedHost(uri)
        require(!isBlockedHost(host)) { "Agent Reach blocks local/private/metadata hosts" }
        val path = uri.rawPath?.ifBlank { "/" } ?: "/"
        val decodedPath = uri.path ?: path
        require(!decodedPath.split('/').any { it == "." || it == ".." }) {
            "Agent Reach URL contains unsafe dot-path traversal"
        }

        val platform = when {
            host in setOf("github.com", "raw.githubusercontent.com") -> Platform.GITHUB
            hostMatches(host, "youtube.com") || host == "youtu.be" -> Platform.YOUTUBE
            hostMatches(host, "reddit.com") -> Platform.REDDIT
            hostMatches(host, "x.com") || hostMatches(host, "twitter.com") -> Platform.X
            else -> Platform.WEB
        }
        val (githubKind, owner, repo) = githubInfo(host, path)
        val canonical = URI(
            "https", null, host, -1, path,
            uri.rawQuery?.takeIf { it.isNotBlank() }, null
        ).toASCIIString()
        return Target(
            originalUrl = input,
            canonicalUrl = canonical,
            platform = platform,
            host = host,
            fragment = uri.rawFragment,
            githubKind = githubKind,
            githubOwner = owner,
            githubRepo = repo,
        )
    }

    fun validateRedirect(previous: Target, redirectedUrl: String): Target {
        val next = parse(redirectedUrl)
        // A GitHub read may legitimately move between github.com and raw.githubusercontent.com,
        // but never silently leaves the GitHub capability family.
        if (previous.platform == Platform.GITHUB) {
            require(next.platform == Platform.GITHUB) {
                "GitHub Agent Reach redirect left the approved GitHub capability family"
            }
        }
        return next
    }

    private fun ipv4Unsafe(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return true
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        val c = bytes[2].toInt() and 0xff
        return a == 0 || a == 10 || a == 127 ||
            (a == 100 && b in 64..127) ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 0) ||
            (a == 192 && b == 168) ||
            (a == 192 && b == 0 && c == 2) ||
            (a == 198 && b in 18..19) ||
            (a == 198 && b == 51 && c == 100) ||
            (a == 203 && b == 0 && c == 113) ||
            a >= 224
    }

    internal fun unsafeResolvedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress) return true
        val bytes = address.address
        if (bytes.size == 4) return ipv4Unsafe(bytes)
        if (bytes.size != 16) return true

        // IPv4-mapped / IPv4-compatible IPv6.
        val firstTenZero = (0 until 10).all { bytes[it].toInt() == 0 }
        val mapped = firstTenZero &&
            (bytes[10].toInt() and 0xff) == 0xff && (bytes[11].toInt() and 0xff) == 0xff
        val compatible = (0 until 12).all { bytes[it].toInt() == 0 }
        if (mapped || compatible) return ipv4Unsafe(bytes.copyOfRange(12, 16))

        val b0 = bytes[0].toInt() and 0xff
        val b1 = bytes[1].toInt() and 0xff
        // fc00::/7 unique-local and 2001:db8::/32 documentation range.
        if ((b0 and 0xfe) == 0xfc) return true
        if (b0 == 0x20 && b1 == 0x01 &&
            (bytes[2].toInt() and 0xff) == 0x0d &&
            (bytes[3].toInt() and 0xff) == 0xb8) return true
        return false
    }

    fun validateResolvedAddresses(host: String, addresses: List<InetAddress>) {
        require(addresses.isNotEmpty()) { "Agent Reach host did not resolve" }
        require(addresses.none(::unsafeResolvedAddress)) {
            "Agent Reach host resolved to a local/private/reserved address"
        }
        require(!isBlockedHost(host)) { "Agent Reach host is blocked" }
    }
}
