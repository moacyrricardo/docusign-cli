# 006 — `envelopes list`: filtered envelope listing

Status: **todo**

Prescriptive spec for the `envelopes list` command: list the impersonated user's
envelopes with date, status, subject, and document-name filters, rendered as a human table or
`--json`. See 001 §2.2, §6 for the product framing. Builds on **002** (root command,
`Config`, `ApiClient` factory, output abstraction) and **003** (`TokenProvider`). This
spec defines only the `list` subcommand; sibling envelope commands (`envelope status`,
`void`, `download`) are out of scope.

---

## 1. Command surface

### 1.1 Parent group

Introduce a Picocli parent command `envelopes` that groups envelope subcommands. It does
nothing on its own (prints usage/help when invoked bare).

```java
package io.github.moacyrricardo.docusign.envelope;   // 002 feature-package convention

@Command(
    name = "envelopes",
    description = "Work with envelopes.",
    subcommands = { EnvelopesListCommand.class },
    synopsisSubcommandLabel = "<command>")
public final class EnvelopesCommand implements Runnable {
    @Spec CommandSpec spec;
    @Override public void run() {
        throw new ParameterException(spec.commandLine(),
            "Missing required subcommand. Try 'envelopes list'.");
    }
}
```

**002** ships `EnvelopesCommand` as an empty grouper shell (`subcommands = {}`) already registered
on the root command; this spec fills it in by adding `EnvelopesListCommand` to its `subcommands`
and supplying the leaf. It lives in the shared `envelope` feature package alongside 007's
`EnvelopeCommand`/`EnvelopeStatusCommand`. As further envelope subcommands land they are added to
the `subcommands` array.

### 1.2 `envelopes list`

```java
package io.github.moacyrricardo.docusign.envelope;   // 002 feature-package convention

@Command(
    name = "list",
    description = "List envelopes, with optional date / status / subject / document-name filters.")
public final class EnvelopesListCommand implements Callable<Integer> {

    @Option(names = "--doc-name",
            description = "Keep only envelopes that have a document whose name contains "
                        + "this substring (case-insensitive). DEEP: fetches documents per "
                        + "envelope (N extra API calls). Client-side; see §3.3.")
    String docName;

    @Option(names = "--subject",
            description = "Keep only envelopes whose email subject contains this substring "
                        + "(case-insensitive). CHEAP: no extra API calls. Client-side; see §3.4.")
    String subject;

    @Option(names = "--from",
            description = "Start of the window (inclusive). ISO date 'yyyy-MM-dd' or full "
                        + "ISO-8601 instant. Defaults to 30 days ago (see §3.2).")
    String from;

    @Option(names = "--to",
            description = "End of the window (inclusive). Same formats as --from. "
                        + "Defaults to now.")
    String to;

    @Option(names = "--status",
            description = "Filter by status. One of: signed, sent, delivered, voided, "
                        + "declined, created. 'signed' maps to DocuSign 'completed'.")
    String status;

    @Option(names = "--limit", defaultValue = "100",
            description = "Maximum rows to return (default 100). See §4.")
    int limit;
}
```

Global options (`--json`, `--demo`/`--prod`, etc.) are inherited from the root command per
**002**; this command does not redefine them. The command reads `--json` via the **002**
output abstraction (it does not parse the flag itself).

### 1.3 Status mapping

`--status` accepts friendly aliases and maps to the DocuSign `status` query value. Mapping
is case-insensitive and lives in a small static map / enum `StatusAlias`:

| `--status` value      | DocuSign status |
|-----------------------|-----------------|
| `signed`, `completed` | `completed`     |
| `sent`                | `sent`          |
| `delivered`           | `delivered`     |
| `voided`              | `voided`        |
| `declined`            | `declined`      |
| `created`, `draft`    | `created`       |

An unrecognized value is a usage error (see §6). When `--status` is omitted, no status
filter is applied (all statuses in the window are returned).

---

## 2. Dependencies and wiring

- **003 `TokenProvider`** yields a valid access token (minting/refreshing transparently).
  The command never touches OAuth directly.
- **002 `ApiClient` factory** builds a configured `com.docusign.esign.client.ApiClient`
  (base path + bearer token from `TokenProvider`).
- **002 `Config`** supplies the `accountId` (the impersonated user's account GUID).
- **002 output abstraction** renders rows as a table or JSON.

```java
EnvelopesApi envelopesApi = new EnvelopesApi(ctx.authenticatedApiClient());  // 002 §3.3 (+003)
String accountId = config.accountId();
```

---

## 3. DocuSign API usage

### 3.1 Call

Use `EnvelopesApi.listStatusChanges(accountId, options)` with a
`com.docusign.esign.api.EnvelopesApi.ListStatusChangesOptions` instance. The result is an
`EnvelopesInformation` with `getEnvelopes()` (a `List<Envelope>`) plus paging metadata
(`getResultSetSize`, `getTotalSetSize`, `getNextUri`).

### 3.2 The required `from_date` window

`listStatusChanges` **requires** `from_date` (the API rejects the call without it). We
therefore always set it:

- If `--from` is given, parse it (§6) and use it.
- If `--from` is omitted, default to **now minus 30 days**. Rationale: a bounded default
  keeps the first call fast and cheap, matches the most common "what did I send recently?"
  question, and avoids an unbounded scan of account history. The chosen default window is
  printed to stderr in non-`--json` mode (e.g. `Listing envelopes since 2026-05-10
  (default 30-day window; use --from to widen).`) so the user understands why older
  envelopes are absent.
- `--to` maps to `to_date`; defaults to now (the API treats an absent `to_date` as now, so
  we only set it when `--to` is supplied).

Dates are sent to the API as ISO-8601. A date-only input (`yyyy-MM-dd`) is interpreted at
start-of-day (`--from`) / end-of-day (`--to`) in the system default zone, then converted to
an instant.

```java
ListStatusChangesOptions opts = envelopesApi.new ListStatusChangesOptions();
opts.setFromDate(fromInstant.toString());            // required
if (toArg != null) opts.setToDate(toInstant.toString());
if (dsStatus != null) opts.setStatus(dsStatus);      // mapped value, §1.3
opts.setCount(String.valueOf(pageSize));             // §4
opts.setInclude("recipients");                       // for the recipient summary, §5
```

`setInclude("recipients")` asks DocuSign to embed recipient info on each `Envelope`, so the
recipient-summary column needs no extra per-envelope call.

### 3.3 `--doc-name` filtering (deep, per-envelope document fetch)

`listStatusChanges` exposes no document-name query parameter, and the returned `Envelope`
objects do **not** carry their document list — so `--doc-name` matching is necessarily
client-side and requires fetching each envelope's documents:

- Fetch one page (§4) of envelopes for the window/status.
- For each envelope on the page, lazily call `EnvelopesApi.listDocuments(accountId, envelopeId)`
  and keep the envelope iff any document name contains `--doc-name` (case-insensitive). This is
  **N extra API calls** (one per envelope examined) — expensive and rate-limit-prone on wide
  windows. Stop once `--limit` matches are collected to cap the number of `listDocuments` calls.
- Emit a one-line stderr note in non-`--json` mode when `--doc-name` is active (e.g. `--doc-name
  fetches documents per envelope; narrow --from/--to to reduce API calls.`).

### 3.4 `--subject` filtering (cheap, no extra calls)

`--subject` substring-matches the envelope's `emailSubject` (already present on each `Envelope`
from the single `listStatusChanges` call), case-insensitively. **No extra API calls.** This is the
cheap counterpart to `--doc-name` for the common "what was that envelope about?" question.

### 3.5 Combining filters

`--doc-name` and `--subject` are **independent and combinable** — when both are given an envelope
is kept only if it matches **both** (logical AND). Apply the cheap `--subject` predicate **first**
(it prunes the page with no API cost), then run the deep `--doc-name` `listDocuments` fetch only on
the survivors — minimizing per-envelope calls. All matching is purely client-side; DocuSign
performs no name/subject filtering. `--from`/`--to`/`--status` still constrain the server-side
query (§3.1–§3.2) before either client-side filter runs.

---

## 4. Pagination and result limits

- Set `opts.setCount(String.valueOf(pageSize))` where `pageSize = min(limit, 100)`
  (DocuSign caps a page; 100 is a safe page size).
- `--limit` (default 100) is the total number of **rows the command returns**. If the user
  raises `--limit` above a single page, follow paging:
  - After each call, read `EnvelopesInformation.getResultSetSize()` and `getNextUri()`.
  - If `getNextUri()` is non-empty and we still need more rows, set
    `opts.setStartPosition(...)` (parse the `start_position` from `nextUri`, or advance by
    the running count) and call again, accumulating until we have `limit` rows or paging is
    exhausted.
- When `--doc-name` is active, paging continues until `limit` *matches* are collected or the
  window is exhausted (since name-matching prunes rows after the API returns them).
- Surface a truncation hint: in table mode, if more results exist than were shown (returned
  rows `== limit` and `getTotalSetSize() > limit`), print a stderr footer:
  `Showing first <limit> of <total>; raise --limit or narrow the date range.` In `--json`
  mode, no stderr decoration is added (machine output stays clean).

---

## 5. Output

Each returned `Envelope` becomes one row. Columns (table mode, in order):

| Column        | Source                                                            |
|---------------|------------------------------------------------------------------|
| ENVELOPE ID   | `Envelope.getEnvelopeId()`                                        |
| SUBJECT       | `Envelope.getEmailSubject()` (truncated to fit; full in JSON)    |
| STATUS        | `Envelope.getStatus()`                                            |
| SENT          | `Envelope.getSentDateTime()` (formatted local date-time)         |
| LAST MODIFIED | `Envelope.getLastModifiedDateTime()` (fallback to status changed)|
| RECIPIENTS    | recipient summary (see below)                                    |

**Recipient summary:** from the embedded recipients (`opts.setInclude("recipients")`),
build a compact string of signer name/email + per-recipient status, e.g.
`Moacyr <moacyr.ricardo@gmail.com>: completed`. With multiple recipients, join the first
two with `; ` and append `(+N more)`. If recipients are absent, show `—`.

Dates are formatted to a stable, human-friendly local representation (e.g.
`yyyy-MM-dd HH:mm`); null dates render as `—`.

**JSON mode (`--json`, via 002):** emit an array of objects with the unabridged fields:
`envelopeId`, `emailSubject`, `status`, `sentDateTime`, `lastModifiedDateTime`,
`completedDateTime` (when present), and `recipients` (array of
`{name, email, recipientType, status, routingOrder}`). JSON carries full values (no
truncation) and raw ISO-8601 timestamps. Build a small row DTO
(`EnvelopeRow` / `RecipientSummary`) that both renderers consume, so table and JSON stay in
sync.

---

## 6. Edge cases

- **No results:** table mode prints `No envelopes found.` to stderr and emits an empty table
  (header only) or nothing to stdout; `--json` emits `[]`. Exit code `0`.
- **Invalid `--status`:** Picocli/`StatusAlias` lookup fails → throw
  `ParameterException` listing the accepted values; `ExitCode.USAGE`.
- **Invalid date format** (`--from`/`--to` not parseable as `yyyy-MM-dd` or ISO-8601):
  throw `ParameterException` with the accepted formats; `ExitCode.USAGE`. Also reject
  `--from` later than `--to`.
- **Unauthenticated / token failure:** delegated entirely to **003** via
  `ctx.authenticatedApiClient()`. If no token can be obtained (no consent, bad key) it throws
  `AuthException`; the command lets it propagate to the root error handler (**002**), which prints
  `Not authenticated; run 'docusign-cli login'.` and exits `ExitCode.NOAUTH`. This command adds no
  auth logic.
- **DocuSign API errors** (`com.docusign.esign.client.ApiException`): catch, map to a concise
  stderr message including the HTTP status and DocuSign error body → `ExitCode.API`. The SDK's REST
  calls go through Jersey's `invokeAPI`, which throws **only** `ApiException` (no bare
  `IOException`): a transport failure has no HTTP response and arrives as an `ApiException` with
  `getCode() == 0` — map those to `ExitCode.NETWORK`. Rate-limit (HTTP 429) during `--doc-name`
  document fetches is reported with guidance to narrow the window. Do not leak stack traces unless a
  global `--verbose`/debug flag (002) is set.

Exit codes (002 §6.1): `OK` (0) success incl. empty; `USAGE` (2) bad `--status`/date; `NOAUTH`
(4) not authenticated; `API` (7) / `NETWORK` (8) DocuSign/transport errors.

---

## 7. Testing notes

- **Unit — status mapping:** `StatusAlias` maps every friendly value (and is
  case-insensitive); unknown value raises. Table-drive it.
- **Unit — date parsing/defaults:** `yyyy-MM-dd` and ISO-8601 parse correctly; omitted
  `--from` yields now-minus-30-days; `--from > --to` rejected; date-only `--from`/`--to`
  resolve to start/end of day.
- **Unit — row mapping:** given a stubbed `Envelope` (with/without embedded recipients),
  `EnvelopeRow` carries the right fields; recipient summary handles 0/1/2/3+ recipients and
  null dates (`—`).
- **Unit — pagination accumulation:** with a mocked `EnvelopesApi` returning multiple pages
  (via `nextUri`/`startPosition`), the loop accumulates exactly `--limit` rows and stops;
  truncation footer logic triggers only when `totalSetSize > limit`.
- **Unit — `--doc-name`:** mock `listDocuments` and assert client-side substring matching,
  case-insensitivity, and that fetching stops once `limit` matches are collected.
- **Unit — `--subject`:** substring + case-insensitive match on `emailSubject` with **no**
  `listDocuments` calls (assert zero interactions on the documents API).
- **Unit — combined `--doc-name` + `--subject`:** AND semantics; the cheap subject predicate runs
  first so `listDocuments` is invoked only for subject-surviving envelopes (assert call count).
- **Integration (optional, demo account):** run `envelopes list --from <date>` against the
  DocuSign demo environment and assert non-error exit + parseable `--json`. Gate behind an
  env-var so CI without credentials skips it.
- Mock `EnvelopesApi` and **003 `TokenProvider`** (no live calls in unit tests).
