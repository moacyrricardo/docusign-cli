# 009 — Interactive browser login (Authorization Code Grant)

Status: **todo** (spec — decision made: we WILL add OAuth as a second, friendlier auth mode)

Adds the DocuSign **Authorization Code Grant** (interactive browser login) as a second auth mode
alongside the JWT Grant from [003](003-done-login-jwt-auth.md). The decision: JWT/keypair stays —
it is the right tool for automation/CI (consent once, headless forever) — but for a regular human
running a CLI, "open a browser, sign in, done" is the better **default**. So `login` becomes the
OAuth flow and the keypair flow moves behind a flag (§4). 003's JWT machinery is kept essentially
unchanged and becomes one of **two token sources** feeding the same caching seam.

This extends 003 and reuses 002's `Config`/`Token`/`Environment`/`ApiClientFactory`/`CliContext`
contracts; it does **not** redefine them. Cleanest to build **after the 002–008 stack lands** (it
edits foundation seams 002 owns and command shells 003 owns, so it wants those stable first).

---

## 1. Scope

In scope:

- A localhost **Authorization Code + PKCE** flow: build the authorize URL, launch the browser (with
  a printed-URL fallback for headless), run a one-shot **loopback callback HTTP server** to catch
  the redirect `code`, exchange it at `/oauth/token` for an access token **+ refresh token**.
- **Refresh-token storage and rotation** in `token.json`, and a silent refresh path that uses the
  refresh token instead of re-minting a JWT.
- A **`TokenSource` strategy** (`JwtTokenSource` | `OAuthTokenSource`) so `CachingTokenProvider`
  stays the single seam `CliContext.authenticatedApiClient()` depends on.
- The **command-shape decision** (§4): `login` = OAuth (default), `login --jwt` = keypair mode.
- New credential/config keys: `auth_mode`, optional `client_secret`, loopback `callback_port`.

Out of scope: encrypting tokens at rest; multi-account selection; device-code flow for fully
headless OAuth; any envelope behavior. See **Known Gaps** (§9).

---

## 2. The flow — Authorization Code + PKCE

### 2.1 Authorize URL

Built deterministically against the resolved `Environment.oAuthBasePath()` (002 §7 — `account-d`
demo / `account` prod), the same host the JWT consent URL uses (003 §5):

```
https://{env.oAuthBasePath()}/oauth/auth
  ?response_type=code
  &scope=signature%20extended
  &client_id={integration_key}
  &redirect_uri=http://localhost:{callback_port}/callback
  &code_challenge={S256(code_verifier)}
  &code_challenge_method=S256
  &state={random_nonce}
```

- **Scopes:** `signature` (eSignature API) **+ `extended`**. `extended` is the DocuSign scope that
  makes the authorization-code grant return a **refresh token** — without it there is no offline
  refresh and the user re-logs every hour. Note: `impersonation` (003) is JWT-only and is **not**
  requested here.
- **`state`** is a random nonce generated per login and verified on the callback (CSRF guard).
- **`code_challenge`** is the S256 hash of a per-login random `code_verifier` (PKCE; see §2.4).

### 2.2 Loopback callback server

The redirect URI is a **loopback address** — `http://localhost:{port}/callback` — per the
OAuth-for-native-apps convention (RFC 8252). On `login`:

1. Bind a minimal one-shot HTTP server (JDK `com.sun.net.httpserver.HttpServer`, no new dependency)
   on `127.0.0.1`. Port resolution: use the configured `callback_port` if set, else bind port `0`
   (OS-assigned ephemeral) and read back the actual port to build the redirect URI. A **fixed
   default `callback_port`** (proposed `8088`) is documented because DocuSign requires the exact
   redirect URI to be **pre-registered on the app** — an ephemeral port cannot be pre-registered, so
   the *registered* redirect must be `http://localhost:8088/callback` unless the user overrides the
   port and registers a matching URI. (DocuSign does allow `http://localhost` redirect URIs for this
   reason.)
2. Open the authorize URL via `Desktop.browse(...)` when supported, and **always also print it** so
   SSH/headless users can paste it into a browser elsewhere (same fallback pattern as 003's
   `LoginCommand.tryOpenBrowser`).
3. Block (with a timeout, proposed 120s) for the browser to redirect to `/callback?code=...&state=...`.
4. Validate `state` matches; on success serve a tiny "You may close this tab" HTML page and hand the
   `code` back to the command; on `error=access_denied` (user clicked Deny) or timeout, fail cleanly.
5. **Always** stop the server in a `finally` (release the port).

### 2.3 Code exchange → tokens

POST `https://{env.oAuthBasePath()}/oauth/token` (form-encoded):

```
grant_type=authorization_code
code={code}
redirect_uri=http://localhost:{port}/callback
client_id={integration_key}
code_verifier={code_verifier}          # PKCE proof
[client_secret={client_secret}]        # only if auth_mode resolves to a confidential app — §2.4
```

Response yields `access_token`, `refresh_token`, `expires_in` (typically 8h for auth-code, vs JWT's
1h), `token_type`. Then call `/oauth/userinfo` (reuse the existing logic — see §3 on
`fetchAccount`) to obtain `account_id` + `base_uri`, exactly as JWT login does (003 §4.4), and
persist them to credentials.

### 2.4 PKCE vs. client secret — **decision: PKCE-first, secret optional**

A CLI is a **public client**: it ships as a fat jar the user can decompile, so it **cannot safely
embed a client secret**. DocuSign's Authorization Code Grant supports **PKCE** (`S256`), which is
exactly the mechanism designed for this case — it replaces the secret with a per-request
verifier/challenge pair, so no long-lived secret needs to live on disk or in the binary.

**Decision:**

- **Default: PKCE, no secret.** Generate a 43–128 char random `code_verifier`, send its
  `S256` challenge on `/oauth/auth`, and the verifier on `/oauth/token`. This is the path a user
  gets with a self-registered app configured (in the DocuSign console) as a **public/native** app.
- **Escape hatch: user-supplied `client_secret`.** If the user registered a *confidential* app (the
  current DocuSign default when you create an integration key + secret), they may put
  `client_secret` in `credentials`; when present we send it on the token exchange **in addition to**
  PKCE. We never ship a secret of our own.

Trade-off: PKCE-only requires the user to mark their app as enabling PKCE / public-app login in the
DocuSign console; if their app only has a secret, they fall back to the `client_secret` path. We
optimize for the secret-less path because it is the only one that is actually safe for a distributed
CLI, and we keep the secret path as a documented escape hatch rather than the default. **Final
confirmation that DocuSign honors PKCE without a secret on this grant is an open verification item
(§9)** — to be settled by a live spike before build, mirroring how 003 spiked the SDK signatures.

---

## 3. Impact on existing code (per class)

The core insight: today `CachingTokenProvider` knows *how* to get a token (call `JwtTokenMinter`).
We split that "how" into a **`TokenSource` strategy** so the provider only knows *that* it can ask a
source. The cache + the `TokenProvider.accessToken()` seam — which `CliContext.authenticatedApiClient()`
depends on — **do not change shape**.

### 3.1 New: `TokenSource` (auth package)

```java
/** Mints/refreshes a Token without user interaction on the happy path. */
interface TokenSource {
    /** A fresh token. Throws ConsentException only when interactive (re)login is required. */
    Token obtain() throws ConsentException;
}
```

Two implementations:

- **`JwtTokenSource`** — thin wrapper over the existing `JwtTokenMinter.mint()` (003). `JwtTokenMinter`
  itself stays **unchanged** (still the keypair mode). `JwtTokenSource.obtain()` == `minter.mint()`.
- **`OAuthTokenSource`** — refreshes using the stored refresh token (see §3.2). It does **not** run
  the browser flow; the interactive code-exchange lives only in the new login command (§3.4). When
  there is no refresh token, or DocuSign rejects it (rotated/expired), `obtain()` throws
  `ConsentException` so the caching layer turns it into `NOAUTH` ("run login first") on the silent
  path — identical to how JWT's `consent_required` is handled today.

### 3.2 `OAuthTokenSource.obtain()` — refresh + rotation

```
POST /oauth/token
  grant_type=refresh_token
  refresh_token={stored}
  client_id={integration_key}
  [client_secret={...}]    # if present
```

DocuSign **rotates** refresh tokens: each refresh returns a *new* `refresh_token` that must replace
the stored one, and a refresh token **lapses after ~30 days of non-use**. Consequences:

- On a successful refresh, persist the returned new `refresh_token` (rotation) — this is why the
  refresh path must be able to **write** `token.json`, not just read it.
- A `400 invalid_grant` (lapsed/rotated-away refresh token) → `ConsentException` → on the silent path
  becomes `NOAUTH`; the user re-runs `login`. This is the documented "~30-day idle ⇒ occasional
  re-login" behavior, and it is the price of OAuth that 001 §4 flagged. JWT mode does not have it.

Because rotation writes back inside what is conceptually a "refresh", the cache write currently done
by `CachingTokenProvider.mintAndCache()` already covers it — but the source must surface the new
refresh token (see §3.3 on the richer `Token`), and the provider writes the whole `Token`.

### 3.3 `CachingTokenProvider` — minimal change

Today it holds a `JwtTokenMinter` and calls `mint()`. Change it to hold a **`TokenSource`** and call
`obtain()`. Everything else (read cache → filter on `isExpired` → else mint-and-cache; wrap
`ConsentException` as `AuthException(NOAUTH)`) is **unchanged**. It writes the full `Token` it gets
back (now carrying the rotated refresh token in OAuth mode).

```java
public CachingTokenProvider(Config config, TokenSource source) { ... }   // was JwtTokenMinter
// accessToken(): identical body, source.obtain() instead of minter.mint()
```

### 3.4 `LoginCommand` + a new `--jwt` branch (the §4 shape)

`login` becomes the **OAuth** flow by default and gains a `--jwt` (alias `--key`) flag selecting the
keypair flow. Concretely:

- **`login` (OAuth, default):** validate OAuth config (§5) → generate PKCE + state → start loopback
  server → open/print authorize URL → await `code` → exchange (§2.3) → userinfo → persist
  `account_id`/`base_uri` + write `token.json` (with refresh token, `auth_mode=oauth`). Reuses the
  existing success-emit shape (`message`/`record`/`object`). New code lives in an
  **`OAuthLoginFlow`** helper (browser + server + exchange) so `LoginCommand` stays a thin shell with
  the same test-seam style as today's `useMinter(...)` (e.g. a package-private `useFlow(...)`).
- **`login --jwt` (keypair):** the **current** `LoginCommand.call()` body verbatim — validate
  `integration_key`/`user_id`/key file, `JwtTokenMinter.mint()`, consent handling (003 §5), userinfo,
  persist, write token with `auth_mode=jwt`. No behavior change; just guarded by the flag.

`auth status` (`AuthStatusCommand`): add the resolved/stored **auth mode** and, for OAuth, whether a
refresh token is present and the longer expiry. Still **read-only, no network**.

### 3.5 `Config` / `Credentials` / `Token` — schema additions

**`Credentials`** (002 §4.1) gains three optional keys (snake_case in the `credentials` file; flat
accessors on `Config` + builder fields on `Credentials`, mirroring `redirectUri()`):

| Key | Meaning | Default |
|---|---|---|
| `auth_mode` | `oauth` or `jwt` — which flow login used / refresh should use | `oauth` (the new default) |
| `client_secret` | optional confidential-app secret (§2.4 escape hatch) | unset (PKCE-only) |
| `callback_port` | fixed loopback port for the registered redirect | `8088` |

`redirect_uri` (existing) is **reused** but now also documents the loopback form
`http://localhost:8088/callback` for OAuth; for JWT it keeps its `https://www.docusign.com` meaning.
`integration_key` is shared by both modes; `user_id`/`private_key_path` are JWT-only.

**`Token`** (002 §4.2) grows from `{access_token, token_type, expires_at}` to also carry:

```json
{
  "access_token": "...",
  "token_type": "Bearer",
  "expires_at": "2026-06-10T...Z",
  "refresh_token": "...",        // null in JWT mode (JWT re-mints, no refresh token)
  "auth_mode": "oauth"           // which source minted it — guards which refresh path to use
}
```

Both new fields are **nullable/optional** so existing `token.json` files (003 shape) still
deserialize (Jackson ignores absent fields → null) — a JWT user's cached token keeps working and is
treated as `auth_mode=jwt`, `refresh_token=null`. `isExpired(...)` and the 60s skew are unchanged.

### 3.6 `ApiClientFactory` / `Environment`

- `Environment` is **reused as-is** — `oAuthBasePath()` already gives the right host for both the
  authorize URL and the `/oauth/token` exchange; no new enum, no new field.
- The code exchange and refresh are plain form-POSTs to `/oauth/token`. The DocuSign SDK's
  `ApiClient` exposes `generateAccessToken(clientId, clientSecret, code)` and the OAuth base path is
  already set by `oauthClient()`. **Decision:** add a small **`oauthTokenClient()` / dedicated
  exchange helper** rather than overloading `authenticated(...)`. Either reuse the SDK's
  `generateAccessToken` (verify it threads PKCE `code_verifier` and an empty secret — open item §9)
  or do a direct `HttpClient` POST in `OAuthLoginFlow`/`OAuthTokenSource` if the SDK can't express
  PKCE-without-secret. `authenticated(String accessToken)` (the REST seam) is **untouched** — once we
  have an access token, downstream is identical for both modes.

### 3.7 `GlobalOptions` / `RootCommand` / `CliContext`

- **`GlobalOptions`:** unchanged. The `--jwt`/`--key` selector is a `login`-local `@Option`, not a
  global flag (it only means something for `login`).
- **`RootCommand.buildContext()`:** today it builds `JwtTokenMinter` then `CachingTokenProvider`.
  Change it to **pick the `TokenSource` by resolved auth mode**: read `auth_mode` from credentials
  (default `oauth`), build either `OAuthTokenSource` or `JwtTokenSource`, and hand that to
  `CachingTokenProvider`. The lazy wiring (auth/network only on `accessToken()`) is preserved, so
  `scan`/`--help` still never touch DocuSign.
- **`CliContext`:** signature unchanged — it still exposes `tokenProvider()` /
  `authenticatedApiClient()`. The strategy swap is entirely inside the composition root. This is the
  load-bearing constraint: **do not break the `authenticatedApiClient()` contract** 005/006/007
  depend on.

The auth mode is resolved at one place (`RootCommand.buildContext`) from `credentials.auth_mode`,
written by `login`/`login --jwt`. A user who runs `login --jwt` against a config that previously did
OAuth simply rewrites `auth_mode=jwt`; the next command picks the JWT source.

---

## 4. UX decision — command shape

| | A: one `login` + `--oauth`/`--key` | B: `login` (OAuth) + `login-key` (JWT) | **Hybrid (chosen):** `login` = OAuth, `login --jwt` opt-in |
|---|---|---|---|
| Discoverability | Both modes hidden behind flags; neither is "the" default | Two top-level verbs; both visible in `--help` | Friendly path is the bare verb; keypair is one documented flag |
| Default behavior | Ambiguous — must require one flag or pick a default anyway | `login` already means the friendly thing | `login` already means the friendly thing |
| Help / validation | One command, but help must explain two disjoint input sets and reject the wrong combo per mode | Cleanest: each command validates only its own inputs | One command; validation branches on `--jwt`, but inputs are clearly partitioned |
| Inputs differ a lot? | Yes (PKCE/secret/port vs. key/user_id) → crowded single help | Cleanly separated | Branches, but `--jwt` is a clear pivot in help text |
| Automation ergonomics | `login --oauth`/`login --key` both explicit (good for scripts) | `login-key` explicit and stable | `login --jwt` explicit; CI scripts pin the flag |
| Migration from 003 | Renames default behavior | Adds a verb; 003's `login` body moves to `login-key` | 003's `login` body stays under one flag; least churn |

**Decision: the hybrid — `login` runs OAuth by default; `login --jwt` (alias `--key`) runs the
keypair flow.** Rationale: it makes the friendly, secret-less browser flow the zero-flag default a
regular DocuSign user expects, keeps automation explicit and stable via a single pinned flag, and is
the smallest change to 003 (its existing `call()` body becomes the `--jwt` branch rather than moving
to a new command). It beats Option A because A forces a flag even for the common case (no real
default) and crowds one help screen with two disjoint input sets; it beats Option B's two verbs
because the two flows share most of the shell (config validate → userinfo → persist → write token)
and a `--jwt` pivot reads more clearly than a parallel `login-key` verb. `--jwt` and `--key` are
accepted as aliases for the same option (the JWT flow *is* the keypair flow).

---

## 5. `login` (OAuth) config validation & errors

Validate before starting the browser dance:

- `integration_key` present (shared with JWT) → else `CONFIG`.
- `callback_port` parseable (default `8088`); `redirect_uri`, if set, must be the loopback form for
  OAuth → else `CONFIG` with a message pointing at the registered redirect.
- Browser unavailable (headless) is **not** an error: print the URL and keep waiting on the loopback
  server (the user opens it elsewhere on the same host, or tunnels the port).

Error mapping onto 002's `ExitCode` (reusing 003's matrix where it overlaps):

| Condition | Exit |
|---|---|
| Missing `integration_key` / bad `callback_port` / bad redirect | `CONFIG` |
| User clicks "Deny" (`error=access_denied`) | `CONSENT` |
| Callback timeout (no redirect within the window) | `CONSENT` (human action didn't complete) |
| `state` mismatch on callback | `SOFTWARE` (possible CSRF / stale tab) |
| `/oauth/token` network failure | `NETWORK` |
| `/oauth/token` rejects code/verifier/secret | `CONFIG` |
| Silent refresh: `invalid_grant` (lapsed/rotated) | `NOAUTH` ("run `docusign-cli login`") |
| Loopback port already in use | `CONFIG` (suggest setting `callback_port`) |

`login` (either mode) remains the **only** command that may open a browser or prompt; the refresh
path stays fully silent (§3.3), preserving 003's headless guarantee for 005/006/007.

---

## 6. Persistence summary

`token.json` (002-owned write path; §3.5 schema) after an OAuth login carries `access_token`,
`refresh_token`, `expires_at`, `token_type`, `auth_mode=oauth`. `Config.writeToken`/`readToken`/
`clearToken` are reused unchanged except for the two new optional fields on `Token`. File mode stays
`0600`. `credentials` gains `auth_mode` (+ optional `client_secret`, `callback_port`).

---

## 7. Testing notes

- **`OAuthTokenSource`** — (a) valid stored refresh token → POST issued, new token returned,
  **rotated** refresh token surfaced for caching; (b) `invalid_grant` → `ConsentException` →
  (through the provider) `NOAUTH`; (c) network failure → `NETWORK`; (d) no refresh token present →
  `ConsentException`. Stub the token endpoint (no live calls), per 002's "SDK behind a seam" rule.
- **`CachingTokenProvider`** — re-run 003's four cases against a mock **`TokenSource`** (interface
  swap), proving the cache/seam behavior is source-agnostic and the rotated token is written.
- **`OAuthLoginFlow`** — PKCE verifier/challenge correctness (`S256`), `state` round-trip and
  mismatch rejection, callback parsing (`code` vs `error=access_denied`), timeout, and ephemeral vs
  fixed port selection. The browser-open and the HTTP exchange are isolated as seams so the loopback
  server can be driven by a local test client with no DocuSign.
- **`LoginCommand`** — `login` (default) routes to the OAuth flow; `login --jwt`/`--key` routes to
  the 003 path (existing tests still pass); each persists the right `auth_mode`.
- **`Config`/`Token`** — round-trip the new fields; a 003-shape `token.json` (no `refresh_token`/
  `auth_mode`) still deserializes and is treated as JWT mode.
- **`RootCommand` wiring** — `auth_mode=oauth` builds `OAuthTokenSource`, `jwt` builds
  `JwtTokenSource`; `--help`/`scan` still trigger no auth.

---

## 8. User-facing setup (documentation; extends 003 §9 and the 008 README)

For OAuth mode:

1. Create a DocuSign app (integration key) configured for **Authorization Code Grant**; enable
   **PKCE / public-app login** (no secret) if available, else note the `client_secret`.
2. Register redirect URI **`http://localhost:8088/callback`** (or a custom `callback_port` you then
   set in `credentials`). DocuSign permits `http://localhost` redirects for native apps.
3. Put `integration_key` in `~/.docusign-cli/credentials` (and `client_secret` only for a
   confidential app). Leave `auth_mode` unset (defaults to `oauth`) or set it explicitly.
4. Run `docusign-cli login`, sign in in the browser, click Allow. Subsequent commands refresh
   silently until the refresh token lapses (~30 days idle), then `login` again.

JWT/keypair mode is unchanged — see 003 §9; invoke it with `docusign-cli login --jwt`.

---

## 9. Known Gaps / out of scope (open items)

- **PKCE-without-secret confirmation.** Whether DocuSign's auth-code grant accepts a PKCE
  `code_verifier` with **no** `client_secret` for a public/native app must be confirmed by a live
  spike before build (mirrors 003's SDK-signature spike), and whether the SDK's `generateAccessToken`
  can express it or we POST directly with the JDK `HttpClient` (§3.6). The §2.4 decision (PKCE-first,
  secret as escape hatch) stands either way; this only affects the exchange plumbing.
- **Fully headless / no-browser environments.** We fall back to printing the URL + an open loopback
  port, which works over SSH port-forwarding but is not seamless. A **device-code flow** (no
  loopback, poll for completion) would be the proper answer and is deferred to a future spec.
- **Tokens at rest are not encrypted.** `token.json` now also holds a long-lived refresh token at
  `0600`; OS keychain / encryption-at-rest is out of scope (a future hardening spec).
- **Multi-account.** Like 003, we take the default account from `/oauth/userinfo`; no account picker.
- **Refresh-token lifetime tuning / proactive refresh.** We refresh on demand at the cache miss; a
  background/near-expiry proactive refresh and configurable token lifetime are not in v1.

---

## 10. Build placement

Extends [003](003-done-login-jwt-auth.md) (the keypair mode) and edits 002-owned seams
(`Config`/`Token`/`CliContext` wiring). Cleanest **after the 002–008 stack lands** so those
contracts are stable. No new runtime dependency (loopback server via JDK `com.sun.net.httpserver`,
exchange via the SDK or JDK `HttpClient`). Follows the project per-spec flow: branch
`spec-009-oauth-login`, rename to `doing`, build + harden, open a PR; do not merge without review.
