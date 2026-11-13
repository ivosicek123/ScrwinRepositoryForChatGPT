use elfall_web::logic::*;

const TB: &str = "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7";

#[test]
fn destination_uses_core_validation() {
    assert_eq!(check_destination(""), DestCheck::Empty);
    assert!(matches!(check_destination(TB), DestCheck::Ok { amount: None, .. }));
    match check_destination(&format!("bitcoin:{TB}?amount=0.001")) {
        DestCheck::Ok { address, amount } => { assert_eq!(address, TB); assert_eq!(amount.as_deref(), Some("0.00100000")); }
        x => panic!("{x:?}"),
    }
    assert!(matches!(check_destination(&TB.replace("5k7", "5k8")), DestCheck::Err(_)), "checksum");
    assert!(matches!(check_destination("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"), DestCheck::Err(e) if e.contains("mainnet")));
    assert!(validate_watch("<script>").is_err());
}

#[test]
fn network_allowlist_matches_csp() {
    assert!(allowed_url(&format!("{API_TESTNET}/address/{TB}")));
    assert!(allowed_url(&format!("{API_MAINNET}/v1/blocks")));
    for u in ["https://evil.example/api", "https://mempool.space.evil.example/api/x", "https://mempool.space/api@evil/x",
              "https://mempool.space/api/../x", "http://mempool.space/api/x", "https://mempool.space/apix"] {
        assert!(!allowed_url(u), "{u}");
    }
    let csp = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../scripts/web-leptos-csp.sh")).unwrap();
    assert!(csp.contains(&format!("API=\"{}\"", API_MAINNET.trim_end_matches("/api"))));
    assert!(csp.contains("script-src 'self' 'wasm-unsafe-eval' {hashes}"));
    assert!(!csp.contains("unsafe-inline"));
}

#[test]
fn fees_balance_links() {
    assert_eq!(fee_estimate("141", 2.0).as_deref(), Some("282 sat (0.00000282 BTC)"));
    assert_eq!(fee_estimate("0", 2.0), None);
    assert_eq!(fee_estimate("x", 2.0), None);
    assert_eq!(fee_estimate("141", f64::NAN), None);
    let a: AddrInfo = serde_json::from_str(r#"{"chain_stats":{"funded_txo_sum":150000,"spent_txo_sum":50000},"mempool_stats":{"funded_txo_sum":0,"spent_txo_sum":1000}}"#).unwrap();
    assert_eq!(balance_text(&a), "0.00100000 tBTC (−0.00001000 nepotvrzeno)");
    assert!(tx_link(&"a".repeat(64)).is_some());
    assert!(tx_link("javascript:alert(1)").is_none());
    assert!(tx_link(&"g".repeat(64)).is_none());
}

#[test]
fn help_warns_about_secrets() {
    assert_eq!(help("pošli seed"), HelpAnswer::Warning);
    assert_eq!(help(""), HelpAnswer::None);
    assert!(matches!(help("mempool"), HelpAnswer::Hits(h) if h[0].0 == "mempool"));
    assert_eq!(help("xyzzy"), HelpAnswer::NotFound);
}

#[test]
fn qr_and_checklist() {
    let (n, d) = qr_path(&format!("bitcoin:{TB}")).unwrap();
    assert!(n > 20 && d.starts_with('M') && !d.contains('<'));
    assert_eq!(parse_checklist(None).len(), SECURITY_ITEMS.len());
    assert_eq!(parse_checklist(Some("garbage")), vec![false; SECURITY_ITEMS.len()]);
    assert!(parse_checklist(Some("[true]"))[0]);
}

#[test]
fn no_secret_storage_keys() {
    for k in STORAGE_KEYS { for bad in ["key", "seed", "token", "secret", "mnemonic"] { assert!(!k.to_lowercase().contains(bad), "{k}"); } }
}
