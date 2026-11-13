//! Tenká bezpečná vrstva nad libsodium (libsodium-sys-stable). Jediné místo s `unsafe`;
//! každé volání kontroluje délky bufferů před předáním do C.
//!
//! Invarianty každého `unsafe` bloku (FFI do C):
//! 1. všechny ukazatele pocházejí z živých Rust referencí/polí, žijí po celé volání a nepřekrývají se;
//! 2. délky výstupů odpovídají konstantám libsodium (pevná pole `[u8; N]`) nebo `len + ABYTES` alokovanému výše;
//! 3. vstupní délky se předávají přesně (`slice.len()`), libsodium za ně nečte;
//! 4. návratový kód se vždy kontroluje; stavy proudu (`StreamState`) se při zahození nulují.
#![allow(unsafe_code)]
use crate::CoreError;
use libsodium_sys as s;
use std::sync::OnceLock;

static INIT: OnceLock<bool> = OnceLock::new();

pub fn init() -> Result<(), CoreError> {
    // sodium_init: 0 = ok, 1 = už inicializováno, -1 = chyba
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    if *INIT.get_or_init(|| unsafe { s::sodium_init() } >= 0) { Ok(()) } else { Err(CoreError::Rng) }
}

pub fn random(buf: &mut [u8]) -> Result<(), CoreError> {
    init()?;
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    unsafe { s::randombytes_buf(buf.as_mut_ptr().cast(), buf.len()) };
    Ok(())
}

// ---- Ed25519 (crypto_sign) ----
pub const SIGN_SEED: usize = 32;
pub const SIGN_PK: usize = 32;
pub const SIGN_SK: usize = 64;
pub const SIGN_BYTES: usize = 64;

pub fn sign_seed_keypair(seed: &[u8; SIGN_SEED]) -> Result<([u8; SIGN_PK], zeroize::Zeroizing<[u8; SIGN_SK]>), CoreError> {
    init()?;
    let mut pk = [0u8; SIGN_PK];
    let mut sk = zeroize::Zeroizing::new([0u8; SIGN_SK]);
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    let r = unsafe { s::crypto_sign_seed_keypair(pk.as_mut_ptr(), sk.as_mut_ptr(), seed.as_ptr()) };
    if r == 0 { Ok((pk, sk)) } else { Err(CoreError::InvalidKey) }
}

pub fn sign_detached(sk: &[u8; SIGN_SK], msg: &[u8]) -> Result<[u8; SIGN_BYTES], CoreError> {
    init()?;
    let mut sig = [0u8; SIGN_BYTES];
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    let r = unsafe { s::crypto_sign_detached(sig.as_mut_ptr(), std::ptr::null_mut(), msg.as_ptr(), msg.len() as u64, sk.as_ptr()) };
    if r == 0 { Ok(sig) } else { Err(CoreError::InvalidKey) }
}

pub fn verify_detached(pk: &[u8; SIGN_PK], msg: &[u8], sig: &[u8; SIGN_BYTES]) -> Result<bool, CoreError> {
    init()?;
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    Ok(unsafe { s::crypto_sign_verify_detached(sig.as_ptr(), msg.as_ptr(), msg.len() as u64, pk.as_ptr()) } == 0)
}

// ---- XChaCha20-Poly1305-IETF ----
pub const AEAD_KEY: usize = 32;
pub const AEAD_NONCE: usize = 24;
pub const AEAD_TAG: usize = 16;

pub fn aead_encrypt(key: &[u8; AEAD_KEY], nonce: &[u8; AEAD_NONCE], msg: &[u8], ad: &[u8]) -> Result<Vec<u8>, CoreError> {
    init()?;
    let mut c = vec![0u8; msg.len() + AEAD_TAG];
    let mut clen: u64 = 0;
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    let r = unsafe {
        s::crypto_aead_xchacha20poly1305_ietf_encrypt(c.as_mut_ptr(), &mut clen, msg.as_ptr(), msg.len() as u64,
            ad.as_ptr(), ad.len() as u64, std::ptr::null(), nonce.as_ptr(), key.as_ptr())
    };
    if r != 0 || clen as usize != c.len() { return Err(CoreError::AuthFailed); }
    Ok(c)
}

pub fn aead_decrypt(key: &[u8; AEAD_KEY], nonce: &[u8; AEAD_NONCE], c: &[u8], ad: &[u8]) -> Result<zeroize::Zeroizing<Vec<u8>>, CoreError> {
    init()?;
    if c.len() < AEAD_TAG { return Err(CoreError::AuthFailed); }
    let mut m = zeroize::Zeroizing::new(vec![0u8; c.len() - AEAD_TAG]);
    let mut mlen: u64 = 0;
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    let r = unsafe {
        s::crypto_aead_xchacha20poly1305_ietf_decrypt(m.as_mut_ptr(), &mut mlen, std::ptr::null_mut(), c.as_ptr(), c.len() as u64,
            ad.as_ptr(), ad.len() as u64, nonce.as_ptr(), key.as_ptr())
    };
    if r != 0 || mlen as usize != m.len() { return Err(CoreError::AuthFailed); }
    Ok(m)
}

// ---- Argon2id (crypto_pwhash_str) ----
pub fn pwhash_str(pw: &[u8], ops: u64, mem_bytes: usize) -> Result<String, CoreError> {
    init()?;
    let mut out = [0 as std::os::raw::c_char; s::crypto_pwhash_STRBYTES as usize];
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    let r = unsafe {
        s::crypto_pwhash_str_alg(out.as_mut_ptr(), pw.as_ptr().cast(), pw.len() as u64, ops, mem_bytes, s::crypto_pwhash_ALG_ARGON2ID13 as i32)
    };
    if r != 0 { return Err(CoreError::Hash); }
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    let c = unsafe { std::ffi::CStr::from_ptr(out.as_ptr()) };
    c.to_str().map(str::to_owned).map_err(|_| CoreError::Hash)
}

fn cstr(h: &str) -> Option<std::ffi::CString> {
    if h.len() >= s::crypto_pwhash_STRBYTES as usize { return None; }
    std::ffi::CString::new(h).ok()
}

pub fn pwhash_str_verify(h: &str, pw: &[u8]) -> bool {
    if init().is_err() { return false; }
    let Some(c) = cstr(h) else { return false };
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    unsafe { s::crypto_pwhash_str_verify(c.as_ptr(), pw.as_ptr().cast(), pw.len() as u64) == 0 }
}

/// 0 = parametry odpovídají, 1 = nutný rehash, -1 = neplatný řetězec
pub fn pwhash_str_needs_rehash(h: &str, ops: u64, mem_bytes: usize) -> i32 {
    if init().is_err() { return -1; }
    let Some(c) = cstr(h) else { return -1 };
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    unsafe { s::crypto_pwhash_str_needs_rehash(c.as_ptr(), ops, mem_bytes) }
}

pub fn memzero(buf: &mut [u8]) {
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    unsafe { s::sodium_memzero(buf.as_mut_ptr().cast(), buf.len()) };
}

// ---- BLAKE2b (crypto_generichash) ----
pub fn generichash32(parts: &[&[u8]]) -> Result<[u8; 32], CoreError> {
    init()?;
    let mut st = std::mem::MaybeUninit::<s::crypto_generichash_state>::uninit();
    let mut out = [0u8; 32];
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    unsafe {
        if s::crypto_generichash_init(st.as_mut_ptr(), std::ptr::null(), 0, 32) != 0 { return Err(CoreError::Hash); }
        for p in parts {
            if s::crypto_generichash_update(st.as_mut_ptr(), p.as_ptr(), p.len() as u64) != 0 { return Err(CoreError::Hash); }
        }
        if s::crypto_generichash_final(st.as_mut_ptr(), out.as_mut_ptr(), 32) != 0 { return Err(CoreError::Hash); }
    }
    Ok(out)
}

// ---- X25519 výměna klíčů (crypto_kx) ----
pub const KX_PK: usize = s::crypto_kx_PUBLICKEYBYTES as usize;
pub const KX_SK: usize = s::crypto_kx_SECRETKEYBYTES as usize;
pub const KX_SESSION: usize = s::crypto_kx_SESSIONKEYBYTES as usize;
pub type SessionKey = zeroize::Zeroizing<[u8; KX_SESSION]>;

pub fn kx_keypair() -> Result<([u8; KX_PK], zeroize::Zeroizing<[u8; KX_SK]>), CoreError> {
    init()?;
    let mut pk = [0u8; KX_PK];
    let mut sk = zeroize::Zeroizing::new([0u8; KX_SK]);
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    if unsafe { s::crypto_kx_keypair(pk.as_mut_ptr(), sk.as_mut_ptr()) } != 0 { return Err(CoreError::Rng); }
    Ok((pk, sk))
}

/// Veřejný klíč z tajného (crypto_scalarmult_base) – pro obnovu páru serveru.
pub fn kx_public(sk: &[u8; KX_SK]) -> Result<[u8; KX_PK], CoreError> {
    init()?;
    let mut pk = [0u8; KX_PK];
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    if unsafe { s::crypto_scalarmult_base(pk.as_mut_ptr(), sk.as_ptr()) } != 0 { return Err(CoreError::InvalidKey); }
    Ok(pk)
}

/// (rx, tx). Libsodium odmítne slabé (low-order) veřejné klíče peeru -> InvalidKey.
pub fn kx_session(client: bool, pk: &[u8; KX_PK], sk: &[u8; KX_SK], peer: &[u8; KX_PK]) -> Result<(SessionKey, SessionKey), CoreError> {
    init()?;
    let mut rx = zeroize::Zeroizing::new([0u8; KX_SESSION]);
    let mut tx = zeroize::Zeroizing::new([0u8; KX_SESSION]);
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    let r = unsafe {
        if client { s::crypto_kx_client_session_keys(rx.as_mut_ptr(), tx.as_mut_ptr(), pk.as_ptr(), sk.as_ptr(), peer.as_ptr()) }
        else { s::crypto_kx_server_session_keys(rx.as_mut_ptr(), tx.as_mut_ptr(), pk.as_ptr(), sk.as_ptr(), peer.as_ptr()) }
    };
    if r != 0 { return Err(CoreError::InvalidKey); }
    Ok((rx, tx))
}

// ---- XChaCha20-Poly1305 secretstream ----
pub const SS_HEADER: usize = s::crypto_secretstream_xchacha20poly1305_HEADERBYTES as usize;
pub const SS_ABYTES: usize = s::crypto_secretstream_xchacha20poly1305_ABYTES as usize;
pub const SS_TAG_MESSAGE: u8 = s::crypto_secretstream_xchacha20poly1305_TAG_MESSAGE as u8;
pub const SS_TAG_FINAL: u8 = s::crypto_secretstream_xchacha20poly1305_TAG_FINAL as u8;

/// Stav proudu; při zahození se nuluje (obsahuje odvozený klíč).
pub struct StreamState(Box<s::crypto_secretstream_xchacha20poly1305_state>);

impl Drop for StreamState {
    fn drop(&mut self) {
        let p: *mut s::crypto_secretstream_xchacha20poly1305_state = &mut *self.0;
        // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
        unsafe { s::sodium_memzero(p.cast(), std::mem::size_of::<s::crypto_secretstream_xchacha20poly1305_state>()) };
    }
}

fn zero_state() -> Box<s::crypto_secretstream_xchacha20poly1305_state> {
    // POD struktura z bindgenu – nulová hodnota je platná (init_* ji přepíše)
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    Box::new(unsafe { std::mem::zeroed() })
}

pub fn ss_init_push(key: &[u8; KX_SESSION]) -> Result<(StreamState, [u8; SS_HEADER]), CoreError> {
    init()?;
    let mut st = StreamState(zero_state());
    let mut h = [0u8; SS_HEADER];
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    if unsafe { s::crypto_secretstream_xchacha20poly1305_init_push(&mut *st.0, h.as_mut_ptr(), key.as_ptr()) } != 0 { return Err(CoreError::InvalidKey); }
    Ok((st, h))
}

pub fn ss_init_pull(key: &[u8; KX_SESSION], header: &[u8; SS_HEADER]) -> Result<StreamState, CoreError> {
    init()?;
    let mut st = StreamState(zero_state());
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    if unsafe { s::crypto_secretstream_xchacha20poly1305_init_pull(&mut *st.0, header.as_ptr(), key.as_ptr()) } != 0 { return Err(CoreError::BadFrame); }
    Ok(st)
}

pub fn ss_push(st: &mut StreamState, m: &[u8], ad: &[u8], tag: u8) -> Result<Vec<u8>, CoreError> {
    let mut c = vec![0u8; m.len() + SS_ABYTES];
    let mut clen: u64 = 0;
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    let r = unsafe {
        s::crypto_secretstream_xchacha20poly1305_push(&mut *st.0, c.as_mut_ptr(), &mut clen, m.as_ptr(), m.len() as u64, ad.as_ptr(), ad.len() as u64, tag)
    };
    if r != 0 || clen as usize != c.len() { return Err(CoreError::MessageTooLong); }
    Ok(c)
}

pub fn ss_pull(st: &mut StreamState, c: &[u8], ad: &[u8]) -> Result<(zeroize::Zeroizing<Vec<u8>>, u8), CoreError> {
    if c.len() < SS_ABYTES { return Err(CoreError::AuthFailed); }
    let mut m = zeroize::Zeroizing::new(vec![0u8; c.len() - SS_ABYTES]);
    let mut mlen: u64 = 0;
    let mut tag: u8 = 0;
    // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
    let r = unsafe {
        s::crypto_secretstream_xchacha20poly1305_pull(&mut *st.0, m.as_mut_ptr(), &mut mlen, &mut tag, c.as_ptr(), c.len() as u64, ad.as_ptr(), ad.len() as u64)
    };
    if r != 0 || mlen as usize != m.len() { return Err(CoreError::AuthFailed); }
    Ok((m, tag))
}
