# 007 — `envelope status <id>`: single-envelope status lookup

Status: **todo**

Prescriptive spec for the `envelope status <envelopeId>` command: fetch and display the
status of one envelope by ID, optionally including per-recipient status. Implements goal §2.3
of [001](001-todo-cli-design.md) ("Get envelope status") and the surface line
`docusign-cli envelope status <id>` from 001 §6.

Builds on:
- [002](002-todo-foundation-scaffold.md) — root command, `Config`, `ApiClient` factory, output
  abstraction (human table + `--json`).
- [003](003-todo-login-jwt-auth.md) — `TokenProvider` yielding a valid access token (mints/refreshes
  via JWT; throws a typed "not authenticated" error when no usable credentials exist).
- [006](006-todo-envelopes-list.md) — `envelopes list`. See §1 for the command-grouping
  relationship between these two.

---

## 1. Command surface & grouping

### 1.1 The `envelope` / `envelopes` parent groups

001 §6 uses two spellings: `envelopes list` (plural) and `envelope status <id>` (singular).
We honor that exactly rather than collapse them, because the noun number matches the
cardinality (a *list* of envelopes vs. the status of *one* envelope) and it mirrors the
literal command lines users were shown.

Decision:

- **002 ships both grouper shells** (`EnvelopesCommand` name `envelopes`, `EnvelopeCommand` name
  `envelope`) already registered on the root with empty `subcommands = {}`.
- **006 owns** `envelopes` → attaches `list`; **007 owns** `envelope` → attaches `status`.

Both parents are pure groupers: `@Command` with `subcommands = {...}`, no `call()` of their
own beyond printing usage when invoked bare. They are siblings under the root command from
002; neither nests inside the other, and both live in the shared `envelope` feature package.
007 only adds `EnvelopeStatusCommand` to `EnvelopeCommand`'s `subcommands`.

> Note on consistency: should a future spec consolidate these under one noun, both
> `EnvelopeCommand` and `EnvelopesCommand` are thin groupers and trivially re-parentable; the
> leaf command classes (`EnvelopeStatusCommand`, the list command) carry all behavior and
> would be unaffected.

### 1.2 `EnvelopeCommand` (parent grouper)

```java
package io.github.moacyrricardo.docusign.envelope;   // 002 feature-package convention

@Command(
    name = "envelope",
    description = "Operate on a single envelope.",
    subcommands = { EnvelopeStatusCommand.class }
)
public final class EnvelopeCommand { }
```

Registered in the root command's `subcommands` list (002) alongside `EnvelopesCommand`,
`login`, `auth`, `scan`, `send`.

### 1.3 `EnvelopeStatusCommand` (leaf)

```java
package io.github.moacyrricardo.docusign.envelope;   // 002 feature-package convention

@Command(
    name = "status",
    description = "Show the status of a single envelope by ID."
)
public final class EnvelopeStatusCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<envelopeId>",
                description = "DocuSign envelope ID (GUID).")
    private String envelopeId;

    @Option(names = "--recipients",
            description = "Also list per-recipient status (name, email, status, order).")
    private boolean recipients;

    // --json and other global flags are inherited from the root command (002),
    // not redeclared here.

    @Override
    public Integer call() throws Exception { ... }
}
```

`status` takes exactly one positional argument, the envelope ID. Recipient detail is **opt-in
behind `--recipients`** (default off): the base call is one API round-trip, and most status
checks (especially scripted polling) only need the envelope-level status. Adding the flag
issues a second call.

`--json` is the global flag from 002; when set, the command emits structured JSON instead of
the table (see §4). No per-command output flags are introduced here.

---

## 2. Behavior / control flow

`call()`:

1. Validate `envelopeId` shape locally (§5, invalid-id) before any network call or auth.
2. Build the API over `ctx.authenticatedApiClient()` (002 §3.3 — applies the 003 token and the
   account REST base path; never construct an `ApiClient` here):
   ```java
   EnvelopesApi envelopesApi = new EnvelopesApi(ctx.authenticatedApiClient());
   ```
   If auth fails, `authenticatedApiClient()` throws `AuthException`; let it propagate to the root
   handler (§5, `ExitCode.NOAUTH`) — do not catch-and-swallow.
3. `accountId` comes from `Config` (002 flat accessor; written by 003 `login`/`userinfo`).
4. Fetch the envelope:
   ```java
   Envelope env = envelopesApi.getEnvelope(accountId, envelopeId);
   ```
5. If `--recipients` was passed, fetch recipients:
   ```java
   Recipients r = envelopesApi.listRecipients(accountId, envelopeId);
   ```
   (Default `getEnvelope` does **not** include recipients, so a second call is required.)
6. Map results to the output model (§3) and render via the 002 output abstraction (table or
   `--json`).
7. Return exit code `0`.

No mutation occurs; this command is strictly read-only.

---

## 3. Output model & fields

Define a small DTO populated from the SDK types so both renderers (table + JSON) share one
source of truth:

```java
record EnvelopeStatusView(
    String envelopeId,
    String status,            // env.getStatus()  e.g. sent, delivered, completed, declined, voided
    String emailSubject,      // env.getEmailSubject()
    String createdDateTime,   // env.getCreatedDateTime()
    String sentDateTime,      // env.getSentDateTime()   (null until sent)
    String completedDateTime, // env.getCompletedDateTime() (null until completed)
    List<RecipientView> recipients   // null when --recipients not given; possibly empty
) {}

record RecipientView(
    String name,    // signer.getName()
    String email,   // signer.getEmail()
    String status,  // signer.getStatus()  e.g. created, sent, delivered, signed, completed, declined
    String order    // signer.getRoutingOrder()
) {}
```

Field sources:

- Envelope: `Envelope.getEnvelopeId/getStatus/getEmailSubject/getCreatedDateTime/`
  `getSentDateTime/getCompletedDateTime`. Timestamps are ISO-8601 strings from the SDK;
  render verbatim (no reformatting in v1). `null`/absent timestamps render as `-` in the
  table and as JSON `null`.
- Recipients (only with `--recipients`): from `Recipients.getSigners()`. v1 sends **signers
  only** (001 §5.4), so we surface signers; if `getCarbonCopies()`/agents are present they are
  ignored in v1. Order = `routingOrder` (a string in the SDK). Sort the list by numeric
  `routingOrder`, then by name, for stable display.

### 3.1 Table rendering (default)

Envelope-level block, rendered through the 002 table/key-value helper:

```
Envelope    e1f2...-guid
Status      completed
Subject     Please sign the Q2 invoice
Created     2026-06-01T14:03:22.0000000Z
Sent        2026-06-01T14:03:25.0000000Z
Completed   2026-06-02T09:11:40.0000000Z
```

With `--recipients`, append a table:

```
Recipients
Order  Name            Email                     Status
1      Moacyr Ricardo  moacyr.ricardo@gmail.com  signed
2      Jane Doe        jane@example.com          delivered
```

### 3.2 JSON rendering (`--json`)

Serialize `EnvelopeStatusView` directly via the 002 JSON writer. Without `--recipients`, the
`recipients` field is omitted (or `null`) — pick one and keep it consistent with how 006
represents absent sub-collections; prefer **omit** when null. Example with `--recipients`:

```json
{
  "envelopeId": "e1f2...-guid",
  "status": "completed",
  "emailSubject": "Please sign the Q2 invoice",
  "createdDateTime": "2026-06-01T14:03:22.0000000Z",
  "sentDateTime": "2026-06-01T14:03:25.0000000Z",
  "completedDateTime": "2026-06-02T09:11:40.0000000Z",
  "recipients": [
    { "name": "Moacyr Ricardo", "email": "moacyr.ricardo@gmail.com", "status": "signed", "order": "1" }
  ]
}
```

---

## 4. Exit codes

002's authoritative `ExitCode` enum (§6.1). This command uses:

- `OK` (0) — success.
- `USAGE` (2) — Picocli arg-parsing errors and our invalid-id (non-GUID) case (§5).
- `NOAUTH` (4) — not authenticated (propagated from `authenticatedApiClient()` / 003).
- `NOTFOUND` (6) — envelope 404.
- `API` (7) — other DocuSign API errors; `NETWORK` (8) — transport failures.

---

## 5. Edge cases

**Invalid ID format (pre-flight).** DocuSign envelope IDs are GUIDs. Before calling the API,
validate `envelopeId` against a GUID pattern
(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`). On mismatch,
print `Invalid envelope ID: <value> (expected a GUID).` to stderr and exit `ExitCode.USAGE`. This
avoids a needless round-trip and gives a clearer message than the API's 400.

**Not found (404).** `getEnvelope` throws
`com.docusign.esign.client.ApiException`. Inspect `getCode() == 404` (and/or the
`ENVELOPE_DOES_NOT_EXIST` error code in the response body). Print
`Envelope not found: <envelopeId>` to stderr and exit `ExitCode.NOTFOUND`. Do not print a stack
trace in normal (non-verbose) mode.

**Unauthenticated.** Delegated entirely to 003: `authenticatedApiClient()` throws `AuthException`
when no usable credentials/consent exist. Catch it at the command boundary (or let it propagate to
the root handler), print a short message pointing the user to `docusign-cli login`, and exit
`ExitCode.NOAUTH`. Do not attempt interactive auth here.

**Other API/network errors.** Any other `ApiException` with an HTTP code (401 after a mint attempt,
5xx, rate limiting) → `ExitCode.API`. The SDK's REST `invokeAPI` throws **only** `ApiException`
(Jersey wraps the connection error), so a transport failure arrives as an `ApiException` with
`getCode() == 0` → `ExitCode.NETWORK` — there is no bare `IOException` to catch on REST calls.
Print a concise `DocuSign API error (<code>): <message>` to stderr; include the DocuSign
`errorCode`/`message` from the response body when present. Full detail (body, stack) only under the
global verbose flag (002).

**`--recipients` failure isolation.** If the primary `getEnvelope` succeeds but the secondary
`listRecipients` fails, still render the envelope-level status, then emit a warning to stderr
(`Could not load recipients: <message>`) and exit `ExitCode.API`. The envelope status the user
asked for is not lost to a recipients-only failure.

---

## 6. Testing notes

- **Unit — output mapping:** given a stub `Envelope` (and `Recipients`), assert the
  `EnvelopeStatusView`/`RecipientView` mapping (timestamps verbatim, null→`-` in table,
  recipients sorted by routing order). No network.
- **Unit — ID validation:** table-driven test of the GUID regex (valid GUID passes; empty,
  truncated, non-hex, and obviously-bad inputs exit `USAGE` with no API call). Verify the API is
  never invoked on invalid input (mock `EnvelopesApi`, assert no interaction).
- **Unit — error mapping:** mock `EnvelopesApi.getEnvelope` to throw `ApiException` with codes
  404 / 401 / 500 → assert `NOTFOUND` / `API` / `API` and the stderr messages; an `ApiException`
  with `getCode() == 0` (wrapped transport failure) → `NETWORK`. Mock `authenticatedApiClient()` to
  throw `AuthException` → `NOAUTH`.
- **Unit — `--recipients` isolation:** `getEnvelope` ok + `listRecipients` throws → envelope
  block still rendered, warning on stderr, exit `API`.
- **Rendering:** snapshot/assert both table and `--json` outputs for (a) no recipients and
  (b) with recipients, including a not-yet-sent envelope (null `sent`/`completed`).
- **Integration (optional, gated on demo credentials):** run `envelope status <realId>` and
  `--recipients` against the DocuSign demo environment; assert exit `0` and presence of the
  status field. Skipped when credentials are absent.
