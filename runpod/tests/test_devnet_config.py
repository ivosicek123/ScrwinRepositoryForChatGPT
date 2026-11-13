import json
import pathlib

CFG = json.loads((pathlib.Path(__file__).resolve().parents[1] / "devnet" / "devnet.json").read_text())


def test_five_plus_coins_and_flc():
    chains = {c["chain"].split("-")[0] for c in CFG["chains"]}
    assert len(chains) >= 5
    codes = {t["code"] for t in CFG["tokens"]}
    assert {"FLC", "yFLC"} <= codes
    toks = {t["id"]: t for t in CFG["tokens"]}
    assert toks["FLC"]["display_price_usd"] == 0.33 and toks["FLC"]["display_only"]
    assert toks["yFLC"]["frozen"] is True and toks["yFLC"]["display_price_usd"] is None


def test_testnet_only_https_no_secrets():
    assert CFG["limits"]["mainnet_signing"] is False
    raw = json.dumps({k: v for k, v in CFG.items() if k != "_comment"}).lower()
    for bad in ("secret", "private", "seed", "mnemonic", "api_key", "mempool.space/api\""):
        assert bad not in raw
    for c in CFG["chains"]:
        assert all(u.startswith("https://") for u in c["rpc"])
