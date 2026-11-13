use elfall_core::validate::*;

#[test]
fn testnet_addresses() {
    // BIP-173/350 testovací vektory (testnet)
    assert_eq!(check_testnet_address("tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7"), None);
    assert_eq!(check_testnet_address("tb1pqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvsesf3hn0c"), None);
    assert_eq!(check_testnet_address("mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn"), None);
    assert_eq!(check_testnet_address("2MzQwSSnBHWHqSAqtTVQ6v47XtaisrJa1Vc"), None);
}
#[test]
fn rejects() {
    assert_eq!(check_testnet_address(""), Some("Zadejte adresu"));
    assert!(check_testnet_address("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4").unwrap().contains("mainnet"));
    assert!(check_testnet_address("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2").unwrap().contains("mainnet"));
    // změněný znak => špatný checksum (regex v JS by to propustil)
    assert_eq!(check_testnet_address("tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k8"), Some("Neplatný formát testnet adresy"));
    assert_eq!(check_testnet_address("mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfm"), Some("Neplatný formát testnet adresy"));
}
#[test]
fn payments_and_amounts() {
    assert_eq!(parse_payment(" BITCOIN:tb1qabc?amount=0.001&label=x ").amount_sats, Some(100_000));
    assert_eq!(parse_payment("bitcoin:tb1qabc").address, "tb1qabc");
    assert_eq!(parse_payment("tb1qabc").amount_sats, None);
    assert_eq!(parse_btc_amount("1"), Ok(100_000_000));
    assert_eq!(parse_btc_amount(".00000001"), Ok(1));
    assert_eq!(parse_btc_amount("21000000"), Ok(MAX_SATS));
    for bad in ["", ".", "0", "1.123456789", "-1", "1e3", "21000000.00000001", "1,5", "999999999"] {
        assert!(parse_btc_amount(bad).is_err(), "{bad}");
    }
    assert_eq!(format_sats(123_456_789), "1.23456789");
}
#[test]
fn rp_ids() {
    assert!(rp_id_matches("elfall.test", "elfall.test"));
    assert!(rp_id_matches("elfall.test", "app.elfall.test"));
    assert!(!rp_id_matches("elfall.test", "notelfall.test"));
    assert!(!rp_id_matches("", "x"));
}
