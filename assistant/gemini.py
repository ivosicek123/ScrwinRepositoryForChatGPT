"""Gemini client stub. Gemini only PROPOSES options A–E; it never approves or executes anything."""
from __future__ import annotations

import os
from dataclasses import dataclass, field

GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"  # placeholder
FORBIDDEN_PROMPT_FIELDS = ("seed", "mnemonic", "private_key", "xprv", "password", "iban", "card")


@dataclass
class Option:
    letter: str
    summary: str
    consequences: str
    warnings: list[str] = field(default_factory=list)


class GeminiClient:
    def __init__(self, api_key: str | None = None, model: str = "gemini-flash-latest"):
        self.api_key = api_key if api_key is not None else os.environ.get("GEMINI_API_KEY")
        self.model = model

    @property
    def online(self) -> bool:
        return bool(self.api_key)

    @staticmethod
    def sanitize(context: dict) -> dict:
        """GDPR minimisation: drop secrets/PII before anything could leave the box."""
        return {k: v for k, v in context.items() if not any(f in k.lower() for f in FORBIDDEN_PROMPT_FIELDS)}

    def propose_options(self, incident) -> list[Option]:
        _ = self.sanitize(incident.context)
        # Real call (not wired): POST GEMINI_ENDPOINT with sanitized context, parse 5 options.
        # Offline stub returns deterministic defensive options only.
        return [
            Option("A", "Pouze zalogovat a sledovat", "Žádný dopad na uživatele", ["Hrozba může pokračovat"]),
            Option("B", "Zablokovat akci a upozornit uživatele", "Mírné omezení"),
            Option("C", "Zneplatnit relace/tokeny", "Uživatel se musí znovu přihlásit"),
            Option("D", "Zmrazit citlivé funkce (převody)", "Dočasná nedostupnost funkcí", ["Vyžaduje schválení"]),
            Option("E", "Zachovat důkazy a eskalovat Ivovi / orgánům", "Nejvyšší jistota, pomalejší",
                   ["Při GDPR incidentu lhůta 72 h"]),
        ]
