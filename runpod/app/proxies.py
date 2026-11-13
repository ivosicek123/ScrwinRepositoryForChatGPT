"""Read-only proxies to public testnet endpoints (and optional self-hosted URLs)."""
from __future__ import annotations

import httpx

from . import config


async def _get(client: httpx.AsyncClient, url: str) -> dict | list | str:
    r = await client.get(url, timeout=30.0)
    r.raise_for_status()
    ct = r.headers.get("content-type", "")
    if "application/json" in ct:
        return r.json()
    return r.text


async def btc_address(address: str) -> dict:
    async with httpx.AsyncClient() as client:
        utxo = await _get(client, f"{config.BTC_ESPLORA}/address/{address}/utxo")
        tip = await _get(client, f"{config.BTC_ESPLORA}/blocks/tip/height")
        balance = sum(u.get("value", 0) for u in utxo) if isinstance(utxo, list) else 0
        return {
            "network": "bitcoin-testnet" if config.NETWORK.value == "testnet" else "bitcoin",
            "address": address,
            "balance_sats": balance,
            "utxo_count": len(utxo) if isinstance(utxo, list) else 0,
            "tip_height": tip,
            "upstream": config.BTC_ESPLORA,
        }


async def btc_fees() -> dict:
    async with httpx.AsyncClient() as client:
        fees = await _get(client, f"{config.BTC_ESPLORA}/v1/fees/recommended")
        return {"network": "bitcoin-testnet", "fees": fees, "upstream": config.BTC_ESPLORA}


async def stellar_account(account_id: str) -> dict:
    async with httpx.AsyncClient() as client:
        try:
            data = await _get(client, f"{config.STELLAR_HORIZON}/accounts/{account_id}")
            return {"exists": True, "account": data, "upstream": config.STELLAR_HORIZON}
        except httpx.HTTPStatusError as e:
            if e.response.status_code == 404:
                return {"exists": False, "account_id": account_id, "upstream": config.STELLAR_HORIZON}
            raise


async def eth_block_number() -> dict:
    payload = {"jsonrpc": "2.0", "method": "eth_blockNumber", "params": [], "id": 1}
    async with httpx.AsyncClient() as client:
        r = await client.post(config.ETH_RPC, json=payload, timeout=30.0)
        r.raise_for_status()
        result = r.json().get("result", "0x0")
        return {
            "network": "ethereum-sepolia" if config.NETWORK.value == "testnet" else "ethereum",
            "block_number": int(result, 16),
            "upstream": config.ETH_RPC,
        }


async def solana_slot() -> dict:
    payload = {"jsonrpc": "2.0", "id": 1, "method": "getSlot"}
    async with httpx.AsyncClient() as client:
        r = await client.post(config.SOLANA_RPC, json=payload, timeout=30.0)
        r.raise_for_status()
        return {
            "network": "solana-devnet" if config.NETWORK.value == "testnet" else "solana",
            "slot": r.json().get("result"),
            "upstream": config.SOLANA_RPC,
        }


async def polygon_block_number() -> dict:
    payload = {"jsonrpc": "2.0", "method": "eth_blockNumber", "params": [], "id": 1}
    async with httpx.AsyncClient() as client:
        r = await client.post(config.POLYGON_RPC, json=payload, timeout=30.0)
        r.raise_for_status()
        result = r.json().get("result", "0x0")
        return {
            "network": "polygon-amoy" if config.NETWORK.value == "testnet" else "polygon",
            "block_number": int(result, 16),
            "upstream": config.POLYGON_RPC,
        }
