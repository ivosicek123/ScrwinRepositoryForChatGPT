package cz.faltyn.elfall.backend.bankid

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.text.Normalizer

/** Doménová logika: KYC úroveň, vlastnictví účtu. Vrací jen minimální výsledky (GDPR). */
object Logic {
    fun normName(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        .lowercase().trim().split(Regex("\\s+")).joinToString(" ")
    fun normIban(s: String) = s.replace(Regex("\\s+"), "").uppercase()

    fun kycLevel(profile: JsonObject, claims: Map<String, Any?>): Map<String, Any?> {
        val ver = (profile["verified_claims"] as? JsonObject)?.get("verification") as? JsonObject
        val tf = ver?.str("trust_framework")
        val acr = claims["acr"] as? String
        val level = when { tf != null && acr == "loa3" -> "bank_aml_verified"; acr == "loa3" -> "authenticated_loa3"; else -> "none" }
        return mapOf("level" to level, "trust_framework" to tf, "verification_process" to ver?.str("verification_process"),
            "acr" to acr, "majority" to (profile["majority"] as? JsonPrimitive)?.booleanOrNull)
    }

    fun fullName(p: JsonObject) = listOf("given_name", "middle_name", "family_name").mapNotNull { p.str(it)?.takeIf(String::isNotEmpty) }
        .joinToString(" ").ifEmpty { p.str("name") ?: "" }

    fun accountOwnership(p: JsonObject, iban: String?, holder: String?): Map<String, Any?> {
        val accounts = (p["paymentAccounts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { x -> x.isString }?.content?.let(::normIban) } ?: emptyList()
        val ibanMatch = !iban.isNullOrEmpty() && normIban(iban) in accounts
        val nameMatch = !holder.isNullOrEmpty() && normName(holder) == normName(fullName(p))
        return mapOf("iban_match" to ibanMatch, "name_match" to nameMatch, "confirmed" to ibanMatch, "accounts_claim_available" to accounts.isNotEmpty())
    }
}
