package com.newoether.agora.webui

import android.content.Context
import com.newoether.agora.util.DebugLog
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Whether the WebUI server is listening. */
internal sealed interface WebUiStatus {
    data object Stopped : WebUiStatus
    data object Starting : WebUiStatus
    data class Running(val port: Int) : WebUiStatus
    /** The server could not start, typically because the port is in use. */
    data class Failed(val message: String) : WebUiStatus
}

/**
 * Process-scoped owner of the WebUI: settings, authentication and the embedded server.
 *
 * [WebUiService] keeps the process alive and calls [startServer]/[stopServer]; the Settings page
 * calls the `set*` functions. The server listens on every interface (LAN, Tailscale), so it only
 * runs while a password is set.
 */
internal class WebUiController(
    private val appContext: Context,
    private val store: WebUiSettingsStore,
    scope: CoroutineScope,
    private val hasher: WebUiPasswordHasher = WebUiPasswordHasher(),
) {
    @Volatile private var passwordHash: String? = null
    private val auth = WebUiAuth(passwordHash = { passwordHash }, hasher = hasher)
    private val routes = WebUiServer(auth = auth, readAsset = ::readAsset)
    private val serverLock = Mutex()
    private var engine: EmbeddedServer<*, *>? = null
    private val _status = MutableStateFlow<WebUiStatus>(WebUiStatus.Stopped)

    val status: StateFlow<WebUiStatus> = _status.asStateFlow()
    val enabled: Flow<Boolean> = store.enabled
    val port: Flow<Int> = store.port
    val hasPassword: Flow<Boolean> = store.passwordHash.map { it != null }

    init {
        scope.launch { store.passwordHash.collect { passwordHash = it } }
    }

    /** Stores the new password's hash and signs every browser out. */
    suspend fun setPassword(password: String) {
        require(password.length >= MIN_PASSWORD_LENGTH) { "Password is too short" }
        val hash = withContext(Dispatchers.Default) { hasher.hash(password) }
        store.savePasswordHash(hash)
        passwordHash = hash
        auth.revokeAllSessions()
    }

    /** Returns false when enabling is refused because no password is set. */
    suspend fun setEnabled(enabled: Boolean): Boolean {
        if (enabled && store.passwordHash.first() == null) return false
        store.saveEnabled(enabled)
        if (enabled) WebUiService.start(appContext) else WebUiService.stop(appContext)
        return true
    }

    /** Saves the port and, if the server is running, moves it to the new port. */
    suspend fun setPort(port: Int) {
        store.savePort(port)
        if (serverLock.withLock { engine != null }) {
            stopServer()
            startServer()
        }
    }

    /** Called while the app is in the foreground: restores a server the user left enabled. */
    suspend fun startIfEnabled() {
        if (store.enabled.first() && store.passwordHash.first() != null &&
            _status.value !is WebUiStatus.Running
        ) {
            WebUiService.start(appContext)
        }
    }

    suspend fun startServer() = serverLock.withLock {
        if (engine != null) return@withLock
        val port = store.port.first()
        passwordHash = store.passwordHash.first()
        _status.value = WebUiStatus.Starting
        _status.value = withContext(Dispatchers.IO) {
            try {
                engine = embeddedServer(CIO, port = port, host = ANY_HOST) {
                    routes.install(this)
                }.start(wait = false)
                WebUiStatus.Running(port)
            } catch (error: Exception) {
                DebugLog.e(TAG, "WebUI server failed to start on port $port", error)
                engine = null
                WebUiStatus.Failed(error.localizedMessage ?: error.javaClass.simpleName)
            }
        }
    }

    suspend fun stopServer() = serverLock.withLock {
        val running = engine ?: return@withLock
        engine = null
        withContext(Dispatchers.IO) {
            runCatching { running.stop(STOP_GRACE_MILLIS, STOP_TIMEOUT_MILLIS) }
                .onFailure { DebugLog.e(TAG, "WebUI server failed to stop cleanly", it) }
        }
        auth.revokeAllSessions()
        _status.value = WebUiStatus.Stopped
    }

    /** `http://<address>:<port>` for every non-loopback IPv4 address that is up. */
    fun accessUrls(port: Int): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { "http://${it.hostAddress}:$port" }
            .distinct()
    }.getOrDefault(emptyList())

    private fun readAsset(path: String): ByteArray? = runCatching {
        appContext.assets.open("$ASSET_ROOT/$path").use { it.readBytes() }
    }.getOrNull()

    companion object {
        const val MIN_PASSWORD_LENGTH = 8
        private const val TAG = "WebUi"
        private const val ANY_HOST = "0.0.0.0"
        private const val ASSET_ROOT = "webui"
        private const val STOP_GRACE_MILLIS = 500L
        private const val STOP_TIMEOUT_MILLIS = 2_000L
    }
}
