package cz.faltyn.elfall.shared

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.SigVersion
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.utils.Either

data class FeePreview(
    val destination: String,
    val amountSats: Long,
    val feeRateSatPerVb: Long,
    val estimatedVbytes: Long,
    val feeSats: Long,
    val changeSats: Long,
    val inputs: List<Utxo>,
) {
    val totalSats get() = amountSats + feeSats
}

/**
 * Low-level BTC send engine (P2WPKH build + sign + broadcast).
 *
 * UI naming: call this a **Transfer** (převod), not a withdrawal.
 * Cash-out / "Výběr" is card-only via [cz.faltyn.elfall.shared.offramp.OffRampProvider].
 */
class WithdrawService(private val wallet: WalletCore, private val esplora: EsploraClient) {

    suspend fun preview(destination: String, amountSats: Long, feeRateSatPerVb: Long? = null): FeePreview {
        val check = AddressValidator.check(destination, wallet.network)
        require(check is AddressCheck.Valid) { (check as AddressCheck.Invalid).reason }
        require(amountSats >= DUST_LIMIT) { "Amount is below the dust limit ($DUST_LIMIT sats)" }
        val rate = feeRateSatPerVb ?: esplora.recommendedFees().halfHourFee
        val utxos = esplora.utxos(wallet.receiveAddress()).sortedByDescending { it.value }
        return select(destination.trim(), amountSats, rate, utxos)
    }

    /** Signs locally and broadcasts. Call only after the user confirmed the preview (biometric gate in UI). */
    suspend fun send(preview: FeePreview): String = esplora.broadcast(buildSignedTx(preview))

    fun buildSignedTx(p: FeePreview): String {
        val net = wallet.network
        val priv = wallet.receivePrivateKey()
        val pub = priv.publicKey()
        val destScript = (Bitcoin.addressToPublicKeyScript(net.chainHash, p.destination) as Either.Right).value
        val outputs = buildList {
            add(TxOut(Satoshi(p.amountSats), destScript))
            if (p.changeSats > 0) add(TxOut(Satoshi(p.changeSats), Script.pay2wpkh(pub)))
        }
        val inputs = p.inputs.map { TxIn(OutPoint(TxId(it.txid), it.vout), TxIn.SEQUENCE_FINAL - 2) } // RBF-enabled
        var tx = Transaction(version = 2, txIn = inputs, txOut = outputs, lockTime = 0)
        p.inputs.forEachIndexed { i, utxo ->
            val sig = tx.signInput(i, Script.pay2pkh(pub), SigHash.SIGHASH_ALL, Satoshi(utxo.value), SigVersion.SIGVERSION_WITNESS_V0, priv)
            tx = tx.updateWitness(i, Script.witnessPay2wpkh(pub, fr.acinq.bitcoin.ByteVector(sig)))
        }
        return Transaction.write(tx).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    companion object {
        const val DUST_LIMIT = 546L

        /** vbytes for P2WPKH inputs/outputs: ~10.5 overhead + 68/input + 31/output. */
        fun estimateVbytes(inputs: Int, outputs: Int): Long = (11 + 68L * inputs + 31L * outputs)

        fun select(destination: String, amountSats: Long, rate: Long, utxos: List<Utxo>): FeePreview {
            val chosen = mutableListOf<Utxo>()
            var total = 0L
            for (u in utxos) {
                chosen += u; total += u.value
                val feeWithChange = estimateVbytes(chosen.size, 2) * rate
                if (total >= amountSats + feeWithChange) break
            }
            val vbWithChange = estimateVbytes(chosen.size, 2)
            val feeWithChange = vbWithChange * rate
            require(total >= amountSats + estimateVbytes(chosen.size, 1) * rate) { "Insufficient balance" }
            val change = total - amountSats - feeWithChange
            return if (change >= DUST_LIMIT) {
                FeePreview(destination, amountSats, rate, vbWithChange, feeWithChange, change, chosen)
            } else {
                val vb = estimateVbytes(chosen.size, 1)
                FeePreview(destination, amountSats, rate, vb, total - amountSats, 0, chosen) // leftover goes to fee
            }
        }
    }
}
