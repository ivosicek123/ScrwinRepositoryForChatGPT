//! UniFFI rozhraní pro Kotlin (Android/JVM) a Swift (iOS). Tajemství se předávají jako bajty;
//! na straně Rustu se po použití nulují. Datový klíč vaultu drží platforma zabalený
//! (Android Keystore / iOS Keychain) – jádro klíče neukládá.
use crate::{framing, pin, sign, vault, CoreError};

#[uniffi::export]
pub fn signing_input(msg: Vec<u8>) -> Result<Vec<u8>, CoreError> { framing::frame(&msg) }

#[uniffi::export]
pub fn parse_signing_input(input: Vec<u8>) -> Result<Vec<u8>, CoreError> { framing::unframe(&input).map(<[u8]>::to_vec) }

#[uniffi::export]
pub fn ed25519_generate() -> Result<Vec<u8>, CoreError> { Ok(sign::generate_secret()?.to_vec()) }

#[uniffi::export]
pub fn ed25519_public_key(secret: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    let s = zeroize::Zeroizing::new(secret);
    Ok(sign::public_key(&s)?.to_vec())
}

#[uniffi::export]
pub fn ed25519_sign_framed(secret: Vec<u8>, msg: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    let s = zeroize::Zeroizing::new(secret);
    Ok(sign::sign_framed(&s, &msg)?.to_vec())
}

#[uniffi::export]
pub fn ed25519_verify_framed(public: Vec<u8>, msg: Vec<u8>, sig: Vec<u8>) -> Result<bool, CoreError> {
    sign::verify_framed(&public, &msg, &sig)
}

#[uniffi::export]
pub fn vault_new_key() -> Result<Vec<u8>, CoreError> { Ok(vault::new_key()?.to_vec()) }

#[uniffi::export]
pub fn vault_seal(key: Vec<u8>, field: String, plaintext: String) -> Result<String, CoreError> {
    let k = zeroize::Zeroizing::new(key);
    vault::seal(&k, &field, &plaintext)
}

#[uniffi::export]
pub fn vault_open(key: Vec<u8>, field: String, sealed: String) -> Result<String, CoreError> {
    let k = zeroize::Zeroizing::new(key);
    Ok(vault::open(&k, &field, &sealed)?.to_string())
}

#[uniffi::export]
pub fn pin_hash(pin: String) -> Result<String, CoreError> { let p = zeroize::Zeroizing::new(pin); pin::hash(&p) }

#[uniffi::export]
pub fn pin_verify(pin: String, phc: String) -> bool { let p = zeroize::Zeroizing::new(pin); pin::verify(&p, &phc) }

#[uniffi::export]
pub fn pin_needs_rehash(phc: String) -> bool { pin::needs_rehash(&phc) }

// ---- Šifrovaný kanál ELFALL-CH-v1 (crypto_kx + secretstream), viz channel.rs ----
use crate::channel;
use std::sync::{Arc, Mutex};

#[derive(uniffi::Record)]
pub struct KxKeypair { pub public: Vec<u8>, pub secret: Vec<u8> }

#[derive(uniffi::Record)]
pub struct ChannelMessage { pub data: Vec<u8>, pub last: bool }

#[uniffi::export]
pub fn channel_keypair() -> Result<KxKeypair, CoreError> {
    let k = channel::Keypair::generate()?;
    Ok(KxKeypair { public: k.public.to_vec(), secret: k.secret.to_vec() })
}

#[derive(uniffi::Object)]
pub struct SecureChannel(Mutex<channel::Channel>);

fn lock(c: &SecureChannel) -> Result<std::sync::MutexGuard<'_, channel::Channel>, CoreError> { c.0.lock().map_err(|_| CoreError::BadFrame) }

#[uniffi::export]
impl SecureChannel {
    /// Klient s efemérním párem a připnutým `server_pk` backendu.
    #[uniffi::constructor]
    pub fn client(server_pk: Vec<u8>) -> Result<Arc<Self>, CoreError> {
        let kp = channel::Keypair::generate()?;
        Ok(Arc::new(Self(Mutex::new(channel::Channel::client(&kp, &server_pk)?))))
    }
    /// Serverová strana (testy / referenční implementace); `server_secret` 32 B.
    #[uniffi::constructor]
    pub fn server(server_secret: Vec<u8>, client_pk: Vec<u8>) -> Result<Arc<Self>, CoreError> {
        let s = zeroize::Zeroizing::new(server_secret);
        let kp = channel::Keypair::from_secret(&s)?;
        Ok(Arc::new(Self(Mutex::new(channel::Channel::server(&kp, &client_pk)?))))
    }
    pub fn public_key(&self) -> Result<Vec<u8>, CoreError> { Ok(lock(self)?.public_key().to_vec()) }
    /// Výzva pro podpis klíčem Secure Enclave (váže device-bound token na tuto relaci).
    pub fn binding(&self) -> Result<Vec<u8>, CoreError> { Ok(lock(self)?.binding().to_vec()) }
    pub fn header(&self) -> Result<Vec<u8>, CoreError> { Ok(lock(self)?.header().to_vec()) }
    pub fn accept_header(&self, header: Vec<u8>) -> Result<(), CoreError> { lock(self)?.accept_header(&header) }
    pub fn seal(&self, msg: Vec<u8>, last: bool) -> Result<Vec<u8>, CoreError> {
        let m = zeroize::Zeroizing::new(msg);
        lock(self)?.seal(&m, last)
    }
    pub fn open(&self, ciphertext: Vec<u8>) -> Result<ChannelMessage, CoreError> {
        let (m, last) = lock(self)?.open(&ciphertext)?;
        Ok(ChannelMessage { data: m.to_vec(), last })
    }
}
