# deploy/ztna – Zero Trust síťová vrstva (**NENASAZENO**, jen návrh)

| Soubor | Co | Kdy nasadit |
|---|---|---|
| `cilium-policy.yaml` | Cilium L3–L7 politika: jen ingress → bankid, jen známé cesty, egress jen Bank iD + DNS | až bude k8s s Cilium CNI |
| `xdp_drop.c` | XDP: na veřejném rozhraní propustí jen TCP 443, ICMP a odpovědi | jen při vlastním hostingu (ne za CDN/managed LB) |
| `suricata-elfall.rules` | lokální IDS pravidla (chybějící DPoP, Bearer, `alg:none`, staré TLS, brute-force `/zt/token`) | s produkčním provozem, spolu s ET Open |

Aplikační vrstva Zero Trust je **implementovaná** v backendu (`ZeroTrust.kt`): deny-by-default,
DPoP tokeny ≤ 5 min vázané na passkey + klíč zařízení, auditní log s hash řetězem.
Žádný z těchto souborů neprovádí aktivní zásahy vůči cizím sítím (POLICY.md, pravidlo 1).

## Doplněno ve větvi web-leptos


- **eBPF/XDP filtrování paketů zůstává výhradně na serveru** (Cilium/eBPF CNI, případně XDP drop při vlastním
  hostingu – viz `docs/security/ZERO-TRUST-ROADMAP.md`, bod 5). Klienti (iOS/Android/web) o něm nic nevědí a nic
  nekonfigurují; v aplikacích není žádný eBPF kód.
- Klientský šifrovaný kanál `ELFALL-CH-v1` (`core/src/channel.rs`): X25519 `crypto_kx` + XChaCha20-Poly1305
  `secretstream`, připnutý statický kx klíč backendu, `binding = BLAKE2b-256("ELFALL-CH-v1\0"‖client_pk‖server_pk)`
  je AD každé zprávy a výzva k podpisu klíčem Secure Enclave. První zpráva = krátkodobý device-bound token;
  backend ověří exp, `cnf` (otisk SE klíče) a ES256 podpis `binding`. Serverová implementace v backendu zatím chybí.

## Serverové konfigurace (jen server)
- `cilium-eleanor-web.yaml` – CiliumNetworkPolicy pro Leptos PWA (eBPF, L7 allowlist pro `/passkey/*` a GET), poznámka k XDP.
- `suricata-elfall.rules` – lokální pravidla Suricata IDS (rate passkey options, traversal, seed v URI, staré TLS).
- Neověřeno proti běžícímu clusteru/Suricatě (box nemá k8s ani Suricatu).
