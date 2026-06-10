# docusign-cli

A Java command-line tool for DocuSign eSignature workflows: persistent JWT login, listing and
filtering envelopes, envelope status, and sending PDFs with automatic detection of hidden **anchor
strings** (tiny / white-on-white text) bound to signing tabs. See
[specs/001-todo-cli-design.md](specs/001-todo-cli-design.md) for the full design.

## Tech Stack

- **Language / build**: Java 17, Maven (single module; `maven-shade-plugin` fat jar → runnable `docusign-cli`)
- **CLI**: Picocli
- **DocuSign**: eSignature Java SDK (`com.docusign:docusign-esign-java`), **JWT Grant** auth
- **PDF**: Apache PDFBox **3.x** (anchor detection: per-glyph effective font size + fill colour)
- **Crypto / JSON**: BouncyCastle (SDK's JWT RSA-key parsing), Jackson (config + token)

## Layout

- Base package: `io.github.moacyrricardo.docusign`
- **Feature packages**: `auth`, `anchor`, `send`, `envelope`; foundation lives in `cli`, `config`, `output`, `docusign`
- On-disk config/state: `~/.docusign-cli/` (`credentials`, `private.key`, `token.json`)

## Build & Run

- `mvn -q package` → `target/docusign-cli.jar` (run with `java -jar`)
- `mvn -q test` → unit tests; **no live DocuSign calls** in tests (the SDK sits behind the `ApiClientFactory` / `TokenProvider` seams)

## Catalog

- `specs/` — the decision catalog. File naming: `NNN-status-slug.md`, status `todo` / `doing` / `done`.
- **Read the relevant spec before building.** `001` is the shared design context; `002` is the
  foundation everything else stacks on, and it **owns the cross-spec contracts** (the `ExitCode`
  enum, the on-disk `Token` model, the `ApiClientFactory` / `CliContext` composition root, the
  output abstraction). When in doubt, 002 wins.
- Dependency order: `002` → `{003, 004}` → `005`; `006`/`007` any time after `003`.

## Conventions

- **No Linear / no `linearis` for this project.** The **spec number is the unit of work** — there
  are no BOL tickets here. This overrides the Linear workflow in the global `~/.claude/CLAUDE.md`;
  ignore `/spec-to-linear` and the Linear steps of `/build-spec`.
- **Per-spec flow**: `todo` spec → create branch `spec-XXX-short-desc` → rename spec to `doing` →
  build + harden → open a PR. Stop at the open PR; **never merge without review**.
- **Branch names**: `spec-XXX-short-desc` (e.g. `spec-002-foundation-scaffold`).
- **Commit subjects**: `spec-XXX Short description` (the spec number stands in for the global
  `BOL-XXX` identifier); the body explains intent when non-obvious.
- Otherwise follow the commit hygiene in the global `~/.claude/CLAUDE.md`: one logical concern per
  commit; the codebase compiles cleanly at every commit; renames isolated in their own commit;
  migrations travel with their code; every commit ends with a `Co-Authored-By:` trailer naming the
  model that did the work.
- **Spec status is changed by renaming the file.** The final commit on a branch flips the spec to
  `done`, records the branch (no ticket), and summarises how the implementation diverged from the
  spec.
