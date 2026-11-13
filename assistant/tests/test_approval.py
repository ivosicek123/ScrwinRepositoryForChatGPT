import pytest
from assistant import ApprovalGate, Approver, GeminiClient, Incident

gate = ApprovalGate()

@pytest.mark.parametrize("level", [4, 5])
def test_critical_always_ivo(level):
    assert gate.route(Incident("x", level=level)).approver is Approver.IVO

@pytest.mark.parametrize("cat", ["gdpr", "security_breach", "illegal", "legal_uncertain", "funds_at_risk"])
def test_hard_categories_ivo_even_at_low_level(cat):
    d = gate.route(Incident("x", level=1, categories={cat}))
    assert d.approver is Approver.IVO and d.level == 5

@pytest.mark.parametrize("level", [2, 3])
def test_medium_fallcoin(level):
    assert gate.route(Incident("x", level=level)).approver is Approver.FALLCOIN

def test_info_auto():
    assert gate.route(Incident("fee question", level=1)).approver is Approver.AUTO

def test_weighted_signal_escalates_to_ivo():
    assert gate.route(Incident("leak", level=1, signals={"seed_leak_suspected": 1.0})).approver is Approver.IVO

def test_unknown_level_escalates():
    assert gate.route(Incident("??")).approver is Approver.FALLCOIN

@pytest.mark.parametrize("a", ["hack_back", "decode_foreign_wallet", "deploy_malware", "counter_attack"])
def test_offensive_actions_rejected(a):
    d = gate.route(Incident("x", level=1, requested_action=a))
    assert not d.allowed and d.approver is Approver.IVO

def test_unknown_action_rejected():
    assert not gate.route(Incident("x", level=1, requested_action="scan_their_network")).allowed

def test_defensive_action_allowed():
    assert gate.route(Incident("x", level=2, requested_action="revoke_sessions")).allowed

def test_gemini_only_options_and_sanitizes():
    g = GeminiClient(api_key="")
    assert not g.online
    opts = g.propose_options(Incident("x", context={"seed": "abandon", "fee": 3}))
    assert [o.letter for o in opts] == list("ABCDE")
    assert GeminiClient.sanitize({"seed": 1, "Private_Key": 2, "fee": 3}) == {"fee": 3}
