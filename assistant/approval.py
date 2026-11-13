"""Severity-weighted approval routing. Critical / GDPR / security / illegal ALWAYS go to Ivo."""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum


class Approver(str, Enum):
    AUTO = "auto"          # level 1 informational only
    FALLCOIN = "fallcoin"  # levels 2–3
    IVO = "ivo"            # levels 4–5 + always-escalate categories


class DefensiveAction(str, Enum):
    LOG = "log"
    BLOCK = "block"
    REVOKE_SESSIONS = "revoke_sessions"
    FREEZE_FEATURES = "freeze_features"
    NOTIFY_IVO = "notify_ivo"
    PRESERVE_EVIDENCE = "preserve_evidence"


# Never implemented, always rejected (§ 230 TZ – unauthorized access, etc.).
FORBIDDEN_ACTIONS = frozenset({
    "hack_back", "counter_attack", "access_foreign_system", "decode_foreign_wallet",
    "deploy_malware", "surveillance", "ddos",
})

ALWAYS_IVO = frozenset({"gdpr", "security_breach", "illegal", "legal_uncertain", "funds_at_risk",
                        "money_movement", "key_handling", "critical"})

SIGNAL_WEIGHTS = {
    "seed_leak_suspected": 5.0,
    "personal_data_leak": 5.0,
    "unauthorized_backend_access": 4.5,
    "api_token_leak": 4.0,
    "phishing_domain": 3.5,
    "biometric_failures": 2.5,
    "mainnet_address_in_testnet": 1.5,
    "invalid_input": 0.5,
    "public_api_outage": 0.5,
}


@dataclass
class Incident:
    title: str
    level: int | None = None              # 1–5 if known
    categories: set[str] = field(default_factory=set)
    signals: dict[str, float] = field(default_factory=dict)  # signal -> strength 0..1
    requested_action: str | None = None
    context: dict = field(default_factory=dict)


@dataclass
class Decision:
    approver: Approver
    level: int
    reason: str
    allowed: bool = True


class ApprovalGate:
    def score(self, inc: Incident) -> int:
        weighted = sum(SIGNAL_WEIGHTS.get(k, 1.0) * max(0.0, min(1.0, v)) for k, v in inc.signals.items())
        base = inc.level if inc.level is not None else 2  # unknown -> escalate above informational
        if inc.level is None and not inc.signals:
            base = 3  # uncertainty escalates
        return max(1, min(5, max(base, round(weighted))))

    def route(self, inc: Incident, options=None) -> Decision:
        action = (inc.requested_action or "").lower()
        if action in FORBIDDEN_ACTIONS:
            return Decision(Approver.IVO, 5, f"Zakázaná útočná akce '{action}' – zamítnuto, eskalace Ivovi", allowed=False)
        if action and action not in {a.value for a in DefensiveAction}:
            return Decision(Approver.IVO, 5, f"Neznámá akce '{action}' – jen obranné akce jsou povoleny", allowed=False)
        level = self.score(inc)
        hard = inc.categories & ALWAYS_IVO
        if hard:
            return Decision(Approver.IVO, 5, f"Pevná kategorie {sorted(hard)} → vždy Ivo")
        if level >= 4:
            return Decision(Approver.IVO, level, "Vysoká/kritická závažnost → Ivo")
        if level >= 2:
            return Decision(Approver.FALLCOIN, level, "Střední případ → Fallcoin")
        return Decision(Approver.AUTO, level, "Informační")
