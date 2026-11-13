"""
ELFALL RunPod serverless worker — first network/server for the app.

Routes (input JSON):
  {"route": "health"}
  {"route": "assets"}
  {"route": "btc/address", "address": "..."}
  {"route": "btc/fees"}
  {"route": "stellar/account", "account": "G..."}
  {"route": "eth/block"}
  {"route": "sol/slot"}
  {"route": "polygon/block"}
  {"route": "flc/price"}   # display-only reference

Never accepts seeds or private keys.
"""
from __future__ import annotations

import asyncio
from typing import Any

import runpod

from . import config
from . import proxies


def _ok(data: Any) -> dict:
    return {"ok": True, "network_mode": config.NETWORK.value, "data": data}


def _err(msg: str, code: int = 400) -> dict:
    return {"ok": False, "error": msg, "code": code, "network_mode": config.NETWORK.value}


async def dispatch(event: dict) -> dict:
    config.assert_testnet_or_explicit_mainnet()
    route = (event.get("route") or event.get("input", {}).get("route") or "").strip()
    # RunPod wraps payload in "input"
    body = event.get("input") if isinstance(event.get("input"), dict) else event

    route = (body.get("route") or route or "").strip()
    if not route or route == "health":
        return _ok(
            {
                "service": "elfall-network",
                "mode": config.NETWORK.value,
                "assets": [a["id"] for a in config.DEV_TESTNET_ASSETS],
                "flc_reference_usd": config.FLC_REFERENCE_USD,
                "disclaimer": config.FLC_REFERENCE_DISCLAIMER,
            }
        )

    if route == "assets":
        assets = []
        for a in config.DEV_TESTNET_ASSETS:
            item = dict(a)
            if item["id"] in config.FROZEN_ASSETS:
                item["frozen"] = True
                item["label_cs"] = "zmrazeno"
            elif item["id"] == "FLC":
                item["issuer"] = config.FLC_ISSUER_PUBLIC or None
                item["distribution"] = config.FLC_DISTRIBUTION_PUBLIC or None
            assets.append(item)
        return _ok({"assets": assets})

    if route == "btc/address":
        addr = body.get("address")
        if not addr:
            return _err("address required")
        return _ok(await proxies.btc_address(addr))

    if route == "btc/fees":
        return _ok(await proxies.btc_fees())

    if route == "stellar/account":
        acc = body.get("account")
        if not acc:
            return _err("account required")
        return _ok(await proxies.stellar_account(acc))

    if route == "eth/block":
        return _ok(await proxies.eth_block_number())

    if route == "sol/slot":
        return _ok(await proxies.solana_slot())

    if route == "polygon/block":
        return _ok(await proxies.polygon_block_number())

    if route == "flc/price":
        return _ok(
            {
                "pair": "FLC/USD",
                "price": config.FLC_REFERENCE_USD,
                "yflc_price": None,
                "yflc_frozen": True,
                "disclaimer": config.FLC_REFERENCE_DISCLAIMER,
                "testnet_only": True,
            }
        )

    return _err(f"unknown route: {route}", 404)


def handler(event: dict) -> dict:
    return asyncio.run(dispatch(event))



if __name__ == "__main__":
    runpod.serverless.start({"handler": handler})
