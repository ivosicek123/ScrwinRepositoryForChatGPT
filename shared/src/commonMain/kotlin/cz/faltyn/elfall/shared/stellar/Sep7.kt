package cz.faltyn.elfall.shared.stellar

/**
 * SEP-0007 URI builder (web+stellar:…).
 * Spec: https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0007.md
 *
 * Signing always happens in the user's wallet (LOBSTR / Freighter) — ELFALL never holds Stellar keys.
 */
object Sep7 {
    const val SCHEME = "web+stellar"

    data class PayRequest(
        val destination: String,
        val amount: String? = null,
        val assetCode: String? = null, // null => XLM
        val assetIssuer: String? = null,
        val memo: String? = null,
        val memoType: String? = null, // MEMO_TEXT | MEMO_ID | MEMO_HASH | MEMO_RETURN
        val msg: String? = null,
        val networkPassphrase: String? = null,
        val callbackUrl: String? = null,
        val originDomain: String? = null,
    )

    data class TxRequest(
        /** Base64 TransactionEnvelope (not yet URL-encoded). */
        val xdrBase64: String,
        val callbackUrl: String? = null,
        val msg: String? = null,
        val networkPassphrase: String? = null,
        val originDomain: String? = null,
    )

    fun payUri(req: PayRequest): String {
        require(req.destination.isNotBlank()) { "destination required" }
        require(req.destination.startsWith("G") && req.destination.length == 56) {
            "destination must be a Stellar account (G…, 56 chars)"
        }
        if (req.assetCode != null && req.assetCode != "XLM") {
            require(!req.assetIssuer.isNullOrBlank()) { "asset_issuer required for non-XLM assets" }
        }
        if (req.msg != null) require(req.msg.length <= 300) { "msg max 300 chars before encoding" }

        val q = linkedMapOf<String, String>()
        q["destination"] = req.destination
        req.amount?.let { q["amount"] = it }
        req.assetCode?.let { q["asset_code"] = it }
        req.assetIssuer?.let { q["asset_issuer"] = it }
        req.memo?.let { q["memo"] = it }
        req.memoType?.let { q["memo_type"] = it }
        req.msg?.let { q["msg"] = it }
        req.networkPassphrase?.let { q["network_passphrase"] = it }
        req.callbackUrl?.let { q["callback"] = "url:$it" }
        req.originDomain?.let { q["origin_domain"] = it }
        return "$SCHEME:pay?${encodeQuery(q)}"
    }

    fun txUri(req: TxRequest): String {
        require(req.xdrBase64.isNotBlank()) { "xdr required" }
        val q = linkedMapOf<String, String>()
        q["xdr"] = req.xdrBase64
        req.callbackUrl?.let { q["callback"] = "url:$it" }
        req.msg?.let { q["msg"] = it }
        req.networkPassphrase?.let { q["network_passphrase"] = it }
        req.originDomain?.let { q["origin_domain"] = it }
        return "$SCHEME:tx?${encodeQuery(q)}"
    }

    /** RFC3986-ish query encoding (encodeURIComponent subset, space as %20). */
    fun encodeQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

    fun encode(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                in 'A'..'Z', in 'a'..'z', in '0'..'9', '-', '_', '.', '~' -> append(ch)
                ' ' -> append("%20")
                else -> {
                    val bytes = ch.toString().encodeToByteArray()
                    for (b in bytes) append("%").append(((b.toInt() and 0xff)).toString(16).uppercase().padStart(2, '0'))
                }
            }
        }
    }
}
