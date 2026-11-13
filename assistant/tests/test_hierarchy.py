import pytest
from assistant import (FallcoinSupervisor, GeminiClient, GeminiAnalyst, Hierarchy, Incident, RateLimiter,
                       Tier, Verdict)


def h():
    return Hierarchy(GeminiAnalyst(GeminiClient(api_key="")))


def test_gemini_handles_only_low_info():
    r = h().handle(Incident("fee question", level=1), gemini_choice="A")
    assert r.verdict is Verdict.APPROVED and r.tier is Tier.ANALYST


def test_gemini_cannot_approve_medium_goes_to_fallcoin():
    r = h().handle(Incident("odd session", level=3, categories={"ops"}), gemini_choice="B")
    assert r.verdict is Verdict.APPROVED and r.tier is Tier.SUPERVISOR and r.chosen == "B"


def test_fallcoin_vetoes_bogus_option():
    r = h().handle(Incident("x", level=2), gemini_choice="Z")
    assert r.verdict is Verdict.VETOED


@pytest.mark.parametrize("cat", ["gdpr", "security_breach", "illegal", "legal_uncertain", "money_movement",
                                 "key_handling", "critical", "funds_at_risk"])
def test_watchdog_categories_escalate_to_ivo(cat):
    hy = h()
    r = hy.handle(Incident("x", level=1, categories={cat}), gemini_choice="A")
    assert r.verdict is Verdict.ESCALATED and r.tier is Tier.WATCHDOG
    assert len(hy.watchdog.pending) == 1


def test_high_level_escalates():
    assert h().handle(Incident("takeover", level=4)).tier is Tier.WATCHDOG


def test_secret_in_context_escalates_and_not_logged():
    hy = h()
    r = hy.handle(Incident("x", level=2, context={"mnemonic": "abandon abandon"}), gemini_choice="A")
    assert r.verdict is Verdict.ESCALATED
    assert "abandon" not in str(hy.supervisor.audit.entries)


def test_money_movement_by_amount_escalates():
    assert h().handle(Incident("pay", level=2, context={"amount_sats": 1000})).tier is Tier.WATCHDOG


@pytest.mark.parametrize("a", ["hack_back", "decode_foreign_wallet", "scan_their_network"])
def test_offensive_denied_even_ivo_cannot_enable(a):
    hy = h()
    r = hy.handle(Incident("x", level=2, requested_action=a))
    assert r.verdict is Verdict.DENIED
    out = hy.watchdog.decide(0, approve=True, option="A", decided_by="ivo")
    assert out.verdict is Verdict.DENIED


def test_only_ivo_can_resolve():
    hy = h()
    hy.handle(Incident("x", level=5))
    with pytest.raises(PermissionError):
        hy.watchdog.decide(0, True, "E", decided_by="fallcoin")
    assert hy.watchdog.decide(0, True, "E", decided_by="ivo").verdict is Verdict.APPROVED


def test_unknown_category_escalates():
    assert h().handle(Incident("x", level=2, categories={"weird"})).tier is Tier.WATCHDOG


def test_rate_limit_escalates():
    t = [0.0]
    sup = FallcoinSupervisor(limiter=RateLimiter(max_events=2, window_s=60, clock=lambda: t[0]))
    hy = Hierarchy(GeminiAnalyst(GeminiClient(api_key="")), sup)
    for _ in range(2):
        assert hy.handle(Incident("x", level=2), "A", source="s").verdict is Verdict.APPROVED
    assert hy.handle(Incident("x", level=2), "A", source="s").verdict is Verdict.ESCALATED


def test_audit_chain_verifies_and_detects_tamper():
    hy = h()
    for lvl in (1, 2, 5):
        hy.handle(Incident("x", level=lvl), "A")
    log = hy.supervisor.audit
    assert log.verify()
    log.entries[0]["payload"]["title"] = "tampered"
    assert not log.verify()
