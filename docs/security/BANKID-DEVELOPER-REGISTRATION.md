# Bank iD Developer Portal — registration and sandbox onboarding

**Project:** ScrewWin Royal & Eleanor  
**Owner recorded in project:** Ivo Faltýn  
**Status:** onboarding guide; no Bank iD account or application has been registered by this project  
**Environment:** Sandbox only until a separate production approval

## 1. Purpose and prerequisites

The Bank iD Developer Portal account is for managing developer applications and sandbox configuration. It is not the same thing as an end-user's Bank iD identity, and registering a developer account does not verify the project owner's identity or bank-account ownership.

Official entry points:
- Developer Portal: https://developer.bankid.cz/
- Getting started: https://developer.bankid.cz/docs/sep_get_started
- FAQ: https://developer.bankid.cz/faq
- Company implementation overview: https://bankid.cz/firmy/implementace/

## 2. Registration steps for the project owner

1. Open the official Developer Portal on the owner's device.
2. Select Log in, then Register.
3. Use an email address controlled by the project owner and complete the portal's registration form.
4. Confirm the email using the message sent by Bank iD.
5. Sign in and review My Profile. Enable two-factor authentication where available; it is mandatory for contracted organization administrators.
6. Create a new application named `ScrewWin Royal & Eleanor — ELFALL Sandbox`.
7. Open the application's Sandbox configuration and complete only the settings requested by the portal.
8. Record the Sandbox client identifier and non-secret configuration values in the project's deployment configuration. Store any client secret only in a managed secret store; never commit it, paste it into chat, or include it in logs.
9. Invite additional developers only when required and assign the minimum role needed.

The Sandbox is available after account registration and application creation. The official getting-started guide says it is freely available for development and offers the same service families as production, while FAQ describes testing authentication, user data retrieval, document signing and data-change notifications.

## 3. Callback and application configuration

Before configuring redirect URIs, the engineering team must choose the actual public HTTPS callback endpoint for the deployed backend. Do not guess a callback URL and do not use localhost for a remotely hosted integration unless the Bank iD configuration explicitly supports the chosen development pattern.

The implementation must:
- use the exact registered redirect URI;
- use Authorization Code flow with PKCE S256 where supported/required by the Bank iD profile;
- generate unpredictable, single-use `state` and `nonce`;
- validate issuer, signature, audience, expiry, nonce and token binding;
- keep client secrets server-side in a secret manager;
- use separate Sandbox and Production credentials, redirect URIs and configuration;
- reject callbacks that do not match a pending, unexpired transaction bound to the initiating internal user/session.

## 4. Sandbox test plan

Use only Bank iD-provided synthetic test identities and fictitious payment account data. Never test with the owner's real bank credentials or real IBAN unless a separately approved, lawful production process is in place.

Minimum cases:
- successful synthetic authentication;
- user cancellation and denied consent;
- expired or replayed state;
- incorrect nonce, issuer, audience or signature;
- token endpoint and Profile API timeout / 5xx / rate limit;
- missing `paymentAccounts` claim;
- exact IBAN match and mismatch using synthetic data;
- invalid IBAN checksum;
- duplicate callback and concurrent callback attempts;
- ensure tokens, secrets, full IBAN and raw profile payload are absent from logs.

A missing `paymentAccounts` claim or absent account is `inconclusive`, not proof that the user does not own an account. Only an exact match against a documented, trusted claim may produce `confirmed`.

## 5. Production onboarding is a separate commercial and compliance process

The official Bank iD company implementation overview describes production onboarding as including:
1. company registration and application in the Developer Portal;
2. compliance review and contract with Bankovní identita, a.s.;
3. one-time activation fee;
4. invitation to the Bank iD organization and linking the application;
5. production configuration and credentials.

The project must not assume that a personal developer account alone grants production access. The legal entity contracting with Bank iD must be identified and its authority to provide the intended service confirmed. For ScrewWin, legal/compliance review must also assess the intended use case, regulated gambling context, data minimization and lawful basis before any production identity data is requested.

## 6. Ownership verification is not payment authorization

Bank iD authentication establishes identity according to the selected assurance level and consented claims. The separate account-ownership check is limited to a trusted `paymentAccounts` claim, if the selected service and identity provider actually supply it.

A successful ownership result must not:
- grant `platform_owner` or administrative roles;
- authorize a withdrawal, transfer, deposit or settlement;
- replace fresh authentication, transaction authorization, risk controls or audit;
- bypass ELEANOR's exclusive authority over casino ledger and settlement.

Identity Core remains responsible for the internal subject and session. ELFALL may verify and bind the payout destination. ELEANOR remains the sole casino ledger and settlement authority.

## 7. Secret handling and incident response

- Never ask the owner to send credentials, client secrets, tokens, bank passwords, identity documents or full IBAN through chat.
- If a secret is exposed, revoke/rotate it at the provider and audit its use.
- Use separate secrets per environment and least-privilege access.
- Keep secrets out of Git history, CI logs, issue trackers and screenshots.
- Record only minimal verification metadata and a keyed HMAC of the normalized payout account identifier where retention is justified.

## 8. Go/no-go checklist

- [ ] Owner has independently registered and verified the Developer Portal account.
- [ ] Sandbox application created by the owner.
- [ ] Actual callback endpoint chosen and registered.
- [ ] Sandbox credentials stored in secret manager (not repository/chat).
- [ ] Sandbox OIDC flow passes automated tests.
- [ ] Payment-account claim semantics confirmed against current Bank iD service documentation and actual sandbox responses.
- [ ] Security/privacy/legal review completed.
- [ ] Production contract, organization onboarding and production credentials approved separately.
- [ ] Separate explicit owner approval before any production enablement.

**No registration, application creation, credential issuance, live identity verification or production activation is performed by this document.**
