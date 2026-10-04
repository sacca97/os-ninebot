package com.sacca.openride.core.crypto

/**
 * The credential file / string format shared with the Python tool: the 16-byte password as 32 hex characters
 * (one line; whitespace, ':' separators, a "0x" prefix and either case are accepted when reading, upper case is written).
 */
object CredentialHex {
    fun parse(text: String): ByteArray? {
        val clean = text.filter { !it.isWhitespace() && it != ':' }.removePrefix("0x").removePrefix("0X")
        if (clean.length != 32 || !clean.all { it in "0123456789abcdefABCDEF" }) return null
        return ByteArray(16) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun format(password: ByteArray): String {
        require(password.size == 16) { "credential must be 16 bytes" }
        return password.joinToString("") { "%02X".format(it) }
    }
}
