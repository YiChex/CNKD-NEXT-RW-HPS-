package net.rwhps.server.plugin.beta.http

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Bounded sessions tied to the current management credential, including established sockets. */
internal class ManagementSessionStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 86_400_000L,
    private val capacity: Int = 1024
) {
    init { require(ttlMillis > 0L && capacity > 0) }
    private data class Session(val expiresAt: Long, val credentialHash: ByteArray)
    private val sessions = LinkedHashMap<String, Session>()
    private val random = SecureRandom()

    @Synchronized
    fun issue(credential: String): String {
        require(credential.isNotBlank()) { "Management credential is empty" }
        val now = clock()
        val hash = hash(credential)
        sessions.entries.removeIf { it.value.expiresAt <= now || !MessageDigest.isEqual(hash, it.value.credentialHash) }
        while (sessions.size >= capacity) sessions.remove(sessions.keys.first())
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        sessions[token] = Session(now + ttlMillis, hash)
        return token
    }

    @Synchronized
    fun valid(token: String, currentCredential: String): Boolean {
        val session = sessions[token] ?: return false
        if (currentCredential.isBlank() || session.expiresAt <= clock() ||
            !MessageDigest.isEqual(session.credentialHash, hash(currentCredential))) {
            sessions.remove(token)
            return false
        }
        return true
    }

    private fun hash(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
