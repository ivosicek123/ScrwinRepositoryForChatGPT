package cz.faltyn.elfall.shared.integrity

/**
 * Kritické konstanty chráněné podepsaným manifestem (integrity/critical.json + MANIFEST.sha256.sig).
 * Změna zde bez nového podpisu vlastníka shodí CI (scripts/integrity_manifest.py verify).
 */
object CriticalConstants {
    const val AUTHOR_ATTRIBUTION = "Ivo Faltýn — autor, vývojář a vlastník projektu ELFALL / Fallcoin / Eleanor"
    const val AUTHOR_GITHUB = "ivosicek123"
    const val FLC_ASSET_CODE = "FLC"
    const val YFLC_ASSET_CODE = "yFLC"
    const val FLC_ISSUER_PUBLIC = "GCUMZ6Z7GAYFL2MGSFRG7HXDPL4FRBPZYAYJGTWEEKDI3NJIFUMSRPKT"
    const val STELLAR_HOME_DOMAIN = "elfall.pages.dev"
    const val FLC_REFERENCE_USD = 0.33
    val OFFRAMP_URL_PREFIXES = listOf(
        "https://sell.moonpay.com?",
        "https://pay.coinbase.com/v3/sell/input?",
        "https://www.anycoin.cz/?locale=",
    )
}
