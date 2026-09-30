package com.myra.assistant.ui.workspace

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Bounded private sidecar for verified workflow experience evidence.
 *
 * This is not a second memory brain/database. It is append/idempotent execution evidence only;
 * future behavior changes still require separate learning/promotion gates.
 */
internal class WorkspaceWorkflowExperienceStore(
    private val root: File,
) {
    companion object {
        private const val SCHEMA = 1
        private const val FILE = "verified-workflows.json"
        private const val MAX_RECORDS = 64
        private const val MAX_BYTES = 256 * 1024L
    }

    private fun base(): File {
        require(root.mkdirs() || root.isDirectory) { "Workflow experience root is unavailable" }
        require(!Files.isSymbolicLink(root.toPath())) {
            "Workflow experience root link is forbidden"
        }
        return root.canonicalFile
    }

    private fun file(): File {
        val parent = base()
        val value = File(parent, FILE)
        require(!Files.isSymbolicLink(value.toPath())) {
            "Workflow experience file link is forbidden"
        }
        require(value.canonicalFile.parentFile == parent) {
            "Workflow experience path escaped storage"
        }
        return value.canonicalFile
    }

    private fun decode(raw: String): List<WorkspaceWorkflowExperience.Record> {
        require(raw.toByteArray().size <= MAX_BYTES) {
            "Workflow experience store is too large"
        }
        val rootJson = JSONObject(raw)
        require(rootJson.getInt("schema") == SCHEMA) {
            "Unsupported workflow experience schema"
        }
        val array = rootJson.getJSONArray("records")
        require(array.length() <= MAX_RECORDS) {
            "Workflow experience record count is invalid"
        }
        val records = buildList {
            for (i in 0 until array.length()) {
                add(requireNotNull(
                    WorkspaceWorkflowExperience.fromJson(array.getJSONObject(i))
                ) { "Workflow experience record is invalid" })
            }
        }
        require(records.map { it.id }.distinct().size == records.size) {
            "Workflow experience IDs must be unique"
        }
        return records
    }

    private fun encode(records: List<WorkspaceWorkflowExperience.Record>): String {
        require(records.size <= MAX_RECORDS) { "Workflow experience store exceeds its bound" }
        require(records.map { it.id }.distinct().size == records.size) {
            "Workflow experience IDs must be unique"
        }
        val text = JSONObject()
            .put("schema", SCHEMA)
            .put("records", JSONArray(records.map(WorkspaceWorkflowExperience::toJson)))
            .toString()
        require(text.toByteArray().size <= MAX_BYTES) {
            "Workflow experience store exceeds its byte bound"
        }
        return text
    }

    private fun atomicWrite(target: File, text: String) {
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        require(temp.canonicalFile.parentFile == target.parentFile) {
            "Workflow experience temp path escaped storage"
        }
        try {
            java.io.FileOutputStream(temp).use { out ->
                out.write(text.toByteArray())
                out.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    @Synchronized fun list(): List<WorkspaceWorkflowExperience.Record> {
        val target = file()
        if (!target.exists()) return emptyList()
        require(target.isFile && target.length() in 1..MAX_BYTES) {
            "Workflow experience store is unavailable or too large"
        }
        return decode(target.readText())
    }

    @Synchronized fun record(
        record: WorkspaceWorkflowExperience.Record,
    ): WorkspaceWorkflowExperience.Record {
        val safe = WorkspaceWorkflowExperience.validate(record)
        val current = list().filterNot { it.id == safe.id }
        val next = (current + safe)
            .sortedWith(compareBy<WorkspaceWorkflowExperience.Record> { it.capturedAtMs }
                .thenBy { it.id })
            .takeLast(MAX_RECORDS)
        atomicWrite(file(), encode(next))
        val reopened = list().firstOrNull { it.id == safe.id }
            ?: throw IllegalStateException("Workflow experience could not be verified after write")
        require(reopened == safe) { "Workflow experience changed after persistence" }
        return reopened
    }
}
