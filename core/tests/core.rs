use elfall_core::{framing, pin, sign, vault, CoreError};

// ---- rámování (shoda se SPARK specifikací) ----
#[test]
fn frame_layout() {
    let f = framing::frame(b"abc").unwrap();
    assert_eq!(&f[..14], b"ELFALL-SIG-v1\0");
    assert_eq!(&f[14..18], &[0, 0, 0, 3]);
    assert_eq!(&f[18..], b"abc");
    assert_eq!(framing::unframe(&f).unwrap(), b"abc");
}
#[test]
fn frame_limits() {
    assert!(framing::frame(&vec![0; framing::MAX_MESSAGE]).is_ok());
    assert_eq!(framing::frame(&vec![0; framing::MAX_MESSAGE + 1]), Err(CoreError::MessageTooLong));
    assert_eq!(framing::unframe(&framing::frame(b"").unwrap()).unwrap(), b"");
}
#[test]
fn unframe_rejects_malformed() {
    let f = framing::frame(b"pay 1 FLC").unwrap();
    let mut appended = f.clone(); appended.push(0);
    let mut bad_prefix = f.clone(); bad_prefix[0] ^= 1;
    let mut bad_len = f.clone(); bad_len[17] ^= 1;
    for x in [&appended[..], &bad_prefix[..], &bad_len[..], &f[..f.len() - 1], &f[..10], &[][..]] {
        assert_eq!(framing::unframe(x), Err(CoreError::BadFrame));
    }
}

// ---- Ed25519 ----
#[test]
fn rfc8032_test1_public_key() {
    let sk = hex::decode("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60").unwrap();
    assert_eq!(hex::encode(sign::public_key(&sk).unwrap()), "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
}
#[test]
fn sign_verify_framed() {
    let sk = sign::generate_secret().unwrap();
    let pk = sign::public_key(&sk[..]).unwrap();
    let sig = sign::sign_framed(&sk[..], b"tx").unwrap();
    assert!(sign::verify_framed(&pk, b"tx", &sig).unwrap());
    assert!(!sign::verify_framed(&pk, b"tX", &sig).unwrap());
    let mut s2 = sig; s2[0] ^= 1;
    assert!(!sign::verify_framed(&pk, b"tx", &s2).unwrap());
    assert_eq!(sign::sign_framed(&sk[..31], b"tx"), Err(CoreError::InvalidLength));
    assert_eq!(sign::verify_framed(&pk, b"tx", &sig[..63]), Err(CoreError::InvalidLength));
}
#[test]
fn raw_signature_is_not_a_framed_signature() {
    use ed25519_dalek::{Signer, SigningKey};
    let sk = [7u8; 32];
    let raw = SigningKey::from_bytes(&sk).sign(b"tx").to_bytes(); // podpis bez rámce (doménová separace)
    assert!(!sign::verify_framed(&sign::public_key(&sk).unwrap(), b"tx", &raw).unwrap());
}

// ---- vault ----
#[test]
fn vault_roundtrip_and_binding() {
    let k = vault::new_key().unwrap();
    let s = vault::seal(&k[..], "payout", "GABC").unwrap();
    assert!(s.starts_with("sv1:"));
    assert_eq!(&*vault::open(&k[..], "payout", &s).unwrap(), "GABC");
    assert_eq!(vault::open(&k[..], "stellar", &s), Err(CoreError::AuthFailed)); // jiné pole
    let k2 = vault::new_key().unwrap();
    assert_eq!(vault::open(&k2[..], "payout", &s), Err(CoreError::AuthFailed));
    assert_ne!(s, vault::seal(&k[..], "payout", "GABC").unwrap()); // náhodný nonce
    assert_eq!(vault::open(&k[..], "payout", "GABC"), Err(CoreError::AuthFailed)); // legacy plaintext
}
#[test]
fn vault_every_bit_flip_rejected() {
    use base64::{engine::general_purpose::STANDARD, Engine};
    let k = [1u8; 32];
    let s = vault::seal(&k, "f", "secret").unwrap();
    let raw = STANDARD.decode(&s[4..]).unwrap();
    for i in 0..raw.len() {
        let mut r = raw.clone(); r[i] ^= 0x01;
        assert!(vault::open(&k, "f", &format!("sv1:{}", STANDARD.encode(r))).is_err(), "byte {i}");
    }
}
/// Testovací vektor sdílený s Android testem (lazysodium) – důkaz bajtové kompatibility.
pub const VECTOR: &str = "sv1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXy73GpIPkgWFy7/n/ePZi7kBBt5WPl9s=";
#[test]
fn vault_fixed_vector() {
    let nonce: [u8; 24] = core::array::from_fn(|i| i as u8);
    let s = vault::seal_with_nonce(&[0x42; 32], "payout", "GABCDEF", &nonce).unwrap();
    println!("VECTOR={s}");
    assert_eq!(s, VECTOR);
}

// ---- Argon2id ----
#[test]
fn pin_hash_verify() {
    let h = pin::hash_with("1234", 1024, 1).unwrap(); // rychlé parametry pro test
    assert!(h.starts_with("$argon2id$v=19$m=1024,t=1,p=1$"));
    assert!(pin::verify("1234", &h));
    assert!(!pin::verify("1235", &h));
    assert!(pin::needs_rehash(&h));
    assert!(!pin::verify("1234", "garbage"));
}
#[test]
fn pin_owasp_params() {
    let h = pin::hash("2468").unwrap();
    assert!(h.starts_with("$argon2id$v=19$m=19456,t=2,p=1$"));
    assert!(!pin::needs_rehash(&h));
    assert!(pin::verify("2468", &h));
}
