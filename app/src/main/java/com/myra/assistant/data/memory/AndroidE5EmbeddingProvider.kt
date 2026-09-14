package com.myra.assistant.data.memory

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lazy Android neural embedding lane for multilingual-e5-small.
 *
 * The tokenizer is bundled because it is small and immutable. The encoder is
 * downloaded to app-private storage, verified before rename, and never loaded
 * on the main/audio thread. While warming or offline, the explicitly-labelled
 * feature-hash backend remains available and existing lexical/FTS recall works.
 */
class AndroidE5EmbeddingProvider(context: Context, private val onReady: () -> Unit = {}) : LocalEmbeddingProvider {
    private val app = context.applicationContext
    private val environment = OrtEnvironment.getEnvironment()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lyra-e5-loader").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val loading = AtomicBoolean(false)
    @Volatile private var encoder: OrtSession? = null
    @Volatile private var tokenizer: XlmRobertaUnigramTokenizer? = null

    override val modelId: String get() = if (isNeuralReady) MODEL_ID else FeatureHashEmbeddingProvider.modelId
    override val version: Int get() = if (isNeuralReady) MODEL_VERSION else FeatureHashEmbeddingProvider.version
    override val dimensions: Int get() = if (isNeuralReady) DIMENSIONS else FeatureHashEmbeddingProvider.dimensions
    override val isNeuralReady: Boolean get() = encoder != null && tokenizer != null

    init { initializeAsync() }

    fun initializeAsync() {
        if (isNeuralReady || !loading.compareAndSet(false, true)) return
        executor.execute {
            try {
                val model = verifiedFile(modelFile(), MODEL_BYTES, MODEL_SHA256)
                    ?: download(MODEL_URL, modelFile(), MODEL_BYTES, MODEL_SHA256)
                val tokenizerModel = verifiedFile(tokenizerFile(), TOKENIZER_BYTES, TOKENIZER_SHA256)
                    ?: download(TOKENIZER_URL, tokenizerFile(), TOKENIZER_BYTES, TOKENIZER_SHA256)
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setInterOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                }
                tokenizer = XlmRobertaUnigramTokenizer.fromJson(tokenizerModel.readText())
                encoder = environment.createSession(model.absolutePath, options)
                Log.i(TAG, "MEMORY_EMBEDDING_READY model=$MODEL_ID version=$MODEL_VERSION dimensions=$DIMENSIONS")
                onReady()
            } catch (error: Throwable) {
                encoder?.close(); encoder = null; tokenizer = null
                Log.w(TAG, "MEMORY_EMBEDDING_DEGRADED reason=${error.javaClass.simpleName}")
            } finally { loading.set(false) }
        }
    }

    override fun embed(text: String): DoubleArray = embedResult(text).vector
    override fun embedQuery(text: String): DoubleArray = embedQueryResult(text).vector
    override fun embedResult(text: String): EmbeddingResult = embedSnapshot(passageInput(text))
    override fun embedQueryResult(text: String): EmbeddingResult = embedSnapshot(queryInput(text))

    private fun embedSnapshot(text: String): EmbeddingResult {
        val tokenEncoder = tokenizer
        val encoderSession = encoder
        if (tokenEncoder == null || encoderSession == null) {
            initializeAsync()
            // Capture the fallback result now.  Readiness can flip immediately
            // after this branch, but the returned provenance remains hash/64.
            return FeatureHashEmbeddingProvider.embedResult(text)
        }
        val inputs = E5InputBuilder.build(tokenEncoder.encode(text, MAX_TOKENS))
        val clipped = inputs.ids; val mask = inputs.attentionMask; val types = inputs.tokenTypes
        val hidden = OnnxTensor.createTensor(environment, clipped).use { idTensor ->
            OnnxTensor.createTensor(environment, mask).use { maskTensor ->
                OnnxTensor.createTensor(environment, types).use { typeTensor ->
                    encoderSession.run(mapOf("input_ids" to idTensor, "attention_mask" to maskTensor,
                        "token_type_ids" to typeTensor)).use { output -> toHidden(output[0].value) }
                }
            }
        }
        val tokens = hidden.firstOrNull().orEmpty()
        return EmbeddingResult(E5Pooling.meanNormalized(tokens, DIMENSIONS), MODEL_ID, MODEL_VERSION,
            DIMENSIONS, BACKEND_KIND, neural = true)
    }

    private fun verifiedFile(file: File, bytes: Long, digest: String): File? =
        file.takeIf { it.isFile && it.length() == bytes && sha256(it) == digest }

    private fun download(url: String, destination: File, bytes: Long, digest: String): File {
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, "${destination.name}.partial")
        if (partial.length() > bytes) partial.delete()
        if (partial.length() == bytes && sha256(partial) == digest) return install(partial, destination)
        val offset = partial.length()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            if (connection.responseCode !in 200..299) error("embedding download HTTP ${connection.responseCode}")
            val append = offset > 0L && connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            connection.inputStream.use { input -> FileOutputStream(partial, append).use { output -> input.copyTo(output, 256 * 1024) } }
        } finally { connection.disconnect() }
        check(partial.length() == bytes) { "embedding component length mismatch" }
        check(sha256(partial) == digest) { "embedding component digest mismatch" }
        return install(partial, destination)
    }

    private fun install(partial: File, destination: File): File {
        if (destination.exists()) check(destination.delete()) { "embedding destination replacement failed" }
        check(partial.renameTo(destination)) { "embedding model atomic install failed" }
        return destination
    }

    private fun modelFile() = File(app.filesDir, "memory-models/$MODEL_FILENAME")
    private fun tokenizerFile() = File(app.filesDir, "memory-models/$TOKENIZER_FILENAME")
    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(256 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun toHidden(value: Any): Array<Array<FloatArray>> {
        val batches = value as? Array<*> ?: error("unsupported encoder output")
        return Array(batches.size) { batchIndex ->
            val tokens = batches[batchIndex] as? Array<*> ?: error("unsupported encoder batch")
            Array(tokens.size) { tokenIndex ->
                when (val token = tokens[tokenIndex]) {
                    is FloatArray -> token
                    is Array<*> -> FloatArray(token.size) { i -> (token[i] as Number).toFloat() }
                    else -> error("unsupported encoder token")
                }
            }
        }
    }

    companion object {
        const val MODEL_ID = "intfloat/multilingual-e5-small"
        const val MODEL_REVISION = "614241f622f53c4eeff9890bdc4f31cfecc418b3"
        const val MODEL_VERSION = 1
        const val DIMENSIONS = 384
        const val BACKEND_KIND = "E5_NEURAL"
        const val MODEL_BYTES = 235_052_531L
        const val MODEL_SHA256 = "4654c156f3e4171abc9c716cdb771bf9116455d15ac1aab364aeeede0e3205b0"
        const val MODEL_FILENAME = "multilingual-e5-small-o4.onnx"
        const val MODEL_URL = "https://huggingface.co/intfloat/multilingual-e5-small/resolve/$MODEL_REVISION/onnx/model_O4.onnx"
        const val TOKENIZER_FILENAME = "multilingual-e5-small-tokenizer.json"
        const val TOKENIZER_BYTES = 17_082_730L
        const val TOKENIZER_SHA256 = "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39"
        const val TOKENIZER_URL = "https://huggingface.co/intfloat/multilingual-e5-small/resolve/$MODEL_REVISION/onnx/tokenizer.json"
        private const val MAX_TOKENS = 256
        private const val TAG = "LyraAiriMemory"
        internal fun queryInput(text: String) = "query: $text"
        internal fun passageInput(text: String) = "passage: $text"
    }
}
