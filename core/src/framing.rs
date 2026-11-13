//! Rámování vstupu podpisu – přesně podle specifikace dokázané ve SPARK (`proofs/spark/`):
//! `"ELFALL-SIG-v1\0" || len(msg) jako u32 big-endian || msg`, msg ≤ 65 536 B.
use crate::CoreError;

pub const PREFIX: &[u8; 14] = b"ELFALL-SIG-v1\0";
pub const HEADER_LEN: usize = PREFIX.len() + 4;
pub const MAX_MESSAGE: usize = 65_536;

pub fn frame(msg: &[u8]) -> Result<Vec<u8>, CoreError> {
    if msg.len() > MAX_MESSAGE {
        return Err(CoreError::MessageTooLong);
    }
    let mut out = Vec::with_capacity(HEADER_LEN + msg.len());
    out.extend_from_slice(PREFIX);
    out.extend_from_slice(&(msg.len() as u32).to_be_bytes());
    out.extend_from_slice(msg);
    Ok(out)
}

/// Vrátí zprávu jen pokud prefix sedí a deklarovaná délka přesně odpovídá zbytku.
pub fn unframe(input: &[u8]) -> Result<&[u8], CoreError> {
    if input.len() < HEADER_LEN || input.len() > HEADER_LEN + MAX_MESSAGE || &input[..PREFIX.len()] != PREFIX {
        return Err(CoreError::BadFrame);
    }
    let mut len = [0u8; 4];
    len.copy_from_slice(&input[PREFIX.len()..HEADER_LEN]);
    if u32::from_be_bytes(len) as usize != input.len() - HEADER_LEN {
        return Err(CoreError::BadFrame);
    }
    Ok(&input[HEADER_LEN..])
}
