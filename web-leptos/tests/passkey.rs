use elfall_web::passkey::*;

const CH: &str = "AAECAwQFBgcICQoLDA0ODw"; // 16 B
fn creation_json(rp: &str, uv: &str, alg: i64) -> CreationJson {
    serde_json::from_str(&format!(r#"{{"challenge":"{CH}","rp":{{"id":"{rp}","name":"ELFALL"}},"user":{{"id":"dXNlcg","name":"ivo","displayName":"Ivo"}},
      "pubKeyCredParams":[{{"type":"public-key","alg":{alg}}}],"timeout":60000,"authenticatorSelection":{{"userVerification":"{uv}"}},"excludeCredentials":[{{"id":"AQID"}}]}}"#)).unwrap()
}

#[test]
fn creation_policy() {
    let c = creation(&creation_json("elfall.cz", "required", -7), "app.elfall.cz").unwrap();
    assert_eq!(c.challenge.len(), 16); assert_eq!(c.user_id, b"user"); assert_eq!(c.exclude, vec![vec![1, 2, 3]]);
    assert!(creation(&creation_json("elfall.cz", "required", -8), "elfall.cz").is_ok());
    assert!(creation(&creation_json("evil.cz", "required", -7), "elfall.cz").is_err(), "rpId z jiné domény");
    assert!(creation(&creation_json("elfall.cz", "preferred", -7), "elfall.cz").is_err(), "UV povinné");
    assert!(creation(&creation_json("elfall.cz", "required", -257), "elfall.cz").is_err(), "RS256 zakázán");
}

#[test]
fn request_policy_and_challenge() {
    let ok: RequestJson = serde_json::from_str(&format!(r#"{{"challenge":"{CH}","rpId":"elfall.cz","userVerification":"required","timeout":1}}"#)).unwrap();
    let r = request(&ok, "elfall.cz").unwrap();
    assert_eq!(r.timeout, 10_000, "timeout se ořízne");
    let weak: RequestJson = serde_json::from_str(r#"{"challenge":"AAEC","rpId":"elfall.cz","userVerification":"required"}"#).unwrap();
    assert!(request(&weak, "elfall.cz").is_err(), "challenge < 16 B");
    let bad: RequestJson = serde_json::from_str(r#"{"challenge":"AA+/","rpId":"elfall.cz","userVerification":"required"}"#).unwrap();
    assert!(request(&bad, "elfall.cz").is_err(), "není base64url");
}

#[test]
fn bodies_match_backend_shape() {
    let b = serde_json::to_value(LoginBody { challenge: CH.into(), credential_id: to_b64u(&[1]), authenticator_data: "a".into(), client_data_json: "c".into(), signature: "s".into(), user_handle: None }).unwrap();
    for k in ["challenge", "credentialId", "authenticatorData", "clientDataJSON", "signature", "userHandle"] { assert!(b.get(k).is_some(), "{k}"); }
    let r = serde_json::to_value(RegisterBody { challenge: CH.into(), attestation_object: "x".into(), client_data_json: "y".into() }).unwrap();
    assert!(r.get("attestationObject").is_some() && r.get("clientDataJSON").is_some());
    assert_eq!(b64u(&to_b64u(&[0xfb, 0xff])).unwrap(), vec![0xfb, 0xff]);
    assert!(valid_user("ivo").is_ok() && valid_user("").is_err() && valid_user("a\nb").is_err());
}
