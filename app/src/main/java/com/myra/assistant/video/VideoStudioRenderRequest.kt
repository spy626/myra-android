package com.myra.assistant.video

import org.json.JSONObject

/**
 * Slice 5: local-only reviewed 6s Blender test request.
 * No network submission, credentials, job queue, or MP4 creation.
 * The renderer understands ONLY listed mini-house attributes.
 */
internal data class VideoStudioHouseSpec(
    val floors: Int, val wallColor: String, val roofStyle: String, val garden: Boolean,
)

internal data class VideoStudioRenderRequest(
    val projectId: String, val targetSeconds: Int, val spec: VideoStudioHouseSpec,
) {
    fun toJson(): String = JSONObject()
        .put("request_version", 1)
        .put("renderer", "blender-mini-house-v1")
        .put("project_id", projectId)
        .put("mode", "construction")
        .put("target_duration_seconds", targetSeconds)
        .put("preview_duration_seconds", 6)
        .put("width", 360)
        .put("height", 640)
        .put("fps", 12)
        .put("spec", JSONObject()
            .put("floors", spec.floors)
            .put("wall_color", spec.wallColor)
            .put("roof_style", spec.roofStyle)
            .put("garden", spec.garden))
        .toString(2)
}

internal object VideoStudioRenderRequestFactory {
    private val validProjectId = Regex("^[A-Za-z0-9_-]{1,80}$")
    private val houseWord = Regex("\\b(house|home|ghar)\\b")
    private val unsupportedFloors =
        Regex("\\b(three|four|five|six|seven|eight|nine|ten|[3-9]|10)\\s*(floor|storey|story|level)s?\\b")
    private val firstFloor =
        Regex("\\b(one|single|1|ek)\\s*(floor|storey|story|level)s?\\b")
    private val secondFloor =
        Regex("\\b(two|double|2|do)\\s*(floor|storey|story|level)s?\\b")
    private val wallColors = listOf("cream", "blue", "terracotta")
    private val flatRoof = Regex("\\bflat\\s*roof\\b")
    private val gableRoof = Regex("\\b(gable|pitched|sloped)\\s*roof\\b")
    private val omitGarden = Regex("\\b(without|no)\\s+(a\\s+)?garden\\b")

    fun fromDraft(draft: VideoStudioDraft): VideoStudioRenderRequest {
        require(draft.kind == VideoStudioKind.CONSTRUCTION) {
            "Only mini-house construction has a 3D renderer prototype"
        }
        require(validProjectId.matches(draft.projectId)) { "Invalid project identity" }
        require(draft.durationSeconds == 30 || draft.durationSeconds == 60) {
            "Target Shorts length must be 30 or 60 seconds"
        }
        val raw = draft.prompt
        require(raw.isNotBlank() && raw.length <= 500 && raw.none { it.isISOControl() }) {
            "Mini-house test brief must be 1-500 printable characters"
        }
        val words = raw.lowercase(java.util.Locale.ROOT).trim()
            .replace(Regex("[\\s_-]+"), " ")
        require(houseWord.containsMatchIn(words)) { "Brief must mention house, home, or ghar" }
        require(!unsupportedFloors.containsMatchIn(words)) {
            "Only 1- or 2-floor houses are supported"
        }
        val one = firstFloor.containsMatchIn(words)
        val two = secondFloor.containsMatchIn(words)
        require(!(one && two)) { "Conflicting floor counts" }
        val colors = wallColors.filter { color ->
            Regex("\\b" + color + "\\b").containsMatchIn(words)
        }
        require(colors.size <= 1) { "Choose one supported wall color" }
        val flat = flatRoof.containsMatchIn(words)
        val gable = gableRoof.containsMatchIn(words)
        require(!(flat && gable)) { "Conflicting roof styles" }
        return VideoStudioRenderRequest(
            projectId = draft.projectId,
            targetSeconds = draft.durationSeconds,
            spec = VideoStudioHouseSpec(
                floors = if (one) 1 else 2,
                wallColor = colors.firstOrNull() ?: "cream",
                roofStyle = if (gable) "gable" else "flat",
                garden = !omitGarden.containsMatchIn(words),
            ),
        )
    }

    fun reviewText(request: VideoStudioRenderRequest): String = buildString {
        appendLine("Review MINI-HOUSE 3D test request")
        appendLine()
        appendLine("Floors: " + request.spec.floors)
        appendLine("Wall color: " + request.spec.wallColor)
        appendLine("Roof style: " + request.spec.roofStyle)
        appendLine("Garden: " + (if (request.spec.garden) "yes" else "no"))
        appendLine()
        appendLine("Real render TEST: 6 seconds, 360x640, 12 fps")
        appendLine("Shorts target: " + request.targetSeconds + "s (NOT rendered yet)")
        appendLine()
        appendLine("Only the options above affect Blender. Other words are ignored.")
        appendLine("Sharing JSON does NOT start rendering or upload a video.")
        append("No API key or private conversation content is included.")
    }
}
