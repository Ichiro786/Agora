package com.newoether.agora.webui

import java.security.SecureRandom
import java.util.Base64

/** Outcome of one WebUI login attempt. */
internal sealed interface WebUiLoginResult {
    data class Success(val sessionToken: String) : WebUiLoginResult
    /** [attemptsLeft] failures remain before the lockout starts. */
    data class WrongPassword(val attemptsLeft: Int) : WebUiLoginResult
    /** Every attempt is refused until [untilMillis], including a correct password. */
    data class LockedOut(val untilMillis: Long) : WebUiLoginResult
    /** No password is set, so the WebUI cannot be entered at all. */
    data object NotConfigured : WebUiLoginResult
}

/**
 * Password check, global lockout and in-memory sessions for the WebUI.
 *
 * The lockout is global, not per address: the WebUI has one user, and a per-address counter
 * would let an attacker rotate addresses. After [maxFailures] consecutive failures every attempt
 * is refused for [lockoutMillis]; the counter then starts again. A success resets it.
 *
 * Sessions live only in memory, so a service restart signs every browser out. Changing the
 * password must call [revokeAllSessions].
 */
internal class WebUiAuth(
    private val passwordHash: () -> String?,
    private val hasher: WebUiPasswordHasher = WebUiPasswordHasher(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val lockoutMillis: Long = DEFAULT_LOCKOUT_MILLIS,
) {
    private val lock = Any()
    private var failures = 0
    private var lockedUntil = 0L
    private val sessions = mutableSetOf<String>()

    fun login(password: String): WebUiLoginResult {
        val now = clock()
        synchronized(lock) {
            if (now < lockedUntil) return WebUiLoginResult.LockedOut(lockedUntil)
        }
        val stored = passwordHash() ?: return WebUiLoginResult.NotConfigured
        // Hash outside the lock: PBKDF2 is slow and must not block session checks.
        val matches = hasher.verify(password, stored)
        synchronized(lock) {
            // Another attempt may have started the lockout while this one was hashing.
            if (clock() < lockedUntil) return WebUiLoginResult.LockedOut(lockedUntil)
            if (matches) {
                failures = 0
                val token = newToken()
                sessions += token
                return WebUiLoginResult.Success(token)
            }
            failures += 1
            if (failures >= maxFailures) {
                failures = 0
                lockedUntil = clock() + lockoutMillis
                return WebUiLoginResult.LockedOut(lockedUntil)
            }
            return WebUiLoginResult.WrongPassword(maxFailures - failures)
        }
    }

    fun isValidSession(token: String?): Boolean =
        token != null && synchronized(lock) { token in sessions }

    fun logout(token: String?) {
        if (token == null) return
        synchronized(lock) { sessions -= token }
    }

    fun revokeAllSessions() {
        synchronized(lock) { sessions.clear() }
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val DEFAULT_MAX_FAILURES = 10
        const val DEFAULT_LOCKOUT_MILLIS = 5 * 60 * 1000L
        private const val TOKEN_BYTES = 32
    }
}
