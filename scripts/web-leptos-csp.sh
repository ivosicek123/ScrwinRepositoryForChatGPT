#!/usr/bin/env bash
# Trunk post_build hook: přísná CSP s SHA-384 hashem inline zavaděče (jediný inline skript = Trunk init
# wasm-bindgen glue). Spouští Trunk s TRUNK_STAGING_DIR; ručně: TRUNK_STAGING_DIR=dist scripts/web-leptos-csp.sh
set -euo pipefail
D="${TRUNK_STAGING_DIR:?}"
API="https://mempool.space"
python3 - "$D" "$API" <<'PY'
import base64, hashlib, re, sys, pathlib
d, api = pathlib.Path(sys.argv[1]), sys.argv[2]
html = (d / "index.html").read_text()
# Vlastní pin: inline init od Trunku nahradíme zavaděčem, který stáhne wasm, ověří SHA-384 (navíc k SRI na preload)
# a teprve pak předá BAJTY do wasm-bindgen init() – prohlížeč tak nikdy nespustí neověřený modul. Fail-closed.
wasm = next(d.glob("*_bg.wasm")); glue = next(p for p in d.glob("*.js") if p.name not in ("sw.js",))
pin = base64.b64encode(hashlib.sha384(wasm.read_bytes()).digest()).decode()
loader = (f"\nimport init from '/{glue.name}';\n"
          f"const WASM_SHA384 = '{pin}';\n"
          f"const bytes = await (await fetch('/{wasm.name}', {{ integrity: 'sha384-' + WASM_SHA384 }})).arrayBuffer();\n"
          "const got = btoa(String.fromCharCode(...new Uint8Array(await crypto.subtle.digest('SHA-384', bytes))));\n"
          "if (got !== WASM_SHA384) { document.body.textContent = 'Integrita aplikace nesouhlasí – nepoužívejte ji.'; throw new Error('wasm pin'); }\n"
          "await init({ module_or_path: bytes });\n")
html, n = re.subn(r'(<script type="module">)(.*?)(</script>)', lambda m: m.group(1) + loader + m.group(3), html, count=1, flags=re.S)
if n != 1: sys.exit("Trunk inline zavaděč nenalezen")
inline = re.findall(r"<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>", html, re.S)
hashes = " ".join(f"'sha384-{base64.b64encode(hashlib.sha384(s.encode()).digest()).decode()}'" for s in inline)
csp = ("default-src 'none'; "
       f"script-src 'self' 'wasm-unsafe-eval' {hashes}; ".replace("  ", " ") +
       "style-src 'self'; img-src 'self' data:; "
       f"connect-src 'self' {api}; manifest-src 'self'; worker-src 'self'; "
       "object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; frame-src 'none'; upgrade-insecure-requests")
# <meta> nepodporuje frame-ancestors -> v meta vynechat (platí z HTTP hlavičky)
meta = csp.replace(" frame-ancestors 'none';", "").replace(" upgrade-insecure-requests", "").rstrip("; ")
if 'http-equiv="Content-Security-Policy"' not in html:
    html = html.replace('<meta charset="utf-8">', f'<meta charset="utf-8">\n<meta http-equiv="Content-Security-Policy" content="{meta}">', 1)
(d / "index.html").write_text(html)
(d / "csp.txt").write_text(csp + "\n")
(d / "nginx-csp.conf").write_text(f'add_header Content-Security-Policy "{csp}" always;\n')
hdr = ["/*", f"  Content-Security-Policy: {csp}",
       "  Strict-Transport-Security: max-age=63072000; includeSubDomains; preload",
       "  X-Content-Type-Options: nosniff", "  X-Frame-Options: DENY", "  Referrer-Policy: no-referrer",
       "  Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=(), usb=()",
       "  Cross-Origin-Opener-Policy: same-origin", "  Cross-Origin-Resource-Policy: same-origin"]
(d / "_headers").write_text("\n".join(hdr) + "\n")
tag = hashlib.sha256(wasm.read_bytes()).hexdigest()[:12]
sw = d / "sw.js"; sw.write_text(sw.read_text().replace("__WASM_TAG__", tag))
print("CSP:", csp)
PY
