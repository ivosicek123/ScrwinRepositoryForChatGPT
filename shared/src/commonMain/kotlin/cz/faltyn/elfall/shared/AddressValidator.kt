package cz.faltyn.elfall.shared

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.utils.Either

sealed class AddressCheck {
    data object Valid : AddressCheck()
    data class Invalid(val reason: String) : AddressCheck()
}

/** Validates Base58 (P2PKH/P2SH) and Bech32/Bech32m (P2WPKH/P2WSH/P2TR) addresses for the given network. */
object AddressValidator {
    fun check(address: String, network: BtcNetwork): AddressCheck {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return AddressCheck.Invalid("Address is empty")
        return when (val r = Bitcoin.addressToPublicKeyScript(network.chainHash, trimmed)) {
            is Either.Right -> AddressCheck.Valid
            is Either.Left -> AddressCheck.Invalid("Not a valid ${network.name.lowercase()} address (${r.value})")
        }
    }

    fun isValid(address: String, network: BtcNetwork) = check(address, network) is AddressCheck.Valid
}
