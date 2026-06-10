# 001 — DocuSign CLI: architecture, auth, and anchor scanning

Status: **todo** (concern — framing + decisions; now decomposed into the specs below)

A Java command-line tool for driving DocuSign eSignature workflows: persistent login,
listing/filtering envelopes, reading envelope status, and sending PDFs with a smart
**anchor-scanning** workflow that detects hidden DocuSign anchor strings in a PDF and binds
them to signing tabs.

> **Decomposed into specs (v1):** this concern remains the shared design context; the
> prescriptive, buildable units live in:
> - [002 — Foundation / scaffold](002-todo-foundation-scaffold.md) *(no deps)*
> - [003 — `login` / JWT persistent auth](003-todo-login-jwt-auth.md) *(→ 002)*
> - [004 — Anchor detection engine + `scan`](004-todo-anchor-detection-scan.md) *(→ 002)*
> - [005 — `send` PDF with anchors](005-todo-send-envelope-anchors.md) *(→ 002, 003, 004)*
> - [006 — `envelopes list`](006-todo-envelopes-list.md) *(→ 002, 003)*
> - [007 — `envelope status`](007-todo-envelope-status.md) *(→ 002, 003)*
>
> Suggested build order: **002 → {003, 004 in parallel} → 005**, with 006/007 any time after 003.

---

## 1. Reference tool analysis — `Robben-Media/docusign-cli`

The prior art we were pointed at ([github.com/Robben-Media/docusign-cli](https://github.com/Robben-Media/docusign-cli))
is written in **Go**. Conceptually it covers:

- **Auth:** OAuth 2.0. Integration key + secret key via env vars; browser-based login; access
  tokens stored locally and auto-refreshed. (This is the Authorization Code Grant flow.)
- **Envelopes:** create/send (including draft), list/get with filtering by status and date,
  void with reason, audit trail.
- **Documents:** list documents in an envelope, download signed PDFs.
- **Recipients & templates:** view recipients, search/retrieve templates.
- **Embedded signing:** generate signing URLs for external integration.
- Output formats: JSON / plain text / colored. Confirmation-prompt toggles for automation.

**What it does NOT do** (our differentiator): scanning a PDF for hidden anchor markers
(tiny or white-on-white text) and turning them into placed signing tabs. That is the novel
part of this project.

---

## 2. Goals (this project)

1. **Login (persistent)** — authenticate once; subsequent invocations are headless.
2. **List envelopes** — filter by document name, date, signed/status, and other fields.
3. **Get envelope status** — status of a single envelope by ID.
4. **Send a PDF** with **anchor scanning**:
   - Detect likely anchor strings: text that is **very small font** or **white-on-white**
     (visually hidden markers an author embeds to position signature fields).
   - Two usage modes (see §5): interactive Q&A, and scan-then-parametrize.

---

## 3. Decisions made

- **Stack:** Picocli (CLI framework) + Maven (build) + DocuSign **eSignature Java SDK**.
- **PDF inspection:** Apache **PDFBox** (per-glyph font size and color are accessible via a
  custom `PDFTextStripper`).
- **Auth: JWT Grant** (see §4). Easiest to *live with*: consent once, then headless forever;
  no localhost callback server; no 30-day refresh-token expiry trap. One-time cost is
  uploading an RSA public key in the DocuSign admin console.
- **Credentials on disk:** `~/.docusign-cli/credentials` (config) + cached access token.
- **Anchor parameter syntax:** `anchorString=type:recipient`, where `type:` is optional and
  defaults to `signature`. Examples:
  - `_sig_363_=moacyr.ricardo@gmail.com` → signature tab for that recipient
  - `_sig_i_363_=initials:moacyr.ricardo@gmail.com` → initials tab

---

## 4. Auth design (JWT Grant)

### Why JWT over Authorization Code Grant

| | JWT Grant | Authorization Code Grant |
|---|---|---|
| Persistence | Login once, headless forever | Refresh token expires after 30 days unused → re-consent |
| Per-run | No browser after setup | Needs localhost callback listener; refresh dance |
| One-time setup | Higher: RSA keypair + upload public key + consent once | Lower: key + secret + redirect URI |
| Secret | RSA private key (no client secret) | Client secret |

### Files under `~/.docusign-cli/`

```
~/.docusign-cli/
  credentials        # integration key, user GUID (impersonated user), account ID,
                     # base URI (demo vs prod), private-key path
  private.key        # RSA private key (or referenced from elsewhere)
  token.json         # cached access token + expiry (re-minted on demand when expired)
```

### Flow

- `login` — validate config, perform the one-time **consent** step (print/open the consent
  URL if not yet granted), then mint and cache an access token.
- Every command ensures a valid token: if `token.json` is missing/expired, silently mint a
  new one via JWT assertion (RSA-signed) — no user interaction.
- Demo vs production base URI configurable (`account-d.docusign.com` vs `account.docusign.com`).

### Open setup question

JWT requires the user's **API user GUID** and **account ID**. `login` can fetch these via
`/oauth/userinfo` after first token, so the user only needs to supply the integration key +
RSA key initially.

---

## 5. Anchor scanning design (the novel part)

### 5.1 Detection heuristics

Walk the PDF with PDFBox, capturing each text run's string, font size, and fill color.
Flag a run as a **candidate anchor** when either:

- **Tiny text:** effective font size below a threshold (default ~4pt; configurable).
- **White-on-white:** glyph fill color is white (or near-white) AND the region behind it is
  white/empty. v1 simplification: treat near-white fill text as a candidate regardless of
  background (most hidden anchors are white text on a white page); refine background
  detection later if false positives appear.

Each candidate carries: the matched string, page number, position (x/y), font size, color,
and a guessed **type** from the string pattern (see 5.3).

### 5.2 Two usage modes

**Mode A — Scan-then-parametrize (primary, scriptable):**

```
docusign-cli scan invoice.pdf
# prints detected candidates:
#   page 1  _sig_363_     (white text, 1pt)   guessed: signature
#   page 1  _sig_i_363_   (white text, 1pt)   guessed: initials
#   page 2  _date_363_    (white text, 1pt)   guessed: date_signed

docusign-cli send invoice.pdf \
  --subject "Please sign" \
  --recipient "Moacyr=moacyr.ricardo@gmail.com" \
  _sig_363_=moacyr.ricardo@gmail.com \
  _sig_i_363_=initials:moacyr.ricardo@gmail.com
```

**Mode B — Interactive:**

```
docusign-cli send invoice.pdf --interactive
# For each detected candidate the tool asks:
#   Found "_sig_363_" (white, 1pt) on page 1. Is this an anchor? [Y/n]
#   Type? [signature]/initials/date/text/...
#   Recipient email?
# Then builds the envelope from the answers.
```

### 5.3 Anchor string → tab type

- Param grammar: `<anchorString>=<type>:<recipientEmailOrKey>`; `<type>:` optional, default
  `signature`.
- Supported types map to DocuSign anchor tabs: `signature` (`signHere`), `initials`
  (`initialHere`), `date` (`dateSigned`), `text`, `fullname`, `company`, `title`,
  `checkbox`, ... (start with the first four; extend as needed).
- The anchor string becomes the tab's `anchorString`; offsets/units default sensibly and are
  overridable later if needed.
- **Type guessing** (for display + interactive defaults) from common conventions, e.g.
  `_sig_i_` → initials, `_sig_` → signature, `_date_` → date. Heuristic only; user confirms.

### 5.4 Recipients

`--recipient "Name=email"` declares recipients. Anchor params reference a recipient by email or
by the declared key. **v1: signers only** — one or more named signers, **no signing order**, no
CC recipients, no embedded signing. (CC = recipients who get an emailed copy of the *completed*
envelope but never sign; deferred to post-v1 as `--cc "Name=email"`.)

---

## 6. Command surface (sketch)

```
docusign-cli login                 # JWT consent + mint/cache token
docusign-cli auth status           # show current auth/account
docusign-cli envelopes list        # filters: --doc-name --from --to --status (signed/sent/...)
docusign-cli envelope status <id>  # single envelope status
docusign-cli scan <pdf>            # detect + print anchor candidates
docusign-cli send <pdf> ...        # send with anchor params (Mode A) or --interactive (Mode B)
```

Global flags: `--json` / `--output`, `--yes` (skip confirmations), `--demo`/`--prod`.

---

## 7. Decisions on open questions

1. **v1 scope:** the **core four only** — `login`, `envelopes list`, `envelope status`,
   `send` (with anchor scanning). Templates / void / download / audit / embedded signing are
   **post-v1**.
2. **Recipients:** **signers only, no signing order, no CC** in v1 (see §5.4).
3. **Anchor placement:** **hardcode sensible defaults** in v1 — no `--anchor-x/-y/-units`
   flags yet.
4. **White-on-white detection:** v1 ships the "near-white fill text" simplification (§5.1);
   true background-color analysis deferred unless false positives show up.
5. **Output:** human-readable table by default, `--json` for machine use.

---

## 8. Cross-spec contract decisions (reconciliation)

After 002–007 were drafted in parallel they were reconciled against **002 as the single source of
truth**. The decisions:

1. **Exit codes:** one **granular** `ExitCode` enum owned by 002 §6.1
   (`OK/USAGE/CONFIG/NOAUTH/CONSENT/NOTFOUND/API/NETWORK/INPUT/SOFTWARE`); all specs map onto it,
   none invent numbers.
2. **PDFBox 3.x** (`Loader.loadPDF`), pinned in 002; the effective-font-size and fill-color
   extraction in 004 §2.2–§2.3 carry a **spike-before-build** flag.
3. **Feature packages:** `auth`, `anchor`, `send`, `envelope` for command/feature classes;
   `cli`/`config`/`output`/`docusign` for foundation (002 §2).
4. **`envelopes list` filters:** two independent, combinable (AND) client-side filters —
   `--doc-name` (deep, per-envelope `listDocuments`, cost-warned) and `--subject` (cheap,
   subject-only) (006 §3.3–§3.5).

Other reconciliations folded into the specs: 002 owns the on-disk `Token` + persistence (003
consumes it, no `TokenCache`); snake_case credential keys + flat `Config` accessors; a single
`Environment` enum (no `OAuthHost`); an instance `ApiClientFactory` + `CliContext`-wired
`TokenProvider`/`authenticatedApiClient()` composition root; `redirect_uri` as a config key; and
an explicit dual-representation output contract (commands emit both `object(...)` and
`table()/record()`).
