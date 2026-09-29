package com.newoether.agora.webui

import com.newoether.agora.util.DebugLog
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * HTTPS for the WebUI. Ktor's CIO engine has no TLS, so this listens on the public port with
 * the platform TLS stack, decrypts each connection and relays its bytes to CIO, which listens
 * only on loopback. The relay is byte-for-byte, so HTTP keep-alive and WebSocket upgrades pass
 * through unchanged.
 *
 * Throws from the constructor when the port cannot be bound.
 */
internal class WebUiTlsFront(
    identity: WebUiTlsIdentity,
    publicPort: Int,
    private val backendPort: Int,
    bindHost: String = WebUiController.ANY_HOST,
) : Closeable {
    private val serverSocket: SSLServerSocket
    private val openSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    @Volatile private var closed = false

    /** The bound port; equals the requested one unless it was 0. */
    val localPort: Int get() = serverSocket.localPort

    init {
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(identity.keyStore, identity.password) }
            .keyManagers
        val context = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
        serverSocket = (context.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            reuseAddress = true
            enabledProtocols = supportedProtocols.filter { it in ALLOWED_PROTOCOLS }.toTypedArray()
            bind(InetSocketAddress(bindHost, publicPort), BACKLOG)
        }
        thread(name = "WebUiTlsAccept", isDaemon = true) { acceptLoop() }
    }

    private fun acceptLoop() {
        while (!closed) {
            val client = try {
                serverSocket.accept() as SSLSocket
            } catch (error: IOException) {
                if (closed || serverSocket.isClosed) return
                DebugLog.w(TAG, "TLS accept failed", error)
                continue
            }
            thread(name = "WebUiTlsConnection", isDaemon = true) { relay(client) }
        }
    }

    private fun relay(client: SSLSocket) {
        val backend = Socket()
        openSockets += client
        openSockets += backend
        try {
            // A client that never finishes the handshake (or speaks plain HTTP) is dropped.
            client.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
            client.startHandshake()
            client.soTimeout = 0
            backend.connect(InetSocketAddress(LOOPBACK, backendPort), CONNECT_TIMEOUT_MILLIS)
            // Either direction ending ends the connection: CIO closes idle keep-alive
            // connections itself, and a closed browser tab ends the upstream side.
            val upstream = thread(name = "WebUiTlsUpstream", isDaemon = true) {
                pump(client.inputStream, backend.getOutputStream())
                closeQuietly(client, backend)
            }
            pump(backend.getInputStream(), client.outputStream)
            closeQuietly(client, backend)
            upstream.join()
        } catch (_: IOException) {
            // Handshake failures and resets are routine for a server on an open network.
        } finally {
            closeQuietly(client, backend)
            openSockets -= client
            openSockets -= backend
        }
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: IOException) {
            // The other side closed.
        }
    }

    private fun closeQuietly(vararg sockets: Socket) {
        sockets.forEach { runCatching { it.close() } }
    }

    override fun close() {
        closed = true
        runCatching { serverSocket.close() }
        openSockets.toList().forEach { runCatching { it.close() } }
        openSockets.clear()
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        private const val TAG = "WebUiTls"
        private val ALLOWED_PROTOCOLS = setOf("TLSv1.2", "TLSv1.3")
        private const val BACKLOG = 50
        private const val HANDSHAKE_TIMEOUT_MILLIS = 10_000
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val BUFFER_BYTES = 16 * 1024
    }
}
