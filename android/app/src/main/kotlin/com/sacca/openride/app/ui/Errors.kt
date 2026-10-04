package com.sacca.openride.app.ui

import com.sacca.openride.core.safety.UnsafeCommand
import com.sacca.openride.core.session.CredentialRejected
import com.sacca.openride.core.session.LinkClosedException
import com.sacca.openride.core.session.PowerRefused
import com.sacca.openride.core.session.ScooterTimeout

fun describe(e: Throwable): String = when (e) {
    is CredentialRejected -> "The scooter did not accept the stored credential. Not retrying; wait for the cool-down."
    is ScooterTimeout -> "The scooter stopped answering (${e.message})."
    is LinkClosedException -> "Bluetooth link closed. The scooter allows one app at a time: close the official app."
    is PowerRefused -> e.message ?: "Power off refused."
    is UnsafeCommand -> "Blocked by safety guard: ${e.message}"
    else -> e.message ?: e.toString()
}
