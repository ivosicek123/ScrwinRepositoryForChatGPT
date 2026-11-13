"""ELFALL assistant: Gemini 'sprinter' (options only) + ApprovalGate (Fallcoin / Ivo).

DEFENSE ONLY. Allowed actions: detect, block, log, revoke sessions, freeze features, notify Ivo,
preserve evidence for authorities. Offensive actions (hack-back, unauthorized access, decoding others'
wallets, malware, surveillance) are intentionally NOT implemented and are rejected by the gate.
"""
from .gemini import GeminiClient, Option
from .approval import ApprovalGate, Approver, Decision, Incident, DefensiveAction
from .hierarchy import (AuditLog, FallcoinSupervisor, GeminiAnalyst, Hierarchy, IvoWatchdog, RateLimiter,
                        Review, Tier, Verdict)

__all__ = ["GeminiClient", "Option", "ApprovalGate", "Approver", "Decision", "Incident", "DefensiveAction",
           "AuditLog", "FallcoinSupervisor", "GeminiAnalyst", "Hierarchy", "IvoWatchdog", "RateLimiter",
           "Review", "Tier", "Verdict"]
