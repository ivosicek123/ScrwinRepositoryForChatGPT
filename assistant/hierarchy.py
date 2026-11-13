"""Three-tier assistant hierarchy (deny-by-default).

Tier 1  Gemini   = ANALYST ("sprinter"): proposes options A–E. May only approve LOW (level 1) items
                   that touch no hard category and no sensitive action.
Tier 2  Fallcoin = SUPERVISOR: extended expert approvals for MEDIUM cases (levels 2–3); can VETO
                   Gemini; runs risk scoring, policy rules, rate limits and writes a hash-chained audit log.
Tier 3  Ivo      = WATCHDOG / final authority: critical (4–5), GDPR, security, legal/illegal, money
                   movement, key handling. Nothing in code can approve these on Ivo's behalf.

Defense only: offensive actions do not exist in the action set and are always denied.
"""
from __future__ import annotations

import hashlib
import json
import time
from collections import deque
from dataclasses import dataclass, field
from enum import Enum

from .approval import FORBIDDEN_ACTIONS, ApprovalGate, DefensiveAction, Incident
from .gemini import GeminiClient, Option


class Tier(int, Enum):
    ANALYST = 1     # Gemini
    SUPERVISOR = 2  # Fallcoin
    WATCHDOG = 3    # Ivo


class Verdict(str, Enum):
    APPROVED = "approved"
    VETOED = "vetoed"
    DENIED = "denied"
    ESCALATED = "escalated"   # waiting for a higher tier (human)


# Categories that only Ivo may decide — never configurable away.
WATCHDOG_CATEGORIES = frozenset({
    "critical", "gdpr", "security_breach", "illegal", "legal_uncertain",
    "funds_at_risk", "money_movement", "key_handling",
})

# Context keys that indicate secret / key material; their presence alone forces Ivo + redaction.
SECRET_MARKERS = ("seed", "mnemonic", "private_key", "xprv", "password", "pin", "otp", "cvv", "card", "iban")

# Max level each tier may approve.
TIER_CEILING = {Tier.ANALYST: 1, Tier.SUPERVISOR: 3, Tier.WATCHDOG: 5}


@dataclass
class PolicyHit:
    rule: str
    weight: float
    detail: str


@dataclass
class Review:
    verdict: Verdict
    tier: Tier                       # tier that produced/needs the decision
    level: int
    risk: float                      # 0..100
    chosen: str | None = None        # option letter
    reasons: list[str] = field(default_factory=list)
    policy_hits: list[PolicyHit] = field(default_factory=list)
    audit_hash: str | None = None


class AuditLog:
    """Append-only, hash-chained log. Stores no secrets: context values are never logged, only keys."""

    def __init__(self):
        self.entries: list[dict] = []

    def append(self, event: str, payload: dict) -> str:
        prev = self.entries[-1]["hash"] if self.entries else "0" * 64
        body = {"ts": round(time.time(), 3), "event": event, "payload": payload, "prev": prev}
        h = hashlib.sha256(json.dumps(body, sort_keys=True, default=str).encode()).hexdigest()
        body["hash"] = h
        self.entries.append(body)
        return h

    def verify(self) -> bool:
        prev = "0" * 64
        for e in self.entries:
            body = {k: v for k, v in e.items() if k != "hash"}
            if body["prev"] != prev:
                return False
            if hashlib.sha256(json.dumps(body, sort_keys=True, default=str).encode()).hexdigest() != e["hash"]:
                return False
            prev = e["hash"]
        return True


class RateLimiter:
    """Sliding-window limiter per key (e.g. per incident source)."""

    def __init__(self, max_events: int = 20, window_s: float = 60.0, clock=time.monotonic):
        self.max, self.window, self.clock = max_events, window_s, clock
        self.hits: dict[str, deque] = {}

    def allow(self, key: str) -> bool:
        now = self.clock()
        q = self.hits.setdefault(key, deque())
        while q and now - q[0] > self.window:
            q.popleft()
        if len(q) >= self.max:
            return False
        q.append(now)
        return True


class GeminiAnalyst:
    """Tier 1. Proposes; may self-approve only level-1 informational items."""

    tier = Tier.ANALYST

    def __init__(self, client: GeminiClient | None = None):
        self.client = client or GeminiClient()

    def propose(self, inc: Incident) -> list[Option]:
        return self.client.propose_options(inc)

    def can_approve(self, level: int, inc: Incident) -> bool:
        return level <= TIER_CEILING[self.tier] and not inc.categories and not inc.requested_action


class FallcoinSupervisor:
    """Tier 2. Risk scoring + policy rules + veto + audit."""

    tier = Tier.SUPERVISOR

    def __init__(self, gate: ApprovalGate | None = None, audit: AuditLog | None = None,
                 limiter: RateLimiter | None = None):
        self.gate = gate or ApprovalGate()
        self.audit = audit or AuditLog()
        self.limiter = limiter or RateLimiter()

    # --- policy rules -------------------------------------------------------------------------
    def policy(self, inc: Incident, option: Option | None) -> list[PolicyHit]:
        hits: list[PolicyHit] = []
        action = (inc.requested_action or "").lower()
        if action in FORBIDDEN_ACTIONS:
            hits.append(PolicyHit("offensive_action", 100, action))
        elif action and action not in {a.value for a in DefensiveAction}:
            hits.append(PolicyHit("unknown_action_deny_by_default", 100, action))
        secret_keys = [k for k in inc.context if any(m in k.lower() for m in SECRET_MARKERS)]
        if secret_keys:
            hits.append(PolicyHit("secret_material_in_context", 60, ",".join(sorted(secret_keys))))
        if inc.categories & WATCHDOG_CATEGORIES:
            hits.append(PolicyHit("watchdog_category", 50, ",".join(sorted(inc.categories & WATCHDOG_CATEGORIES))))
        unknown_cats = inc.categories - WATCHDOG_CATEGORIES - {"ops", "ux", "info", "abuse", "phishing"}
        if unknown_cats:
            hits.append(PolicyHit("unknown_category", 20, ",".join(sorted(unknown_cats))))
        if option is not None and option.warnings and any("schválení" in w or "approval" in w.lower() for w in option.warnings):
            hits.append(PolicyHit("option_flags_approval", 15, option.letter))
        if inc.context.get("amount_sats") or inc.context.get("amount"):
            hits.append(PolicyHit("money_movement", 50, "amount present"))
        return hits

    def risk(self, level: int, hits: list[PolicyHit]) -> float:
        return min(100.0, level * 15 + sum(h.weight for h in hits))

    # --- review -------------------------------------------------------------------------------
    def review(self, inc: Incident, options: list[Option], proposed: str | None = None,
               source: str = "default") -> Review:
        letters = {o.letter: o for o in options}
        option = letters.get(proposed) if proposed else None
        level = self.gate.score(inc)
        hits = self.policy(inc, option)
        risk = self.risk(level, hits)
        rules = {h.rule for h in hits}
        reasons: list[str] = []

        if not self.limiter.allow(source):
            v, tier = Verdict.ESCALATED, Tier.WATCHDOG
            reasons.append("rate limit exceeded → Ivo")
        elif rules & {"offensive_action", "unknown_action_deny_by_default"}:
            v, tier = Verdict.DENIED, Tier.WATCHDOG
            reasons.append("deny-by-default: only defensive actions exist")
        elif proposed and option is None:
            v, tier = Verdict.VETOED, Tier.SUPERVISOR
            reasons.append(f"Gemini proposed unknown option '{proposed}' → veto")
        elif rules & {"watchdog_category", "secret_material_in_context", "money_movement"} or level >= 4 or risk >= 70:
            v, tier = Verdict.ESCALATED, Tier.WATCHDOG
            reasons.append("watchdog-only scope (critical/GDPR/security/legal/money/keys) → Ivo")
        elif "unknown_category" in rules:
            v, tier = Verdict.ESCALATED, Tier.WATCHDOG
            reasons.append("uncertainty escalates")
        else:
            v, tier = Verdict.APPROVED, Tier.SUPERVISOR
            reasons.append("medium case approved by Fallcoin" if level >= 2 else "low case confirmed by Fallcoin")

        chosen = proposed if v is Verdict.APPROVED else None
        if v is Verdict.APPROVED and chosen is None:
            chosen = "A" if "A" in letters else None  # least-impact default
        r = Review(v, tier, level, risk, chosen, reasons, hits)
        r.audit_hash = self.audit.append("review", {
            "title": inc.title, "verdict": v.value, "tier": tier.name, "level": level, "risk": risk,
            "chosen": chosen, "rules": sorted(rules), "context_keys": sorted(inc.context.keys()),
        })
        return r


class IvoWatchdog:
    """Tier 3. Holds a queue; only an explicit human decision resolves it."""

    tier = Tier.WATCHDOG

    def __init__(self, audit: AuditLog):
        self.audit = audit
        self.pending: list[tuple[Incident, Review]] = []

    def enqueue(self, inc: Incident, review: Review) -> None:
        self.pending.append((inc, review))
        self.audit.append("notify_ivo", {"title": inc.title, "level": review.level, "risk": review.risk})

    def decide(self, index: int, approve: bool, option: str | None, decided_by: str) -> Review:
        if decided_by != "ivo":
            raise PermissionError("Only Ivo (human) can resolve watchdog items")
        inc, rev = self.pending.pop(index)
        if any(h.rule in {"offensive_action", "unknown_action_deny_by_default"} for h in rev.policy_hits):
            approve = False  # not even Ivo can enable offensive actions
        rev.verdict = Verdict.APPROVED if approve else Verdict.DENIED
        rev.chosen = option if approve else None
        rev.audit_hash = self.audit.append("ivo_decision", {"title": inc.title, "approve": approve, "option": rev.chosen})
        return rev


class Hierarchy:
    """incident → Gemini options → (Gemini self-approve low) | Fallcoin review → Ivo queue."""

    def __init__(self, analyst: GeminiAnalyst | None = None, supervisor: FallcoinSupervisor | None = None):
        self.analyst = analyst or GeminiAnalyst()
        self.supervisor = supervisor or FallcoinSupervisor()
        self.watchdog = IvoWatchdog(self.supervisor.audit)

    def handle(self, inc: Incident, gemini_choice: str | None = None, source: str = "default") -> Review:
        options = self.analyst.propose(inc)
        level = self.supervisor.gate.score(inc)
        if self.analyst.can_approve(level, inc) and gemini_choice in {o.letter for o in options}:
            hits = self.supervisor.policy(inc, None)
            if not hits:
                r = Review(Verdict.APPROVED, Tier.ANALYST, level, self.supervisor.risk(level, hits), gemini_choice,
                           ["low informational item auto-handled by Gemini"])
                r.audit_hash = self.supervisor.audit.append("gemini_low", {"title": inc.title, "chosen": gemini_choice})
                return r
        r = self.supervisor.review(inc, options, gemini_choice, source)
        if r.verdict is Verdict.ESCALATED or (r.verdict is Verdict.DENIED and r.tier is Tier.WATCHDOG):
            self.watchdog.enqueue(inc, r)
        return r
