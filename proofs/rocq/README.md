# Rocq – invarianty politiky přístupu ke klíčům

`KeyPolicy.v` (Rocq 9, `rocq compile KeyPolicy.v`, CI job `rocq`) je **model** pravidla,
které vynucuje backend (`ZeroTrust.kt`) a mobilní aplikace.

## Co je dokázáno
1. `auth_sound` – podpis/operaci autorizuje **jen** dvojice *passkey + klíč zařízení*, oba vázané
   na vlastníka, oba registrované a oba neodvolané.
2. `no_device_only`, `foreign_key_rejected` – samotný klíč zařízení ani cizí klíč nestačí.
3. `revoked_never` – odvolaný klíč (passkey nebo zařízení) neautorizuje.
4. `revoked_forever` – po odvolání klíč neautorizuje v **žádném** budoucím stavu, ať následuje
   jakákoli posloupnost registrací a odvolání (odvolání nelze vrátit, registrace odvolaný klíč neobnoví);
   vlastník se operacemi nemění (`run_keeps_owner`).

## Co dokázáno NENÍ
- Že Kotlin backend model přesně implementuje (kryje to jen testy `ZeroTrustTest`).
- Kryptografie (podpisy WebAuthn, DPoP JWS) – předpokládá se, že ověření podpisu je správné.
- Perzistence/synchronizace seznamu odvolaných klíčů mezi instancemi, časové útoky, ztráta zařízení.

## Framing.v – rámování podpisu a registr GPU bufferů

`rocq compile Framing.v` (CI, Rocq 9.3); lokálně ověřeno v Coq 8.20.1. Bez `Admitted`.
- `unframe_frame`, `unframe_sound`, `frame_injective`, `frame_len` – model `core/src/framing.rs`
  (`ELFALL-SIG-v1\0` ‖ BE32 délka ‖ zpráva). Kódování délky je parametr se dvěma předpoklady
  (délka 4 B, dekódování ∘ kódování = id do MAX) – pro BE u32 platí, v Rustu kryto testy.
- `no_double_free`, `foreign_pointer_rejected` – model registru `LIVE` v `core/src/gpu.rs`.
Model není extrahovaný kód: shodu s Rustem drží testy (`core/tests/`), ne důkaz.
