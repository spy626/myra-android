package com.myra.assistant.video

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Explicit-refresh, unauthenticated read-only public GitHub API client.
 * Fixed official host and repository; no redirects, tokens, write endpoints,
 * background polling, sensitive logs, or independent source of truth.
 */
internal object VideoStudioJobStatusReader {
    private const val MAX_BYTES = 65536
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    fun refresh(runId: String, onResult: (Result<VideoStudioJobStatus>) -> Unit): Call {
        val url = VideoStudioJobStatusContract.publicApiUrl(runId)
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "LYRA-VideoStudio-ReadOnly/1")
            .build()
        return client.newCall(request).also { call ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!call.isCanceled()) onResult(Result.failure(
                        IOException("GitHub status could not be reached. Open the official run page.", e)
                    ))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use { r ->
                            if (r.code == 403 || r.code == 429) {
                                throw IOException("Public GitHub API rate-limited; use the browser page.")
                            }
                            if (r.code == 404) {
                                throw IOException("Run unavailable or private. Check its GitHub page.")
                            }
                            if (!r.isSuccessful) {
                                throw IOException("GitHub API returned HTTP " + r.code)
                            }
                            val body = r.body ?: throw IOException("GitHub returned no status data")
                            if (body.contentLength() > MAX_BYTES) {
                                throw IOException("GitHub status response is too large")
                            }
                            val bytes = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(4096)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (bytes.size() + count > MAX_BYTES) {
                                        throw IOException("GitHub response exceeded safe size")
                                    }
                                    bytes.write(buffer, 0, count)
                                }
                            }
                            VideoStudioJobStatusContract.parse(
                                runId, bytes.toString(Charsets.UTF_8.name())
                            )
                        }
                    }
                    if (!call.isCanceled()) onResult(result)
                }
            })
        }
    }
}
