# Attachment audit (redacted) — 2026-10-12

Files lived under the agent attachments directory (content-addressed hashes). Findings only; **no secrets, tokens, passwords or Chrome autofill values are repeated here.**

## 1. GitHub Enterprise license CSV (`*.csv`)
- User: **ivosicek123** / Ivo Faltýn.
- Roles: **Owner** of org **AgrazZzePavok**; enterprise roles Owner + Member; license type **Enterprise**; GHE license active.
- Profile: https://github.com/ivosicek123
- **Action:** Enable GitHub 2FA before the required-by date shown in the export (**2026-11-24**). Keep the CSV offline; do not commit it.

## 2. Org roles doc (`*.md` — GitHub docs "Roles in an organization")
- Stock GitHub documentation about owner / member / custom roles on Enterprise Cloud.
- **Action:** Use as reference when assigning Fallcoin teammates; no secrets.

## 3. Copies of inventory / build plan (`*.md`)
- Snapshots of our earlier `/workspace/github-inventory.md` and `/workspace/build-plan.md`.
- **Action:** Prefer the live files in this repo's `docs/` and `/workspace/`; treat the attachments as outdated copies.

## 4. Private AgrazZzePavok Atom feed (`*.atom`) — **SENSITIVE**
- Self URL is a **private organization feed** for AgrazZzePavok scoped to ivosicek123.
- The URL embeds a **feed token**. That token is **redacted** in this report and **must not** be pasted into issues, PRs or chat.
- Feed content (public facts only): two branch-create events on **2026-09-23** for
  - `AgrazZzePavok/demo-repository`
  - `AgrazZzePavok/laughing-octo-meme-demo-repository`
  (These match the org demos the user listed earlier; still not visible to the unscoped connector.)
- **Action (urgent):** In GitHub → Settings → Feeds (or regenerate the org private feed), **revoke/regenerate** that private feed URL. Treat the old token as compromised once the file left the browser. Do not re-upload Atom feeds that contain tokens.

## 5. ORCID export ZIP (`*.zip`)
Public profile facts only (from `person.xml` + employment record):
- ORCID iD: **https://orcid.org/0009-0007-4808-8653**
- Name: Ivo Faltýn (credit name Faltyn)
- Employment: Dräxlmaier (Germany), Vilsbiburg, DE — role "Zakladatel/Vlastník", years 2025–2037 (as recorded in the export)
- **Action:** Safe to cite the ORCID iD and public name in READMEs. Do not commit the raw ZIP if it contains more than you want public.

## 6. Chrome "Password Manager Internals" HTML (`*.html` ×3)
- Page titles: **Password Manager Internals**.
- These are Chrome diagnostics dumps. **Passwords and autofill values were not extracted and must not be.**
- **Action:** Delete the HTML files from shared storage. Never paste them into chats or repos. They are useless for Fallcoin and high-risk if they contain credential material.

## 7. Cloudflare errors (`*.txt` ×2)
- Text: `Session terminated` with `cf-ray=…-FRA`.
- **Action:** Harmless; indicates a Cloudflare-fronted session was killed (likely a Grok/web preview). No secret.

## 8. Images
| File | Finding |
|---|---|
| QR PNG | Decodes to **https://orcid.org/0009-0007-4808-8653** — safe to use as a public ORCID badge. |
| Two clover PNGs | Logos / brand marks (no QR payload). Candidates for Fallcoin branding assets once licensed/owned rights are clear. |

## What we will **not** put in git
- Private feed tokens, bank account numbers, Chrome password/autofill values, RunPod API keys, owner PII beyond the public ORCID name/iD already used in the wallet README.
