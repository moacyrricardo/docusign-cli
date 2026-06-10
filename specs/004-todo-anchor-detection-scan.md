# 004 — Anchor detection engine and the `scan` command

Status: **todo**

Prescriptive decision doc for the **anchor detection engine** (local PDF analysis) and the
`scan <pdf>` command. Resolves the detection portion of [001](001-todo-cli-design.md) §5.1–§5.3
into concrete classes, method signatures, and command annotations.

This engine performs **local PDF analysis only** — no DocuSign auth and no API calls. It
therefore depends **only on the foundation in [002](002-todo-foundation-scaffold.md)** (root command,
global options, output/formatting abstraction). It is consumed by two callers:

- the `scan` command defined here (display candidates);
- the `send` command in [005](005-todo-send-envelope-anchors.md), which calls `AnchorScanner.scan(...)` to drive
  anchor → tab binding. **The `AnchorScanner.scan` signature in §4 is the contract 005 depends
  on; do not change it without updating 005.**

---

## 1. Package layout

Base package `io.github.moacyrricardo.docusign`.

```
io.github.moacyrricardo.docusign.anchor
  AnchorScanner          # public entry point — the engine
  ScanOptions            # immutable config (thresholds)
  AnchorCandidate        # one detected candidate
  CandidateReason        # enum: TINY, NEAR_WHITE
  GuessedType            # enum: SIGNATURE, INITIALS, DATE, TEXT, UNKNOWN
  TypeGuesser            # string-pattern → GuessedType heuristic
  CandidateTextStripper  # PDFBox PDFTextStripper subclass (package-private)
  EncryptedPdfException   # thrown for password-protected PDFs
  ScanCommand            # the `scan` Picocli subcommand (root subcommand, registered by 002)
```

Per the 002 feature-package convention, **all** of this spec's classes live in the `anchor`
package (the `scan` command included). `AnchorScanner`, `ScanOptions`, `AnchorCandidate`, the two
enums, `TypeGuesser`, and `EncryptedPdfException` are **public** (consumed by 005);
`CandidateTextStripper` is package-private (implementation detail).

---

## 2. PDF inspection via PDFBox

### 2.1 The text stripper

`CandidateTextStripper extends org.apache.pdfbox.text.PDFTextStripper`. It overrides:

```java
@Override
protected void writeString(String text, List<TextPosition> textPositions) {
    // text          = the run's string as PDFBox assembled it
    // textPositions = per-glyph positions; used for geometry + font size
}
```

Per text run it captures:

| Field        | Source |
|--------------|--------|
| `string`     | the `text` argument (trimmed for matching; raw kept for display) |
| `page`       | `getCurrentPageNo()` (1-based, as PDFBox reports) |
| `x`, `y`     | `textPositions.get(0).getXDirAdj()` / `getYDirAdj()` (top-left of first glyph; y in PDFBox display space) |
| `fontSize`   | **effective** font size — see §2.2 |
| `color`      | non-stroking (fill) color — see §2.3 |

Each run that passes a heuristic (§3) becomes an `AnchorCandidate` accumulated in a list the
stripper exposes via `List<AnchorCandidate> getCandidates()`.

The stripper is driven in **layout-agnostic** mode but with `setSortByPosition(true)` so that
multi-column hidden text is assembled in reading order. We do **not** restrict to a page range
at the stripper level — the caller selects pages via `ScanOptions` (default: all pages) and the
stripper honors `setStartPage`/`setEndPage` accordingly.

### 2.2 Effective font size

A glyph's on-page size is `textSize * horizontalScale` derived from the text and CTM matrices,
not the raw font size in the content stream. Compute it per glyph from
`TextPosition.getFontSizeInPt()` combined with `getXScale()`:

```java
float effectiveSize = tp.getXScale(); // already folds in font size * scaling * CTM
```

Use `getXScale()` (PDFBox's "adjusted" size in display units) as the effective font size. For a
multi-glyph run, take the **maximum** glyph `getXScale()` across `textPositions` (so a run is
"tiny" only if all of its glyphs are tiny). Store this as `AnchorCandidate.fontSize`.

### 2.3 Fill color from the graphics state

`writeString` does not receive color directly. Capture it by also overriding the operator-level
hooks so the current non-stroking color is known when a run is emitted. Concretely, read the
graphics state at write time:

```java
PDColor fill = getGraphicsState().getNonStrokingColor();
float[] rgb = fill.getColorSpace().toRGB(fill.getComponents()); // 0..1 floats
```

Convert to 0–255 ints and store as `java.awt.Color` (no alpha) on the candidate. If the color
space cannot be converted to RGB (rare, e.g. certain separation/pattern color spaces), treat the
fill as **unknown** → the NEAR_WHITE heuristic does not fire (color is recorded as `null` and
that candidate can still qualify via the TINY heuristic).

> **⚠ Spike required before building.** The two computations in §2.2 (effective on-page font size —
> `getXScale()` vs `getFontSizeInPt()` × text/CTM scaling) and §2.3 (reading the non-stroking color
> from the graphics state *at the moment a run is written*) are the highest-risk details in the
> whole project and PDFBox's behavior here is easy to get subtly wrong. Validate both empirically
> against a real white-text/tiny-text PDF (PDFBox 3.x) **before** locking the `AnchorCandidate`
> shape, since 005 depends on it. The field set may change as a result of the spike.

---

## 3. Detection heuristics

A run is flagged as a candidate when **either** heuristic fires. Both thresholds are configurable
via `ScanOptions` (and exposed as `scan` flags, §5).

### 3.1 Tiny text — reason `TINY`

```
effectiveFontSize < opts.maxFontSizePt   (default 4.0f)
```

Runs with `effectiveFontSize <= 0` (degenerate/zero-size) are **skipped** (not flagged), to avoid
noise from spacing artifacts.

### 3.2 Near-white fill — reason `NEAR_WHITE`

```
color != null
&& red   >= opts.whiteThreshold
&& green >= opts.whiteThreshold
&& blue  >= opts.whiteThreshold       (default threshold 245, on the 0–255 scale)
```

Per [001](001-todo-cli-design.md) §5.1, this is the **v1 simplification**: flag near-white fill
text **regardless of the background behind it**. True background analysis (confirming the region
behind the glyphs is also white/empty) is **explicitly deferred**; this is acceptable because
most hidden anchors are white text on a white page, and the `scan` command's job is to surface
candidates for human confirmation, not to decide authoritatively.

### 3.3 Both reasons

If a run is both tiny and near-white, `reason` records the set (see model below) — both flags
set. Display prefers the most specific human phrasing (e.g. "white, 1.0pt").

### 3.4 Whitespace / empty runs

Runs whose trimmed string is empty are skipped. Leading/trailing whitespace is trimmed for the
stored `anchorString` used in matching, but candidates with no visible characters never qualify.

---

## 4. Public API — the contract for 005

```java
package io.github.moacyrricardo.docusign.anchor;

public final class AnchorScanner {

    /**
     * Scan a PDF for candidate anchor strings (tiny and/or near-white text).
     *
     * @param pdf  an existing, readable PDF file
     * @param opts detection thresholds and page selection; never null (use ScanOptions.defaults())
     * @return candidates in document order (page asc, then top-to-bottom, then left-to-right);
     *         empty list if none found — never null
     * @throws EncryptedPdfException if the PDF is password-protected / cannot be opened without a password
     * @throws java.io.IOException   if the file is missing, unreadable, or not a valid PDF
     */
    public List<AnchorCandidate> scan(File pdf, ScanOptions opts) throws IOException;
}
```

`AnchorScanner` is instantiable (no static state); callers create `new AnchorScanner()`. The
method is the **single integration point** for 005.

### 4.1 `ScanOptions`

Immutable value object built via a static factory / builder:

```java
public final class ScanOptions {
    float maxFontSizePt;   // default 4.0f   — tiny-text threshold (exclusive upper bound)
    int   whiteThreshold;  // default 245    — per-channel near-white floor (0..255 inclusive)
    Integer startPage;     // 1-based, null = first page
    Integer endPage;       // 1-based, null = last page

    public static ScanOptions defaults();
    public static Builder builder();
    // getters; Builder with maxFontSizePt(...), whiteThreshold(...), pages(start, end)
}
```

### 4.2 `AnchorCandidate`

```java
public final class AnchorCandidate {
    String       anchorString;  // trimmed run text, e.g. "_sig_363_"
    int          page;          // 1-based
    float        x;             // display-space, top-left of first glyph
    float        y;
    float        fontSize;      // effective size in pt
    java.awt.Color color;       // fill color; null if color space not RGB-convertible
    GuessedType  guessedType;   // §6 — heuristic, never authoritative
    EnumSet<CandidateReason> reason; // TINY and/or NEAR_WHITE (never empty)
    // value semantics: equals/hashCode over (anchorString, page, x, y); toString for debug
}
```

### 4.3 Enums

```java
public enum CandidateReason { TINY, NEAR_WHITE }

public enum GuessedType { SIGNATURE, INITIALS, DATE, TEXT, UNKNOWN }
```

`GuessedType` is for display and as the interactive default in 005; it is **never** treated as
authoritative — the user (or `send` parameters) decides the real tab type.

---

## 5. The `scan` command

```java
@Command(
    name = "scan",
    description = "Detect candidate DocuSign anchor strings (tiny / near-white text) in a PDF."
)
public final class ScanCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<pdf>", description = "PDF file to scan.")
    File pdf;

    @Option(names = "--max-font-size", paramLabel = "<pt>",
            description = "Flag text smaller than this (pt). Default: 4.0")
    float maxFontSize = 4.0f;

    @Option(names = "--white-threshold", paramLabel = "<0-255>",
            description = "Per-channel floor for near-white fill. Default: 245")
    int whiteThreshold = 245;

    @Option(names = "--pages", paramLabel = "<start[-end]>",
            description = "Restrict to a 1-based page range, e.g. 2 or 2-5. Default: all pages.")
    String pages;

    // --json / --output and the OutputWriter come from 002 (global options / mixin).
}
```

`call()`:

1. Resolve `ScanOptions` from the flags (parse `--pages` into start/end; reject inverted ranges
   with a usage error → `ExitCode.USAGE`).
2. `List<AnchorCandidate> candidates = new AnchorScanner().scan(pdf, opts);`
3. Render via the 002 output abstraction:
   - **Human table** (default): columns `PAGE | ANCHOR | REASON | FONT | COLOR | GUESS`, e.g.

     ```
     PAGE  ANCHOR        REASON          FONT   COLOR        GUESS
     1     _sig_363_     white, tiny     1.0pt  #FFFFFF      signature
     1     _sig_i_363_   white           1.0pt  #FFFFFF      initials
     2     _date_363_    tiny            1.0pt  #FEFEFE      date
     ```

     Column rendering uses the 002 table formatter; REASON renders the EnumSet as a short phrase.
   - **`--json`**: an array of candidate objects with fields
     `{ anchorString, page, x, y, fontSize, color (hex or null), guessedType, reasons[] }`,
     serialized through the 002 JSON output path.
4. **No candidates found:** human mode prints an informational line
   (`No candidate anchors found.`) to stderr/stdout per 002 and exits **0** (not an error —
   "scanned successfully, found nothing"); `--json` emits `[]` and exits 0.
5. **Encrypted PDF:** catch `EncryptedPdfException` → user-facing error
   (`Cannot scan password-protected PDF: <name>`) → `ExitCode.INPUT`.

Exit codes (002 §6.1): `OK` (0) success including no-candidates; `USAGE` (2) bad flags/inverted
range; `INPUT` (9) encrypted, unreadable, or non-PDF input.

---

## 6. Type guessing (`TypeGuesser`)

Static heuristic mapping the anchor string to a `GuessedType`, used only for display and as the
interactive default in 005. Match is **case-insensitive** and order-sensitive (most specific
first), per [001](001-todo-cli-design.md) §5.3:

```java
public static GuessedType guess(String anchorString) {
    String s = anchorString.toLowerCase(Locale.ROOT);
    if (s.contains("_sig_i_"))                         return INITIALS;   // initials before signature
    if (s.contains("_sig_") || s.contains("_signature_")) return SIGNATURE;
    if (s.contains("_date_"))                          return DATE;
    if (s.contains("_text_") || s.contains("_name_"))  return TEXT;
    return UNKNOWN;
}
```

`_sig_i_` **must** be tested before `_sig_` (it contains the latter as a substring). These are
conventions only; the mapping is intentionally small and extensible.

---

## 7. Edge cases

- **Encrypted / password-protected PDF:** `Loader.loadPDF` (PDFBox 3.x) throws
  `InvalidPasswordException`; `AnchorScanner` catches it and rethrows `EncryptedPdfException`.
  We do **not** prompt for a password in v1.
- **No candidates:** valid outcome — empty list, exit 0 (see §5.4). Never an error.
- **Overlapping / duplicate strings:** the same `anchorString` may legitimately appear on
  multiple pages (e.g. per-page initials) or twice on one page. Candidates are **not**
  de-duplicated by string; each occurrence is a distinct `AnchorCandidate` (distinguished by
  page + x/y). 005 is responsible for any anchor-string semantics across occurrences.
- **Multi-page:** candidates are returned in document order (page asc, then y desc in display
  space — top to bottom — then x asc). `--pages` restricts the scan; out-of-range bounds are
  clamped to `[1, pageCount]` (with the inverted-range usage error noted in §5).
- **Non-RGB fill color:** color recorded as `null`; NEAR_WHITE cannot fire, but TINY still can
  (§2.3).
- **Corrupt / non-PDF input:** surfaces as `IOException` from the loader; mapped to `ExitCode.INPUT`.
- **Zero/negative effective size:** skipped (§3.1).

---

## 8. Testing notes

- **Fixture PDFs:** generate with PDFBox itself in a test helper so fixtures are reproducible and
  checked in (or built on the fly in `@BeforeAll`):
  - *tiny text:* `PDPageContentStream.setFont(font, 1f)` then `showText("_sig_363_")` at a known
    position; assert one `TINY` candidate with `fontSize ≈ 1.0` and the expected string/page.
  - *near-white text:* `setNonStrokingColor(new Color(255,255,255))` (or `254,254,254` to exercise
    the threshold) at a normal font size; assert one `NEAR_WHITE` candidate with `color` near white.
  - *both:* tiny **and** white in the same run; assert `reason == {TINY, NEAR_WHITE}`.
  - *negative control:* normal-size black text (`12pt`, `Color.BLACK`); assert **no** candidate.
- **Multi-page fixture:** strings on pages 1 and 3; assert ordering and that `--pages 3` (via
  `ScanOptions.pages`) returns only the page-3 candidate.
- **Type guessing:** unit-test `TypeGuesser.guess` table-style, including the `_sig_i_` vs `_sig_`
  precedence and the `UNKNOWN` fallback.
- **Encrypted fixture:** save a fixture with an owner/user password via
  `StandardProtectionPolicy`; assert `scan` throws `EncryptedPdfException` and the command maps it
  to the error exit code.
- **Color extraction:** verify `getNonStrokingColor()` → RGB conversion against a known fill,
  including a separation/pattern color space producing `color == null` without throwing.

---

## 9. Out of scope (deferred)

- True background-color analysis for white-on-white (001 §5.1, decision §7.4) — v1 flags near-white
  fill regardless of background.
- Password-protected PDF support (no password prompt in v1).
- Stroke-color-only hidden text (we inspect fill / non-stroking color only).
- Any DocuSign auth/API or tab construction — that is 005's responsibility, which consumes
  `AnchorScanner.scan` (§4).
