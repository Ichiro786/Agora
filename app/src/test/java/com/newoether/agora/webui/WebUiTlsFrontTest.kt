package com.newoether.agora.webui

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebUiTlsFrontTest {
    private val dir: File = Files.createTempDirectory("webui-tls").toFile()
    private val identity = WebUiCertificateStore(
        directory = dir,
        seal = { it },
        unseal = { it },
        addresses = { listOf(InetAddress.getByName("127.0.0.1")) },
    ).loadOrCreate()
    private val routes = WebUiServer(
        auth = WebUiAuth(
            passwordHash = { WebUiPasswordHasher(iterations = 1_000).hash("correct horse") },
            hasher = WebUiPasswordHasher(iterations = 1_000),
        ),
        readAsset = { path -> if (path == WebUiServer.INDEX) "<title>Agora</title>".toByteArray() else null },
        secureCookies = { true },
    )
    private val backend = startWebUiEngine(port = 0, routes = routes, host = WebUiTlsFront.LOOPBACK)
    private val front = WebUiTlsFront(
        identity,
        publicPort = 0,
        backendPort = kotlinx.coroutines.runBlocking { backend.engine.resolvedConnectors().first().port },
        bindHost = WebUiTlsFront.LOOPBACK,
    )

    @After
    fun tearDown() {
        front.close()
        backend.stop(100, 500)
        dir.deleteRecursively()
    }

    /** A client that trusts exactly this self-signed certificate, as a browser does after accepting it. */
    private fun open(path: String): HttpsURLConnection {
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("webui", identity.certificate)
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(trust) }.trustManagers, null)
        }
        return (URL("https://127.0.0.1:${front.localPort}$path").openConnection() as HttpsURLConnection)
            .apply { sslSocketFactory = context.socketFactory }
    }

    @Test
    fun servesPagesOverTlsWithTheStoredCertificate() {
        val connection = open("/")
        assertEquals(200, connection.responseCode)
        assertTrue(connection.inputStream.bufferedReader().readText().contains("Agora"))
        assertEquals(identity.certificate, connection.serverCertificates.first())
    }

    @Test
    fun loginOverTlsSetsASecureCookie() {
        val connection = open("/api/login").apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write("""{"password":"correct horse"}""".toByteArray()) }
        }
        assertEquals(200, connection.responseCode)
        val cookie = connection.getHeaderField("Set-Cookie")
        assertTrue(cookie, cookie.contains("Secure"))
        assertTrue(cookie, cookie.contains("HttpOnly"))
    }

    @Test
    fun plainHttpOnTheTlsPortGetsNoResponse() {
        val connection = URL("http://127.0.0.1:${front.localPort}/").openConnection() as HttpURLConnection
        connection.readTimeout = 15_000
        try {
            connection.responseCode
            fail("plain HTTP must not be answered")
        } catch (_: IOException) {
            // The front drops a connection whose handshake fails.
        }
    }
}
