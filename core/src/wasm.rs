//! wasm-bindgen rozhraní pro web. Úmyslně jen veřejné operace (rámování, ověření podpisu):
//! v prohlížeči nedržíme dlouhodobé tajné klíče (XSS) – podepisuje autentizátor/passkey nebo
//! mobilní aplikace. Viz docs/security/ZERO-TRUST-ROADMAP.md.
use crate::{framing, sign};
use wasm_bindgen::prelude::*;

fn js(e: crate::CoreError) -> JsError { JsError::new(&e.to_string()) }

#[wasm_bindgen(js_name = signingInput)]
pub fn signing_input(msg: &[u8]) -> Result<Vec<u8>, JsError> { framing::frame(msg).map_err(js) }

#[wasm_bindgen(js_name = parseSigningInput)]
pub fn parse_signing_input(input: &[u8]) -> Result<Vec<u8>, JsError> { framing::unframe(input).map(<[u8]>::to_vec).map_err(js) }

#[wasm_bindgen(js_name = verifyFramed)]
pub fn verify_framed(public: &[u8], msg: &[u8], sig: &[u8]) -> Result<bool, JsError> { sign::verify_framed(public, msg, sig).map_err(js) }

// ---- validace vstupů (přesunuto z web/app.js) ----
use crate::validate;

/// Vrací `{ address, amountSats }` (amountSats = undefined, pokud chybí/neplatná).
#[wasm_bindgen(js_name = parsePayment)]
pub fn parse_payment(raw: &str) -> js_sys_free::PaymentJs {
    let p = validate::parse_payment(raw);
    js_sys_free::PaymentJs { address: p.address, amount_sats: p.amount_sats.map(|s| s as f64) }
}

/// `undefined` = platná testnet adresa, jinak text chyby.
#[wasm_bindgen(js_name = checkAddress)]
pub fn check_address(a: &str) -> Option<String> { validate::check_testnet_address(a).map(str::to_owned) }

#[wasm_bindgen(js_name = parseBtcAmount)]
pub fn parse_btc_amount(s: &str) -> Result<f64, JsError> { validate::parse_btc_amount(s).map(|v| v as f64).map_err(JsError::new) }

#[wasm_bindgen(js_name = formatSats)]
pub fn format_sats(sats: f64) -> Result<String, JsError> {
    if !(0.0..=validate::MAX_SATS as f64).contains(&sats) || sats.fract() != 0.0 { return Err(JsError::new("Neplatná částka")); }
    Ok(validate::format_sats(sats as u64))
}

#[wasm_bindgen(js_name = rpIdMatches)]
pub fn rp_id_matches(rp_id: &str, host: &str) -> bool { validate::rp_id_matches(rp_id, host) }

mod js_sys_free {
    use wasm_bindgen::prelude::*;
    #[wasm_bindgen(getter_with_clone)]
    pub struct PaymentJs {
        pub address: String,
        #[wasm_bindgen(js_name = amountSats)]
        pub amount_sats: Option<f64>,
    }
}
