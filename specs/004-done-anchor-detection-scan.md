# 004 — Anchor detection engine and the `scan` command

Status: **done** (branch `spec-004-anchor-detection-scan`)

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

`CandidateTextStripper extends org.apache.pdfbox.text.PDFTextStripper`. Two non-obvious things are
**required** (both proven by the spike, §10) and easy to miss:

1. **Register the non-stroking colour operators.** `PDFTextStripper` does *not* process `rg/g/k/
   cs/sc/scn` by default, so `getGraphicsState().getNonStrokingColor()` stays at the **default
   black** and NEAR_WHITE never fires. Register them in the constructor.
2. **Read colour during stream execution, not in `writeString`.** With `setSortByPosition(true)`,
   `writeString` runs *after* the page is processed, on a **reset** graphics state — so colour read
   there is always default black. Capture colour per glyph in `processTextPosition` (state is live)
   into an **identity map**, then look it up in `writeString` (the same `TextPosition` instances
   reach both callbacks — confirmed). Size, by contrast, is baked into each `TextPosition` and is
   safe to read in `writeString`.

```java
final class CandidateTextStripper extends PDFTextStripper {
    private final Map<TextPosition, float[]> fillRgbByGlyph = new IdentityHashMap<>();
    private final List<AnchorCandidate> candidates = new ArrayList<>();

    CandidateTextStripper() throws IOException {
        setSortByPosition(true);
        addOperator(new SetNonStrokingColorSpace(this));
        addOperator(new SetNonStrokingColor(this));
        addOperator(new SetNonStrokingColorN(this));
        addOperator(new SetNonStrokingDeviceRGBColor(this));
        addOperator(new SetNonStrokingDeviceGrayColor(this));
        addOperator(new SetNonStrokingDeviceCMYKColor(this));   // contentstream.operator.color.*
    }

    @Override                       // stream execution — graphics state is LIVE
    protected void processTextPosition(TextPosition t) {
        fillRgbByGlyph.put(t, toRgbOrNull(getGraphicsState().getNonStrokingColor()));
        super.processTextPosition(t);
    }

    @Override                       // post-sort — use TextPosition geometry + the captured colour
    protected void writeString(String run, List<TextPosition> tps) {
        // effective size from tps (§2.2); fill colour from fillRgbByGlyph (§2.3); classify (§3)
    }

    List<AnchorCandidate> getCandidates() { return candidates; }
}
```

Per text run it captures:

| Field        | Source |
|--------------|--------|
| `string`     | the `run` argument (trimmed for matching; raw kept for display) |
| `page`       | `getCurrentPageNo()` (1-based, as PDFBox reports) |
| `x`, `y`     | `tps.get(0).getXDirAdj()` / `getYDirAdj()` (top-left of first glyph; y in PDFBox display space) |
| `fontSize`   | **effective** on-page size from the `TextPosition` — see §2.2 |
| `color`      | non-stroking (fill) color from `fillRgbByGlyph` — see §2.3 |

The stripper uses `setSortByPosition(true)` so multi-column hidden text is assembled in reading
order. We do **not** restrict the page range at the stripper level — the caller selects pages via
`ScanOptions` (default: all pages) and the stripper honors `setStartPage`/`setEndPage`.

### 2.2 Effective font size

A glyph's on-page size is its content-stream font size folded through the text matrix **and the
CTM**. The spike (§10) measured every candidate accessor against known fixtures and settled it:

```java
float effectiveSize = tp.getXScale();   // text-rendering-matrix scale: folds Tm AND CTM
```

| Accessor | 12pt, no transform | 12pt under `cm` scale 0.5 (on-page 6pt) | Verdict |
|---|---|---|---|
| `getFontSize()` | 12 | **12** (ignores everything) | ✗ raw `Tf` only |
| `getFontSizeInPt()` | 12 | **12 — wrong** (folds Tm but **ignores the CTM**) | ✗ the trap |
| `getXScale()` / `getYScale()` | 12 | **6 ✓** (folds Tm+CTM; rotation-safe) | ✓ use this |
| `getHeightDir()` | ~6.9 (glyph bbox, char-dependent) | ~3.5 | ✗ not a font-size proxy |

**Do not** "simplify" to `getFontSizeInPt()` — it looks like the obvious choice and silently
under-reports size whenever a `cm` transform scales the text, missing genuinely tiny anchors.
`getXScale()`/`getYScale()` are equal under isotropic scaling; for the rare anisotropic case
(e.g. horizontal-only `Tz`) use the **height axis** `getYScale()` so a merely-condensed run isn't
mistaken for tiny. For a multi-glyph run, take the **maximum** effective size across `tps` (a run
is "tiny" only if *all* its glyphs are tiny). Store as `AnchorCandidate.fontSize`.

### 2.3 Fill color from the graphics state

Colour is **ambient graphics state**, not a property of the run, and §2.1 establishes the two
rules the spike proved are mandatory: (a) the colour operators must be **registered**, or the
state never leaves default black; (b) the read must happen in `processTextPosition` while the
state is **live**, because under `setSortByPosition(true)` the state is reset by the time
`writeString` runs. So capture per glyph during processing:

```java
PDColor fill = getGraphicsState().getNonStrokingColor();   // in processTextPosition
float[] rgb  = toRgbOrNull(fill);                          // null if not RGB-convertible
```

```java
static float[] toRgbOrNull(PDColor c) {
    if (c == null) return null;
    try { return c.getColorSpace().toRGB(c.getComponents()); }  // 0..1 floats; DeviceCMYK 0,0,0,0 → white
    catch (Exception e) { return null; }                        // separation/pattern → unknown
}
```

In `writeString`, look the run's glyphs up in `fillRgbByGlyph` (identity map) and store the colour
on the candidate (convert 0–1 → 0–255 ints; `java.awt.Color`, no alpha). The spike confirmed
DeviceRGB white, **DeviceCMYK white (0,0,0,0 → 1,1,1)**, and near-white (.996) all convert
correctly and clear the 245/channel floor. If the colour space cannot convert to RGB (rare —
certain separation/pattern spaces), colour is recorded as `null` → NEAR_WHITE does not fire, but
the candidate can still qualify via the TINY heuristic.

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
- **Color extraction:** verify per-glyph capture → RGB conversion against a known fill, including
  **DeviceCMYK white (0,0,0,0 → 1,1,1)** and a separation/pattern space producing `color == null`
  without throwing.
- **Regression — colour operators registered (spike §10):** a white-text fixture must yield a
  `NEAR_WHITE` candidate. Guards against the default-black failure mode (forgetting the
  `SetNonStrokingColor*` operators makes every run read black → silent no-op).
- **Regression — CTM-scaled tiny text (spike §10):** `_sig_363_` at 12pt under a `cm` scale of 0.5
  (on-page 6pt) must be flagged `TINY` (asserts `getXScale`, not `getFontSizeInPt`, drives size —
  `getFontSizeInPt` would report 12 and miss it).

---

## 9. Out of scope (deferred)

- True background-color analysis for white-on-white (001 §5.1, decision §7.4) — v1 flags near-white
  fill regardless of background.
- Password-protected PDF support (no password prompt in v1).
- Stroke-color-only hidden text (we inspect fill / non-stroking color only).
- Any DocuSign auth/API or tab construction — that is 005's responsibility, which consumes
  `AnchorScanner.scan` (§4).

---

## 10. Spike results (PDFBox 3.0.3 — validated, not theoretical)

A throwaway spike (PDFBox 3.0.3, JDK) generated fixtures at known sizes/colours/transforms and
dumped every candidate accessor, capturing colour in both the `processTextPosition` (live) and
`writeString` (post-sort) paths. Findings, now baked into §2:

1. **Colour operators are not registered by default.** Without registering `SetNonStrokingColor*`,
   `getNonStrokingColor()` returns the default **black (DeviceGray 0,0,0)** for *all* text — the
   three white fixtures included. NEAR_WHITE would never fire. **Fix:** register them (§2.1).
2. **`writeString` colour is reset, not just stale.** With `setSortByPosition(true)` the live
   colours (white / CMYK-white / near-white / black) were correct in `processTextPosition` but
   **every** run read `(0,0,0) DeviceGray` in `writeString`. **Fix:** capture in
   `processTextPosition` (§2.3).
3. **Identity bridge works.** The same `TextPosition` instances reach both callbacks
   (`IdentityHashMap` lookup succeeded on every glyph), so `writeString`'s string assembly is kept
   while colour comes from the live-captured map.
4. **Effective size = `getXScale()`/`getYScale()`, not `getFontSizeInPt()`.** Under a `cm` scale of
   0.5, `getFontSizeInPt()` returned **12** (wrong; ignores the CTM) while `getXScale()` returned
   **6** (correct). Both axes were correct under no-transform, Tm-scale, and 90° rotation.
   `getHeightDir()` is glyph-bbox height (≈0.58×size here), not a size proxy.
5. **Colour-space conversion is fine.** DeviceRGB white, DeviceCMYK white (0,0,0,0), and
   near-white (.996) all convert via `toRGB()` and clear the 245/channel floor; black does not.

The `AnchorCandidate` shape in §4 is **unchanged** by the spike — only the stripper's internals
(operator registration + capture timing + size accessor) changed — so 005's contract holds.

---

## Implementation Notes (branch `spec-004-anchor-detection-scan`)

Built as specified; the §10 spike findings held against PDFBox 3.0.3 and are now covered by
regression tests (white text fires NEAR_WHITE only with the colour operators registered;
CTM-scaled 12pt@0.5 is flagged via `getYScale`, which `getFontSizeInPt` would miss). Notes:

- **Colour operators** are constructed with `this` (`new SetNonStrokingColor(this)`, …) — PDFBox
  3.x's `OperatorProcessor` subclasses take the owning `PDFStreamEngine`. The §2.1 sketch's
  no-arg form does not compile against 3.0.3.
- **Effective size** uses `getYScale()` (the height axis) as the per-glyph size and takes the
  **max** across a run's glyphs, exactly as §2.2 prescribes for the anisotropic-safe path.
- **Run colour** is taken from the first glyph's live-captured RGB (a run shares one fill colour);
  the identity-map bridge from `processTextPosition` to `writeString` works as the spike found.
- **Document-order y axis.** Sorting uses `AnchorCandidate.y` from `getYDirAdj()` — PDFBox
  *display* space, where y increases downward — so "top to bottom" is y **ascending** here. The
  spec's "y desc" (§7) refers to PDF user space (origin bottom-left); the display-space adjusted
  value inverts that, and the multi-page test confirms page-1-then-page-3 / top-to-bottom order.
- **`scan` now requires a `<pdf>` positional** (§5). Two 002 `CliWiringTest` cases that used a bare
  `scan` to exercise global-option parsing were updated to pass a dummy `doc.pdf`; this is the only
  cross-spec test change.
- **`AnchorScanner.scan(File, ScanOptions)`** replaced the 002 `scan(Object)` shell — the §4
  contract 005 consumes.
- **Out of scope (unchanged, §9):** background-colour analysis for white-on-white, password-prompt
  for encrypted PDFs, stroke-only hidden text, and all DocuSign auth/tab construction (005).
