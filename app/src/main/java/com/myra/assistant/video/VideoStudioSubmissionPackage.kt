package com.myra.assistant.video

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Slice 8: explicitly reviewed, offline manual dispatch package.
 *
 * This is NOT an authenticated submission, webhook, signing mechanism,
 * render job, or token authorization. A SHA-256 fingerprint only identifies
 * the exact approved, non-sensitive input bytes for manual comparison.
 */
internal data class VideoStudioSubmissionPackage(
    val projectId: String,
    val floors: Int,
    val color: String,
    val roof: String,
    val garden: Boolean,
    val targetSeconds: Int,
) {
    fun inputs(): LinkedHashMap<String, String> = linkedMapOf(
        "project_id" to projectId,
        "floors" to floors.toString(),
        "color" to color,
        "roof" to roof,
        "garden" to if (garden) "yes" else "no",
        "target" to targetSeconds.toString(),
    )

    private fun canonicalInput(): String =
        inputs().entries.joinToString(separator = "\n", postfix = "\n",
            prefix = "lyra-video-render-manual-v1\n") { (key, value) -> "$key=$value" }

    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalInput().toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    fun humanReview(): String = buildString {
        appendLine("Review manual GitHub 3D render inputs")
        appendLine()
        inputs().forEach { (key, value) -> appendLine("$key: $value") }
        appendLine()
        appendLine("Requested Shorts goal: " + targetSeconds + "s")
        appendLine("Actual Blender test output: SIX seconds, 360x640, 12 fps")
        appendLine()
        appendLine("Approval fingerprint (not a security signature):")
        appendLine(fingerprint())
        appendLine()
        appendLine("Copying these fields does NOT submit a GitHub job.")
        appendLine("Only a GitHub-authorized owner can submit after this workflow")
        appendLine("has been separately approved for default-branch deployment.")
        appendLine("GitHub runner quotas and temporary artifacts apply.")
        appendLine("No free-text prompt, API key or personal conversation is included.")
    }.trimEnd()

    fun copyableInputs(): String = buildString {
        appendLine("LYRA approved manual Blender test inputs — no render started")
        inputs().forEach { (key, value) -> appendLine("$key: $value") }
        appendLine("fingerprint (reference only): " + fingerprint())
        append("Only enter the six input fields in the official GitHub workflow.")
    }
}

internal object VideoStudioSubmissionFactory {
    private val safeProject = Regex("^[A-Za-z0-9_-]{1,80}$")

    fun prepare(request: VideoStudioRenderRequest): VideoStudioSubmissionPackage {
        require(safeProject.matches(request.projectId)) {
            "Render job project ID must contain 1–80 safe characters"
        }
        require(request.targetSeconds in setOf(30, 60)) {
            "Only a future Shorts target of 30 or 60 seconds is supported"
        }
        require(request.spec.floors in 1..2) { "Only 1–2 floors are supported" }
        require(request.spec.wallColor in setOf("cream", "blue", "terracotta")) {
            "Unsupported wall color"
        }
        require(request.spec.roofStyle in setOf("flat", "gable")) {
            "Unsupported roof shape"
        }
        return VideoStudioSubmissionPackage(
            projectId = request.projectId,
            floors = request.spec.floors,
            color = request.spec.wallColor,
            roof = request.spec.roofStyle,
            garden = request.spec.garden,
            targetSeconds = request.targetSeconds,
        )
    }
}
