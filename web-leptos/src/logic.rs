//! Čistá logika obrazovek (bez DOM) – testuje se nativně (`cargo test`) i ve wasm.
//! Validace/formátování deleguje na `elfall-core` (stejný kód jako Android/iOS/UniFFI).
use elfall_core::validate;
use serde::Deserialize;

pub const API_TESTNET: &str = "https://mempool.space/testnet/api";
pub const API_MAINNET: &str = "https://mempool.space/api";
pub const EXPLORER_TESTNET: &str = "https://mempool.space/testnet";

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Net { Testnet, Mainnet }
impl Net {
    pub fn api(self) -> &'static str { match self { Net::Testnet => API_TESTNET, Net::Mainnet => API_MAINNET } }
}

/// Povolené cíle síťových dotazů = `connect-src` v CSP. Cokoli jiného se odmítne ještě před fetch.
pub fn allowed_url(u: &str) -> bool {
    [API_TESTNET, API_MAINNET].iter().any(|b| u.starts_with(&format!("{b}/")))
        && !u.contains("..") && !u.contains('@') && !u.contains('#')
}

/// Výsledek kontroly cílové adresy pro obrazovku Převést.
#[derive(Debug, PartialEq)]
pub enum DestCheck { Empty, Ok { address: String, amount: Option<String> }, Err(String) }

pub fn check_destination(raw: &str) -> DestCheck {
    if raw.trim().is_empty() { return DestCheck::Empty; }
    let p = validate::parse_payment(raw);
    match validate::check_testnet_address(&p.address) {
        None => DestCheck::Ok { amount: p.amount_sats.map(validate::format_sats), address: p.address },
        Some(e) => DestCheck::Err(e.to_owned()),
    }
}

/// Uloží jen platnou testnet adresu (watch-only), jinak chyba.
pub fn validate_watch(raw: &str) -> Result<String, String> {
    let p = validate::parse_payment(raw);
    match validate::check_testnet_address(&p.address) { None => Ok(p.address), Some(e) => Err(e.to_owned()) }
}

pub fn short(addr: &str) -> String {
    if addr.chars().count() <= 20 { return addr.to_owned(); }
    let c: Vec<char> = addr.chars().collect();
    format!("{}…{}", c[..10].iter().collect::<String>(), c[c.len() - 8..].iter().collect::<String>())
}

#[derive(Deserialize, Debug, Clone, PartialEq)]
pub struct Fees { #[serde(rename = "fastestFee")] pub fastest: f64, #[serde(rename = "halfHourFee")] pub half_hour: f64, #[serde(rename = "hourFee")] pub hour: f64 }

/// Odhad poplatku v sat pro `vbytes` (1..=100 000 vB) a sazbu sat/vB.
pub fn fee_estimate(vbytes: &str, rate: f64) -> Option<String> {
    let vb: u64 = vbytes.trim().parse().ok().filter(|v| (1..=100_000).contains(v))?;
    if !(rate.is_finite() && (0.0..=10_000.0).contains(&rate)) { return None; }
    let sats = (vb as f64 * rate).ceil() as u64;
    Some(format!("{sats} sat ({} BTC)", validate::format_sats(sats)))
}

#[derive(Deserialize, Debug, Clone, PartialEq)]
pub struct ChainStats { pub funded_txo_sum: u64, pub spent_txo_sum: u64 }
#[derive(Deserialize, Debug, Clone, PartialEq)]
pub struct AddrInfo { pub chain_stats: ChainStats, pub mempool_stats: ChainStats }

pub fn balance_text(a: &AddrInfo) -> String {
    let conf = a.chain_stats.funded_txo_sum.saturating_sub(a.chain_stats.spent_txo_sum);
    let pend = a.mempool_stats.funded_txo_sum as i128 - a.mempool_stats.spent_txo_sum as i128;
    let mut s = format!("{} tBTC", validate::format_sats(conf));
    if pend != 0 { s += &format!(" ({}{} nepotvrzeno)", if pend > 0 { "+" } else { "−" }, validate::format_sats(pend.unsigned_abs() as u64)); }
    s
}

#[derive(Deserialize, Debug, Clone, PartialEq)]
pub struct TxStatus { pub confirmed: bool, pub block_height: Option<u64> }
#[derive(Deserialize, Debug, Clone, PartialEq)]
pub struct Tx { pub txid: String, pub status: TxStatus }

/// txid musí být 64 hex znaků – jinak se odkaz nevytvoří (ochrana před injekcí do URL).
pub fn tx_link(txid: &str) -> Option<String> {
    (txid.len() == 64 && txid.bytes().all(|b| b.is_ascii_hexdigit())).then(|| format!("{EXPLORER_TESTNET}/tx/{txid}"))
}

pub const GLOSSARY: &[(&str, &str)] = &[
    ("poplatek", "Poplatek (sat/vB) platíte těžařům za zařazení transakce do bloku. Vyšší = rychlejší."),
    ("mempool", "Mempool je čekárna nepotvrzených transakcí."),
    ("seed", "12 slov = záloha peněženky. Nikdy je nikomu nesdělujte, ani asistentovi."),
    ("testnet", "Testovací síť – mince nemají hodnotu. Eleanor podepisuje jen na testnetu."),
    ("potvrzení", "Počet bloků nad transakcí. 1+ = zapsáno, 6 = velmi bezpečné."),
    ("bip21", "Formát bitcoin:adresa?amount=… pro QR kódy."),
];

#[derive(Debug, PartialEq)]
pub enum HelpAnswer { None, Warning, Hits(Vec<(&'static str, &'static str)>), NotFound }

pub fn help(q: &str) -> HelpAnswer {
    let q = q.trim().to_lowercase();
    if q.is_empty() { return HelpAnswer::None; }
    let words: Vec<&str> = q.split(|c: char| !c.is_alphanumeric()).collect();
    let secret = words.iter().any(|w| ["heslo", "seed", "privátní", "private", "klíč"].contains(w));
    let share = ["pošli", "zadej", "sdílej", "send", "share"].iter().any(|w| q.contains(w));
    if secret && share { return HelpAnswer::Warning; }
    let hits: Vec<_> = GLOSSARY.iter().copied().filter(|(k, v)| k.contains(&q) || v.to_lowercase().contains(&q)).collect();
    if hits.is_empty() { HelpAnswer::NotFound } else { HelpAnswer::Hits(hits) }
}

/// QR jako SVG cesta (moduly), bez inline stylů/skriptů → kompatibilní s přísnou CSP.
pub fn qr_path(text: &str) -> Option<(i32, String)> {
    let q = qrcodegen::QrCode::encode_text(text, qrcodegen::QrCodeEcc::Medium).ok()?;
    let n = q.size();
    let mut d = String::new();
    for y in 0..n { for x in 0..n { if q.get_module(x, y) { d += &format!("M{} {}h1v1h-1z", x + 4, y + 4); } } }
    Some((n + 8, d))
}

pub const SECURITY_ITEMS: &[(&str, &str)] = &[
    ("Aktualizovaný systém iOS/Android a aplikace", "Nastavení → Obecné → Aktualizace softwaru."),
    ("Zámek obrazovky (min. 6místný kód) + Face ID / otisk", "Bez zámku nelze klíč peněženky svázat s biometrií."),
    ("Apple ID / Google účet: 2FA a hardwarový bezpečnostní klíč (FIDO2)", "Bezpečnostní klíče chrání před phishingem."),
    ("Bezpečnostní kontrola (Safety Check) na iPhonu", "Nastavení → Soukromí a zabezpečení → Bezpečnostní kontrola: zkontrolujte sdílení polohy, přístupy aplikací a zařízení."),
    ("Žádné neznámé profily, VPN ani správa zařízení (MDM)", "Nastavení → Obecné → VPN a správa zařízení – smažte, co neznáte."),
    ("Sdílení polohy v Najít (Find My) jen s lidmi, které znáte", "Aplikace Najít → Lidé."),
    ("PIN / heslo u mobilního operátora proti SIM swapu a přenosu čísla", "Požádejte operátora o zákaz výměny SIM bez osobní návštěvy."),
    ("Seed (12/24 slov) jen na papíře/kovu, offline", "Nikdy ne do fotek, cloudu, e-mailu ani chatu. Nikdo z ELFALL o seed nikdy nežádá."),
    ("Neinstalovat aplikace pro vzdálený přístup na výzvu cizích lidí", "AnyDesk/TeamViewer „podpora“ je běžný podvod."),
    ("Odkazy na platby otevírat jen z ověřených zdrojů", "Kontrolujte doménu a adresu příjemce před potvrzením."),
    ("Veřejné Wi‑Fi jen s opatrností; doma silné heslo routeru a aktuální firmware", "Výchozí heslo routeru změňte, vypněte WPS a vzdálenou správu."),
    ("Zkontrolovat spárovaná Bluetooth zařízení", "Nastavení → Bluetooth: odstraňte neznámá zařízení. Bluetooth má dosah jen desítky metrů."),
    ("Při podezření: Režim izolace (Lockdown Mode)", "Nastavení → Soukromí a zabezpečení → Režim izolace."),
];

/// Stav checklistu: JSON pole bool; poškozený záznam = vše nesplněno.
pub fn parse_checklist(s: Option<&str>) -> Vec<bool> {
    let mut v: Vec<bool> = s.and_then(|s| serde_json::from_str(s).ok()).unwrap_or_default();
    v.resize(SECURITY_ITEMS.len(), false);
    v
}

/// Klíče localStorage, které aplikace smí zapisovat – nikdy tajemství (žádné klíče, seed, tokeny).
pub const STORAGE_KEYS: &[&str] = &["eleanor.watch", "elfall.securityChecklist"];
