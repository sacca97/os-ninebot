package com.sacca.openride.core.profile

import java.util.Locale
import kotlin.math.abs

/** Common encodings are data; special formats are named implementations. */
data class ReadingDecoder(
    val kind: String, val signed: Boolean = false, val scale: Double = 1.0,
    val offset: Double = 0.0, val precision: Int = 2, val unit: String = "",
    val prefix: String = "raw ", val width: Int = 0, val mask: Long = -1L,
    val trueLabel: String = "on", val falseLabel: String = "off",
    val values: Map<Long, String> = emptyMap(), val byteOrder: String = "little_endian",
) {
    fun decode(raw: ByteArray): String {
        val bytes = if (byteOrder == "big_endian") raw.reversedArray() else raw
        // Array encodings do not pack the entire array into a Long.
        if (kind == "ascii") return String(raw.takeWhile { it != 0.toByte() }.toByteArray(), Charsets.US_ASCII)
        if (kind == "cells") {
            val cells = raw.asList().chunked(2).map { unsignedLittleEndian(if (byteOrder == "big_endian") it.reversed().toByteArray() else it.toByteArray()) }
            return "${cells.joinToString(" ")} $unit (spread ${cells.max() - cells.min()} $unit)"
        }
        if (kind == "temperatures") return raw.joinToString(", ") { "${((it.toInt() and 0xFF) + offset).toInt()} $unit" }
        val unsigned = unsignedLittleEndian(bytes)
        val value = if (signed) (unsigned shl (64 - raw.size * 8)) shr (64 - raw.size * 8) else unsigned
        return when (kind) {
            "number" -> fixed(value * scale + offset)
            "current" -> when {
                value < 0 -> "charging " + fixed(abs(value * scale + offset))
                value > 0 -> "discharging " + fixed(value * scale + offset)
                else -> "idle " + fixed(offset)
            }
            "firmware" -> "${unsigned shr 8}.${(unsigned shr 4) and 0xF}.${unsigned and 0xF}"
            "hex" -> (if (prefix == "raw ") "" else prefix) + "%0${width.coerceAtLeast(1)}X".format(Locale.ROOT, unsigned)
            "enum" -> values[value] ?: "unknown ($value)"
            "boolean" -> if (unsigned and mask != 0L) trueLabel else falseLabel
            "raw" -> "$prefix$value"
            else -> error("unsupported decoder: $kind")
        }
    }

    private fun fixed(value: Double) = "%.${precision}f".format(Locale.ROOT, value) + if (unit.isEmpty()) "" else " $unit"
}
