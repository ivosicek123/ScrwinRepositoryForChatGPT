package cz.faltyn.elfall.shared

const val SATS_PER_BTC = 100_000_000L

fun Long.satsToBtcString(): String {
    val whole = this / SATS_PER_BTC
    val frac = (this % SATS_PER_BTC).toString().padStart(8, '0')
    return "$whole.$frac"
}

/** Parses a decimal BTC amount ("0.0015") into satoshis without floating point. */
fun parseBtcToSats(input: String): Long? {
    val s = input.trim().replace(',', '.')
    if (s.isEmpty() || s.count { it == '.' } > 1 || !s.all { it.isDigit() || it == '.' }) return null
    val parts = s.split('.')
    val whole = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
    val fracStr = parts.getOrElse(1) { "" }
    if (fracStr.length > 8) return null
    val frac = fracStr.padEnd(8, '0').toLongOrNull() ?: return null
    return whole * SATS_PER_BTC + frac
}
