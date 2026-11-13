//! XChaCha20-Poly1305 vault přes libsodium – bajtově kompatibilní s Android/iOS `SodiumVault`
//! (`"sv1:" || base64(nonce24 || ct || tag16)`, AD = `"elfall/v1/" || pole`).
//! Integrita a vazba na pole: viz `proofs/easycrypt/SodiumVault.ec`.
use crate::{sodium, CoreError};
use base64::{engine::general_purpose::STANDARD, Engine};
use zeroize::Zeroizing;

pub const PREFIX: &str = "sv1:";
const NONCE: usize = sodium::AEAD_NONCE;

fn ad(field: &str) -> Vec<u8> {
    [b"elfall/v1/".as_slice(), field.as_bytes()].concat()
}

fn key32(key: &[u8]) -> Result<Zeroizing<[u8; 32]>, CoreError> {
    Ok(Zeroizing::new(key.try_into().map_err(|_| CoreError::InvalidLength)?))
}

pub fn new_key() -> Result<Zeroizing<[u8; 32]>, CoreError> {
    let mut k = Zeroizing::new([0u8; 32]);
    sodium::random(&mut k[..])?;
    Ok(k)
}

pub fn seal(key: &[u8], field: &str, plaintext: &str) -> Result<String, CoreError> {
    let mut nonce = [0u8; NONCE];
    sodium::random(&mut nonce)?;
    seal_with_nonce(key, field, plaintext, &nonce)
}

/// Jen pro testovací vektory – v produkci vždy `seal` (náhodný nonce).
pub fn seal_with_nonce(key: &[u8], field: &str, plaintext: &str, nonce: &[u8; NONCE]) -> Result<String, CoreError> {
    let ct = sodium::aead_encrypt(&*key32(key)?, nonce, plaintext.as_bytes(), &ad(field))?;
    let mut raw = Vec::with_capacity(NONCE + ct.len());
    raw.extend_from_slice(nonce);
    raw.extend_from_slice(&ct);
    Ok(format!("{PREFIX}{}", STANDARD.encode(raw)))
}

/// `Err(AuthFailed)` při změně dat, jiném klíči nebo jiném poli.
pub fn open(key: &[u8], field: &str, sealed: &str) -> Result<Zeroizing<String>, CoreError> {
    let k = key32(key)?;
    let b64 = sealed.strip_prefix(PREFIX).ok_or(CoreError::AuthFailed)?;
    let raw = STANDARD.decode(b64).map_err(|_| CoreError::AuthFailed)?;
    if raw.len() < NONCE + sodium::AEAD_TAG {
        return Err(CoreError::AuthFailed);
    }
    let mut nonce = [0u8; NONCE];
    nonce.copy_from_slice(&raw[..NONCE]);
    let pt = sodium::aead_decrypt(&k, &nonce, &raw[NONCE..], &ad(field))?;
    let s = std::str::from_utf8(&pt).map_err(|_| CoreError::AuthFailed)?;
    Ok(Zeroizing::new(s.to_owned()))
}
