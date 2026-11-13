//! WebAuthn/Passkeys – politika na straně klienta (čistý Rust, testuje se nativně).
//! Backend (`/passkey/*`, webauthn4j) vydává jednorázovou challenge a ověřuje podpis; prohlížeč
//! nedrží žádné tajemství – privátní klíč zůstává v autentizátoru (Secure Enclave / TPM / FIDO2).
//! Klient jen odmítne slabé volby: UV povinné, jen ES256/EdDSA, rpId = host stránky, challenge ≥ 16 B.
use base64::{engine::general_purpose::URL_SAFE_NO_PAD as B64U, Engine};
use elfall_core::validate::rp_id_matches;
use serde::{Deserialize, Serialize};

pub const ALLOWED_ALGS: [i64; 2] = [-7, -8]; // ES256, EdDSA

pub fn b64u(s: &str) -> Result<Vec<u8>, String> {
    if s.is_empty() || !s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_') { return Err("neplatné base64url".into()); }
    B64U.decode(s).map_err(|_| "neplatné base64url".into())
}
pub fn to_b64u(b: &[u8]) -> String { B64U.encode(b) }

fn challenge(c: &str) -> Result<Vec<u8>, String> {
    let b = b64u(c)?;
    if b.len() < 16 { return Err("slabá challenge".into()); }
    Ok(b)
}
fn rp(id: &str, host: &str) -> Result<(), String> { if rp_id_matches(id, host) { Ok(()) } else { Err("rpId neodpovídá originu".into()) } }

#[derive(Deserialize, Debug, Clone)] pub struct Rp { pub id: String, pub name: String }
#[derive(Deserialize, Debug, Clone)] pub struct User { pub id: String, pub name: String, #[serde(rename = "displayName")] pub display_name: String }
#[derive(Deserialize, Debug, Clone)] pub struct Param { #[serde(rename = "type")] pub kind: String, pub alg: i64 }
#[derive(Deserialize, Debug, Clone)] pub struct Cred { pub id: String }
#[derive(Deserialize, Debug, Clone, Default)] pub struct Sel { #[serde(rename = "userVerification")] pub user_verification: Option<String> }

#[derive(Deserialize, Debug, Clone)]
pub struct CreationJson {
    pub challenge: String, pub rp: Rp, pub user: User,
    #[serde(rename = "pubKeyCredParams")] pub params: Vec<Param>,
    pub timeout: Option<u32>,
    #[serde(rename = "authenticatorSelection", default)] pub selection: Sel,
    #[serde(rename = "excludeCredentials", default)] pub exclude: Vec<Cred>,
}
#[derive(Deserialize, Debug, Clone)]
pub struct RequestJson {
    pub challenge: String, #[serde(rename = "rpId")] pub rp_id: String, pub timeout: Option<u32>,
    #[serde(rename = "userVerification")] pub user_verification: Option<String>,
    #[serde(rename = "allowCredentials", default)] pub allow: Vec<Cred>,
}

/// Ověřené volby připravené pro `navigator.credentials.create` (binární pole dekódovaná).
#[derive(Debug, PartialEq)]
pub struct Creation { pub challenge: Vec<u8>, pub rp_id: String, pub rp_name: String, pub user_id: Vec<u8>, pub user_name: String, pub display_name: String, pub algs: Vec<i64>, pub timeout: u32, pub exclude: Vec<Vec<u8>> }
#[derive(Debug, PartialEq)]
pub struct Request { pub challenge: Vec<u8>, pub rp_id: String, pub timeout: u32, pub allow: Vec<Vec<u8>> }

const DEFAULT_TIMEOUT: u32 = 60_000;
fn timeout(t: Option<u32>) -> u32 { t.unwrap_or(DEFAULT_TIMEOUT).clamp(10_000, 300_000) }

pub fn creation(o: &CreationJson, host: &str) -> Result<Creation, String> {
    rp(&o.rp.id, host)?;
    let ch = challenge(&o.challenge)?;
    if o.selection.user_verification.as_deref() != Some("required") { return Err("vyžadováno ověření uživatele".into()); }
    if o.params.is_empty() || o.params.iter().any(|p| p.kind != "public-key" || !ALLOWED_ALGS.contains(&p.alg)) { return Err("nepovolený algoritmus".into()); }
    Ok(Creation {
        challenge: ch, rp_id: o.rp.id.clone(), rp_name: o.rp.name.clone(), user_id: b64u(&o.user.id)?,
        user_name: o.user.name.clone(), display_name: o.user.display_name.clone(),
        algs: o.params.iter().map(|p| p.alg).collect(), timeout: timeout(o.timeout),
        exclude: o.exclude.iter().map(|c| b64u(&c.id)).collect::<Result<_, _>>()?,
    })
}

pub fn request(o: &RequestJson, host: &str) -> Result<Request, String> {
    rp(&o.rp_id, host)?;
    let ch = challenge(&o.challenge)?;
    if o.user_verification.as_deref() != Some("required") { return Err("vyžadováno ověření uživatele".into()); }
    Ok(Request { challenge: ch, rp_id: o.rp_id.clone(), timeout: timeout(o.timeout), allow: o.allow.iter().map(|c| b64u(&c.id)).collect::<Result<_, _>>()? })
}

/// Těla pro `/passkey/*/verify` (stejný tvar jako backend `PkRegister` / `PkLogin`).
#[derive(Serialize, Debug, PartialEq)]
pub struct RegisterBody { pub challenge: String, #[serde(rename = "attestationObject")] pub attestation_object: String, #[serde(rename = "clientDataJSON")] pub client_data_json: String }
#[derive(Serialize, Debug, PartialEq)]
pub struct LoginBody {
    pub challenge: String, #[serde(rename = "credentialId")] pub credential_id: String,
    #[serde(rename = "authenticatorData")] pub authenticator_data: String,
    #[serde(rename = "clientDataJSON")] pub client_data_json: String, pub signature: String,
    #[serde(rename = "userHandle")] pub user_handle: Option<String>,
}

/// Uživatelské jméno pro passkey: 1–64 znaků, bez řídicích znaků.
pub fn valid_user(u: &str) -> Result<String, String> {
    let t = u.trim();
    if t.is_empty() || t.chars().count() > 64 || t.chars().any(char::is_control) { return Err("Neplatné jméno".into()); }
    Ok(t.to_owned())
}
