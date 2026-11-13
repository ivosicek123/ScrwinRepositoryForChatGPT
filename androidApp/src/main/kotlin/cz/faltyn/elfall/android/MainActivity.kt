package cz.faltyn.elfall.android

import cz.faltyn.elfall.shared.identity.BankIdStrings
import cz.faltyn.elfall.shared.identity.IdentityFlags
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import cz.faltyn.elfall.shared.BtcNetwork
import cz.faltyn.elfall.shared.Bip21
import cz.faltyn.elfall.shared.HistoryItem
import cz.faltyn.elfall.shared.assets.FLC_REFERENCE_DISCLAIMER_CS
import cz.faltyn.elfall.shared.network.ElfallNetwork
import cz.faltyn.elfall.shared.offramp.FiatCurrency
import cz.faltyn.elfall.shared.offramp.OffRampValidation
import cz.faltyn.elfall.shared.satsToBtcString
import java.text.SimpleDateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {
    private val vm: WalletViewModel by viewModels()

    private val scanLauncher = registerForActivityResult(ScanContract()) { r -> r.contents?.let(vm::onScanned) }

    private fun auth(title: String, then: () -> Unit) =
        BiometricGate.confirm(this, title, getString(R.string.biometric_subtitle)) { ok, err ->
            if (ok) then() else err?.let { vm.onAuthError(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val s by vm.state.collectAsStateWithLifecycle()
                    AppScreen(
                        s, vm,
                        authThen = ::auth,
                        onScan = {
                            scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("BTC / BIP21 / Stellar").setBeepEnabled(false).setOrientationLocked(true))
                        },
                    )
                }
            }
        }
    }

    override fun onStop() { super.onStop(); if (!isChangingConfigurations) vm.lock() }
}

private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm")

@Composable
fun AppScreen(s: UiState, vm: WalletViewModel, authThen: (String, () -> Unit) -> Unit, onScan: () -> Unit) {
    val ctx = LocalContext.current
    fun openUrl(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("BTC: ${s.network.name}" + if (s.standalone) " · standalone" else "", style = MaterialTheme.typography.labelMedium)
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        s.info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        if (!s.standalone) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ElfallNetwork.entries.forEach { n ->
                    FilterChip(selected = s.elfallNetwork == n, onClick = { vm.setElfallNetwork(n) }, label = { Text(n.displayName) })
                }
            }
            Text("Stellar testnet: ${vm.stellarAssetLabels().joinToString()}", style = MaterialTheme.typography.bodySmall)
            Text("FLC ref ${vm.flcReferenceUsd} USD — $FLC_REFERENCE_DISCLAIMER_CS", style = MaterialTheme.typography.bodySmall)
        }

        // Network info is readable without unlocking
        if (!s.unlocked) {
            if (s.hasSeed) {
                Button(onClick = { authThen("Odemknout / Unlock") { vm.unlockAfterAuth() } }) { Text("Odemknout peněženku / Unlock") }
            } else {
                var restore by remember { mutableStateOf("") }
                Button(onClick = { authThen("Nová peněženka") { vm.createWalletAfterAuth() } }) { Text(stringResource(R.string.create_wallet)) }
                OutlinedTextField(restore, { restore = it }, label = { Text(stringResource(R.string.restore_wallet)) }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { authThen("Obnovit") { vm.restoreWalletAfterAuth(restore) } }) { Text(stringResource(R.string.restore_wallet)) }
            }
            HorizontalDivider()
            NetworkPanel(s, vm)
            return@Column
        }

        if (!s.authBound) Text("⚠ Zařízení nemá zámek obrazovky — klíč není vázán na biometrii.", color = MaterialTheme.colorScheme.error)

        s.newMnemonic?.let { words ->
            Card { Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.backup_warning))
                Text(words.joinToString(" "), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = vm::backupConfirmed) { Text("Zapsáno / I wrote them down") }
            } }
        }

        BalanceCard(s)

        if (s.mode == AppMode.HOME) {
            HomeGrid(s, onOpen = vm::setMode, onScan = onScan)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.setMode(AppMode.HOME) }) { Text("← Domů") }
                Text(label(s.mode), style = MaterialTheme.typography.titleLarge)
            }
        }

        when (s.mode) {
            AppMode.HOME -> Unit
            AppMode.SETTINGS -> SettingsPanel(s, vm)
            AppMode.RECEIVE -> {
                val addr = s.address.orEmpty()
                Text("${stringResource(R.string.receive_address)} (#${s.addressIndex})")
                SelectionContainer { Text(addr) }
                if (addr.isNotEmpty()) {
                    val bmp = remember(addr) { QrCode.bitmap(Bip21.build(addr)) }
                    Image(bmp.asImageBitmap(), contentDescription = "QR", modifier = Modifier.size(240.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::nextAddress) { Text("Nová adresa / New address") }
                    OutlinedButton(onClick = vm::refresh) { Text(stringResource(R.string.refresh)) }
                }
            }
            AppMode.TRANSFER -> {
                Text(stringResource(R.string.transfer_title), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onScan) { Text("Skenovat QR / Scan QR") }
                OutlinedTextField(s.transferAddress, vm::onTransferAddressChange, label = { Text(stringResource(R.string.destination_address)) },
                    isError = s.addressError != null, supportingText = { s.addressError?.let { Text(it) } }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = vm::saveTransferAddress) { Text(stringResource(R.string.save_address)) }
                AmountField(s.amountInput, vm::onAmountChange)
                Button(onClick = vm::previewTransfer, enabled = !s.busy && s.addressError == null && s.transferAddress.isNotBlank()) { Text(stringResource(R.string.preview_fee)) }
                s.transferPreview?.let { p ->
                    Card { Column(Modifier.padding(12.dp)) {
                        Text("To: ${p.destination}"); Text("Amount: ${p.amountSats.satsToBtcString()} BTC")
                        Text("Fee: ${p.feeSats} sats (${p.feeRateSatPerVb} sat/vB)")
                        Button(onClick = { authThen(ctx.getString(R.string.biometric_title)) { vm.sendTransferConfirmed() } }, enabled = !s.busy) { Text(stringResource(R.string.confirm_send)) }
                    } }
                }
                s.lastTxid?.let { Text("Odesláno / Sent: $it", modifier = Modifier.clickable { openUrl("${s.network.explorerUrl}/tx/$it") }) }
            }
            AppMode.HISTORY -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::loadHistory) { Text(stringResource(R.string.refresh)) }
                    if (s.busy) CircularProgressIndicator(Modifier.size(24.dp))
                }
                Text("Bitcoin", style = MaterialTheme.typography.titleSmall)
                if (s.history.isEmpty() && !s.busy) EmptyState("📭", "Zatím žádné transakce", "Pošlete si testovací BTC z faucetu na adresu v sekci Přijmout.")
                s.history.forEach { HistoryRow(it, "BTC", ::openUrl) }
                HorizontalDivider()
                Text("Stellar (testnet, jen čtení)", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(s.stellarAccount, vm::onStellarAccountChange, label = { Text("Stellar účet G…") }, modifier = Modifier.fillMaxWidth())
                s.stellarHistory.forEach { HistoryRow(it, it.asset, ::openUrl) }
            }
            AppMode.NETWORK -> NetworkPanel(s, vm)
            AppMode.WITHDRAW_CARD -> {
                Text(stringResource(R.string.withdraw_card_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.withdraw_card_hint), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FiatCurrency.supported.forEach { c -> FilterChip(selected = s.cashCurrency == c, onClick = { vm.setCashCurrency(c) }, label = { Text(c.code) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.offRampProviderNames.forEachIndexed { i, n -> FilterChip(selected = s.cashProviderIndex == i, onClick = { vm.setCashProvider(i) }, label = { Text(n) }) }
                }
                AmountField(s.amountInput, vm::onAmountChange)
                if (IdentityFlags.bankIdEnabled) {
                    // Potvrzovací krok před výběrem: step-up Bank iD svázaný s hashem transakce (WithdrawalGate)
                    OutlinedButton(onClick = { }) { Text(BankIdStrings.CONFIRM_WITHDRAWAL) }
                }
                Button(onClick = vm::quoteCashOut) { Text(stringResource(R.string.get_quote)) }
                s.cashQuote?.let { q -> Card { Column(Modifier.padding(12.dp)) {
                    Text("${q.amountCrypto} ${q.cryptoAsset} → ${OffRampValidation.formatFiat(q.fiatNetMinor, q.currency)}")
                    if (q.notes.isNotBlank()) Text(q.notes, style = MaterialTheme.typography.bodySmall)
                    s.cashFlow?.let { f -> Button(onClick = { openUrl(f.url) }) { Text(stringResource(R.string.open_provider)) } }
                } } }
            }
            AppMode.SWAP -> {
                Text(stringResource(R.string.swap_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(s.swapFrom, { vm.setSwapAssets(it, s.swapTo) }, label = { Text(stringResource(R.string.from_asset)) })
                OutlinedTextField(s.swapTo, { vm.setSwapAssets(s.swapFrom, it) }, label = { Text(stringResource(R.string.to_asset)) })
                AmountField(s.amountInput, vm::onAmountChange)
                Button(onClick = vm::quoteSwap) { Text(stringResource(R.string.get_quote)) }
                s.swapQuote?.let { q -> Text("${q.amountIn} ${q.fromAsset} → ~${q.amountOut} ${q.toAsset}") }
                s.swapFlow?.let { f -> Button(onClick = { openUrl(f.url) }) { Text(stringResource(R.string.open_provider)) } }
            }
        }
    }
}

@Composable
private fun NetworkPanel(s: UiState, vm: WalletViewModel) {
    LaunchedEffect(Unit) { if (s.snapshot == null) vm.loadNetworkInfo() }
    Text("Síť / Network (jen informace)", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(BtcNetwork.TESTNET, BtcNetwork.MAINNET).forEach { n ->
            FilterChip(selected = s.infoNetwork == n, onClick = { vm.setInfoNetwork(n) }, label = { Text(n.name.lowercase()) })
        }
        OutlinedButton(onClick = vm::loadNetworkInfo) { Text("↻") }
    }
    if (s.infoNetwork == BtcNetwork.MAINNET) Text("Mainnet data jen pro zobrazení — žádné podepisování.", style = MaterialTheme.typography.bodySmall)
    val snap = s.snapshot ?: run { Text("Načítám… / Loading…"); return }
    Text("Poplatky (sat/vB): rychle ${snap.fees.fastestFee} · 30 min ${snap.fees.halfHourFee} · 1 h ${snap.fees.hourFee}")
    Text("Mempool: ${snap.mempool.count} tx · ${snap.mempool.vsize / 1_000_000.0} MvB")
    Text("Výška bloku / Block height: ${snap.tipHeight}")
    snap.prices?.let { p -> Text("BTC (mainnet trh): ${p.usd?.toLong() ?: "?"} USD · ${p.eur?.toLong() ?: "?"} EUR" + (p.czk?.let { " · ${it.toLong()} CZK" } ?: "")) }
    Text("Poslední bloky:", style = MaterialTheme.typography.titleSmall)
    snap.recentBlocks.forEach { b -> Text("#${b.height} · ${b.txCount} tx · ${dateFmt.format(Date(b.timestamp * 1000))}", style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun HistoryRow(h: HistoryItem, asset: String, open: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { open(h.explorerUrl) }) {
        Column(Modifier.padding(10.dp)) {
            Text("${if (asset == "BTC" && h.netSats > 0) "+" else ""}${h.amountText} $asset")
            Text("${if (h.confirmations > 0) "${h.confirmations} potvrzení" else "nepotvrzeno"} · ${h.timeEpochSec?.let { dateFmt.format(Date(it * 1000)) } ?: "mempool"}",
                style = MaterialTheme.typography.bodySmall)
            Text(h.id.take(16) + "…", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun label(m: AppMode) = when (m) {
    AppMode.HOME -> "Domů"
    AppMode.SETTINGS -> "Nastavení"
    AppMode.RECEIVE -> stringResource(R.string.mode_receive)
    AppMode.TRANSFER -> stringResource(R.string.mode_transfer)
    AppMode.HISTORY -> "Historie / History"
    AppMode.NETWORK -> "Síť / Network"
    AppMode.SWAP -> stringResource(R.string.mode_swap)
    AppMode.WITHDRAW_CARD -> stringResource(R.string.mode_withdraw_card)
}

@Composable
private fun AmountField(value: String, onChange: (String) -> Unit) = OutlinedTextField(
    value, onChange, label = { Text(stringResource(R.string.amount_btc)) },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
)

private data class Activity(val emoji: String, val title: String, val subtitle: String, val mode: AppMode?)

@Composable
private fun HomeGrid(s: UiState, onOpen: (AppMode) -> Unit, onScan: () -> Unit) {
    val items = buildList {
        add(Activity("📥", "Přijmout", "Adresa a QR kód", AppMode.RECEIVE))
        add(Activity("📤", "Převést", "Poslat BTC na adresu", AppMode.TRANSFER))
        add(Activity("📷", "Skenovat QR", "BTC / BIP21 / Stellar", null))
        add(Activity("🧾", "Historie", "Transakce a potvrzení", AppMode.HISTORY))
        add(Activity("🌐", "Síť", "Poplatky, mempool, bloky", AppMode.NETWORK))
        if (!s.standalone) {
            add(Activity("🔁", "Směna", "Swap přes poskytovatele", AppMode.SWAP))
            add(Activity("💳", "Výběr na kartu", "CZK / EUR / USD", AppMode.WITHDRAW_CARD))
        }
        add(Activity("⚙️", "Nastavení", "Zabezpečení a síť", AppMode.SETTINGS))
    }
    items.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { a ->
                ElevatedCard(
                    onClick = { a.mode?.let(onOpen) ?: onScan() },
                    modifier = Modifier.weight(1f).heightIn(min = 108.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(a.emoji, style = MaterialTheme.typography.headlineSmall)
                        Text(a.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(a.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun BalanceCard(s: UiState) = Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    modifier = Modifier.fillMaxWidth(),
) {
    Column(Modifier.padding(18.dp)) {
        Text("Zůstatek", style = MaterialTheme.typography.labelLarge)
        Text("${s.balanceSats?.satsToBtcString() ?: "…"} ${if (s.network == BtcNetwork.MAINNET) "BTC" else "tBTC"}",
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Síť: ${s.network.name.lowercase()} · adresa #${s.addressIndex}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyState(emoji: String, title: String, hint: String) = Column(
    Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp),
) {
    Text(emoji, style = MaterialTheme.typography.displaySmall)
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text(hint, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
}

@Composable
private fun SettingsPanel(s: UiState, vm: WalletViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Zabezpečení", style = MaterialTheme.typography.titleMedium)
            Text(if (s.authBound) "✅ Klíč chráněn biometrií / zámkem obrazovky" else "⚠️ Bez zámku obrazovky – nastavte PIN/otisk")
            Text("Seed je uložen jen v tomto zařízení (Android Keystore). Nikdy ho nesdílejte.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Síť: ${s.network.name.lowercase()} (podpis jen na testnetu)")
            Text(s.flcIssuerCheck ?: "Vydavatel FLC: neověřeno", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.verifyFlcIssuer() }) { Text("Ověřit vydavatele FLC") }
            Text(stringResource(R.string.about_author), style = MaterialTheme.typography.bodySmall)
            Text("Verze: ${BuildConfig.VERSION_NAME}${if (s.standalone) " · Eleanor standalone" else " · ELFALL"}", style = MaterialTheme.typography.bodySmall)
            Text("Podmínky použití: docs/terms-cs.md", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = vm::lock) { Text("🔒 Zamknout peněženku") }
            if (IdentityFlags.bankIdEnabled) { // feature flag, výchozí VYPNUTO (sandbox)
                HorizontalDivider()
                // TODO(bankid): napojit na BankIdVerifier.start(KYC) + otevřít authorize_url v Custom Tab
                OutlinedButton(onClick = { }) { Text(BankIdStrings.VERIFY_BUTTON) }
                Text(BankIdStrings.SANDBOX_NOTE, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
