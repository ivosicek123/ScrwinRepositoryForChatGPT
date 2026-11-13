//! Argon2id přes libsodium `crypto_pwhash_str_alg` (ARGON2ID13), OWASP: m = 19 MiB, t = 2, p = 1.
use crate::{sodium, CoreError};

pub const M_KIB: u32 = 19_456;
pub const T: u32 = 2;

fn mem(m_kib: u32) -> usize { m_kib as usize * 1024 }

pub fn hash(pin: &str) -> Result<String, CoreError> { hash_with(pin, M_KIB, T) }

/// libsodium vyžaduje t ≥ 1 a m ≥ 8 KiB; p je u libsodium vždy 1.
pub fn hash_with(pin: &str, m_kib: u32, t: u32) -> Result<String, CoreError> {
    sodium::pwhash_str(pin.as_bytes(), t as u64, mem(m_kib))
}

pub fn verify(pin: &str, phc: &str) -> bool {
    phc.starts_with("$argon2id$") && sodium::pwhash_str_verify(phc, pin.as_bytes())
}

/// true, pokud hash neodpovídá aktuální politice (nebo je neplatný).
pub fn needs_rehash(phc: &str) -> bool {
    !phc.starts_with("$argon2id$") || sodium::pwhash_str_needs_rehash(phc, T as u64, mem(M_KIB)) != 0
}
