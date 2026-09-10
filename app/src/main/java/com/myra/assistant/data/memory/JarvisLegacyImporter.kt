package com.myra.assistant.data.memory

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import java.text.Normalizer
import java.util.Locale

/**
 * One-time, read-only bridge from LYRA's retired stores into the JARVIS Room database.
 * After this completes, production reads and writes use only JarvisDatabase.
 */
object JarvisLegacyImporter {
    private const val PREFS = "jarvis_memory_migration"
    private const val VERSION_KEY = "import_version"
    private const val VERSION = 1
    private const val TAG = "JarvisLegacyImport"

    private val sensitive = Regex(
        "\\b(?:otp|password|passcode|pin|cvv|verification code|recovery code|api[ _-]?key|" +
            "private key|seed phrase|bank account|card number|aadhaar|aadhar|passport number|pan number)\\b",
        RegexOption.IGNORE_CASE
    )

    @Synchronized fun importOnce(context: Context) {
        val app = context.applicationContext
        val state = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (state.getInt(VERSION_KEY, 0) >= VERSION) return
        val dao = JarvisDatabase.getInstance(app).jarvisDao()

        runCatching { importAiriDatabase(app, dao) }
            .onFailure { Log.w(TAG, "AIRI import skipped: ${it.javaClass.simpleName}") }
        runCatching { importPreviousSimpleDatabase(app, dao) }
            .onFailure { Log.w(TAG, "Previous JARVIS import skipped: ${it.javaClass.simpleName}") }
        runCatching { importLegacyActionHistory(app, dao) }
            .onFailure { Log.w(TAG, "Action-history import skipped: ${it.javaClass.simpleName}") }

        state.edit().putInt(VERSION_KEY, VERSION).apply()
    }

    private fun importAiriDatabase(context: Context, dao: JarvisDao) {
        val file = context.getDatabasePath("lyra_memory.db")
        if (!file.exists()) return
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            if (tableExists(db, "airi_people")) {
                val people = mutableMapOf<String, String>()
                db.rawQuery("SELECT entityId, canonicalName FROM airi_people WHERE active=1 AND deletedAt IS NULL", null).use { cursor ->
                    while (cursor.moveToNext()) people[cursor.string("entityId")] = cursor.string("canonicalName")
                }

                if (tableExists(db, "airi_semantic_memory")) {
                    db.rawQuery(
                        "SELECT memoryId, semanticKey, category, statement, subjectEntityId, confidence, importance, provenance, sourceTurnId, sourceUtteranceId, createdAt, updatedAt, lastAccessed, accessCount FROM airi_semantic_memory WHERE active=1 AND deletedAt IS NULL ORDER BY updatedAt ASC",
                        null
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val statement = cursor.string("statement").trim()
                            if (statement.isBlank() || sensitive.containsMatchIn(statement)) continue
                            val category = cursor.string("category")
                            val type = legacyType(category)
                            val subjectId = cursor.nullableString("subjectEntityId")
                            val subject = subjectId?.let(people::get).orEmpty().ifBlank { "user" }
                            val key = cursor.string("semanticKey").ifBlank { "legacy:${cursor.string("memoryId")}" }
                            val createdAt = cursor.long("createdAt")
                            val updatedAt = cursor.long("updatedAt")
                            dao.upsertMemory(
                                JarvisMemoryEntity(
                                    memoryKey = key,
                                    memoryType = type.name,
                                    subject = subject,
                                    normalizedSubject = normalize(subject),
                                    value = "FACT|$statement",
                                    sourceText = statement,
                                    sourceKind = "LEGACY_AIRI:${cursor.string("provenance")}",
                                    sourceSessionId = "legacy-airi",
                                    sourceTurnId = cursor.long("sourceTurnId"),
                                    sourceUtteranceId = cursor.string("sourceUtteranceId"),
                                    confidence = cursor.float("confidence").coerceIn(0f, 1f),
                                    importance = cursor.int("importance").coerceIn(1, 10),
                                    active = true,
                                    createdAt = createdAt,
                                    updatedAt = updatedAt,
                                    lastAccessedAt = cursor.long("lastAccessed"),
                                    accessCount = cursor.int("accessCount").coerceAtLeast(0)
                                )
                            )
                        }
                    }
                }

                if (tableExists(db, "airi_relationships")) {
                    db.rawQuery(
                        "SELECT relationshipId, targetEntityId, relationshipType, confidence, provenance, sourceTurnId, sourceUtteranceId, createdAt, updatedAt, lastAccessed, accessCount FROM airi_relationships WHERE active=1 AND deletedAt IS NULL ORDER BY updatedAt ASC",
                        null
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = people[cursor.string("targetEntityId")]?.trim().orEmpty()
                            if (name.isBlank()) continue
                            val relation = cursor.string("relationshipType").uppercase(Locale.ROOT).let {
                                when (it) {
                                    "BEST_FRIEND" -> "BEST_FRIEND"
                                    "GOOD_FRIEND" -> "GOOD_FRIEND"
                                    else -> "FRIEND"
                                }
                            }
                            val sourceText = "$name is Zopy's ${relationshipLabel(relation)}"
                            dao.upsertMemory(
                                JarvisMemoryEntity(
                                    memoryKey = "relationship:${keyToken(name)}",
                                    memoryType = JarvisMemoryType.RELATIONSHIP.name,
                                    subject = name,
                                    normalizedSubject = normalize(name),
                                    value = "$relation|$name",
                                    sourceText = sourceText,
                                    sourceKind = "LEGACY_AIRI:${cursor.string("provenance")}",
                                    sourceSessionId = "legacy-airi",
                                    sourceTurnId = cursor.long("sourceTurnId"),
                                    sourceUtteranceId = cursor.string("sourceUtteranceId"),
                                    confidence = cursor.float("confidence").coerceIn(0f, 1f),
                                    importance = 9,
                                    active = true,
                                    createdAt = cursor.long("createdAt"),
                                    updatedAt = cursor.long("updatedAt"),
                                    lastAccessedAt = cursor.long("lastAccessed"),
                                    accessCount = cursor.int("accessCount").coerceAtLeast(0)
                                )
                            )
                        }
                    }
                }
            }

            if (tableExists(db, "airi_goals")) {
                db.rawQuery(
                    "SELECT goalId, stableKey, title, provenance, sourceTurnId, sourceUtteranceId, priority, createdAt, updatedAt, lastAccessed, accessCount FROM airi_goals WHERE deletedAt IS NULL AND status NOT IN ('COMPLETED','ABANDONED') ORDER BY updatedAt ASC",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val title = cursor.string("title").trim()
                        if (title.isBlank() || sensitive.containsMatchIn(title)) continue
                        dao.upsertMemory(
                            JarvisMemoryEntity(
                                memoryKey = cursor.string("stableKey").ifBlank { "goal:${cursor.string("goalId")}" },
                                memoryType = JarvisMemoryType.GOAL.name,
                                subject = "user",
                                normalizedSubject = "user",
                                value = "FACT|$title",
                                sourceText = title,
                                sourceKind = "LEGACY_AIRI:${cursor.string("provenance")}",
                                sourceSessionId = "legacy-airi",
                                sourceTurnId = cursor.long("sourceTurnId"),
                                sourceUtteranceId = cursor.string("sourceUtteranceId"),
                                confidence = 1f,
                                importance = (6 + cursor.int("priority")).coerceIn(1, 10),
                                active = true,
                                createdAt = cursor.long("createdAt"),
                                updatedAt = cursor.long("updatedAt"),
                                lastAccessedAt = cursor.long("lastAccessed"),
                                accessCount = cursor.int("accessCount").coerceAtLeast(0)
                            )
                        )
                    }
                }
            }

            if (tableExists(db, "airi_conversation_truth")) {
                db.rawQuery(
                    "SELECT messageId, sessionId, turnId, utteranceId, role, content, committedAt FROM airi_conversation_truth ORDER BY committedAt ASC",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val content = cursor.string("content").trim()
                        if (content.isBlank()) continue
                        val messageId = cursor.string("messageId")
                        dao.insertMessage(
                            JarvisMessageEntity(
                                sender = cursor.string("role").ifBlank { "user" },
                                content = content,
                                timestamp = cursor.long("committedAt"),
                                messageKey = "legacy-airi:$messageId",
                                sessionId = cursor.string("sessionId").ifBlank { "legacy-airi" },
                                turnId = cursor.long("turnId"),
                                utteranceId = cursor.string("utteranceId").ifBlank { messageId },
                                sourceKind = JarvisMemorySource.LEGACY.name
                            )
                        )
                    }
                }
            }
        }
    }

    private fun importPreviousSimpleDatabase(context: Context, dao: JarvisDao) {
        val file = context.getDatabasePath("jarvis_simple_memory.db")
        if (!file.exists()) return
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            if (tableExists(db, "messages")) {
                db.rawQuery(
                    "SELECT messageKey, sessionId, turnId, utteranceId, sender, content, sourceKind, timestamp FROM messages ORDER BY timestamp ASC",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        dao.insertMessage(
                            JarvisMessageEntity(
                                sender = cursor.string("sender"),
                                content = cursor.string("content"),
                                timestamp = cursor.long("timestamp"),
                                messageKey = "legacy-simple:${cursor.string("messageKey")}",
                                sessionId = cursor.string("sessionId"),
                                turnId = cursor.long("turnId"),
                                utteranceId = cursor.string("utteranceId"),
                                sourceKind = cursor.string("sourceKind").ifBlank { JarvisMemorySource.LEGACY.name }
                            )
                        )
                    }
                }
            }
            if (tableExists(db, "memories")) {
                db.rawQuery(
                    "SELECT memoryKey, memoryType, subject, value, sourceText, sourceKind, sourceSessionId, sourceTurnId, sourceUtteranceId, confidence, importance, active, createdAt, updatedAt, lastAccessedAt, accessCount FROM memories WHERE active=1 ORDER BY updatedAt ASC",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val sourceText = cursor.string("sourceText")
                        if (sensitive.containsMatchIn(sourceText)) continue
                        val subject = cursor.string("subject").ifBlank { "user" }
                        dao.upsertMemory(
                            JarvisMemoryEntity(
                                memoryKey = cursor.string("memoryKey"),
                                memoryType = cursor.string("memoryType"),
                                subject = subject,
                                normalizedSubject = normalize(subject),
                                value = cursor.string("value"),
                                sourceText = sourceText,
                                sourceKind = cursor.string("sourceKind").ifBlank { JarvisMemorySource.LEGACY.name },
                                sourceSessionId = cursor.string("sourceSessionId"),
                                sourceTurnId = cursor.long("sourceTurnId"),
                                sourceUtteranceId = cursor.string("sourceUtteranceId"),
                                confidence = cursor.float("confidence").coerceIn(0f, 1f),
                                importance = cursor.int("importance").coerceIn(1, 10),
                                active = true,
                                createdAt = cursor.long("createdAt"),
                                updatedAt = cursor.long("updatedAt"),
                                lastAccessedAt = cursor.long("lastAccessedAt"),
                                accessCount = cursor.int("accessCount").coerceAtLeast(0)
                            )
                        )
                    }
                }
            }
            if (tableExists(db, "command_logs")) {
                db.rawQuery(
                    "SELECT rawCommand, intentType, resultText, success, verified, sourceKind, timestamp FROM command_logs ORDER BY timestamp ASC",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        dao.insertLog(
                            JarvisCommandLogEntity(
                                rawCommand = cursor.string("rawCommand"),
                                intentType = cursor.string("intentType"),
                                resultText = cursor.string("resultText"),
                                isSuccess = cursor.int("success") != 0,
                                timestamp = cursor.long("timestamp"),
                                verified = cursor.int("verified") != 0,
                                sourceKind = cursor.string("sourceKind").ifBlank { JarvisMemorySource.LEGACY.name }
                            )
                        )
                    }
                }
            }
        }
    }

    private fun importLegacyActionHistory(context: Context, dao: JarvisDao) {
        val prefs = context.getSharedPreferences("myra_action_history", Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(prefs.getString("entries", "[]")) }.getOrNull() ?: return
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val action = entry.optString("action").trim()
            if (action.isBlank()) continue
            val target = entry.optString("target").takeIf { it.isNotBlank() && it != "null" }
            val success = entry.optBoolean("success", false)
            dao.insertLog(
                JarvisCommandLogEntity(
                    rawCommand = listOfNotNull(action, target).joinToString(" "),
                    intentType = action,
                    resultText = if (success) "Action completed" else "Action failed",
                    isSuccess = success,
                    timestamp = entry.optLong("time", System.currentTimeMillis()),
                    verified = entry.optBoolean("verified", false),
                    sourceKind = JarvisMemorySource.LEGACY.name
                )
            )
        }
    }

    private fun legacyType(category: String): JarvisMemoryType = when (category.uppercase(Locale.ROOT)) {
        "PREFERENCE", "COMMUNICATION_STYLE", "CONTENT_INTEREST", "CURRENT_INTEREST" -> JarvisMemoryType.PREFERENCE
        "PROJECT", "WORKFLOW", "SOLUTION" -> JarvisMemoryType.PROJECT
        "GOAL" -> JarvisMemoryType.GOAL
        "IDENTITY" -> JarvisMemoryType.IDENTITY
        else -> JarvisMemoryType.NOTE
    }

    private fun relationshipLabel(value: String) = when (value) {
        "BEST_FRIEND" -> "best friend"
        "GOOD_FRIEND" -> "good friend"
        else -> "friend"
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
        arrayOf(table)
    ).use { it.moveToFirst() }

    private fun Cursor.index(name: String) = getColumnIndexOrThrow(name)
    private fun Cursor.string(name: String) = getString(index(name)).orEmpty()
    private fun Cursor.nullableString(name: String): String? = index(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(name: String) = getLong(index(name))
    private fun Cursor.int(name: String) = getInt(index(name))
    private fun Cursor.float(name: String) = getFloat(index(name))

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKC)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()
    private fun keyToken(value: String): String = normalize(value).replace(' ', '_').take(80)
}
