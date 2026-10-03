package com.myra.assistant.ui.workspace

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Private sidecar persistence for evidence-backed skill overlays.
 *
 * Imported skill packages remain immutable. This store writes only overlay metadata plus bounded
 * evidence records (refs/hashes/outcomes), never project source, provider prompts, keys or skill body.
 */
internal class WorkspaceSkillOverlayStore(
    private val root: File,
) {
    data class Saved(
        val skillName: String,
        val overlay: WorkspaceSkillOverlay.Overlay,
        val evidence: List<WorkspaceSkillImprovementEvidence.Record>,
        val evidenceSha256: String,
        val savedAtMs: Long,
    )

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val DIRECTORY = "overlays"
        private const val MAX_FILE_BYTES = 256 * 1024L
        private const val MAX_EVIDENCE = 32
        private val NAME = Regex("""[a-z0-9][a-z0-9-]{0,63}""")
        private val SHA = Regex("""[0-9a-f]{64}""")
    }

    private fun base(): File {
        require(root.mkdirs() || root.isDirectory) { "Skill storage root is unavailable" }
        require(!Files.isSymbolicLink(root.toPath())) { "Skill storage root link is forbidden" }
        return root.canonicalFile
    }

    private fun overlaysRoot(): File {
        val parent = base()
        val dir = File(parent, DIRECTORY)
        require(!Files.isSymbolicLink(dir.toPath())) { "Skill overlay root link is forbidden" }
        require(dir.mkdirs() || dir.isDirectory) { "Skill overlay root is unavailable" }
        require(dir.canonicalFile.parentFile == parent) { "Skill overlay root escaped storage" }
        return dir.canonicalFile
    }

    private fun overlayFile(name: String): File {
        require(NAME.matches(name)) { "Invalid skill overlay name" }
        val dir = overlaysRoot()
        val file = File(dir, "$name.json")
        require(!Files.isSymbolicLink(file.toPath())) { "Skill overlay link is forbidden" }
        require(file.canonicalFile.parentFile == dir) { "Skill overlay path escaped storage" }
        return file.canonicalFile
    }

    private fun evidenceJson(record: WorkspaceSkillImprovementEvidence.Record): JSONObject =
        JSONObject()
            .put("ref", record.ref)
            .put("skillName", record.skillName)
            .put("baseContentSha256", record.baseContentSha256)
            .put("kind", record.kind.name)
            .put("signal", record.signal.name)
            .put("capturedAtMs", record.capturedAtMs)
            .put("sourceRevision", record.sourceRevision ?: JSONObject.NULL)

    private fun overlayJson(overlay: WorkspaceSkillOverlay.Overlay): JSONObject =
        JSONObject()
            .put("baseContentSha256", overlay.baseContentSha256)
            .put("descriptionOverride", overlay.descriptionOverride ?: JSONObject.NULL)
            .put("examples", JSONArray(overlay.examples))
            .put("evidenceRefs", JSONArray(overlay.evidenceRefs))
            .put("createdAtMs", overlay.createdAtMs)

    private fun encode(saved: Saved): String =
        JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("skillName", saved.skillName)
            .put("overlay", overlayJson(saved.overlay))
            .put("evidence", JSONArray(saved.evidence.map(::evidenceJson)))
            .put("evidenceSha256", saved.evidenceSha256)
            .put("savedAtMs", saved.savedAtMs)
            .toString()

    private fun stringList(array: JSONArray, label: String, max: Int): List<String> {
        require(array.length() <= max) { "$label exceeds its bound" }
        return buildList {
            for (i in 0 until array.length()) {
                val value = array.getString(i)
                require(value.isNotBlank()) { "$label contains an empty value" }
                add(value)
            }
        }
    }

    private fun decode(skill: WorkspaceSkillContract.ParsedSkill, raw: String): Saved {
        require(raw.toByteArray().size <= MAX_FILE_BYTES) { "Skill overlay sidecar is too large" }
        val rootJson = runCatching { JSONObject(raw) }
            .getOrElse { throw IllegalArgumentException("Skill overlay sidecar is invalid JSON") }
        require(rootJson.optInt("schemaVersion") == SCHEMA_VERSION) {
            "Unsupported skill overlay sidecar version"
        }
        require(rootJson.getString("skillName") == skill.name) {
            "Skill overlay sidecar belongs to another skill"
        }

        val o = rootJson.getJSONObject("overlay")
        val description = o.optString("descriptionOverride")
            .takeIf { it.isNotBlank() && it != "null" }
        val overlay = WorkspaceSkillOverlay.Overlay(
            baseContentSha256 = o.getString("baseContentSha256"),
            descriptionOverride = description,
            examples = stringList(o.getJSONArray("examples"), "Skill overlay examples", 8),
            evidenceRefs = stringList(o.getJSONArray("evidenceRefs"), "Skill overlay evidence refs", 32),
            createdAtMs = o.getLong("createdAtMs"),
        )

        val evidenceArray = rootJson.getJSONArray("evidence")
        require(evidenceArray.length() in 1..MAX_EVIDENCE) {
            "Skill overlay evidence record count is invalid"
        }
        val evidence = buildList {
            for (i in 0 until evidenceArray.length()) {
                val e = evidenceArray.getJSONObject(i)
                add(WorkspaceSkillImprovementEvidence.validate(
                    WorkspaceSkillImprovementEvidence.Record(
                        ref = e.getString("ref"),
                        skillName = e.getString("skillName"),
                        baseContentSha256 = e.getString("baseContentSha256"),
                        kind = WorkspaceSkillImprovementEvidence.Kind.valueOf(e.getString("kind")),
                        signal = WorkspaceSkillImprovementEvidence.Signal.valueOf(e.getString("signal")),
                        capturedAtMs = e.getLong("capturedAtMs"),
                        sourceRevision = e.optString("sourceRevision")
                            .takeIf { it.isNotBlank() && it != "null" },
                    )
                ))
            }
        }
        val storedDigest = rootJson.getString("evidenceSha256")
        require(SHA.matches(storedDigest)) { "Skill overlay evidence digest is invalid" }
        val savedAt = rootJson.getLong("savedAtMs")
        require(savedAt >= 0L) { "Skill overlay saved timestamp is invalid" }

        val promotion = WorkspaceSkillOverlayPromotion.evaluate(skill, overlay, evidence)
        require(promotion.evidenceSha256 == storedDigest) {
            "Skill overlay evidence digest changed"
        }
        return Saved(
            skillName = skill.name,
            overlay = overlay,
            evidence = evidence.sortedBy { it.ref },
            evidenceSha256 = storedDigest,
            savedAtMs = savedAt,
        )
    }

    private fun atomicWrite(file: File, text: String) {
        val bytes = text.toByteArray()
        require(bytes.size.toLong() <= MAX_FILE_BYTES) { "Skill overlay sidecar exceeds its bound" }
        val temp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        require(temp.canonicalFile.parentFile == file.parentFile) { "Skill overlay temp path escaped storage" }
        temp.writeBytes(bytes)
        try {
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    @Synchronized fun save(
        skill: WorkspaceSkillContract.ParsedSkill,
        overlay: WorkspaceSkillOverlay.Overlay,
        evidence: Collection<WorkspaceSkillImprovementEvidence.Record>,
        savedAtMs: Long,
    ): Saved {
        require(savedAtMs >= 0L) { "Skill overlay saved timestamp is invalid" }
        val promotion = WorkspaceSkillOverlayPromotion.evaluate(skill, overlay, evidence)
        val byRef = evidence.associateBy { it.ref }
        val selected = overlay.evidenceRefs.map { ref ->
            byRef[ref] ?: throw IllegalArgumentException("Skill overlay evidence ref disappeared: $ref")
        }.sortedBy { it.ref }
        val saved = Saved(
            skillName = skill.name,
            overlay = overlay,
            evidence = selected,
            evidenceSha256 = promotion.evidenceSha256,
            savedAtMs = savedAtMs,
        )
        atomicWrite(overlayFile(skill.name), encode(saved))
        return requireNotNull(load(skill)) { "Saved skill overlay could not be reopened" }
    }

    @Synchronized fun load(
        skill: WorkspaceSkillContract.ParsedSkill,
    ): Saved? {
        val file = overlayFile(skill.name)
        if (!file.exists()) return null
        require(file.isFile && file.length() in 1..MAX_FILE_BYTES) {
            "Skill overlay sidecar is unavailable or too large"
        }
        return decode(skill, file.readText())
    }

    @Synchronized fun clear(skillName: String) {
        val file = overlayFile(skillName)
        if (file.exists()) require(file.delete()) { "Skill overlay sidecar could not be removed" }
    }
}
