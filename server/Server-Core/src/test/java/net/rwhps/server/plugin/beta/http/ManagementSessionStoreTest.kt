package net.rwhps.server.plugin.beta.http

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ManagementSessionStoreTest {
    @Test
    fun expiryAndCredentialRotationInvalidateEstablishedSessions() {
        var now = 1000L
        val store = ManagementSessionStore({ now }, ttlMillis = 100L)
        val token = store.issue("initial")
        assertTrue(store.valid(token, "initial"))
        now = 1099L
        assertTrue(store.valid(token, "initial"))
        now = 1100L
        assertFalse(store.valid(token, "initial"))
        val rotated = store.issue("initial")
        assertFalse(store.valid(rotated, "changed"))
        assertFalse(store.valid(rotated, "initial"))
        assertFalse(store.valid("unknown", "initial"))
        assertThrows(IllegalArgumentException::class.java) { store.issue("") }
    }

    @Test
    fun sessionsAreBoundedAndNewTokensDoNotReuseOldIdentity() {
        val store = ManagementSessionStore({ 0L }, capacity = 2)
        val first = store.issue("credential")
        val second = store.issue("credential")
        val third = store.issue("credential")
        assertNotEquals(first, second)
        assertFalse(store.valid(first, "credential"))
        assertTrue(store.valid(second, "credential"))
        assertTrue(store.valid(third, "credential"))
        val next = store.issue("replacement")
        assertFalse(store.valid(second, "credential"))
        assertTrue(store.valid(next, "replacement"))
    }
}
