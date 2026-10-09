package com.sacca.openride.core.profile

/** Display-only F2 identity; provenance and hardware status: docs/f2pro-findings.md. */
fun modelNameFromSerial(serial: String): String? {
    val value = serial.uppercase()
    if (value.length != 14 || value.any { it !in 'A'..'Z' && it !in '0'..'9' }) return null
    return when (value.take(4)) {
        "NAGR", "NAGV", "NAGU", "NAGT", "NAGS" -> "Ninebot F2 Pro"
        "NAGF", "NAGK", "NAGJ", "NAGH", "NAGG" -> "Ninebot F2 Plus"
        "NAGA", "NAGE", "NAGD", "NAGC", "NAGB" -> "Ninebot F2"
        else -> null
    }
}
