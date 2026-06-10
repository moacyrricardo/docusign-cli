# docusign-cli

A command-line tool for DocuSign eSignature workflows: persistent JWT login, listing and filtering
envelopes, checking envelope status, and **sending PDFs with automatic anchor detection** — it finds
hidden anchor strings (tiny or white-on-white text) in a document and binds them to signing tabs.

## Build

Requires JDK 17+ and Maven.

```bash
mvn package
```

This produces a runnable fat jar at `target/docusign-cli.jar`. The examples below use `docusign-cli`
as a stand-in for `java -jar target/docusign-cli.jar` (drop a wrapper script with that line on your
`PATH` if you like).

```bash
docusign-cli --help        # global help + command list
docusign-cli --version     # version
```

## Setup

The CLI authenticates with DocuSign using **JWT Grant** — you consent once, then it mints tokens
headlessly. One-time setup:

1. In the DocuSign admin console, create an app (integration key).
2. Generate an RSA keypair; upload the **public** key to the app; save the **private** key to
   `~/.docusign-cli/private.key`.
3. Register the redirect URI `https://www.docusign.com` on the app (used only to satisfy the consent
   URL; the JWT flow never calls back).
4. Create `~/.docusign-cli/credentials` (a `key=value` properties file):

   ```properties
   integration_key  = <your integration key / client id>
   user_id          = <the impersonated user's API GUID>
   private_key_path = ~/.docusign-cli/private.key   # optional; this is the default
   redirect_uri     = https://www.docusign.com      # optional; this is the default
   ```

   `account_id` and `base_uri` are filled in automatically by `login`.

5. Run the one-time login and grant consent:

   ```bash
   docusign-cli login
   ```

   On first run it prints a consent URL — open it, click **Allow**, then re-run `login`. After that,
   commands run without prompting; the access token is cached in `~/.docusign-cli/token.json` and
   re-minted silently when it expires.

### Demo vs. production

DocuSign runs two separate environments, selected with `--demo` (the default) or `--prod`:

- **`--demo`** — the developer sandbox (`account-d.docusign.com`). Free, **test-only**, envelopes are
  watermarked and **not legally binding**. Use this while building.
- **`--prod`** — production (`account.docusign.com`). **Real, legally-binding** signatures; requires a
  paid account and a one-time "go-live" promotion of your integration key.

The two are independent — a demo token won't work against prod. Log in separately per environment
(`docusign-cli login --prod` once your app is promoted). The flag defaults to **demo** so you can't
accidentally send a real binding envelope.

## Commands

### `login` / `auth status`

```bash
docusign-cli login                # JWT consent (one-time) + cache a token
docusign-cli auth status          # show account, base URI, and token validity
```

### `scan <pdf>` — find hidden anchors

Detects candidate anchor strings (very small text, or near-white-on-white text) without contacting
DocuSign. Use it to discover which anchors a document contains before sending.

```bash
docusign-cli scan invoice.pdf
docusign-cli scan invoice.pdf --max-font-size 4 --white-threshold 245 --pages 1-2
```

Options: `--max-font-size <pt>` (flag text smaller than this; default 4.0), `--white-threshold
<0-255>` (per-channel near-white floor; default 245), `--pages <start[-end]>` (default: all pages).

### `send <pdf>` — send for signature with anchor-positioned tabs

Two modes.

**Mode A — scriptable.** Bind anchors explicitly with positional `anchor=type:recipient` arguments
(`type:` is optional and defaults to `signature`):

```bash
docusign-cli send agreement.pdf \
  --subject "Please sign the Q2 agreement" \
  --recipient "Moacyr=moacyr.ricardo@gmail.com" \
  _sig_363_=moacyr.ricardo@gmail.com \
  _sig_i_363_=initials:moacyr.ricardo@gmail.com \
  _date_363_=date:moacyr.ricardo@gmail.com
```

- `--recipient "Name=email"` declares a signer (repeatable).
- Anchor types: `signature`, `initials`, `date`, `text`. The recipient is referenced by email or by
  the declared name. `_sig_363_=moacyr.ricardo@gmail.com` is shorthand for
  `signature:moacyr.ricardo@gmail.com`.

**Mode B — interactive.** Let the tool scan the PDF and confirm each candidate:

```bash
docusign-cli send agreement.pdf --subject "Please sign" \
  --recipient "Moacyr=moacyr.ricardo@gmail.com" --interactive
```

A pre-send summary is shown and confirmed unless you pass `--yes`.

### `envelopes list` — list and filter envelopes

```bash
docusign-cli envelopes list
docusign-cli envelopes list --status signed --from 2026-05-01 --to 2026-06-01
docusign-cli envelopes list --subject invoice            # cheap: matches email subject
docusign-cli envelopes list --doc-name contract.pdf      # deep: matches document filenames
docusign-cli envelopes list --subject invoice --doc-name contract --limit 50
```

- `--from` / `--to` — `yyyy-MM-dd` or ISO-8601; `--from` defaults to 30 days ago.
- `--status` — `signed` (= completed), `sent`, `delivered`, `voided`, `declined`, `created`.
- `--subject <substr>` — substring-match the email subject (cheap; no extra API calls).
- `--doc-name <substr>` — substring-match document filenames (**deep**: one extra API call per
  envelope examined). Combine with `--subject` (AND).
- `--limit <n>` — max rows (default 100).

### `envelope status <id>`

```bash
docusign-cli envelope status a1b2c3d4-....
docusign-cli envelope status a1b2c3d4-.... --recipients     # include per-recipient status
```

## Global options

Available on every command (before or after the subcommand name):

| Option | Meaning |
|--------|---------|
| `--json` | Emit machine-readable JSON instead of the human table |
| `--output <file>` | Write primary output to a file instead of stdout |
| `--yes`, `-y` | Assume "yes" for confirmation prompts (automation) |
| `--demo` / `--prod` | Select the DocuSign environment (default: demo) |
| `--verbose`, `-v` | Print full stack traces on unexpected errors |
| `--help`, `-h` | Show help for that command |

## Exit codes

Scripts can branch on the exit code:

| Code | Meaning | Code | Meaning |
|------|---------|------|---------|
| `0` | success | `7` | DocuSign API error |
| `2` | usage / bad arguments | `8` | network / transport failure |
| `3` | missing/invalid config or key | `9` | bad input (unreadable PDF, etc.) |
| `4` | not authenticated — run `login` | `70` | unexpected internal error |
| `5` | consent required (`login` only) | | |
| `6` | envelope not found | | |
