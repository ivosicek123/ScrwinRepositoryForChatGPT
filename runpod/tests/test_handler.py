import pytest
import respx
import httpx

from app import config
from app.handler import dispatch


@pytest.mark.asyncio
async def test_health_and_assets():
    h = await dispatch({"input": {"route": "health"}})
    assert h["ok"] is True
    assert h["network_mode"] == "testnet"
    assert "tBTC" in h["data"]["assets"]
    assert h["data"]["flc_reference_usd"] == 0.33

    a = await dispatch({"input": {"route": "assets"}})
    ids = [x["id"] for x in a["data"]["assets"]]
    assert ids == ["tBTC", "XLM", "sepETH", "devSOL", "amoyMATIC", "FLC", "yFLC"]
    y = next(x for x in a["data"]["assets"] if x["id"] == "yFLC")
    assert y["frozen"] is True and y["label_cs"] == "zmrazeno"
    assert "issuer" not in y


@pytest.mark.asyncio
@respx.mock
async def test_btc_fees_proxy():
    respx.get(f"{config.BTC_ESPLORA}/v1/fees/recommended").mock(
        return_value=httpx.Response(200, json={"fastestFee": 5, "halfHourFee": 3, "hourFee": 2})
    )
    out = await dispatch({"input": {"route": "btc/fees"}})
    assert out["ok"]
    assert out["data"]["fees"]["fastestFee"] == 5


@pytest.mark.asyncio
@respx.mock
async def test_stellar_account_404():
    respx.get(f"{config.STELLAR_HORIZON}/accounts/GTEST").mock(
        return_value=httpx.Response(404, json={"detail": "Not Found"})
    )
    out = await dispatch({"input": {"route": "stellar/account", "account": "GTEST"}})
    assert out["ok"]
    assert out["data"]["exists"] is False


@pytest.mark.asyncio
async def test_flc_price_disclaimer():
    out = await dispatch({"input": {"route": "flc/price"}})
    assert out["data"]["price"] == 0.33
    assert out["data"]["testnet_only"] is True
    assert out["data"]["yflc_price"] is None and out["data"]["yflc_frozen"] is True
    assert "NOT a real peg" in out["data"]["disclaimer"]


@pytest.mark.asyncio
async def test_unknown_route():
    out = await dispatch({"input": {"route": "nope"}})
    assert out["ok"] is False
    assert out["code"] == 404
