package com.sacca.openride.core.session

import kotlinx.coroutines.delay

/** Replaces the scooter's password with a client-chosen one. */
interface PairingStrategy {
    val label: String

    /**
     * Returns true once the scooter reports the password accepted (idx 1). [onPending] fires when the scooter asks
     * for the power-button press. On success the same connection can log in with the new password.
     */
    suspend fun pair(session: ScooterSession, password: ByteArray, onPending: () -> Unit): Boolean
}

/**
 * Weak-mode pairing (the `f2 pair` flow, verified live on an F2 Pro): SET_PWD at counter 0 with the BLE key,
 * repeated every 2 s up to 60 s until the scooter confirms (power button), then log in on the same connection.
 */
object WeakModePairing : PairingStrategy {
    override val label = "weak mode"

    override suspend fun pair(session: ScooterSession, password: ByteArray, onPending: () -> Unit): Boolean {
        val deadline = System.currentTimeMillis() + 60_000
        var pending = false
        while (System.currentTimeMillis() < deadline) {
            val r = session.sendSetPassword(password, weak = true, timeoutMs = 3000)
            if (r == 1) return true
            if (r == 0 && !pending) { pending = true; onPending() }
            delay(2000)
        }
        return false
    }
}
