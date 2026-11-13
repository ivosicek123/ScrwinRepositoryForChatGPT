"""ELFALL network config — TESTNET default. Mainnet is gated."""
from __future__ import annotations

import os
from enum import Enum
from typing import Final


class NetworkMode(str, Enum):
    TESTNET = "testnet"
    MAINNET = "mainnet"


NETWORK: Final[NetworkMode] = NetworkMode(os.getenv("NETWORK", "testnet").lower())

# Display-only reference for testnet UI. NOT a peg, NOT redeemable, NOT audited.
FLC_REFERENCE_USD: Final[float] = float(os.getenv("FLC_REFERENCE_USD", "0.33"))
FLC_REFERENCE_DISCLAIMER: Final[str] = (
    "FLC/yFLC reference 1.00 FLC = 0.33 USD is a TESTNET display constant only. "
    "It is NOT a real peg, e-money, or claim on USD reserves."
)

# Public upstreams (proxied; optional self-hosted overrides via env)
BTC_ESPLORA = os.getenv(
    "BTC_ESPLORA",
    "https://mempool.space/testnet/api" if NETWORK == NetworkMode.TESTNET else "https://mempool.space/api",
)
STELLAR_HORIZON = os.getenv(
    "STELLAR_HORIZON",
    "https://horizon-testnet.stellar.org" if NETWORK == NetworkMode.TESTNET else "https://horizon.stellar.org",
)
STELLAR_NETWORK_PASSPHRASE = (
    "Test SDF Network ; September 2015"
    if NETWORK == NetworkMode.TESTNET
    else "Public Global Stellar Network ; September 2015"
)
ETH_RPC = os.getenv(
    "ETH_RPC",
    "https://rpc.sepolia.org" if NETWORK == NetworkMode.TESTNET else "https://cloudflare-eth.com",
)
SOLANA_RPC = os.getenv(
    "SOLANA_RPC",
    "https://api.devnet.solana.com" if NETWORK == NetworkMode.TESTNET else "https://api.mainnet-beta.solana.com",
)
POLYGON_RPC = os.getenv(
    "POLYGON_RPC",
    "https://rpc-amoy.polygon.technology" if NETWORK == NetworkMode.TESTNET else "https://polygon-rpc.com",
)

# Optional issuer public keys (set after scripts/issue_flc_testnet.py)
FLC_ISSUER_PUBLIC = os.getenv("FLC_ISSUER_PUBLIC", "")
FLC_DISTRIBUTION_PUBLIC = os.getenv("FLC_DISTRIBUTION_PUBLIC", "")
YFLC_ISSUER_PUBLIC = os.getenv("YFLC_ISSUER_PUBLIC", "")  # often same issuer on testnet

# yFLC is frozen by owner decision (2026-10-13): never issued, distributed or priced.
FROZEN_ASSETS: Final[frozenset[str]] = frozenset({"yFLC"})

DEV_TESTNET_ASSETS: Final[list[dict]] = [
    {"id": "tBTC", "symbol": "tBTC", "network": "bitcoin-testnet", "kind": "native"},
    {"id": "XLM", "symbol": "XLM", "network": "stellar-testnet", "kind": "native"},
    {"id": "sepETH", "symbol": "ETH", "network": "ethereum-sepolia", "kind": "native"},
    {"id": "devSOL", "symbol": "SOL", "network": "solana-devnet", "kind": "native"},
    {"id": "amoyMATIC", "symbol": "MATIC", "network": "polygon-amoy", "kind": "native"},
    {
        "id": "FLC",
        "symbol": "FLC",
        "network": "stellar-testnet",
        "kind": "credit_alphanum4",
        "code": "FLC",
        "reference_usd": FLC_REFERENCE_USD,
        "disclaimer": FLC_REFERENCE_DISCLAIMER,
    },
    {
        "id": "yFLC",
        "symbol": "yFLC",
        "network": "stellar-testnet",
        "kind": "credit_alphanum4",
        "code": "yFLC",
        "reference_usd": FLC_REFERENCE_USD,
        "disclaimer": FLC_REFERENCE_DISCLAIMER + " yFLC is FROZEN (zmrazeno): not issued or distributed.",
        "frozen": True,
    },
]


def assert_testnet_or_explicit_mainnet() -> None:
    if NETWORK == NetworkMode.MAINNET and os.getenv("ALLOW_MAINNET", "").lower() not in ("1", "true", "yes"):
        raise RuntimeError(
            "MAINNET blocked: set ALLOW_MAINNET=true only after security review + legal checklist "
            "(see docs/elfall-network.md)."
        )
