# Off-ramp providers: crypto → card payout (CZK / EUR / USD), usable from CZ/EU

Researched 2026-10-13 from public docs. Fees/coverage change often; always confirm at runtime via each provider's
API (currencies / payment-methods endpoints) and in the partner contract. ELFALL never handles card data, KYC
documents or fiat: the user completes KYC and enters card details **inside the provider's hosted widget**.

| Provider | Card payout | Payout fiat (relevant) | KYC | API / SDK | Fees (public) | EU licensing | Fit for ELFALL |
|---|---|---|---|---|---|---|---|
| **MoonPay** | Yes – Visa Direct cards (fast ≤ 30 min, standard ≤ 2 business days); also SEPA, PayPal | `quoteCurrencyCode` includes **CZK**, EUR, USD | Full KYC in MoonPay | Web SDK / widget `flow: "sell"`, `paymentMethod=credit_debit_card`, sandbox | Not fixed publicly; shown in widget quote | MoonPay Europe B.V. – MiCA CASP (AFM, NL, 30 Dec 2024), passported EEA | **High** – only one with documented CZK card payout |
| **Coinbase Onramp/Offramp (CDP)** | Yes – `paymentMethod: CARD`, cards in 90+ countries incl. EU | EUR, USD (`cashoutCurrency`) | Coinbase account + KYC | REST: `/onramp/v1/sell/config`, `/sell/options`, `/sell/quote`; one-click sell URL | Quote-based | Coinbase Luxembourg S.A. – MiCA CASP (CSSF, LU, 20 Jun 2025) | Medium – needs user Coinbase account |

**Active providers (owner decision 2026-10-13): MoonPay and Coinbase only.**

### Excluded providers
Ramp, Banxa, Transak and Mercuryo were evaluated and excluded by the owner's decision. Ramp (Ramp Ireland, CBI 02/12/2025)
and Banxa (EU Internet Ventures B.V., AFM 08/10/2025) are MiCA-licensed but excluded; Transak and Mercuryo are not listed
in the ESMA MiCA CASP register. None of them is present in code, tests or UI.

## Recommendation
1. **MoonPay** first (CZK card payout, MiCA CASP – MoonPay Europe B.V., AFM 30/12/2024), **Coinbase** second (Coinbase Luxembourg S.A., CSSF 20/06/2025; needs user Coinbase account).
2. Integrate via hosted widget / redirect only (`shared/.../offramp/` already models providers as stubs).
3. Contract terms to check: who is the "customer" (provider, not ELFALL), Travel Rule handling, refund address,
   partner fee disclosure (MiCA conflicts/fees disclosures), data processing agreement (GDPR Art. 28).

## Sources
- MoonPay sell params (CZK, `credit_debit_card`): https://dev.moonpay.com/widget/off-ramp/customization/parameters
- MoonPay payout timing / Visa Direct: https://support.moonpay.com/en/articles/522834-how-do-i-confirm-locate-or-troubleshoot-my-sell-order-payout-on-moonpay
- MoonPay Europe MiCA CASP: https://paymentproviders.io/registry/mica/254900KXWAZ3B1340G26
- ESMA interim MiCA register (verify any provider): https://www.esma.europa.eu/esmas-activities/digital-finance-and-innovation/markets-crypto-assets-regulation-mica
