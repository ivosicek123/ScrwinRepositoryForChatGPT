//! Validace vstupů webu (dříve JS v web/app.js): platební URI, testnet adresy s plnou kontrolou
//! kontrolního součtu (bech32/bech32m dle BIP-173/350, Base58Check), částky BTC, rpId passkey.
//! Čistý Rust – stejný kód běží nativně i ve wasm.

pub const MAX_SATS: u64 = 21_000_000 * 100_000_000;

#[derive(Debug, PartialEq, Eq)]
pub struct Payment {
    pub address: String,
    pub amount_sats: Option<u64>,
}

/// `bitcoin:<addr>?amount=<BTC>` nebo holá adresa. Neplatná částka => `None` (ne chyba adresy).
pub fn parse_payment(raw: &str) -> Payment {
    let raw = raw.trim();
    let Some(rest) = raw.get(..8).filter(|p| p.eq_ignore_ascii_case("bitcoin:")).map(|_| &raw[8..]) else {
        return Payment { address: raw.to_owned(), amount_sats: None };
    };
    let (addr, query) = rest.split_once('?').unwrap_or((rest, ""));
    let amount_sats = query.split('&').find_map(|kv| kv.strip_prefix("amount=")).and_then(|v| parse_btc_amount(v).ok());
    Payment { address: addr.to_owned(), amount_sats }
}

/// `None` = platná testnet adresa; jinak česká chybová hláška (shodná s původním JS).
pub fn check_testnet_address(a: &str) -> Option<&'static str> {
    let a = a.trim();
    if a.is_empty() {
        return Some("Zadejte adresu");
    }
    if let Ok((hrp, _ver, _prog)) = bech32::segwit::decode(a) {
        return match hrp.to_string().to_ascii_lowercase().as_str() {
            "tb" => None,
            "bc" => Some("To je mainnet adresa – Eleanor podepisuje jen na testnetu"),
            _ => Some("Neplatný formát testnet adresy"),
        };
    }
    if let Ok(p) = bs58::decode(a).with_check(None).into_vec() {
        if p.len() == 21 {
            return match p[0] {
                0x6f | 0xc4 => None,
                0x00 | 0x05 => Some("To je mainnet adresa – Eleanor podepisuje jen na testnetu"),
                _ => Some("Neplatný formát testnet adresy"),
            };
        }
    }
    let l = a.to_ascii_lowercase();
    if l.starts_with("bc1") || a.starts_with('1') || a.starts_with('3') {
        return Some("To je mainnet adresa – Eleanor podepisuje jen na testnetu");
    }
    Some("Neplatný formát testnet adresy")
}

/// Desetinná částka v BTC (max 8 desetinných míst, max 21 M BTC) na satoshi – bez floatů.
pub fn parse_btc_amount(s: &str) -> Result<u64, &'static str> {
    let s = s.trim();
    let (int, frac) = s.split_once('.').unwrap_or((s, ""));
    if int.is_empty() && frac.is_empty() || frac.len() > 8 || !int.chars().chain(frac.chars()).all(|c| c.is_ascii_digit()) || int.len() > 8 {
        return Err("Neplatná částka");
    }
    let i: u64 = if int.is_empty() { 0 } else { int.parse().map_err(|_| "Neplatná částka")? };
    let f: u64 = format!("{frac:0<8}").parse().map_err(|_| "Neplatná částka")?;
    let sats = i.checked_mul(100_000_000).and_then(|x| x.checked_add(f)).ok_or("Neplatná částka")?;
    if sats == 0 || sats > MAX_SATS { Err("Neplatná částka") } else { Ok(sats) }
}

pub fn format_sats(sats: u64) -> String {
    format!("{}.{:08}", sats / 100_000_000, sats % 100_000_000)
}

/// WebAuthn: rpId musí být host stránky nebo jeho nadřazená doména (ne jen sufix řetězce).
pub fn rp_id_matches(rp_id: &str, host: &str) -> bool {
    !rp_id.is_empty() && (host == rp_id || host.strip_suffix(rp_id).is_some_and(|p| p.ends_with('.')))
}
