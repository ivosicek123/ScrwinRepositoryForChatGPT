//! wasm32: libsodium-sys pro wasm32-unknown-unknown nepřeložíme, proto jen ověření podpisu
//! (ed25519-dalek, verify_strict – odmítá malleabilní podpisy stejně jako libsodium).
use crate::{framing, CoreError};
use ed25519_dalek::{Signature, VerifyingKey};

pub fn verify_framed(public: &[u8], msg: &[u8], sig: &[u8]) -> Result<bool, CoreError> {
    let pk: [u8; 32] = public.try_into().map_err(|_| CoreError::InvalidLength)?;
    let sig: [u8; 64] = sig.try_into().map_err(|_| CoreError::InvalidLength)?;
    let vk = VerifyingKey::from_bytes(&pk).map_err(|_| CoreError::InvalidKey)?;
    Ok(vk.verify_strict(&framing::frame(msg)?, &Signature::from_bytes(&sig)).is_ok())
}
