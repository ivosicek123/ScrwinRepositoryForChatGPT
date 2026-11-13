#!/usr/bin/env python3
"""
Issue FALLCOIN (FLC) as a Stellar TESTNET asset.
yFALLCOIN (yFLC) is FROZEN (owner decision 2026-10-13) and is never issued or distributed.

- Uses Friendbot to fund issuer + distribution accounts.
- Secrets written ONLY to scripts/.env and scripts/flc_keys.json (gitignored).
- Prints PUBLIC keys and stellar.expert testnet links only.

Usage (from repo root, with venv that has stellar-sdk):
  python scripts/issue_flc_testnet.py
"""
from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

try:
    from stellar_sdk import Asset, Keypair, Network, Server, TransactionBuilder
except ImportError:
    print("Install: pip install stellar-sdk httpx", file=sys.stderr)
    sys.exit(1)

import httpx

HORIZON = "https://horizon-testnet.stellar.org"
FRIENDBOT = "https://friendbot.stellar.org"
NETWORK = Network.TESTNET_NETWORK_PASSPHRASE
OUT_DIR = Path(__file__).resolve().parent
KEYS_PATH = OUT_DIR / "flc_keys.json"
ENV_PATH = OUT_DIR / ".env"
EXPERT = "https://stellar.expert/explorer/testnet"


def fund(public: str) -> None:
    r = httpx.get(FRIENDBOT, params={"addr": public}, timeout=60.0)
    if r.status_code not in (200, 201):
        # already funded is ok
        if "op_already_exists" not in r.text and r.status_code != 400:
            r.raise_for_status()


def load_or_create_keys() -> dict:
    if KEYS_PATH.exists():
        data = json.loads(KEYS_PATH.read_text())
        print("Reusing existing keys file (secrets not printed).")
        return data
    issuer = Keypair.random()
    dist = Keypair.random()
    data = {
        "network": "stellar-testnet",
        "issuer_public": issuer.public_key,
        "issuer_secret": issuer.secret,
        "distribution_public": dist.public_key,
        "distribution_secret": dist.secret,
        "assets": ["FLC"],
        "frozen_assets": ["yFLC"],
        "reference_usd_display_only": 0.33,
        "disclaimer": "TESTNET only. 0.33 USD is NOT a real peg.",
    }
    KEYS_PATH.write_text(json.dumps(data, indent=2) + "\n")
    os.chmod(KEYS_PATH, 0o600)
    ENV_PATH.write_text(
        "\n".join(
            [
                f"FLC_ISSUER_PUBLIC={data['issuer_public']}",
                f"FLC_DISTRIBUTION_PUBLIC={data['distribution_public']}",
                f"FALLCOIN_ISSUER_SECRET={data['issuer_secret']}",
                f"FALLCOIN_DISTRIBUTION_SECRET={data['distribution_secret']}",
                "FLC_REFERENCE_USD=0.33",
                "NETWORK=testnet",
                "",
            ]
        )
    )
    os.chmod(ENV_PATH, 0o600)
    return data


def change_trust_and_pay(server: Server, issuer_kp: Keypair, dist_kp: Keypair, code: str, amount: str) -> str:
    asset = Asset(code, issuer_kp.public_key)
    # distribution trusts
    dist_account = server.load_account(dist_kp.public_key)
    tx1 = (
        TransactionBuilder(dist_account, NETWORK, base_fee=100)
        .append_change_trust_op(asset)
        .set_timeout(120)
        .build()
    )
    tx1.sign(dist_kp)
    server.submit_transaction(tx1)
    # issuer pays distribution
    issuer_account = server.load_account(issuer_kp.public_key)
    tx2 = (
        TransactionBuilder(issuer_account, NETWORK, base_fee=100)
        .append_payment_op(dist_kp.public_key, asset, amount)
        .set_timeout(120)
        .build()
    )
    tx2.sign(issuer_kp)
    resp = server.submit_transaction(tx2)
    return resp["hash"]


FROZEN_ASSETS = frozenset({"yFLC"})
ISSUE_ASSETS = (("FLC", "1000000"),)


def main() -> None:
    data = load_or_create_keys()
    issuer_pub = data["issuer_public"]
    dist_pub = data["distribution_public"]
    print("=== ELFALL Stellar TESTNET asset issuance ===")
    print(f"Issuer PUBLIC:       {issuer_pub}")
    print(f"Distribution PUBLIC: {dist_pub}")
    print(f"Issuer explorer:     {EXPERT}/account/{issuer_pub}")
    print(f"Distribution:        {EXPERT}/account/{dist_pub}")

    print("Funding via Friendbot…")
    fund(issuer_pub)
    time.sleep(2)
    fund(dist_pub)
    time.sleep(3)

    server = Server(HORIZON)
    issuer_kp = Keypair.from_secret(data["issuer_secret"])
    dist_kp = Keypair.from_secret(data["distribution_secret"])

    for code, amount in ISSUE_ASSETS:
        if code in FROZEN_ASSETS:
            print(f"{code}: frozen, skipping.")
            continue
        try:
            txh = change_trust_and_pay(server, issuer_kp, dist_kp, code, amount)
            print(f"{code} issued → distribution. tx: {EXPERT}/tx/{txh}")
            print(f"{code} asset:  {EXPERT}/asset/{code}-{issuer_pub}")
        except Exception as e:
            print(f"{code}: {e}", file=sys.stderr)

    print("\nDisclaimer: 1.00 FLC = 0.33 USD is a TESTNET display constant only — NOT a real peg.")
    print(f"Secrets saved under {KEYS_PATH} and {ENV_PATH} (gitignored). Do not commit.")


if __name__ == "__main__":
    main()
