# 008 — CLI help, Javadoc coverage, and README usage docs

Status: **todo** (spec — decisions made; quality/polish pass to run after the 002–007 stack)

A documentation/usability pass. The 002–007 stack produces a working CLI (144 tests green), but
running the built jar surfaced help/usability and documentation gaps that none of the functional
specs covered. This spec closes them. **No new functional behavior** — only help wiring, Javadoc,
and the README.

Depends on **002** (`cli` package: `GlobalOptions`, `RootCommand`). Touches all packages for
Javadoc; adds the project `README.md`.

---

## Context

A manual pass over `target/docusign-cli.jar` found:

1. **Subcommands have no `--help`.** Only `RootCommand` sets `mixinStandardHelpOptions = true`. The
   subcommands mix in `GlobalOptions` (`--json/--output/--yes/--verbose/--demo/--prod`) but no help
   option, so:
   - `docusign-cli send --help` → `Missing required parameter: '<pdf>'` (exit 2)
   - `docusign-cli envelopes list --help` → `Unknown option: '--help'` (exit 2)
   Users cannot get per-command help.
2. **`--demo`/`--prod` render twice** in every command's help (root and subcommands) — the exclusive
   `@ArgGroup EnvironmentFlags` in `GlobalOptions` is listed twice in the options block.
3. **Javadoc coverage is thin at the method level** — ~85% of public/protected types carry a doc
   comment, but only ~48% of public/protected methods do (weakest: `envelope` 9/37, `auth` 6/15,
   `output` 6/18).
4. **The README is a one-line stub** — no install/build/setup/usage instructions.

These are foundation-level (1, 2 live in 002's `cli` package) plus a cross-cutting Javadoc sweep
and a new README.

---

## Decision

1. **Give every subcommand `--help` via the shared mixin.** Add a `usageHelp` `-h`/`--help` option to
   `GlobalOptions` (which every command already mixes in). Stop relying on `RootCommand`'s
   `mixinStandardHelpOptions` for help.
2. **Keep `-V`/`--version` on the root only.** Because `mixinStandardHelpOptions` also adds `-h`,
   leaving it on while adding `-h` to `GlobalOptions` would duplicate help on the root. So remove
   `mixinStandardHelpOptions` from `RootCommand` and add an explicit `versionHelp` `-V`/`--version`
   option there (version is a CLI-wide concept, not per-subcommand).
3. **Make `--demo`/`--prod` appear once.** Fix the `EnvironmentFlags` `@ArgGroup` so each flag is
   rendered a single time, and lock it with a help-rendering test.
4. **Raise Javadoc to a consistent bar** for the public API; document the README.
5. **Write real CLI usage docs into `README.md`** — the primary user-facing deliverable.

---

## Implementation

### 1–3. Help wiring (002 `cli` package)

`GlobalOptions` — add:

```java
@Option(names = {"-h", "--help"}, usageHelp = true,
        description = "Show this help message and exit.")
public boolean help;
```

`RootCommand`:
- Remove `mixinStandardHelpOptions = true`.
- Add an explicit version option (keep the existing `versionProvider`):
  ```java
  @Option(names = {"-V", "--version"}, versionHelp = true,
          description = "Print version information and exit.")
  boolean versionRequested;
  ```

`--demo`/`--prod` duplication: root-cause the double render of the `EnvironmentFlags` exclusive
`@ArgGroup` and correct it so each option appears once in the options list (still shown as
`[--demo | --prod]` in the synopsis). Verify empirically.

**Tests** (extend `CliWiringTest`, all in-process via `new CommandLine(...).getUsageMessage(...)`):
- every subcommand's usage contains `-h, --help`;
- `--demo` (and `--prod`) appears **exactly once** in each command's usage;
- `docusign-cli send --help` exits `0` and prints usage (not the missing-`<pdf>` error);
- root usage still contains `-V, --version`.

### 4. Javadoc sweep

Document the public API to this bar:
- **All** public/protected **types** (already ~85% — finish the rest).
- **All non-trivial** public/protected **methods** — especially the seam interfaces
  (`TokenProvider`, `OutputWriter`, `EnvelopeQuery`, `EnvelopeStatusReader`), factories
  (`ApiClientFactory`), builders (`ScanOptions.Builder`), and public service/command-helper methods.
- **May skip** boilerplate where Javadoc adds nothing: `equals`/`hashCode`/`toString` overrides,
  Picocli `call()` bodies, `Main.main`, and trivial record accessors.

Optionally (decide during build) add `maven-javadoc-plugin` configured with `<doclint>missing</doclint>`
and `failOnWarnings=false` so coverage is visible in `mvn verify` without breaking the build —
prevents regression. Do **not** gate CI on it in v1.

### 5. `README.md` — CLI usage (key deliverable)

Replace the stub with a user-facing guide:

- **What it is** — one paragraph.
- **Build** — `mvn package` → `target/docusign-cli.jar`; note the `docusign-cli` invocation used in
  examples.
- **Setup** — create a DocuSign app + integration key; generate an RSA keypair and upload the public
  half; the `~/.docusign-cli/credentials` keys (`integration_key`, `user_id`, `private_key_path`,
  `redirect_uri`); **demo vs prod** (what each means; default demo); the one-time `login` + consent.
- **Commands** — each with a runnable example:
  - `login`, `auth status`
  - `scan <pdf>` — detect hidden anchor candidates
  - `send <pdf> --subject "…" --recipient "Name=email" _sig_363_=signature:email` (Mode A) and
    `send <pdf> --interactive` (Mode B); explain the `anchor=type:recipient` grammar and the
    default-`signature` shorthand
  - `envelopes list` with `--doc-name` / `--subject` / `--from` / `--to` / `--status` / `--limit`
  - `envelope status <id> [--recipients]`
  - global flags: `--json`, `--output`, `--yes`, `--demo`/`--prod`, `-v`
- **Exit codes** — the table from 002 §6.1 (so scripts can branch).

Keep the README in sync with the actual `--help` output produced by the fixes above.

---

## Known Gaps (out of scope)

- No new functional behavior, options, or commands — purely help wiring, Javadoc, and docs.
- README is **user-facing usage only**, not contributor/architecture docs (the `specs/` catalog
  covers architecture).
- Not enforcing Javadoc coverage in CI (optional non-failing `verify` report only).
- Does not add shell completion or man-page generation (possible future spec).
