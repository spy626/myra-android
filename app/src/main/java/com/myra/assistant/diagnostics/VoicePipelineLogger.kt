package com.myra.assistant.diagnostics

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Private rolling diagnostics for phone-only voice debugging. Never pass content or secrets here. */
object VoicePipelineLogger {
    private const val TAG = "LyraVoicePipeline"
    private const val FILE_NAME = "lyra_voice_pipeline.log"
    private const val MAX_BYTES = 512 * 1024L
    private const val KEEP_BYTES = 256 * 1024
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lyra-voice-log-writer").apply { isDaemon = true }
    }
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) { appContext = context.applicationContext }

    fun debug(message: String) {
        Log.d(TAG, "$message tMs=${SystemClock.elapsedRealtime()}")
        append("D", message)
    }

    fun error(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        val detail = throwable?.let { " error=${it.javaClass.simpleName}:${it.message.orEmpty()}" }.orEmpty()
        append("E", message + detail)
    }

    /** Flushes queued writes before producing a share-safe snapshot. */
    fun createExport(context: Context, onReady: (Result<File>) -> Unit) {
        initialize(context)
        writer.execute {
            val result = runCatching {
                val source = logFile(context)
                val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
                val output = File(shareDir, "lyra-voice-logs-${exportStamp()}.txt")
                output.writeText(buildString {
                    appendLine("LYRA voice pipeline diagnostic log")
                    appendLine("Created: ${wallClock()}")
                    appendLine("No audio, transcripts, memories, or API keys are included.")
                    appendLine()
                    if (source.exists()) append(source.readText()) else appendLine("No voice events recorded yet.")
                })
                output
            }
            android.os.Handler(context.mainLooper).post { onReady(result) }
        }
    }

    private fun append(level: String, message: String) {
        val context = appContext ?: return
        val safe = message.replace('\n', ' ').replace('\r', ' ')
        writer.execute {
            runCatching {
                val file = logFile(context)
                file.parentFile?.mkdirs()
                rotateIfNeeded(file)
                file.appendText("${wallClock()} $level $safe tMs=${SystemClock.elapsedRealtime()}\n")
            }.onFailure { Log.e(TAG, "Unable to persist voice diagnostic event", it) }
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < MAX_BYTES) return
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOfRange((bytes.size - KEEP_BYTES).coerceAtLeast(0), bytes.size))
    }

    private fun logFile(context: Context) = File(context.filesDir, "diagnostics/$FILE_NAME")
    private fun wallClock() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    private fun exportStamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
