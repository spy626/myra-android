package com.myra.assistant.ui.workspace

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors

/** Ephemeral read-only project preview. Binds only to 127.0.0.1, never to Wi-Fi/LAN. */
internal class WorkspacePreviewServer(
    private val projects: WorkspaceProjectStore,
    private val projectId: String,
) : Closeable {
    private val secret = UUID.randomUUID().toString().replace("-", "")
    private val clients = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "lyra-preview-client").apply { isDaemon = true }
    }
    @Volatile private var listener: ServerSocket? = null

    val url: String
        get() {
            val socket = requireNotNull(listener) { "Preview server is not running" }
            return "http://127.0.0.1:${socket.localPort}/p/$secret/"
        }

    @Synchronized fun start(): String {
        listener?.let { return url }
        require(projects.getProject(projectId)?.type == WorkspaceProjectType.WEBSITE) {
            "Website project required"
        }
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        listener = socket
        Thread({
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    clients.execute { serve(client, socket.localPort) }
                } catch (_: Exception) {
                    if (socket.isClosed) break
                }
            }
        }, "lyra-preview-listener").apply { isDaemon = true; start() }
        return url
    }

    private fun serve(socket: Socket, port: Int) {
        socket.use { client ->
            try {
                client.soTimeout = 2500
                val input = client.getInputStream()
                val first = line(input) ?: return
                val request = first.split(' ')
                if (request.size != 3 || request[2] !in listOf("HTTP/1.0", "HTTP/1.1")) {
                    reply(client, 400, "Bad Request"); return
                }
                var host: String? = null
                var origin: String? = null
                var count = 0
                var total = 0
                while (true) {
                    val header = line(input) ?: return
                    if (header.isEmpty()) break
                    total += header.length
                    if (++count > 40 || total > 8192) { reply(client, 400, "Bad Request"); return }
                    val key = header.substringBefore(':').trim().lowercase()
                    val value = header.substringAfter(':', "").trim()
                    if (key == "host") host = value
                    if (key == "origin") origin = value
                }
                if (!WorkspacePreviewPolicy.validHost(host, port) ||
                    (origin != null && origin != "http://127.0.0.1:$port")) {
                    reply(client, 403, "Forbidden"); return
                }
                if (request[0] != "GET" && request[0] != "HEAD") {
                    reply(client, 405, "Method Not Allowed"); return
                }
                val prefix = "/p/$secret/"
                if (!request[1].startsWith(prefix)) { reply(client, 404, "Not Found"); return }
                val relative = WorkspacePreviewPolicy.route("/" + request[1].removePrefix(prefix))
                if (relative == null) { reply(client, 404, "Not Found"); return }
                val mime = WorkspacePreviewPolicy.mime(relative) ?: run {
                    reply(client, 404, "Not Found"); return
                }
                val project = projects.getProject(projectId)
                if (project?.type != WorkspaceProjectType.WEBSITE) {
                    reply(client, 404, "Not Found"); return
                }
                // The existing WorkspaceFileStore remains the file-tree authority.
                val files = WorkspaceFileStore(projects)
                if (files.list(projectId).none { !it.folder && it.path == relative }) {
                    reply(client, 404, "Not Found"); return
                }
                val root = projects.projectRoot(projectId).canonicalFile
                var cursor = root
                for (segment in relative.split('/')) {
                    cursor = File(cursor, segment)
                    if (Files.isSymbolicLink(cursor.toPath()) ||
                        !cursor.canonicalFile.toPath().startsWith(root.toPath())) {
                        reply(client, 404, "Not Found"); return
                    }
                }
                if (!cursor.isFile || cursor.length() > 4 * 1024 * 1024) {
                    reply(client, 404, "Not Found"); return
                }
                val content = cursor.readBytes()
                if (content.size > 4 * 1024 * 1024) { reply(client, 404, "Not Found"); return }
                reply(client, 200, "OK", mime, if (request[0] == "HEAD") ByteArray(0) else content,
                    content.size)
            } catch (_: Exception) {
                // Browser disconnects and malformed requests must not crash LYRA.
            }
        }
    }

    private fun line(input: InputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (bytes.size <= 2048) {
            val read = input.read()
            if (read < 0) return null
            if (read == 10) return bytes.toByteArray().toString(Charsets.ISO_8859_1)
            if (read != 13) bytes.add(read.toByte())
        }
        return null
    }

    private fun reply(socket: Socket, status: Int, reason: String,
                      mime: String = "text/plain; charset=utf-8",
                      body: ByteArray = reason.toByteArray(Charsets.UTF_8),
                      length: Int = body.size) {
        val headers = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $mime\r\nContent-Length: $length\r\n" +
            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Cross-Origin-Resource-Policy: same-origin\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(headers.toByteArray(Charsets.ISO_8859_1))
            write(body)
            flush()
        }
    }

    @Synchronized override fun close() {
        listener?.close()
        listener = null
        clients.shutdownNow()
    }
}
