//! Ed25519 přes libsodium `crypto_sign` – podepisuje se vždy jen orámovaný vstup.
use crate::{framing, sodium, CoreError};
use zeroize::Zeroizing;

/// 32B seed (RFC 8032 „secret key“).
pub fn generate_secret() -> Result<Zeroizing<[u8; 32]>, CoreError> {
    let mut s = Zeroizing::new([0u8; 32]);
    sodium::random(&mut s[..])?;
    Ok(s)
}

fn seed(secret: &[u8]) -> Result<Zeroizing<[u8; 32]>, CoreError> {
    Ok(Zeroizing::new(secret.try_into().map_err(|_| CoreError::InvalidLength)?))
}

pub fn public_key(secret: &[u8]) -> Result<[u8; 32], CoreError> {
    Ok(sodium::sign_seed_keypair(&*seed(secret)?)?.0)
}

pub fn sign_framed(secret: &[u8], msg: &[u8]) -> Result<[u8; 64], CoreError> {
    let framed = framing::frame(msg)?;
    let (_, sk) = sodium::sign_seed_keypair(&*seed(secret)?)?;
    sodium::sign_detached(&sk, &framed)
}

pub fn verify_framed(public: &[u8], msg: &[u8], sig: &[u8]) -> Result<bool, CoreError> {
    let pk: [u8; 32] = public.try_into().map_err(|_| CoreError::InvalidLength)?;
    let sig: [u8; 64] = sig.try_into().map_err(|_| CoreError::InvalidLength)?;
    let framed = framing::frame(msg)?;
    sodium::verify_detached(&pk, &framed, &sig)
}
