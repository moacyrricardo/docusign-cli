# 003 — Login & persistent JWT authentication

Status: **todo** (spec — prescriptive decision; ready to build)

Implements the JWT Grant auth flow framed in [001 §4](001-todo-cli-design.md). Provides the
`login` and `auth status` commands, the JWT token-minting mechanics, the on-disk token cache,
and the **`TokenProvider` seam** that every API command (envelopes list [006], envelope status
[007], send [005]) calls to obtain a valid access token without user interaction.

Depends on **spec 002** (foundation) for the `Config` abstraction (reads/writes
`~/.docusign-cli/credentials`), the `ApiClientFactory` (builds a configured
`com.docusign.esign.client.ApiClient`), the root command + global `--demo`/`--prod` option, and
the shared exit-code constants. This spec does **not** redefine those; it consumes them.

---

## 1. Scope

In scope:

- `login` command: validate config → ensure consent → mint + cache an access token → fetch and
  persist `accountId` + `baseUri` via `/oauth/userinfo`.
- `auth status` command: report account, base URI, and cached-token validity/expiry.
- JWT mechanics via the DocuSign SDK (`ApiClient.requestJWTUserToken`).
- `/oauth/userinfo` lookup so the user supplies only the integration key + RSA key initially.
- Token caching in `token.json` and a `TokenProvider` that auto-mints silently on the refresh path.
- Demo vs production OAuth base-path selection tied to the root `--demo`/`--prod` option.

Out of scope: Authorization Code Grant, anything envelope-related, the initial keypair/admin
console setup (documented as user prerequisites only, see §9).

---

## 2. Prerequisites & config (from 002)

`~/.docusign-cli/credentials` (Java `.properties`, managed by 002's `Config`) carries:

Keys are **snake_case** (002 §4.1 is authoritative); access them via 002's flat `Config`
accessors (`config.integrationKey()`, `config.userId()`, …).

| Key (`credentials`) | Supplied by | When |
|---|---|---|
| `integration_key` | user | setup |
| `user_id` | user | setup — the impersonated user's API GUID |
| `private_key_path` | user (optional) | defaults to `~/.docusign-cli/private.key` |
| `redirect_uri` | user (optional) | defaults to `https://www.docusign.com`; consent URL only (§5) |
| `account_id` | **`login`** | written automatically from `/oauth/userinfo` |
| `base_uri` | **`login`** | written automatically (e.g. `https://demo.docusign.net`) |

`~/.docusign-cli/private.key` — PEM-encoded RSA private key (PKCS#8 or PKCS#1) whose public half
was uploaded to the DocuSign app. `~/.docusign-cli/token.json` — see §6.

The OAuth host (`account-d.docusign.com` demo vs `account.docusign.com` prod) is **not** stored
in credentials; it is derived per-invocation from the root `--demo`/`--prod` flag (§7).

---

## 3. Package layout

All under base package `io.github.moacyrricardo.docusign`:

```
auth/
  LoginCommand.java          # @Command(name = "login")
  AuthStatusCommand.java     # @Command(name = "status") under an `auth` parent group
  AuthCommand.java           # @Command(name = "auth", subcommands = AuthStatusCommand.class)
  CachingTokenProvider.java  # implements 002's TokenProvider interface (the seam)
  JwtTokenMinter.java        # wraps ApiClient.requestJWTUserToken
  PrivateKeyLoader.java      # PEM → byte[] for the SDK
  ConsentException.java      # carries the consent URL
  AuthException.java         # extends CliException (002) → ExitCode.NOAUTH (or CONSENT in login)
```

**Token persistence is owned by 002**, not redefined here: this spec reads/writes the cached
token through `Config.readToken()` / `Config.writeToken(Token)` / `Config.clearToken()` and the
`config.Token` type (002 §4.2). There is no separate `TokenCache`/`CachedToken`. The
`TokenProvider` **interface** is defined in 002 (§7.1); this spec supplies `CachingTokenProvider`.

`login` is registered as a subcommand of the root command (002); `auth status` is registered as
`auth` → `status`. Command surface matches [001 §6](001-todo-cli-design.md): `docusign-cli login`
and `docusign-cli auth status`.

---

## 4. JWT mechanics

### 4.1 OAuth host & scopes

```java
// OAuth host comes from 002's Environment.oAuthBasePath() — no host constants here.
static final List<String> SCOPES = List.of("signature", "impersonation");
static final long TOKEN_LIFETIME_SECONDS = 3600; // 1 hour — SDK max for JWT user tokens
static final long SKEW_SECONDS = 60;             // freshness margin, applied at check time
```

`signature` grants the eSignature API; `impersonation` is required for JWT Grant so the
integration may act as `user_id`. The demo/prod host is selected by the resolved `Environment`
(002 §7); this spec does **not** define its own host enum.

> **SDK stack — verified against `docusign-esign-java:5.1.0`.** The SDK's HTTP client is
> **Jersey / JAX-RS** (not OkHttp/Gson), OAuth uses **Apache Oltu**, and JWT key handling uses
> **BouncyCastle** (`PemReader` + `KeyFactory`/`PKCS8EncodedKeySpec`). The SDK declares all of these
> as `optional`, so 002's `pom.xml` must list them explicitly (`jakarta.ws.rs-api`, Jersey client +
> media + `jersey-hk2`, Oltu, jose4j, BouncyCastle) — confirmed present after the 002 build. This
> spec assumes that stack.

### 4.2 Private-key loading — `PrivateKeyLoader`

The 5.x signature (verified against `docusign-esign-java:5.1.0`) is
`ApiClient.requestJWTUserToken(String clientId, String userId, List<String> scopes, byte[] privateKeyBytes, long expiresInSeconds)`
— **5 args, no base-path parameter**. The OAuth base path is set on the `ApiClient` *beforehand*
via `setOAuthBasePath(...)` (done by 002's `ApiClientFactory.oauthClient()`), not passed to this
call. It takes the **raw PEM bytes** including the `-----BEGIN ... PRIVATE KEY-----` armor; the SDK
parses them internally with BouncyCastle.

> ⚠ The 3.x signature took the base path as an argument and scopes last; **5.x dropped the
> base-path arg and moved `scopes` to position 3**. An earlier draft of this spec used the 3.x form
> — it would not compile against 5.1.0. Target 5.x.

```java
byte[] load(Path keyPath) throws AuthException;  // Files.readAllBytes; validates non-empty
```

Validate: file exists and is readable (else `AuthException` → exit `ExitCode.CONFIG`); content begins
with `-----BEGIN`. Do **not** hand-roll RSA parsing — defer to the SDK.

**Dependency note:** the eSignature SDK's JWT path requires BouncyCastle (`bcprov-jdk18on` +
`bcpkix-jdk18on`) at runtime or it throws `NoClassDefFoundError`. These are declared in **002's
`pom.xml`** (§1.1/§1.2); this spec only depends on their presence.

### 4.3 Minting — `JwtTokenMinter`

```java
final class JwtTokenMinter {
    JwtTokenMinter(ApiClientFactory factory, Config config, Environment env); // all from 002

    /** Mints a fresh token. Throws ConsentException if consent_required. */
    Token mint() throws AuthException, ConsentException;   // returns 002's config.Token
}
```

Implementation:

1. `ApiClient client = factory.oauthClient();` — the OAuth-host client (002 §7; host =
   `env.oAuthBasePath()`).
2. `byte[] key = privateKeyLoader.load(config.privateKeyPath());`
3. The client's OAuth base path was already set in step 1 (the factory called
   `setOAuthBasePath(env.oAuthBasePath())`). Mint with the **5.x** arg order:
   `OAuthToken t = client.requestJWTUserToken(config.integrationKey(), config.userId(),
   SCOPES, key, TOKEN_LIFETIME_SECONDS);`  // (clientId, userId, scopes, key, expiresInSeconds)
4. Store the **raw** expiry — `Instant expiresAt = Instant.now().plusSeconds(t.getExpiresIn());`.
   The `SKEW_SECONDS` margin is **not** baked in here; freshness is decided at check time by
   `Token.isExpired(now, Duration.ofSeconds(SKEW_SECONDS))` (002 §4.2), keeping skew in exactly
   one place.
5. Return `new Token(t.getAccessToken(), "Bearer", expiresAt)`.

`requestJWTUserToken` declares `throws IllegalArgumentException, ApiException, IOException`. A
`consent_required` response arrives as an `ApiException` whose body contains `"consent_required"`;
the minter detects that and converts it to a `ConsentException` carrying the consent URL (§5). A
transport failure (no HTTP response) surfaces as a **checked `IOException`** thrown directly →
`ExitCode.NETWORK` (§11); an unreadable/invalid key surfaces as `IllegalArgumentException` /
`ApiException` → `ExitCode.CONFIG`.

### 4.4 userinfo — `accountId` + `baseUri`

After the first successful mint, `login` calls `client.getUserInfo(accessToken)`
(`/oauth/userinfo`). The default account (`account.getIsDefault() == "true"`, fallback: first)
yields `account_id` and `base_uri = account.getBaseUri()` (e.g. `https://demo.docusign.net`).
Persist both into credentials via 002's `Config`. 002's `ApiClientFactory.authenticated()` then
builds the eSignature REST base path as `base_uri + "/restapi"`.

---

## 5. `login` command

```java
@Command(name = "login",
         description = "Authenticate via JWT Grant; grant consent if needed and cache a token.")
final class LoginCommand implements Callable<Integer> { ... }
```

Flow:

1. **Validate config** — `integration_key`, `user_id`, and the private key file must be present.
   Missing → human-readable message naming the missing item; exit `ExitCode.CONFIG`.
2. **Mint** via `JwtTokenMinter.mint()` (environment resolved from `--demo`/`--prod`, §7).
3. **On `ConsentException`** — print and, when a desktop is available, open the consent URL, then
   instruct and retry:

   ```
   Consent required. Grant this integration access to act on your behalf:

     <consentUrl>

   Open the URL, sign in, click "Allow", then re-run `docusign-cli login`.
   ```

   The consent URL is built deterministically (do not rely on parsing it from the error):

   ```
   https://{env.oAuthBasePath()}/oauth/auth
     ?response_type=code
     &scope=signature%20impersonation
     &client_id={integration_key}
     &redirect_uri={config.redirectUri()}
   ```

   `redirect_uri` is `config.redirectUri()` (the `redirect_uri` credential key, default
   `https://www.docusign.com`, 002 §4.1) and must match a redirect URI registered on the DocuSign
   app (see §9). Attempt `Desktop.browse(...)` when `Desktop.isDesktopSupported()`; always also
   print the URL (headless/SSH). Exit `ExitCode.CONSENT` (002 §6.1) so scripts can distinguish
   "needs human" from hard failure.
4. **On success** — call userinfo, persist `accountId` + `baseUri` (§4.4), write `token.json` (§6),
   print:

   ```
   Logged in.
     Account:  <accountId>  (<accountName>)
     Base URI: <baseUri>
     Token expires: <localized expiry>
   ```

   Exit `ExitCode.OK`.

`login` is the **only** command that may trigger consent or print interactive instructions.

---

## 6. Token cache — owned by 002

There is **no** `TokenCache`/`CachedToken` in this spec. The cached token is the `config.Token`
type persisted by 002's `Config` (§4.2/§4.3):

- read: `Config.readToken()` → `Optional<Token>` (empty on missing/corrupt — a cache miss, never
  a hard error);
- write: `Config.writeToken(Token)` (mode `0600`, atomic temp + `ATOMIC_MOVE`);
- clear: `Config.clearToken()`.

`token.json` schema (002 §4.2): `{ "access_token", "token_type", "expires_at" }` with `expires_at`
the **raw** mint expiry. Freshness is `token.isExpired(Instant.now(), Duration.ofSeconds(SKEW_SECONDS))`
— the 60s skew lives only at the check, not in the stored value.

---

## 7. Demo vs production — 002's `Environment`

This spec uses 002's single `Environment` enum (`oAuthBasePath()` = `account-d.docusign.com` /
`account.docusign.com`); it defines **no** `OAuthHost`. The environment is resolved from the root
`--demo`/`--prod` mutually-exclusive option group (002 §3.2). Default: **demo** (safer). Two
practical consequences:

- The environment chosen at `login` time determines which one the `account_id`/`base_uri`
  belong to. Mixing environments (logging in on prod, then running a command with `--demo`)
  yields a token for one host against the other's `base_uri` and will 401.
- `auth status` therefore prints the resolved environment so the mismatch is visible. A `base_uri`
  already in credentials whose host disagrees with the selected `Environment` is reported as a
  warning by `auth status` and causes `login` to overwrite `account_id`/`base_uri` for the new
  environment.

---

## 8. `TokenProvider` seam

The `TokenProvider` **interface is defined in 002** (§7.1: `String accessToken() throws
CliException`). This spec supplies the implementation. API commands (005/006/007) never call it
directly — they go through `CliContext.authenticatedApiClient()` (002 §3.3), which calls it. The
**refresh path is fully silent — never interactive**.

```java
final class CachingTokenProvider implements TokenProvider {
    CachingTokenProvider(Config config, JwtTokenMinter minter);   // both from 002 wiring

    public String accessToken() throws AuthException {
        Instant now = Instant.now();
        Duration skew = Duration.ofSeconds(SKEW_SECONDS);
        return config.readToken()                       // 002 persistence
            .filter(t -> !t.isExpired(now, skew))       // 002 Token.isExpired
            .map(Token::accessToken)
            .orElseGet(this::mintAndCache);             // silent
    }
}
```

`mintAndCache` calls `minter.mint()` and `config.writeToken(...)`. Critically, on the refresh path
a `ConsentException` is **not** surfaced as a consent prompt — it is wrapped as an `AuthException`
(message *"Not authorized. Run `docusign-cli login` first."*, → `ExitCode.NOAUTH`). Only the
`login` command handles consent interactively. This keeps headless commands headless.

Exit codes used by this spec map onto 002's authoritative `ExitCode` enum (002 §6.1):

| `ExitCode` | Meaning | Used by |
|---|---|---|
| `OK` | success | all |
| `CONFIG` | missing/invalid config or key file | login, provider |
| `CONSENT` | consent required (human action needed) | login |
| `NOAUTH` | no cached token & cannot mint silently | provider / API commands |
| `NETWORK` | network/transport failure reaching DocuSign | login, provider |
| `SOFTWARE` | unexpected error | all |

---

## 9. User-facing setup prerequisites (documentation only)

`login` does not automate these; surface them in the missing-config error text and the README:

1. Create a DocuSign app (integration key) in the admin console.
2. Generate an RSA keypair; upload the **public** key to the app; save the **private** key to
   `~/.docusign-cli/private.key`.
3. Register redirect URI `https://www.docusign.com` on the app (used only to satisfy the consent
   URL; the JWT flow itself never calls back).
4. Put `integration_key` and `user_id` (impersonated user's API GUID) in
   `~/.docusign-cli/credentials`.
5. Run `docusign-cli login` once and grant consent.

---

## 10. `auth status` command

```java
@Command(name = "status", description = "Show current account, base URI, and token validity.")
final class AuthStatusCommand implements Callable<Integer> { ... }
```

Read-only; performs **no** network calls and never mints. Reports:

- Resolved `Environment` (demo/prod) from the root flag.
- `account_id` + `base_uri` from credentials (or "not logged in — run `docusign-cli login`").
- From `token.json`: present/absent; if present, valid vs expired and the expiry timestamp +
  remaining duration.
- Warning if the credentials `base_uri` host disagrees with the selected `Environment` (§7).

Example:

```
Environment: demo (account-d.docusign.com)
Account:     1234-...-abcd  (Acme Inc)
Base URI:    https://demo.docusign.net
Token:       valid, expires in 42m (2026-06-09T21:30:00Z)
```

Exit `ExitCode.OK` when logged in (regardless of token validity — a status check on an expired token is
still a successful status report); `ExitCode.NOAUTH` when no credentials/account at all. Honors `--json`
(002) by emitting the same fields as an object.

---

## 11. Error handling matrix

| Condition | Detection | Behavior | Exit |
|---|---|---|---|
| Missing integrationKey/userId | `Config` lookup empty | name the field; point to §9 | `ExitCode.CONFIG` |
| Private key file missing/unreadable | `PrivateKeyLoader.load` | "private key not found at <path>" | `ExitCode.CONFIG` |
| Malformed/invalid key | SDK throws on `requestJWTUserToken` | "private key is invalid or not RSA" | `ExitCode.CONFIG` |
| consent_required (login) | body contains `consent_required` | print/open consent URL (§5) | `ExitCode.CONSENT` |
| consent_required (refresh path) | same | wrap → "run login first" | `ExitCode.NOAUTH` |
| Clock skew (`exp`/`iat` rejected) | SDK/HTTP 400, body mentions invalid time/`iat` | hint: "check system clock"; freshness skew is applied at check time (§4.3/§6) | `ExitCode.SOFTWARE` |
| Network/DNS/timeout (JWT / userinfo) | checked `IOException`/`UnknownHostException` thrown **directly** by `requestJWTUserToken` / `getUserInfo` | "could not reach <oauthHost>" | `ExitCode.NETWORK` |
| userinfo has no accounts | empty account list | "user has no DocuSign account" | `ExitCode.CONFIG` |
| Anything else | catch-all | print message; full stack only with `--verbose` (002) | `ExitCode.SOFTWARE` |

Clock skew note: JWT assertions carry `iat`/`exp`; a host clock off by minutes makes DocuSign
reject the assertion. We always set a 1h lifetime and the §4.3 skew; the remaining failure mode is
a grossly wrong local clock, which we cannot fix — we only hint.

---

## 12. Testing notes

- **`PrivateKeyLoader`** — unit: valid PEM bytes returned verbatim; missing file → `AuthException`;
  non-PEM content → `AuthException`.
- Token persistence (`Config.readToken/writeToken`, `Token.isExpired`, file mode, corrupt→empty)
  is covered by **002**'s tests, not re-tested here.
- **`CachingTokenProvider`** — (a) valid cached token → returned, minter **not** invoked;
  (b) expired token → minter invoked, result cached; (c) missing cache → mint; (d) minter throws
  `ConsentException` → surfaces as `AuthException`/`ExitCode.NOAUTH`, **never** an interactive prompt.
  Mock `JwtTokenMinter`.
- **`JwtTokenMinter`** — inject a fake `ApiClient`/`ApiClientFactory`: assert it is called with the
  configured integration key, user id, `["signature","impersonation"]` (scopes), key bytes, and
  `TOKEN_LIFETIME_SECONDS` (5.x arg order), and that the OAuth base path was set on the client via
  `setOAuthBasePath` (it is **not** a `requestJWTUserToken` argument); a `consent_required` response
  maps to `ConsentException` with a non-null URL; the returned `Token` stores the raw (un-skewed)
  expiry.
- **`LoginCommand`** via Picocli `CommandLine.execute` with mocked minter/`Config`: success path
  persists `account_id`/`base_uri` and writes the token; consent path prints the URL and returns
  `ExitCode.CONSENT`.
- **`AuthStatusCommand`** — logged-in valid, logged-in expired, not-logged-in, and host-mismatch
  warning; assert no network calls (mock factory verifies zero interactions).
- Live JWT against `account-d.docusign.com` is a manual smoke test, not part of CI (needs real
  consent + key).
```