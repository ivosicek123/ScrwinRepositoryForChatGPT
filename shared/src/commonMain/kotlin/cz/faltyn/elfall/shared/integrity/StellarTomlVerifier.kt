package cz.faltyn.elfall.shared.integrity

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Výsledek runtime kontroly vydavatele FLC proti Horizonu a stellar.toml (SEP-1). */
sealed class IssuerCheck {
    data object Ok : IssuerCheck()
    data class Mismatch(val reason: String) : IssuerCheck()
    data class Unavailable(val reason: String) : IssuerCheck()
}

/**
 * Ochrana proti "swap" útoku: aplikace ověří, že
 * 1) účet vydavatele na Horizonu má home_domain == očekávaná doména,
 * 2) https://<home_domain>/.well-known/stellar.toml deklaruje [[CURRENCIES]] code=FLC s issuer == pevně zakompilovaný klíč.
 * Při Mismatch musí UI zablokovat operace s FLC a zobrazit varování.
 */
class StellarTomlVerifier(
    private val http: HttpClient,
    private val horizon: String = "https://horizon-testnet.stellar.org",
    private val expectedIssuer: String = CriticalConstants.FLC_ISSUER_PUBLIC,
    private val expectedDomain: String = CriticalConstants.STELLAR_HOME_DOMAIN,
    private val assetCode: String = CriticalConstants.FLC_ASSET_CODE,
) {
    suspend fun verify(): IssuerCheck {
        val homeDomain = try {
            val r = http.get("$horizon/accounts/$expectedIssuer")
            if (!r.status.isSuccess()) return IssuerCheck.Unavailable("Horizon HTTP ${r.status.value}")
            Json.parseToJsonElement(r.bodyAsText()).jsonObject["home_domain"]?.jsonPrimitive?.content
        } catch (e: Exception) { return IssuerCheck.Unavailable("Horizon: ${e.message}") }
        if (homeDomain != expectedDomain) return IssuerCheck.Mismatch("home_domain '$homeDomain' != '$expectedDomain'")

        val toml = try {
            val r = http.get("https://$expectedDomain/.well-known/stellar.toml")
            if (!r.status.isSuccess()) return IssuerCheck.Unavailable("stellar.toml HTTP ${r.status.value}")
            r.bodyAsText()
        } catch (e: Exception) { return IssuerCheck.Unavailable("stellar.toml: ${e.message}") }
        val issuers = currencyIssuers(toml, assetCode)
        return when {
            issuers.isEmpty() -> IssuerCheck.Mismatch("stellar.toml nedeklaruje $assetCode")
            issuers.any { it != expectedIssuer } -> IssuerCheck.Mismatch("stellar.toml issuer ${issuers} != $expectedIssuer")
            else -> IssuerCheck.Ok
        }
    }

    companion object {
        /** Výchozí instance (sdílený Ktor klient) – pro platformy bez přímé závislosti na Ktor. */
        fun default(): StellarTomlVerifier = StellarTomlVerifier(cz.faltyn.elfall.shared.EsploraClient.defaultHttpClient())

        /** Minimální parser [[CURRENCIES]] bloků (SEP-1) – vrací issuery pro daný code. */
        fun currencyIssuers(toml: String, code: String): List<String> {
            val out = mutableListOf<String>()
            var inCur = false; var c: String? = null; var i: String? = null
            fun flush() { if (inCur && c == code && i != null) out += i!!; c = null; i = null }
            for (raw in toml.lines()) {
                val line = raw.substringBefore('#').trim()
                if (line.startsWith("[")) { flush(); inCur = line == "[[CURRENCIES]]"; continue }
                if (!inCur || '=' !in line) continue
                val k = line.substringBefore('=').trim()
                val v = line.substringAfter('=').trim().trim('"')
                if (k == "code") c = v else if (k == "issuer") i = v
            }
            flush()
            return out
        }
    }
}
