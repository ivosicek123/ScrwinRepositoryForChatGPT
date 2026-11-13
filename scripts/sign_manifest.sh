#!/usr/bin/env bash
# Podpis manifestu kritických hodnot – spouští POUZE vlastník (Ivo Faltýn) svým klíčem, ideálně FIDO2 (sk-ssh-ed25519).
# Použití: scripts/sign_manifest.sh ~/.ssh/id_ed25519_sk
set -euo pipefail
KEY="${1:?cesta k soukromému SSH klíči (ideálně hardware sk-ssh-ed25519)}"
cd "$(dirname "$0")/.."
# Jen Ed25519 (software nebo FIDO2 hardware) – RSA/ECDSA odmítnuto.
KTYPE="$(ssh-keygen -y -f "$KEY" 2>/dev/null | awk '{print $1}' || true)"
[ -z "$KTYPE" ] && KTYPE="$(awk '{print $1}' "$KEY.pub" 2>/dev/null || true)"
case "$KTYPE" in ssh-ed25519|sk-ssh-ed25519@openssh.com) ;; *) echo "Klíč musí být Ed25519 (nalezeno: ${KTYPE:-neznámý})" >&2; exit 2;; esac
python3 scripts/integrity_manifest.py generate
ssh-keygen -Y sign -f "$KEY" -n elfall-integrity integrity/MANIFEST.sha512
python3 scripts/integrity_manifest.py verify
echo "Hotovo. Commitněte integrity/MANIFEST.sha512 + .sig podepsaným commitem (git commit -S)."
