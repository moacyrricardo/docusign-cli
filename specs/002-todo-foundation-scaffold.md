# 002 — Foundation scaffold: build, CLI root, config, output, ApiClient

Status: **todo**

The project foundation every command spec builds on. This spec fixes the Maven build, the
package layout, the root Picocli command and global options, the on-disk config + token
abstraction, the output/formatting abstraction, the DocuSign `ApiClient` factory, and the
shared error-handling/exit-code conventions.

It does **not** implement any subcommand logic. Subcommands are registered here as seams and
specced separately:

- `login` → **003**
- `scan` → **004**
- `send` → **005**
- `envelopes list` → **006**
- `envelope status` → **007**

Implements the stack and file-layout decisions of **001** (§3, §4, §6).

---

## 1. Build — `pom.xml`

### 1.1 Coordinates and Java level

```xml
<groupId>io.github.moacyrricardo</groupId>
<artifactId>docusign-cli</artifactId>
<version>0.1.0-SNAPSHOT</version>
<packaging>jar</packaging>
```

Java 17 throughout. Pin versions via `<properties>` so sibling specs reference one source of
truth:

```xml
<properties>
  <maven.compiler.release>17</maven.compiler.release>
  <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

  <picocli.version>4.7.6</picocli.version>
  <docusign.esign.version>5.1.0</docusign.esign.version>
  <pdfbox.version>3.0.3</pdfbox.version>
  <bouncycastle.version>1.78.1</bouncycastle.version>
  <jackson.version>2.17.2</jackson.version>
  <junit.version>5.10.3</junit.version>
  <shade.plugin.version>3.6.0</shade.plugin.version>
  <compiler.plugin.version>3.13.0</compiler.plugin.version>
</properties>
```

**Version strategy:** explicit pinned versions in `<properties>` (no version ranges, no BOM
import in v1). Bumps are deliberate edits. The functional dependencies are Picocli, the DocuSign
eSign SDK, PDFBox **3.x**, Jackson, and **BouncyCastle**. BouncyCastle is declared explicitly
because the SDK's JWT-Grant path (`requestJWTUserToken`, used by [003](003-todo-login-jwt-auth.md))
parses the RSA key with BouncyCastle at runtime and will throw `NoClassDefFoundError` without it.
Other transitives (JOSE, OkHttp/Gson used by the SDK) come in via the SDK and are not declared
directly.

### 1.2 Dependencies

```xml
<dependencies>
  <dependency>
    <groupId>info.picocli</groupId>
    <artifactId>picocli</artifactId>
    <version>${picocli.version}</version>
  </dependency>

  <dependency>
    <groupId>com.docusign</groupId>
    <artifactId>docusign-esign-java</artifactId>
    <version>${docusign.esign.version}</version>
  </dependency>

  <dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
    <version>${pdfbox.version}</version>
  </dependency>

  <!-- required at runtime by the SDK's JWT-Grant RSA-key parsing (003) -->
  <dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcprov-jdk18on</artifactId>
    <version>${bouncycastle.version}</version>
  </dependency>
  <dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcpkix-jdk18on</artifactId>
    <version>${bouncycastle.version}</version>
  </dependency>

  <!-- credentials/token (de)serialization -->
  <dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>${jackson.version}</version>
  </dependency>

  <dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>${junit.version}</version>
    <scope>test</scope>
  </dependency>
</dependencies>
```

> Note for **004**/**005**: PDFBox is **3.x** (load via `org.apache.pdfbox.Loader.loadPDF`, not
> the 2.x `PDDocument.load`). Glyph-level inspection (font size, fill color) uses a custom
> `PDFTextStripper`; no extra dependency is needed beyond `pdfbox`.

### 1.3 Plugins — compiler + executable fat jar

`maven-compiler-plugin` honoring `maven.compiler.release` (and enabling Picocli's annotation
processor is optional; v1 uses reflection-based command discovery, so no annotation processor is
required).

`maven-shade-plugin` produces a single runnable artifact:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-shade-plugin</artifactId>
  <version>${shade.plugin.version}</version>
  <executions>
    <execution>
      <phase>package</phase>
      <goals><goal>shade</goal></goals>
      <configuration>
        <finalName>docusign-cli</finalName>
        <transformers>
          <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
            <mainClass>io.github.moacyrricardo.docusign.Main</mainClass>
          </transformer>
          <!-- merge META-INF/services so SDK/JOSE SPI files survive shading -->
          <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
        </transformers>
        <filters>
          <filter>
            <artifact>*:*</artifact>
            <excludes>
              <exclude>META-INF/*.SF</exclude>
              <exclude>META-INF/*.DSA</exclude>
              <exclude>META-INF/*.RSA</exclude>
            </excludes>
          </filter>
        </filters>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Result: `target/docusign-cli.jar`, runnable as `java -jar target/docusign-cli.jar`. A thin
wrapper script `docusign-cli` (in repo root, `exec java -jar <path> "$@"`) is the invoked name in
all examples; packaging/installation of that wrapper is out of scope for v1.

### 1.4 Main class

`io.github.moacyrricardo.docusign.Main` — the JVM entry point. Its sole job:

```java
public static void main(String[] args) {
    int exit = new picocli.CommandLine(new RootCommand())
        .setCaseInsensitiveEnumValuesAllowed(true)
        .setExecutionExceptionHandler(new CliExceptionHandler())  // §6
        .execute(args);
    System.exit(exit);
}
```

`Main` holds no logic beyond wiring; all behavior lives in `RootCommand` and subcommands.

---

## 2. Package layout

Base package `io.github.moacyrricardo.docusign`:

```
**Convention: feature packages.** Each sibling spec's classes live together under a
feature-named subpackage (`auth`, `anchor`, `send`, `envelope`); foundation concerns own
`cli`, `config`, `output`, `docusign`. This spec creates every package and a **registered
shell** for each command/seam class so the project compiles; sibling specs fill in bodies.

```
io.github.moacyrricardo.docusign
  Main                        # entry point (§1.4)
  cli/
    RootCommand               # @Command(name="docusign-cli"); subcommands + composition root (§3.1)
    CliExceptionHandler       # IExecutionExceptionHandler → exit codes (§6)
    GlobalOptions             # @Mixin: --json, --output, --yes, --demo/--prod (§3)
    CliContext                # resolved runtime context passed to subcommands (§3.3)
    ExitCode                  # exit-code enum (§6)
    CliException              # base unchecked CLI error carrying an ExitCode (§6)
  config/
    Config                    # load/save credentials + token; flat accessors (§4)
    Credentials               # credentials schema POJO (§4.1)
    Token                     # token.json schema POJO (§4.2)
    ConfigPaths               # resolves ~/.docusign-cli/* paths (§4.3)
    ConfigException           # CliException → ExitCode.CONFIG (§4.3, §6)
  output/
    OutputFormat              # enum { TABLE, JSON }
    OutputWriter              # interface used by all commands (§5)
    TableWriter               # human table impl
    JsonWriter                # --json impl
  docusign/
    ApiClientFactory          # demo/prod ApiClient construction (§7)
    Environment               # enum { DEMO, PROD } + base paths (§7)
    DocuSignException         # wraps SDK ApiException → CliException (§6)
  auth/                       # owned by 003; this spec ships shells + the seam
    TokenProvider             # interface (the auth seam, §7); impl in 003
    CachingTokenProvider      # shell → 003
    JwtTokenMinter            # shell → 003
    LoginCommand              # shell → 003   (@Command name="login", root subcommand)
    AuthCommand               # shell → 003   (@Command name="auth", root subcommand)
    AuthStatusCommand         # shell → 003   (@Command name="status" under `auth`)
  anchor/                     # owned by 004
    ScanCommand               # shell → 004   (@Command name="scan", root subcommand)
    AnchorScanner             # shell → 004
  send/                       # owned by 005
    SendCommand               # shell → 005   (@Command name="send", root subcommand)
  envelope/                   # owned by 006 / 007
    EnvelopesCommand          # shell → 006   (@Command name="envelopes", root subcommand; parent of `list`)
    EnvelopesListCommand      # shell → 006   (@Command name="list" under `envelopes`)
    EnvelopeCommand           # shell → 007   (@Command name="envelope", root subcommand; parent of `status`)
    EnvelopeStatusCommand     # shell → 007   (@Command name="status" under `envelope`)
```

Command/seam classes in this spec are **registered shells** (the `@Command` annotation, the
subcommand wiring, and field declarations for their options) with `call()`/`run()` bodies left
to their owning specs. This spec must compile: shells may `throw new UnsupportedOperationException`
or return `ExitCode.SOFTWARE` as a placeholder until their spec lands. The two parent groupers
(`EnvelopesCommand`, `EnvelopeCommand`) are created here with empty `subcommands = {}`; 006/007
add their leaf into that array.

---

## 3. Root command and global options

### 3.1 `RootCommand`

```java
@Command(
    name = "docusign-cli",
    mixinStandardHelpOptions = true,           // --help, --version
    versionProvider = ...,                      // reads Implementation-Version from manifest
    subcommands = {
        LoginCommand.class,                     // 003
        AuthCommand.class,                      // 003 (hosts `status`)
        ScanCommand.class,                      // 004
        SendCommand.class,                      // 005
        EnvelopesCommand.class,                 // 006 (hosts `list`)
        EnvelopeCommand.class                   // 007 (hosts `status`)
    })
public class RootCommand implements Runnable { ... }
```

Invoked with no subcommand → print usage and exit `USAGE` (§6). The six subcommand classes are
referenced here; their internal structure (e.g. `envelopes list` as a nested `@Command`) is
defined by their owning specs.

**Composition root.** `RootCommand` is where the foundation and the auth wiring meet. Before
dispatching a subcommand it resolves the environment/output (§3.2) and constructs the runtime
graph that `CliContext` (§3.3) exposes:

```
Config config            = Config.open();
ApiClientFactory factory = new ApiClientFactory(environment, config);     // §7
JwtTokenMinter minter     = new JwtTokenMinter(factory, config, environment); // body in 003
TokenProvider tokens      = new CachingTokenProvider(config, minter);     // body in 003
```

`TokenProvider`/`CachingTokenProvider`/`JwtTokenMinter` are the `auth`-package shells this spec
ships (§2); 003 supplies their bodies. The graph is built lazily (auth/network only happens when
a command actually calls `tokens.accessToken()`), so `scan` and `--help` never touch DocuSign.

### 3.2 Global options (`GlobalOptions`, a Picocli `@Mixin`)

Declared once and mixed into `RootCommand` and every subcommand so they may appear before or
after the subcommand name.

| Option | Type | Default | Meaning |
|---|---|---|---|
| `--json` | boolean | false | Emit machine JSON instead of the human table. Sets `OutputFormat.JSON`. |
| `--output <file>` | `Path` | none (stdout) | Write primary output to a file instead of stdout. Applies to both formats. |
| `--yes` / `-y` | boolean | false | Assume "yes" for all confirmation prompts (automation). |
| `--demo` | boolean | true* | Use DocuSign demo environment (`account-d.docusign.com` / `demo.docusign.net`). |
| `--prod` | boolean | false | Use DocuSign production environment. |

`--demo` and `--prod` are mutually exclusive (`@ArgGroup(exclusive = true)`); specifying neither
resolves the environment from config (`base_uri`, §4.1), and if config is silent the default is
**DEMO**. The flags override config for that invocation.

`--json` and `--output` are independent: `--json --output env.json` writes JSON to a file.

### 3.3 `CliContext`

`RootCommand` builds an immutable `CliContext` from the resolved global options and hands it to
subcommands (via a shared parent reference or Picocli `@ParentCommand`). Shape:

```java
public final class CliContext {
    Environment environment();     // DEMO | PROD, after override resolution (§3.2, §7)
    OutputWriter output();         // selected per --json/--output (§5)
    boolean assumeYes();           // --yes
    Config config();               // lazily loaded (§4)

    // auth + API seams (constructed by the composition root, §3.1)
    ApiClientFactory apiClientFactory();   // §7
    TokenProvider tokenProvider();         // §7 / 003
    ApiClient authenticatedApiClient();    // = apiClientFactory().authenticated(tokenProvider().accessToken())
}
```

Subcommands depend on `CliContext`, never on raw Picocli option fields of the root, so that 003–007
share one resolution path for environment/output/confirmation. **API commands (005/006/007) obtain
their client exclusively via `ctx.authenticatedApiClient()`** — they never build an `ApiClient`
themselves nor touch `TokenProvider` directly. `authenticatedApiClient()` triggers a silent
mint/refresh through `TokenProvider`; if no token can be obtained without consent it throws an
`AuthException` (→ `ExitCode.NOAUTH`, owned by 003).

---

## 4. Config abstraction

All state lives under `~/.docusign-cli/` (001 §4). `ConfigPaths` resolves them; everything else is
relative to `ConfigPaths.root()` (override root via env var `DOCUSIGN_CLI_HOME` for tests).

### 4.1 `credentials` — schema

A flat properties-style file (`java.util.Properties`-compatible: `key=value`, `#` comments). Keys:

| Key | Meaning | Set by |
|---|---|---|
| `integration_key` | DocuSign integration (client) key / GUID | user, at setup |
| `user_id` | impersonated API user GUID | user, or fetched by `login` (001 §4) |
| `account_id` | DocuSign account ID | user, or fetched by `login` |
| `base_uri` | account base URI / environment hint (`demo` or `prod`, or a full host) | user / `login` |
| `private_key_path` | path to RSA private key; defaults to `~/.docusign-cli/private.key` | user |
| `redirect_uri` | consent redirect URI registered on the app; defaults to `https://www.docusign.com` | user (optional); used only to build the consent URL (003 §5) |

`Credentials` is the typed view:

```java
public final class Credentials {
    String integrationKey();
    String userId();           // nullable until login fetches it
    String accountId();        // nullable until login fetches it
    String baseUri();          // nullable → environment falls back to flags/DEMO
    Path privateKeyPath();     // defaults to ConfigPaths.privateKey()
    String redirectUri();      // defaults to "https://www.docusign.com" (003 §5)
    // builder/with-style copy for login to fill userId/accountId
}
```

### 4.2 `token.json` — schema

JSON (Jackson). Cached access token minted by JWT grant (minting itself specced in 003):

```json
{
  "access_token": "ey...",
  "token_type": "Bearer",
  "expires_at": "2026-06-09T18:42:00Z"   // absolute UTC instant; checked before reuse
}
```

```java
public final class Token {
    String accessToken();
    String tokenType();        // "Bearer"
    Instant expiresAt();
    boolean isExpired(Instant now, Duration skew);  // true if now+skew >= expiresAt
}
```

Store an absolute `expires_at` (not a relative `expires_in`) so freshness is a pure comparison.
Default clock skew: 60s.

### 4.3 `Config` — type and method signatures

`Config` is the single read/write gateway; it does no network or crypto (that is 003).

```java
public final class Config {
    static Config open();                       // uses ConfigPaths defaults
    static Config open(ConfigPaths paths);      // test seam

    boolean exists();                            // credentials file present

    Credentials readCredentials();               // throws ConfigException if missing/invalid
    void writeCredentials(Credentials c);        // creates dir (0700) + file (0600), atomic

    // flat convenience accessors (delegate to readCredentials(); cached) so 003/005/006/007
    // can call config.accountId() etc. without threading a Credentials object through.
    String integrationKey();
    String userId();
    String accountId();
    String baseUri();
    Path   privateKeyPath();
    String redirectUri();

    Optional<Token> readToken();                 // empty if token.json absent/unparseable
    void writeToken(Token t);                    // file mode 0600, atomic write
    void clearToken();                           // delete token.json (logout / forced re-mint)
}

public final class ConfigPaths {
    static ConfigPaths defaults();               // honors DOCUSIGN_CLI_HOME
    Path root();          // ~/.docusign-cli
    Path credentials();   // root/credentials
    Path privateKey();    // root/private.key
    Path token();         // root/token.json
}
```

**File permissions:** on creation, `root()` is `0700`, `credentials`/`token.json`/`private.key`
are `0600` (POSIX). Writes are atomic (write temp + `ATOMIC_MOVE`) to avoid torn files.
`ConfigException` is a `CliException` mapping to `ExitCode.CONFIG` (§6).

---

## 5. Output / formatting abstraction

One interface all commands write through; the concrete writer is chosen by `CliContext` from
`--json`/`--output`.

```java
public interface OutputWriter extends AutoCloseable {
    // a tabular result: ordered column headers + rows of cells
    void table(List<String> headers, List<List<String>> rows);

    // a single record (e.g. envelope status) as key→value
    void record(Map<String, ?> fields);

    // free-form human message (status lines, prompts' echoes); suppressed in JSON mode
    void message(String text);

    // structured payload commands hand off; JSON writer serializes it,
    // table writer ignores it in favor of table()/record()
    void object(Object payload);

    @Override void close();   // flush; close --output file if any
}
```

- `OutputFormat { TABLE, JSON }`. `TableWriter` renders aligned columns (and human-formats
  `record`); `JsonWriter` serializes via Jackson (pretty-printed) and emits one JSON document per
  invocation — `message(...)` is a no-op so JSON output stays clean and pipe-safe.
- Commands **must not** call `System.out` directly; they go through `CliContext.output()`.
- `--output <file>` routes the primary stream to that file (UTF-8); errors/diagnostics still go to
  stderr regardless of format (§6).
- **Dual-representation contract (format-agnostic commands).** A command emits *both*
  representations unconditionally and never branches on the format: it calls `object(dto)` with a
  full structured payload **and** `table(...)`/`record(...)` for the human view. `JsonWriter`
  honors `object(...)` and ignores `table/record/message`; `TableWriter` honors `table/record`
  and ignores `object`. This is why 005/006/007 build a row/view DTO *and* describe table columns —
  both feed the same call sequence.

This is the contract 005/006/007 rely on for their table/record/JSON shapes.

---

## 6. Error handling and exit codes

### 6.1 Exit codes (`ExitCode`)

`ExitCode` is an `enum` (each constant has an `int code()`). **This table is authoritative for the
whole CLI**; 003–007 map their outcomes onto these constants and never invent their own numbers.

| Constant | Value | Meaning | Primary owner |
|---|---|---|---|
| `OK` | 0 | success | all |
| `USAGE` | 2 | bad CLI usage / parse error (Picocli default; malformed `--recipient`/anchor spec, bad `--status`/date) | all |
| `CONFIG` | 3 | missing/invalid config, credentials, or key file (`ConfigException`) | 002, 003 |
| `NOAUTH` | 4 | no cached token and cannot mint silently — run `login` (`AuthException`) | 003, API cmds |
| `CONSENT` | 5 | consent required; human action needed (`login` only) | 003 |
| `NOTFOUND` | 6 | resource not found (e.g. envelope 404) | 007 |
| `API` | 7 | DocuSign API error (`DocuSignException` wrapping SDK `ApiException`) | 005, 006, 007 |
| `NETWORK` | 8 | network/transport failure reaching DocuSign | 003, API cmds |
| `INPUT` | 9 | bad input not catchable by Picocli (unreadable/encrypted PDF, nothing to send) | 004, 005 |
| `SOFTWARE` | 70 | unexpected/internal error (uncaught) | all |

`ExitCode` replaces the earlier `AUTH`/`INPUT=6` placeholders: authentication failures now split
into `NOAUTH` (silent-refresh impossible) and `CONSENT` (interactive consent needed), and `INPUT`
moved to 9 so `NOTFOUND` can take 6.

### 6.2 Conventions

- Commands signal failure by throwing a `CliException` (unchecked) carrying an `ExitCode` and a
  human message; never call `System.exit` from a command.
- `CliExceptionHandler` (Picocli `IExecutionExceptionHandler`) maps:
  - `CliException` → its `exitCode`, printing `message` to **stderr** (and, in `--json` mode, also
    an `{"error": "..."}` object so machine consumers see it).
  - `ConfigException` → `CONFIG`. `AuthException` (003) → `NOAUTH` (or `CONSENT` when raised by
    `login`). `DocuSignException` carries the `ExitCode` it was constructed with — `NOTFOUND` for
    a 404, `NETWORK` for transport failures, otherwise `API`.
  - any other `Exception` → `SOFTWARE`, printing the message; full stack trace only when
    `--verbose`/`-v` or env `DOCUSIGN_CLI_DEBUG=1` is set (add `-v` to `GlobalOptions`).
- Picocli parameter/usage errors keep the framework default `USAGE` (2).
- Diagnostics always go to **stderr**; **stdout** carries only the requested output (§5), so
  `--json` stays pipe-clean.

`DocuSignException` exposes the SDK error body (DocuSign returns a JSON `{errorCode, message}`) so
the printed message is actionable.

---

## 7. DocuSign `ApiClient` factory

`ApiClientFactory` builds a configured SDK `com.docusign.esign.client.ApiClient`. It is an
**instance** (constructed by the composition root with the resolved `Environment` and `Config`),
not a bag of statics. There is exactly **one** environment enum — `Environment` — used for both the
OAuth host (JWT mint) and the REST base path; 003 does **not** define a second `OAuthHost`.

```java
public enum Environment {
    DEMO,   // oauth host account-d.docusign.com ; rest base https://demo.docusign.net/restapi
    PROD;   // oauth host account.docusign.com    ; rest base https://www.docusign.net/restapi
    String oAuthBasePath();   // e.g. "account-d.docusign.com" (used by 003 for JWT)
    String restBasePath();    // e.g. "https://demo.docusign.net/restapi" (fallback when base_uri unset)
}

public final class ApiClientFactory {
    ApiClientFactory(Environment env, Config config);

    /** OAuth-host client with no bearer; used by 003's JWT mint (requestJWTUserToken). */
    ApiClient oauthClient();

    /** REST client ready for EnvelopesApi: base path = config.baseUri()+"/restapi"
     *  (fallback env.restBasePath()), with the given bearer token applied. */
    ApiClient authenticated(String accessToken);
}
```

- `authenticated(accessToken)` is the single seam API commands reach (via
  `CliContext.authenticatedApiClient()`, §3.3). It prefers the account-specific
  `config.baseUri()+"/restapi"` (written by `login`, 003 §4.4) and falls back to
  `env.restBasePath()` when `base_uri` is unset.
- `oauthClient()` points only at `env.oAuthBasePath()`; the JWT minting that turns it into a
  `Token` is entirely 003's concern.
- Environment resolution order (from §3.2): explicit `--demo`/`--prod` → `base_uri` in credentials
  → default `DEMO`.

### 7.1 `TokenProvider` seam (interface owned here, impl in 003)

The auth seam lives in the `auth` package as an interface so the foundation/composition root can
reference it without depending on 003's concrete classes:

```java
package io.github.moacyrricardo.docusign.auth;

public interface TokenProvider {
    /** A valid bearer access token, silently minting/refreshing+caching when missing/expired.
     *  @throws AuthException (a CliException → ExitCode.NOAUTH) if a token cannot be obtained
     *          without interactive consent. Never prompts. */
    String accessToken() throws CliException;
}
```

003 supplies `CachingTokenProvider implements TokenProvider` (and `AuthException extends
CliException`). The interface and a throwing shell ship in this spec (§2) so 002 compiles before
003 lands.

---

## 8. Testing notes

- **Config:** point `DOCUSIGN_CLI_HOME` at a JUnit `@TempDir`; round-trip `writeCredentials` /
  `readCredentials` and `writeToken` / `readToken`; assert POSIX file modes (`0600`/`0700`) and
  atomic-write behavior (no temp leftovers). Verify `readToken` returns `Optional.empty()` on a
  missing/garbage file rather than throwing.
- **Token freshness:** unit-test `Token.isExpired` across the 60s skew boundary with a fixed
  `Instant`.
- **Output:** capture an `OutputWriter` over a `StringWriter`; assert table alignment for
  `TableWriter` and valid, stable JSON for `JsonWriter`; assert `message(...)` is suppressed in
  JSON mode.
- **CLI wiring:** drive `new CommandLine(new RootCommand())` in-process; assert global options
  parse before and after the subcommand name, that `--demo`/`--prod` are mutually exclusive (exit
  `USAGE`), and that the registered subcommand names (`login`, `auth`, `scan`, `send`,
  `envelopes`, `envelope`) resolve. Subcommand behavior is tested in 003–007.
- **Exit codes:** assert `CliExceptionHandler` maps each `CliException` subclass to the right code
  and writes to stderr (not stdout).
- **ApiClientFactory:** assert `authenticated(token)` uses `config.baseUri()+"/restapi"` when set
  and `env.restBasePath()` otherwise, and applies the bearer; assert `oauthClient()` targets
  `env.oAuthBasePath()` with no auth header.
- No live DocuSign calls in unit tests; SDK interactions are exercised behind the factory/seam and
  covered by the command specs that own them.
