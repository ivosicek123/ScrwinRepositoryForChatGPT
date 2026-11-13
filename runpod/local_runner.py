#!/usr/bin/env python3
"""Local smoke runner (no RunPod deploy). Usage: python local_runner.py btc/fees"""
from __future__ import annotations

import json
import sys

from app.handler import handler


def main() -> None:
    route = sys.argv[1] if len(sys.argv) > 1 else "health"
    payload = {"input": {"route": route}}
    if route == "btc/address" and len(sys.argv) > 2:
        payload["input"]["address"] = sys.argv[2]
    if route == "stellar/account" and len(sys.argv) > 2:
        payload["input"]["account"] = sys.argv[2]
    print(json.dumps(handler(payload), indent=2)[:4000])


if __name__ == "__main__":
    main()
