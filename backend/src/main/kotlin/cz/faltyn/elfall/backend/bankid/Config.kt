package cz.faltyn.elfall.backend.bankid

/** Konfigurace výhradně z proměnných prostředí. Žádná tajemství v repozitáři. */
data class BankIdConfig(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    val env: String = "sandbox",
    val kycScopes: List<String> = listOf("openid", "profile.name", "profile.birthdate", "profile.verification"),
    val accountScopes: List<String> = listOf("openid", "profile.name", "profile.paymentAccounts"),
    val txnScopes: List<String> = listOf("openid"),
    val pendingTtlS: Long = 600,
    val stepUpMaxAgeS: Long = 120,
) {
    val issuer: String get() = ISSUERS.getValue(env)
    val discoveryUrl: String get() = issuer + ".well-known/openid-configuration"

    override fun toString() = "BankIdConfig(clientId=$clientId, clientSecret=***, redirectUri=$redirectUri, env=$env)"

    companion object {
        val ISSUERS = mapOf("sandbox" to "https://oidc.sandbox.bankid.cz/", "production" to "https://oidc.bankid.cz/")

        fun fromEnv(e: Map<String, String> = System.getenv()): BankIdConfig {
            val env = (e["BANKID_ENV"] ?: "sandbox").trim().lowercase()
            if (env !in ISSUERS) throw IllegalStateException("Neznámé BANKID_ENV=$env")
            if (env == "production" && e["BANKID_ALLOW_PRODUCTION"] != "1")
                throw IllegalStateException("Produkce vyžaduje smlouvu s Bankovní identitou a BANKID_ALLOW_PRODUCTION=1")
            val missing = listOf("BANKID_CLIENT_ID", "BANKID_CLIENT_SECRET", "BANKID_REDIRECT_URI").filter { e[it].isNullOrEmpty() }
            if (missing.isNotEmpty()) throw IllegalStateException("Chybí proměnné prostředí: " + missing.joinToString())
            val redirect = e.getValue("BANKID_REDIRECT_URI")
            if (!(redirect.startsWith("https://") || redirect.startsWith("http://localhost")))
                throw IllegalStateException("BANKID_REDIRECT_URI musí být https:// (výjimka jen http://localhost pro vývoj)")
            return BankIdConfig(e.getValue("BANKID_CLIENT_ID"), e.getValue("BANKID_CLIENT_SECRET"), redirect, env)
        }
    }
}
