//! Šifrovaný kanál klient↔backend: X25519 (libsodium `crypto_kx`) + XChaCha20-Poly1305
//! `secretstream` v každém směru zvlášť.
//!
//! Protokol `ELFALL-CH-v1` (podrobně `docs/security/CLIENT-CHANNEL.md`):
//! 1. Klient zná **připnutý** statický kx klíč backendu (`server_pk`); jinak MITM.
//! 2. Klient vygeneruje efemérní kx pár pro každou relaci, `crypto_kx_client_session_keys`.
//! 3. `binding = BLAKE2b-256("ELFALL-CH-v1\0" || client_pk || server_pk)` – AD každé zprávy
//!    a výzva, kterou klient podepíše klíčem v Secure Enclave (ES256, Face ID).
//! 4. První zpráva klienta = krátkodobý device-bound token backendu; backend ověří token
//!    (exp, `cnf` = otisk SE klíče) a ES256 podpis `binding` → token nelze přenést do jiné relace.
//! 5. Zprávy s `TAG_FINAL` uzavírají směr; po něm se další data odmítnou.
//!
//! Filtrování paketů (eBPF/XDP) zůstává čistě na serveru (`deploy/ztna/`), klient o něm neví.
use crate::{sodium, CoreError};
use zeroize::Zeroizing;

pub const PROTOCOL: &[u8] = b"ELFALL-CH-v1\0";
pub const KEY_LEN: usize = sodium::KX_PK;
pub const HEADER_LEN: usize = sodium::SS_HEADER;
pub const OVERHEAD: usize = sodium::SS_ABYTES;
/// Horní mez jedné zprávy (DoS na straně klienta i serveru).
pub const MAX_MESSAGE: usize = 1 << 20;

pub struct Keypair {
    pub public: [u8; KEY_LEN],
    pub secret: Zeroizing<[u8; KEY_LEN]>,
}

impl Keypair {
    pub fn generate() -> Result<Self, CoreError> {
        let (public, secret) = sodium::kx_keypair()?;
        Ok(Self { public, secret })
    }
    pub fn from_secret(secret: &[u8]) -> Result<Self, CoreError> {
        let s: Zeroizing<[u8; KEY_LEN]> = Zeroizing::new(secret.try_into().map_err(|_| CoreError::InvalidLength)?);
        Ok(Self { public: sodium::kx_public(&s)?, secret: s })
    }
}

pub fn binding(client_pk: &[u8; KEY_LEN], server_pk: &[u8; KEY_LEN]) -> Result<[u8; 32], CoreError> {
    sodium::generichash32(&[PROTOCOL, client_pk, server_pk])
}

pub struct Channel {
    tx: Option<sodium::StreamState>,
    rx: Option<sodium::StreamState>,
    rx_key: sodium::SessionKey,
    header: [u8; HEADER_LEN],
    binding: [u8; 32],
    local_pk: [u8; KEY_LEN],
    rx_done: bool,
}

fn pk32(b: &[u8]) -> Result<[u8; KEY_LEN], CoreError> { b.try_into().map_err(|_| CoreError::InvalidLength) }

impl Channel {
    /// Klient: efemérní pár + připnutý klíč serveru.
    pub fn client(kp: &Keypair, server_pk: &[u8]) -> Result<Self, CoreError> {
        let server = pk32(server_pk)?;
        let (rx, tx) = sodium::kx_session(true, &kp.public, &kp.secret, &server)?;
        Self::new(kp.public, binding(&kp.public, &server)?, rx, &tx)
    }
    /// Server (pro testy a referenční implementaci backendu).
    pub fn server(kp: &Keypair, client_pk: &[u8]) -> Result<Self, CoreError> {
        let client = pk32(client_pk)?;
        let (rx, tx) = sodium::kx_session(false, &kp.public, &kp.secret, &client)?;
        Self::new(kp.public, binding(&client, &kp.public)?, rx, &tx)
    }
    fn new(local_pk: [u8; KEY_LEN], binding: [u8; 32], rx_key: sodium::SessionKey, tx_key: &sodium::SessionKey) -> Result<Self, CoreError> {
        let (tx, header) = sodium::ss_init_push(tx_key)?;
        Ok(Self { tx: Some(tx), rx: None, rx_key, header, binding, local_pk, rx_done: false })
    }

    pub fn public_key(&self) -> [u8; KEY_LEN] { self.local_pk }
    pub fn binding(&self) -> [u8; 32] { self.binding }
    /// Hlavička odchozího proudu – pošlete ji peeru jako první (není tajná).
    pub fn header(&self) -> [u8; HEADER_LEN] { self.header }

    pub fn accept_header(&mut self, header: &[u8]) -> Result<(), CoreError> {
        if self.rx.is_some() { return Err(CoreError::BadFrame); }
        let h: [u8; HEADER_LEN] = header.try_into().map_err(|_| CoreError::InvalidLength)?;
        self.rx = Some(sodium::ss_init_pull(&self.rx_key, &h)?);
        sodium::memzero(&mut self.rx_key[..]); // klíč už je ve stavu proudu
        Ok(())
    }

    /// `last = true` uzavře odchozí směr (TAG_FINAL).
    pub fn seal(&mut self, msg: &[u8], last: bool) -> Result<Vec<u8>, CoreError> {
        if msg.len() > MAX_MESSAGE { return Err(CoreError::MessageTooLong); }
        let st = self.tx.as_mut().ok_or(CoreError::BadFrame)?;
        let tag = if last { sodium::SS_TAG_FINAL } else { sodium::SS_TAG_MESSAGE };
        let c = sodium::ss_push(st, msg, &self.binding, tag)?;
        if last { self.tx = None; }
        Ok(c)
    }

    /// Vrací (zpráva, byla poslední). Změna, přeskupení, zahození i přehrání → AuthFailed.
    pub fn open(&mut self, c: &[u8]) -> Result<(Zeroizing<Vec<u8>>, bool), CoreError> {
        if self.rx_done { return Err(CoreError::BadFrame); }
        if c.len() > MAX_MESSAGE + OVERHEAD { return Err(CoreError::MessageTooLong); }
        let st = self.rx.as_mut().ok_or(CoreError::BadFrame)?;
        let (m, tag) = sodium::ss_pull(st, c, &self.binding)?;
        let last = match tag {
            t if t == sodium::SS_TAG_MESSAGE => false,
            t if t == sodium::SS_TAG_FINAL => true,
            _ => return Err(CoreError::BadFrame), // PUSH/REKEY protokol nepoužívá
        };
        if last { self.rx_done = true; self.rx = None; }
        Ok((m, last))
    }
}
