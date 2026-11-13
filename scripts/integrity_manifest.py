#!/usr/bin/env python3
"""ELFALL integrity manifest – ochrana proti tichému 'swap' útoku na kritické hodnoty.

  generate  – přepočítá integrity/MANIFEST.sha512 ze souborů v integrity/protected-files.txt
  verify    – (CI) 1) hashe souborů == manifest, 2) konstanty ve zdrojácích == integrity/critical.json,
              3) podpis integrity/MANIFEST.sha512.sig ověřený přes ssh-keygen -Y verify a integrity/allowed_signers.
              --no-signature přeskočí bod 3 (jen lokální vývoj).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INTEG = ROOT / "integrity"
MANIFEST = INTEG / "MANIFEST.sha512"
SIG = INTEG / "MANIFEST.sha512.sig"
SIGNERS = INTEG / "allowed_signers"
NAMESPACE = "elfall-integrity"
IDENTITY = "ivosicek123"
# Bankovní účet nesmí být v repu – hrubý vzor CZ účtu "číslo/kód banky".
ACCOUNT_RE = re.compile(r"\b\d{6,10}/\d{4}\b")


def protected(root: Path = ROOT) -> list[str]:
    lines = (root / "integrity" / "protected-files.txt").read_text(encoding="utf-8").splitlines()
    return [ln.strip() for ln in lines if ln.strip() and not ln.startswith("#")]


def sha512(p: Path) -> str:
    return hashlib.sha512(p.read_bytes()).hexdigest()


# Povolené typy podpisových klíčů vlastníka: jen Ed25519 (softwarový nebo FIDO2 hardware).
ALLOWED_KEY_TYPES = ("ssh-ed25519", "sk-ssh-ed25519@openssh.com")


def build_manifest(root: Path = ROOT) -> str:
    out = []
    for rel in protected(root):
        p = root / rel
        if not p.is_file():
            raise SystemExit(f"Chráněný soubor chybí: {rel}")
        out.append(f"{sha512(p)}  {rel}")
    return "\n".join(out) + "\n"


def check_constants(root: Path = ROOT) -> list[str]:
    crit = json.loads((root / "integrity" / "critical.json").read_text(encoding="utf-8"))
    st = crit["stellar"]
    errs: list[str] = []
    dev = (root / "shared/src/commonMain/kotlin/cz/faltyn/elfall/shared/assets/DevAssets.kt").read_text(encoding="utf-8")
    cc = (root / "shared/src/commonMain/kotlin/cz/faltyn/elfall/shared/integrity/CriticalConstants.kt").read_text(encoding="utf-8")
    stubs = (root / "shared/src/commonMain/kotlin/cz/faltyn/elfall/shared/offramp/StubProviders.kt").read_text(encoding="utf-8")
    toml = (root / "web/.well-known/stellar.toml").read_text(encoding="utf-8")
    issuers_dev = set(re.findall(r'issuerPublic = "([A-Z0-9]{56})"', dev))
    if issuers_dev != {st["flc_issuer_public"]}:
        errs.append(f"DevAssets.kt issuer {issuers_dev} != critical.json")
    if f'FLC_ISSUER_PUBLIC = "{st["flc_issuer_public"]}"' not in cc:
        errs.append("CriticalConstants.FLC_ISSUER_PUBLIC != critical.json")
    if f'STELLAR_HOME_DOMAIN = "{st["home_domain"]}"' not in cc:
        errs.append("CriticalConstants.STELLAR_HOME_DOMAIN != critical.json")
    if f'FLC_REFERENCE_USD = {st["flc_reference_usd"]}' not in dev:
        errs.append("DevAssets.FLC_REFERENCE_USD != critical.json")
    if f'issuer = "{st["flc_issuer_public"]}"' not in toml or f'code = "{st["flc_asset_code"]}"' not in toml:
        errs.append("web/.well-known/stellar.toml issuer/code != critical.json")
    if crit["author_attribution"] not in cc:
        errs.append("CriticalConstants.AUTHOR_ATTRIBUTION != critical.json")
    for name in ("AUTHORS", "NOTICE", "LICENSE"):
        if "Ivo Faltýn" not in (root / name).read_text(encoding="utf-8"):
            errs.append(f"{name}: chybí atribuce autora")
    for prefix in crit["offramp_provider_url_prefixes"]:
        if prefix not in stubs or prefix not in cc:
            errs.append(f"URL prefix poskytovatele chybí/změněn: {prefix}")
    if st["yflc_frozen"] and "YFLC_FROZEN = true" not in dev:
        errs.append("yFLC již není zmrazen")
    for rel in protected(root):
        p = root / rel
        if p.is_file() and ACCOUNT_RE.search(p.read_text(encoding="utf-8", errors="ignore")):
            errs.append(f"{rel}: obsahuje vzor čísla bankovního účtu – nepatří do repa")
    return errs


def verify_signature(root: Path = ROOT) -> str | None:
    signers = root / "integrity" / "allowed_signers"
    keys = [ln for ln in signers.read_text().splitlines() if ln.strip() and not ln.startswith("#")]
    if not keys:
        return "integrity/allowed_signers neobsahuje veřejný klíč vlastníka"
    for ln in keys:
        if not any(f" {t} " in f" {ln} " for t in ALLOWED_KEY_TYPES):
            return "integrity/allowed_signers: povoleny jen klíče Ed25519 (ssh-ed25519 / sk-ssh-ed25519@openssh.com)"
    sig = root / "integrity" / "MANIFEST.sha512.sig"
    if not sig.is_file():
        return "chybí integrity/MANIFEST.sha512.sig – vlastník musí spustit scripts/sign_manifest.sh"
    if not shutil.which("ssh-keygen"):
        return "ssh-keygen není k dispozici"
    with (root / "integrity" / "MANIFEST.sha512").open("rb") as f:
        r = subprocess.run(["ssh-keygen", "-Y", "verify", "-f", str(signers), "-I", IDENTITY,
                            "-n", NAMESPACE, "-s", str(sig)], stdin=f, capture_output=True, text=True)
    return None if r.returncode == 0 else f"podpis manifestu neplatný: {r.stderr.strip() or r.stdout.strip()}"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["generate", "verify"])
    ap.add_argument("--no-signature", action="store_true")
    a = ap.parse_args(argv)
    if a.cmd == "generate":
        MANIFEST.write_text(build_manifest(), encoding="utf-8")
        print(f"Zapsáno {MANIFEST.relative_to(ROOT)} – nyní podepište: scripts/sign_manifest.sh")
        return 0
    errs = check_constants()
    current = build_manifest()
    if not MANIFEST.is_file() or MANIFEST.read_text(encoding="utf-8") != current:
        errs.append("chráněné soubory se liší od integrity/MANIFEST.sha512 (změna bez nového podpisu)")
    if not a.no_signature:
        e = verify_signature()
        if e:
            errs.append(e)
    for e in errs:
        print(f"::error::INTEGRITY {e}")
    print("INTEGRITY OK" if not errs else f"INTEGRITY FAILED ({len(errs)})")
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
