package com.sacca.openride.core

import com.sacca.openride.core.crypto.CredentialHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialHexTest {
    private val bytes = ByteArray(16) { (0xA0 + it).toByte() }
    private val hex = "A0A1A2A3A4A5A6A7A8A9AAABACADAEAF"

    @Test fun roundTrip() {
        assertEquals(hex, CredentialHex.format(bytes))
        assertArrayEquals(bytes, CredentialHex.parse(hex))
    }

    @Test fun toleratesFileFormatting() {
        assertArrayEquals(bytes, CredentialHex.parse(hex.lowercase() + "\n")) // a saved file: one line, trailing newline
        assertArrayEquals(bytes, CredentialHex.parse("0x$hex"))
        assertArrayEquals(bytes, CredentialHex.parse(hex.chunked(2).joinToString(":")))
        assertArrayEquals(bytes, CredentialHex.parse("  " + hex.chunked(8).joinToString(" ") + "  "))
    }

    @Test fun rejectsAnythingElse() {
        assertNull(CredentialHex.parse(""))
        assertNull(CredentialHex.parse(hex.dropLast(2)))
        assertNull(CredentialHex.parse(hex + "00"))
        assertNull(CredentialHex.parse(hex.dropLast(1) + "G"))
    }
}
