# Postupné nasazení Zero Trust (canary 1 % → 10 % → 50 % → 100 %)

Zero Trust je ve výchozím stavu **vypnutý** (`ZT_ENABLE` není `true`). Zapíná se jen v canary deploymentu
`bankid-zt` (`canary.yaml`). `/healthz` je vždy veřejný (PUBLIC_ROUTES), sondy fungují i se ZT.

## Příprava (jednou, mimo repozitář)
1. Ed25519 klíč: vygenerovat offline OKP JWK (s `kid`) a uložit do secret store:
   `kubectl -n elfall create secret generic zt-signing --from-file=jwk=/bezpecne/misto/zt-signing.jwk` – soubor nikdy necommitovat.
2. Doplnit digest image a host v `canary.yaml`, `kubectl apply -f deploy/rollout/canary.yaml` (váha 1 %).
3. Načíst alerty: `deploy/monitoring/zt-alerts.yaml` (PrometheusRule).

## Kroky
| Krok | canary-weight | Doba držení | Postup dál, jen když |
|---|---|---|---|
| 1 | 1 | ≥ 24 h | žádný alert `ZtDenySpike`/`ZtAuditChainBroken`/`ZtReplayAttempts`, chybovost 5xx ≤ stabilní |
| 2 | 10 | ≥ 24 h | totéž + podíl 401 < 2 % |
| 3 | 50 | ≥ 48 h | totéž, ruční test passkey + DPoP na iOS i webu |
| 4 | 100 | – | poté nastavit `ZT_ENABLE=true` i ve stabilním `bankid` a canary odstranit |

Změna váhy: `kubectl -n elfall annotate ingress bankid-zt-canary nginx.ingress.kubernetes.io/canary-weight=10 --overwrite`

## Rollback (kdykoli, bez nového buildu)
1. `kubectl -n elfall annotate ingress bankid-zt-canary nginx.ingress.kubernetes.io/canary-weight=0 --overwrite` (provoz okamžitě zpět na stabilní).
2. Pokud jde o kompromitaci klíče: nový JWK do `zt-signing`, `kubectl -n elfall rollout restart deploy/bankid-zt` (staré tokeny po restartu neplatí, max. TTL 5 min).
3. Revokace (`/var/lib/elfall/zt-revocations.log` na PVC) se při rollbacku **nemaže** – odvolané klíče zůstávají odvolané.
4. Po 100 %: zpět na `ZT_ENABLE=false` ve stabilním deploymentu = návrat ke stavu před ZT.
5. Zaznamenat do `docs/security/SECURITY-LOG.md`.

Poznámka: souborové úložiště revokací je pro 1 repliku (RWO PVC). Pro více replik použít SQL backend se schématem
`backend/src/main/resources/db/migration/V1__zt_revocations.sql`.
