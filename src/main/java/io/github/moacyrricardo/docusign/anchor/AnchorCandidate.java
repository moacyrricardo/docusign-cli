package io.github.moacyrricardo.docusign.anchor;

import java.awt.Color;
import java.util.EnumSet;
import java.util.Objects;

/**
 * One detected candidate anchor: a text run flagged as tiny and/or near-white (spec 004 §4.2). The
 * shape is the contract 005 consumes; do not change it without updating 005. Value equality is over
 * the locating tuple {@code (anchorString, page, x, y)} — a run is the same candidate iff it is the
 * same string at the same position.
 */
public final class AnchorCandidate {

    private final String anchorString;
    private final int page;
    private final float x;
    private final float y;
    private final float fontSize;
    private final Color color;
    private final GuessedType guessedType;
    private final EnumSet<CandidateReason> reason;

    public AnchorCandidate(String anchorString, int page, float x, float y, float fontSize,
                           Color color, GuessedType guessedType, EnumSet<CandidateReason> reason) {
        this.anchorString = anchorString;
        this.page = page;
        this.x = x;
        this.y = y;
        this.fontSize = fontSize;
        this.color = color;
        this.guessedType = guessedType;
        this.reason = EnumSet.copyOf(reason);
    }

    /** Trimmed run text, e.g. {@code "_sig_363_"}. */
    public String anchorString() {
        return anchorString;
    }

    /** 1-based page number. */
    public int page() {
        return page;
    }

    /** Display-space x of the first glyph's top-left. */
    public float x() {
        return x;
    }

    /** Display-space y of the first glyph's top-left. */
    public float y() {
        return y;
    }

    /** Effective on-page font size in points. */
    public float fontSize() {
        return fontSize;
    }

    /** Fill colour; {@code null} if the colour space is not RGB-convertible. */
    public Color color() {
        return color;
    }

    /** Heuristic tab-type guess (never authoritative). */
    public GuessedType guessedType() {
        return guessedType;
    }

    /** The reason(s) this run was flagged; never empty. */
    public EnumSet<CandidateReason> reason() {
        return EnumSet.copyOf(reason);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AnchorCandidate other)) {
            return false;
        }
        return page == other.page
                && Float.compare(x, other.x) == 0
                && Float.compare(y, other.y) == 0
                && Objects.equals(anchorString, other.anchorString);
    }

    @Override
    public int hashCode() {
        return Objects.hash(anchorString, page, x, y);
    }

    @Override
    public String toString() {
        return "AnchorCandidate{" + anchorString + " p" + page
                + " (" + x + "," + y + ") " + fontSize + "pt " + reason + "}";
    }
}
