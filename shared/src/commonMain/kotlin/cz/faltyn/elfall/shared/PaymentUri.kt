package cz.faltyn.elfall.shared

/** Result of parsing a scanned QR / pasted payment string. */
sealed class ParsedPayment {
    data class Bitcoin(val address: String, val amountSats: Long? = null, val label: String? = null, val message: String? = null) : ParsedPayment()
    data class Stellar(val destination: String, val amount: String? = null, val assetCode: String? = null, val assetIssuer: String? = null, val memo: String? = null) : ParsedPayment()
    data class Unknown(val raw: String) : ParsedPayment()
}

object Bip21 {
    /** Parses `bitcoin:<addr>?amount=0.001&label=..&message=..` (BIP21) or a bare address. */
    fun parse(input: String): ParsedPayment.Bitcoin? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        val noScheme = if (raw.startsWith("bitcoin:", ignoreCase = true)) raw.substring(8) else raw
        val addr = noScheme.substringBefore('?').trim()
        if (addr.isEmpty()) return null
        val params = parseQuery(noScheme.substringAfter('?', ""))
        params.keys.firstOrNull { it.startsWith("req-") }?.let { return null } // unknown required param → reject per BIP21
        val amount = params["amount"]?.let { parseBtcToSats(it) ?: return null }
        return ParsedPayment.Bitcoin(addr, amount, params["label"], params["message"])
    }

    fun build(address: String, amountSats: Long? = null, label: String? = null): String {
        val q = buildList {
            amountSats?.let { add("amount=${it.satsToBtcString().trimEnd('0').trimEnd('.')}") }
            label?.let { add("label=${percentEncode(it)}") }
        }
        return "bitcoin:$address" + if (q.isEmpty()) "" else "?" + q.joinToString("&")
    }
}

object PaymentUriParser {
    fun parse(input: String): ParsedPayment {
        val raw = input.trim()
        return when {
            raw.startsWith("web+stellar:pay", ignoreCase = true) -> {
                val p = parseQuery(raw.substringAfter('?', ""))
                val dest = p["destination"] ?: return ParsedPayment.Unknown(raw)
                ParsedPayment.Stellar(dest, p["amount"], p["asset_code"], p["asset_issuer"], p["memo"])
            }
            isStellarAccount(raw) -> ParsedPayment.Stellar(raw)
            else -> Bip21.parse(raw) ?: ParsedPayment.Unknown(raw)
        }
    }

    fun isStellarAccount(s: String) = s.length == 56 && s.startsWith("G") && s.all { it in 'A'..'Z' || it in '2'..'7' }
}

internal fun parseQuery(q: String): Map<String, String> =
    if (q.isBlank()) emptyMap() else q.split('&').filter { it.isNotEmpty() }.associate {
        val k = it.substringBefore('=')
        val v = it.substringAfter('=', "")
        percentDecode(k).lowercase() to percentDecode(v)
    }

internal fun percentDecode(s: String): String {
    val out = ArrayList<Byte>()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length + 1 && i + 2 <= s.length - 1) {
            val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
            if (hex != null) { out.add(hex.toByte()); i += 3; continue }
        }
        if (c == '+') out.add(' '.code.toByte()) else c.toString().encodeToByteArray().forEach { out.add(it) }
        i++
    }
    return out.toByteArray().decodeToString()
}

internal fun percentEncode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val ch = b.toInt().toChar()
        if (ch.isLetterOrDigit() && b >= 0 || ch in "-_.~") append(ch)
        else append('%').append((b.toInt() and 0xff).toString(16).uppercase().padStart(2, '0'))
    }
}
