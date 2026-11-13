package cz.faltyn.elfall.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.faltyn.elfall.shared.*
import cz.faltyn.elfall.shared.assets.DevAssets
import cz.faltyn.elfall.shared.assets.FLC_REFERENCE_USD
import cz.faltyn.elfall.shared.network.ElfallNetwork
import cz.faltyn.elfall.shared.offramp.*
import cz.faltyn.elfall.shared.swap.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppMode { HOME, SETTINGS, RECEIVE, TRANSFER, HISTORY, NETWORK, SWAP, WITHDRAW_CARD }

data class UiState(
    /** Výsledek runtime kontroly vydavatele FLC (stellar.toml + home_domain); null = zatím neověřeno. */
    val flcIssuerCheck: String? = null,
    val flcBlocked: Boolean = false,
    val standalone: Boolean = BuildConfig.STANDALONE,
    val network: BtcNetwork = BtcNetwork.DEFAULT,
    val elfallNetwork: ElfallNetwork = ElfallNetwork.DEFAULT,
    val mode: AppMode = AppMode.HOME,
    val hasSeed: Boolean = false,
    val unlocked: Boolean = false,
    val authBound: Boolean = false,
    val newMnemonic: List<String>? = null,
    val addressIndex: Long = 0,
    val address: String? = null,
    val balanceSats: Long? = null,
    // Transfer
    val transferAddress: String = "",
    val amountInput: String = "",
    val addressError: String? = null,
    val transferPreview: TransferFeePreview? = null,
    val lastTxid: String? = null,
    // History
    val stellarAccount: String = "",
    val history: List<HistoryItem> = emptyList(),
    val stellarHistory: List<HistoryItem> = emptyList(),
    // Network info (display only)
    val infoNetwork: BtcNetwork = BtcNetwork.DEFAULT,
    val snapshot: NetworkSnapshot? = null,
    // ELFALL-only
    val cashCurrency: FiatCurrency = FiatCurrency.CZK,
    val cashProviderIndex: Int = 0,
    val cashQuote: OffRampQuote? = null,
    val cashFlow: HostedFlow? = null,
    val swapFrom: String = "BTC",
    val swapTo: String = "ETH",
    val swapQuote: SwapQuote? = null,
    val swapFlow: SwapHostedFlow? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
) {
    val modes: List<AppMode> get() =
        if (standalone) listOf(AppMode.RECEIVE, AppMode.TRANSFER, AppMode.HISTORY, AppMode.NETWORK) else AppMode.entries.filter { it != AppMode.HOME && it != AppMode.SETTINGS }
}

class WalletViewModel(app: Application) : AndroidViewModel(app) {
    private val store = KeystoreSeedStore(app)
    private val network = BtcNetwork.fromName(BuildConfig.BTC_NETWORK)
    private val esplora = EsploraClient.forNetwork(network)
    private val horizon = HorizonClient()
    private val netInfo = NetworkInfoClient()
    private var wallet: WalletCore? = null
    private val offRampProviders = defaultOffRampProviders(OffRampConfig())
    private val swapProviders = defaultSwapProviders()

    private val _state = MutableStateFlow(
        UiState(
            network = network,
            hasSeed = store.hasSeed(),
            addressIndex = store.loadAddressIndex(),
            transferAddress = store.loadPayoutAddress().orEmpty(),
            stellarAccount = store.loadStellarAccount().orEmpty(),
        )
    )
    val state: StateFlow<UiState> = _state
    /** Ochrana proti swap útoku: ověří issuer FLC proti Horizonu a https://<home_domain>/.well-known/stellar.toml. */
    fun verifyFlcIssuer() {
        viewModelScope.launch {
            val r = cz.faltyn.elfall.shared.integrity.StellarTomlVerifier.default().verify()
            _state.update {
                when (r) {
                    is cz.faltyn.elfall.shared.integrity.IssuerCheck.Ok -> it.copy(flcIssuerCheck = "✅ Vydavatel FLC ověřen (stellar.toml)", flcBlocked = false)
                    is cz.faltyn.elfall.shared.integrity.IssuerCheck.Mismatch -> it.copy(flcIssuerCheck = "⛔ Nesoulad vydavatele FLC: ${r.reason}. Operace s FLC zablokovány.", flcBlocked = true)
                    is cz.faltyn.elfall.shared.integrity.IssuerCheck.Unavailable -> it.copy(flcIssuerCheck = "⚠️ Vydavatele FLC nelze ověřit: ${r.reason}")
                }
            }
        }
    }

    val offRampProviderNames get() = offRampProviders.map { it.id.displayName }

    private fun fail(e: Throwable, prefix: String = "") = _state.update { it.copy(error = prefix + (e.message ?: e::class.simpleName), busy = false) }

    // --- Unlock / create (call only after BiometricGate success) ---
    fun unlockAfterAuth() = runCatching {
        val words = store.loadMnemonic() ?: error("No wallet")
        open(words)
    }.onFailure { fail(it, "Unlock: ") }

    fun createWalletAfterAuth() = runCatching {
        val words = WalletCore.newMnemonic()
        store.saveMnemonic(words)
        open(words)
        _state.update { it.copy(newMnemonic = words, hasSeed = true) }
    }.onFailure { fail(it, "Create: ") }

    fun restoreWalletAfterAuth(input: String) = runCatching {
        val words = input.trim().lowercase().split(Regex("\\s+"))
        WalletCore.fromMnemonic(words, network)
        store.saveMnemonic(words)
        open(words)
        _state.update { it.copy(hasSeed = true) }
    }.onFailure { fail(it, "Invalid recovery phrase: ") }

    fun onAuthError(msg: String) = _state.update { it.copy(error = msg) }

    fun lock() { wallet = null; _state.update { it.copy(unlocked = false, newMnemonic = null) } }

    private fun open(words: List<String>) {
        val w = WalletCore.fromMnemonic(words, network)
        wallet = w
        val idx = store.loadAddressIndex()
        _state.update { it.copy(unlocked = true, authBound = store.isAuthBound, addressIndex = idx, address = w.receiveAddress(idx), error = null) }
        refresh()
    }

    fun backupConfirmed() = _state.update { it.copy(newMnemonic = null) }
    fun setMode(mode: AppMode) {
        _state.update { it.copy(mode = mode, error = null) }
        when (mode) { AppMode.HISTORY -> loadHistory(); AppMode.NETWORK -> loadNetworkInfo(); else -> Unit }
    }

    /** BIP84 next unused-style address: advance external index (gap ≤ 20). */
    fun nextAddress() {
        val w = wallet ?: return
        val idx = (_state.value.addressIndex + 1).coerceAtMost(19)
        store.saveAddressIndex(idx)
        _state.update { it.copy(addressIndex = idx, address = w.receiveAddress(idx)) }
    }

    private fun ownAddresses(): List<String> = wallet?.receiveAddresses((_state.value.addressIndex + 1).toInt()) ?: emptyList()

    fun refresh() {
        val addrs = ownAddresses().ifEmpty { return }
        viewModelScope.launch {
            runCatching { addrs.map { a -> async { esplora.balanceSats(a) } }.awaitAll().sum() }
                .onSuccess { b -> _state.update { it.copy(balanceSats = b, error = null) } }
                .onFailure { fail(it, "Balance: ") }
        }
    }

    // --- History ---
    fun onStellarAccountChange(v: String) { _state.update { it.copy(stellarAccount = v.trim()) }; store.saveStellarAccount(v.trim()) }

    fun loadHistory() {
        val addrs = ownAddresses()
        val g = _state.value.stellarAccount
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching {
                val tip = esplora.tipHeight()
                val txs = addrs.map { a -> async { esplora.addressTxs(a) } }.awaitAll().flatten().distinctBy { it.txid }
                HistoryMapper.fromEsplora(txs, addrs.toSet(), tip, network.explorerUrl)
                    .sortedByDescending { it.timeEpochSec ?: Long.MAX_VALUE }
            }.onSuccess { h -> _state.update { it.copy(history = h, busy = false) } }.onFailure { fail(it, "History: ") }
            if (PaymentUriParser.isStellarAccount(g)) {
                runCatching { horizon.toHistory(g, horizon.payments(g)) }
                    .onSuccess { h -> _state.update { it.copy(stellarHistory = h) } }
                    .onFailure { e -> _state.update { it.copy(stellarHistory = emptyList(), info = "Stellar: ${e.message}") } }
            }
        }
    }

    // --- Network info (read-only) ---
    fun setInfoNetwork(n: BtcNetwork) { _state.update { it.copy(infoNetwork = n, snapshot = null) }; loadNetworkInfo() }

    fun loadNetworkInfo() {
        val n = _state.value.infoNetwork
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching { netInfo.snapshot(n) }
                .onSuccess { s -> _state.update { it.copy(snapshot = s, busy = false) } }
                .onFailure { fail(it, "Network: ") }
        }
    }

    // --- QR scan result ---
    fun onScanned(raw: String) {
        when (val p = PaymentUriParser.parse(raw)) {
            is ParsedPayment.Bitcoin -> {
                onTransferAddressChange(p.address)
                p.amountSats?.let { s -> _state.update { it.copy(amountInput = s.satsToBtcString()) } }
                _state.update { it.copy(mode = AppMode.TRANSFER) }
            }
            is ParsedPayment.Stellar -> {
                onStellarAccountChange(p.destination)
                _state.update { it.copy(info = "Stellar ${p.destination.take(8)}… ${p.amount ?: ""} ${p.assetCode ?: "XLM"} — podpis v LOBSTR/Freighter (SEP-7)") }
            }
            is ParsedPayment.Unknown -> _state.update { it.copy(error = "Neznámý QR / Unknown QR") }
        }
    }

    // --- Transfer ---
    fun onTransferAddressChange(v: String) {
        val err = if (v.isBlank()) null else (AddressValidator.check(v, network) as? AddressCheck.Invalid)?.reason
        _state.update { it.copy(transferAddress = v, addressError = err, transferPreview = null) }
    }
    fun saveTransferAddress() { val a = _state.value.transferAddress.trim(); if (AddressValidator.isValid(a, network)) store.savePayoutAddress(a) }
    fun onAmountChange(v: String) = _state.update { it.copy(amountInput = v, transferPreview = null, cashQuote = null, cashFlow = null, swapQuote = null) }

    fun previewTransfer() {
        val w = wallet ?: return
        val s = _state.value
        val sats = parseBtcToSats(s.amountInput) ?: run { _state.update { it.copy(error = "Invalid amount") }; return }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { TransferService(w, esplora).preview(s.transferAddress, sats) }
                .onSuccess { p -> _state.update { it.copy(transferPreview = p, busy = false) } }.onFailure { fail(it) }
        }
    }

    fun sendTransferConfirmed() {
        val w = wallet ?: return
        val p = _state.value.transferPreview ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { TransferService(w, esplora).send(p) }
                .onSuccess { txid -> _state.update { it.copy(lastTxid = txid, transferPreview = null, busy = false) }; refresh() }
                .onFailure { fail(it) }
        }
    }

    // --- ELFALL-only (hidden in standalone Eleanor) ---
    fun setElfallNetwork(n: ElfallNetwork) {
        if (n == ElfallNetwork.MAINNET) { _state.update { it.copy(error = "Mainnet je zatím zamčený / Mainnet locked") }; return }
        _state.update { it.copy(elfallNetwork = n, error = null) }
    }
    fun stellarAssetLabels(): List<String> = DevAssets.stellarTestnetAssets().map { a ->
        if (a.frozen) "${a.symbol} (zmrazeno)" else if (a.referenceUsd != null) "${a.symbol} (ref ${a.referenceUsd} USD, testnet)" else a.symbol
    }
    val flcReferenceUsd get() = FLC_REFERENCE_USD

    fun setCashCurrency(c: FiatCurrency) = _state.update { it.copy(cashCurrency = c, cashQuote = null, cashFlow = null) }
    fun setCashProvider(i: Int) = _state.update { it.copy(cashProviderIndex = i, cashQuote = null, cashFlow = null) }
    fun quoteCashOut() {
        val addr = _state.value.address ?: return
        val s = _state.value
        val amount = s.amountInput.replace(',', '.').toDoubleOrNull() ?: run { _state.update { it.copy(error = "Invalid amount") }; return }
        val provider = offRampProviders.getOrNull(s.cashProviderIndex) ?: return
        viewModelScope.launch {
            runCatching { val req = OffRampQuoteRequest(amount, "BTC", s.cashCurrency, addr); val q = provider.quote(req); q to provider.buildHostedFlowUrl(req, q) }
                .onSuccess { (q, f) -> _state.update { it.copy(cashQuote = q, cashFlow = f) } }.onFailure { fail(it) }
        }
    }
    fun setSwapAssets(from: String, to: String) = _state.update { it.copy(swapFrom = from, swapTo = to, swapQuote = null, swapFlow = null) }
    fun quoteSwap() {
        val addr = _state.value.address ?: return
        val s = _state.value
        val amount = s.amountInput.replace(',', '.').toDoubleOrNull() ?: run { _state.update { it.copy(error = "Invalid amount") }; return }
        viewModelScope.launch {
            runCatching { val p = swapProviders.first(); val req = SwapQuoteRequest(s.swapFrom, s.swapTo, amount, addr); val q = p.quote(req); q to p.buildHostedFlowUrl(req, q) }
                .onSuccess { (q, f) -> _state.update { it.copy(swapQuote = q, swapFlow = f) } }.onFailure { fail(it) }
        }
    }
}
