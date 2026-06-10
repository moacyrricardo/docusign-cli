# 005 — `send`: send a PDF as an envelope with anchor-positioned tabs

Status: **todo**

The `send` command takes a PDF, a set of declared recipients, and a set of anchor→tab
bindings, then builds and sends a DocuSign envelope whose signing tabs are positioned by
**anchor strings** embedded in the PDF. Two modes: **Mode A** (scan-then-parametrize,
scriptable, primary) and **Mode B** (`--interactive`).

This spec depends on:
- **002** (foundation): root command, `Config` (account ID, base path, `--demo`/`--prod`),
  `ApiClient` factory, output helpers (table + `--json`), the global `--yes` flag.
- **003** (auth): `TokenProvider` yielding a valid access token (mint/refresh transparent).
- **004** (scanning): `AnchorScanner.scan(File pdf, ScanOptions opts) -> List<AnchorCandidate>`,
  where `AnchorCandidate` carries `anchorString`, `page`, `x`, `y`, `fontSize`, `color`,
  `guessedType`.

It implements decisions from **001** §5 (anchor scanning), §5.3 (anchor→tab mapping), §5.4
(recipients: signers only, no order, no CC) and §7 (hardcoded placement defaults; no
x/y/unit flags in v1).

---

## 1. Command surface

Package: `io.github.moacyrricardo.docusign.send`.

```
docusign-cli send <pdf>
    --subject <text>
    --recipient "Name=email"        (repeatable)
    [anchorString=type:recipient ...]   (positional, repeatable)
    [--interactive]
    [--yes]
    [--json]                         (inherited from 002 root)
```

Picocli command class:

```java
@Command(
    name = "send",
    description = "Send a PDF as an envelope with anchor-positioned tabs.")
public class SendCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<pdf>",
        description = "Path to the PDF to send.")
    Path pdf;

    @Option(names = "--subject", required = true,
        description = "Email subject for the envelope.")
    String subject;

    @Option(names = "--recipient", paramLabel = "Name=email", required = true,
        description = "Declare a signer. Repeatable.")
    List<String> recipientSpecs = new ArrayList<>();

    @Parameters(index = "1..*", arity = "0..*", paramLabel = "anchor=type:recipient",
        description = "Anchor→tab bindings (Mode A). Repeatable.")
    List<String> anchorSpecs = new ArrayList<>();

    @Option(names = "--interactive",
        description = "Mode B: scan the PDF and confirm each candidate interactively.")
    boolean interactive;

    // --yes and --json are inherited from the 002 root command (mixin / @ParentCommand).
}
```

Notes:
- `--subject` and at least one `--recipient` are **required** in both modes.
- Positional `anchorSpecs` are **required in Mode A**, **forbidden in Mode B** (see §7 edge cases).
- v1 has **no** `--cc`, **no** signing-order flag, **no** `--anchor-x/-y/-units` (001 §7).

---

## 2. Recipient parsing

`RecipientSpecParser` parses each `--recipient "Name=email"` into a `DeclaredRecipient`.

```java
public record DeclaredRecipient(String name, String email) {}
```

Grammar: `Name=email`. Split on the **first** `=`. Both sides trimmed and non-empty.
The `email` is validated to contain `@` (light validation only — DocuSign is authoritative).

Recipients are collected into a registry keyed for anchor lookup:

```java
public final class RecipientRegistry {
    // Lookup is by email (case-insensitive) OR by declared name (the "key").
    DeclaredRecipient resolve(String emailOrKey);   // null if not found
    List<DeclaredRecipient> all();
}
```

Anchor params reference a recipient by **email** or by the declared **name key** (001 §5.4).
Each declared recipient becomes exactly one DocuSign `Signer`; `recipientId` is assigned
sequentially (`"1"`, `"2"`, …) in declaration order. No `routingOrder` is set (no order in v1).

Errors:
- Malformed spec (no `=`, empty side) → `ParameterException`: `Invalid --recipient "<spec>": expected "Name=email"`.
- Duplicate email → `ParameterException`: `Duplicate recipient email: <email>`.

---

## 3. Anchor spec grammar + parser

### 3.1 Grammar

```
<anchorSpec> ::= <anchorString> "=" [ <type> ":" ] <recipientEmailOrKey>
<type>       ::= "signature" | "initials" | "date" | "text"
```

- Split on the **first** `=` → `anchorString` (left) and `rhs` (right).
- If `rhs` contains a `:`, split on the **first** `:` → `typeToken` and `recipientRef`;
  otherwise `typeToken` is absent and defaults to `signature`.
- `anchorString` is taken **verbatim** (it is the literal embedded marker, e.g. `_sig_363_`);
  it is not trimmed of underscores or otherwise normalized.

Examples:
- `_sig_363_=moacyr.ricardo@gmail.com` → type `signature`, recipient by email.
- `_sig_i_363_=initials:moacyr.ricardo@gmail.com` → type `initials`.
- `_date_363_=date:Moacyr` → type `date`, recipient by declared key `Moacyr`.

### 3.2 Parser

```java
public record AnchorSpec(String anchorString, TabType type, String recipientRef) {}

public final class AnchorSpecParser {
    AnchorSpec parse(String raw);   // throws AnchorSpecException on malformed input
}
```

`TabType` enum: `SIGNATURE`, `INITIALS`, `DATE`, `TEXT`. Parsed case-insensitively from the
token; the extension path (fullname/company/title/checkbox per 001 §5.3) adds enum values +
mapping cases only.

### 3.3 Validation (against declared recipients)

After parsing all specs, a `SendPlanBuilder` validates each `AnchorSpec`:

1. **Unknown type** — `typeToken` not in `TabType` → error:
   `Unknown tab type "<token>" in "<spec>". Supported: signature, initials, date, text.`
2. **Unknown recipient** — `RecipientRegistry.resolve(recipientRef)` is null → error:
   `Anchor "<anchorString>" references undeclared recipient "<recipientRef>". Declare it with --recipient.`
3. **Malformed** — no `=`, empty `anchorString`, or empty `recipientRef` → error:
   `Invalid anchor spec "<spec>": expected anchorString=[type:]recipient.`

All three surface as a `picocli.CommandLine.ParameterException` (`ExitCode.USAGE`). Validation is
**eager**: all specs are parsed/validated before any network call; errors are accumulated and
reported together where practical.

---

## 4. Type → DocuSign tab mapping

v1 supports four types. Each maps to a DocuSign tab class from the eSignature Java SDK
(`com.docusign.esign.model.*`), using the anchor string as `anchorString` with **hardcoded
default offsets/units** (001 §7 — no x/y/unit flags in v1):

| `TabType`   | DocuSign tab class | Notes |
|-------------|--------------------|-------|
| `SIGNATURE` | `SignHere`         | signer's signature |
| `INITIALS`  | `InitialHere`      | signer's initials |
| `DATE`      | `DateSigned`       | auto-filled signing date |
| `TEXT`      | `Text`             | free-text field |

Defaults applied to every tab (`TabDefaults` constants):

```java
anchorUnits           = "pixels";
anchorXOffset         = "0";
anchorYOffset         = "0";
anchorIgnoreIfNotPresent = "false";   // missing anchor → DocuSign errors (we surface it)
// Text tabs only:
required              = "true";
```

`TabFactory.build(AnchorSpec spec)` returns the populated tab object (one of the four classes
above). A `TabSet` accumulates tabs **per recipient** (the `Tabs` model has `signHereTabs`,
`initialHereTabs`, `dateSignedTabs`, `textTabs` lists); `TabFactory.applyTo(Tabs tabs, ...)`
appends each built tab into the correct list on that signer's `Tabs`.

Extension path: add an enum constant + a `case` in `TabFactory` + the corresponding `Tabs`
list; no other code changes.

---

## 5. Mode A — scan-then-parametrize (primary, scriptable)

1. Parse `--recipient` specs → `RecipientRegistry`.
2. Parse positional `anchorSpecs` → `List<AnchorSpec>`, validate per §3.3.
3. **Optional verification scan:** call `AnchorScanner.scan(pdf, ScanOptions.defaults())`
   (004). Collect the set of detected `anchorString`s. For each `AnchorSpec` whose
   `anchorString` is **not** found in the scan, print a **warning** (not an error) to stderr:
   `warning: anchor "<anchorString>" not found in <pdf>; DocuSign will reject placement if absent.`
   The scan never blocks the send in Mode A — the user may know the anchor exists (e.g. font
   tricks the scanner missed). If scanning itself fails (e.g. unreadable PDF), log a warning
   and proceed; the document parse error will surface from DocuSign if real.
4. Build the `SendPlan` (recipients + their tabs) and proceed to §7 (build + send).

---

## 6. Mode B — interactive

Triggered by `--interactive`. Positional `anchorSpecs` are forbidden (§8 edge cases).

1. Parse `--recipient` specs → `RecipientRegistry`.
2. Call `AnchorScanner.scan(pdf, ScanOptions.defaults())` (004) → `List<AnchorCandidate>`.
3. If the list is empty, report it (see §8) and abort with a clear message.
4. For each `AnchorCandidate`, prompt via an `InteractivePrompter` (reads stdin):
   - `Found "<anchorString>" (<color>, <fontSize>pt) on page <page>. Is this an anchor? [Y/n]`
     — default **Y**. `n` skips this candidate.
   - `Type? [<guessedType>]/signature/initials/date/text:` — Enter accepts `guessedType`
     (mapped onto the nearest supported `TabType`; if `guessedType` is unsupported in v1, the
     default shown is `signature`). Input validated against `TabType`; re-prompt on bad input.
   - `Recipient (email or name):` — validated against `RecipientRegistry`; re-prompt if
     unknown. (Single declared recipient → offered as default.)
5. Each accepted answer becomes an `AnchorSpec`; build the `SendPlan` and proceed to §7.

`InteractivePrompter` is an interface so tests can inject scripted answers. With `--yes`,
prompts that have a default are auto-accepted at their default **except** the per-candidate
"Is this an anchor?" and recipient choice, which still require an answer (there is no safe
default for *which* recipient signs) — i.e. `--yes` only suppresses the final send
confirmation (§7), not the interactive field prompts.

---

## 7. Envelope build + send

`EnvelopeSender.send(SendPlan plan)`:

1. **Document:** read the PDF bytes, base64-encode, build a `Document`:
   ```java
   Document doc = new Document();
   doc.setDocumentBase64(Base64.getEncoder().encodeToString(pdfBytes));
   doc.setName(pdf.getFileName().toString());
   doc.setFileExtension("pdf");
   doc.setDocumentId("1");
   ```
2. **Signers:** for each `DeclaredRecipient`, build a `Signer` with `recipientId` (sequential),
   `name`, `email`, and the accumulated `Tabs` (from §4). Set on `Recipients.setSigners(...)`.
   No `routingOrder` (no signing order in v1). No CC recipients.
3. **Envelope:**
   ```java
   EnvelopeDefinition env = new EnvelopeDefinition();
   env.setEmailSubject(subject);
   env.setDocuments(List.of(doc));
   env.setRecipients(recipients);
   env.setStatus("sent");          // "sent" = send immediately; "created" would be a draft
   ```
4. **Pre-send confirmation** (skipped with `--yes`): print a summary (recipients, tab counts
   per recipient, document name, subject) and prompt `Send envelope? [y/N]`. Default **N**.
   Declining aborts with `ExitCode.OK` and message `Aborted; nothing sent.`
5. **API call:** obtain the client via `ctx.authenticatedApiClient()` (002 §3.3 — it applies the
   003 token and the account REST base path; never build an `ApiClient` here):
   ```java
   EnvelopesApi api = new EnvelopesApi(ctx.authenticatedApiClient());
   EnvelopeSummary summary = api.createEnvelope(config.accountId(), env);
   ```
6. **Output:** print `envelopeId` and `status` from the returned `EnvelopeSummary`.
   - Default: a table via the 002 output helper:
     ```
     ENVELOPE ID                           STATUS
     a1b2c3d4-....                          sent
     ```
   - `--json`: `{"envelopeId":"...","status":"sent"}`.

`ExitCode.OK` (0) on success. DocuSign `ApiException` is caught and rendered as a clean error
(status code + DocuSign error body) → `ExitCode.API` (002 §6.1); a not-authenticated error from
`authenticatedApiClient()` propagates to the root handler as `ExitCode.NOAUTH`.

---

## 8. Edge cases

Exit codes are 002's `ExitCode` (§6.1).

| Case | Behavior |
|------|----------|
| **No recipients** (`--recipient` absent) | Picocli `required=true` rejects before run: `ExitCode.USAGE`. |
| **Anchor references undeclared recipient** | §3.3 rule 2 error, `ExitCode.USAGE`, before any network call. |
| **No anchors at all** (Mode A: no positional specs) | Error: `No anchor bindings given. Provide anchorString=type:recipient args, or use --interactive.` `ExitCode.USAGE`. |
| **No anchors detected** (Mode B: scan returns empty) | Message: `No anchor candidates detected in <pdf>. Nothing to place.` `ExitCode.INPUT`; nothing sent. |
| **PDF not found / unreadable** | Validate `Files.isReadable(pdf)` up front → error `PDF not found or unreadable: <pdf>`, `ExitCode.INPUT`. |
| **Mode A params + `--interactive`** | Mutually exclusive: error `Cannot combine positional anchor args with --interactive.` `ExitCode.USAGE`. Enforced via an explicit check in `call()` (Picocli `ArgGroup` is awkward across a positional list + flag, so check manually). |
| **Unknown tab type** | §3.3 rule 1, `ExitCode.USAGE`. |
| **Subject missing** | Picocli `required=true`, `ExitCode.USAGE`. |
| **Send declined at confirmation** | `ExitCode.OK`, `Aborted; nothing sent.` |

---

## 9. Testing notes

- **AnchorSpecParser:** table-driven tests for default type, explicit type, name-key vs email
  recipient, malformed (no `=`, empty sides), unknown type, anchor strings containing extra
  `:` or `=` (first-split semantics).
- **RecipientSpecParser / RecipientRegistry:** name-vs-email resolution (case-insensitive
  email), duplicate detection, malformed specs.
- **TabFactory:** each `TabType` produces the correct SDK tab class with the hardcoded
  defaults and the right `anchorString`; tabs land in the correct per-recipient `Tabs` list.
- **SendPlanBuilder validation:** undeclared-recipient and unknown-type errors accumulate and
  surface together.
- **EnvelopeSender:** mock `EnvelopesApi` (`createEnvelope`); assert the `EnvelopeDefinition`
  has `status="sent"`, one base64 document, signers with sequential `recipientId` and the
  expected tabs; assert output rendering for table and `--json`. Assert `--yes` skips the
  confirmation prompt and the un-confirmed default aborts.
- **Mode B:** inject a scripted `InteractivePrompter` + a stub `AnchorScanner` returning fixed
  candidates; assert the resulting `SendPlan` matches the scripted answers, and that empty
  scan results abort cleanly.
- **Edge cases:** Mode A + `--interactive` rejection; missing PDF; no-anchors-in-Mode-A.

Network/SDK calls are isolated behind the 002 `ApiClient` factory and the `EnvelopesApi`
seam so unit tests need no live DocuSign account.
