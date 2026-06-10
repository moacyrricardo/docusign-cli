package io.github.moacyrricardo.docusign.anchor;

import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorN;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link PDFTextStripper} that flags tiny / near-white text runs as {@link AnchorCandidate}s
 * (spec 004 §2). Two spike-proven rules (§10) are mandatory and applied here:
 *
 * <ol>
 *   <li>the non-stroking colour operators are <b>registered</b> in the constructor, else
 *       {@code getNonStrokingColor()} stays at default black and NEAR_WHITE never fires;</li>
 *   <li>colour is captured per glyph in {@link #processTextPosition} while the graphics state is
 *       <b>live</b> (under {@code setSortByPosition(true)} the state is reset before
 *       {@link #writeString} runs) into an identity map, then looked up in {@code writeString}.</li>
 * </ol>
 *
 * Effective font size is {@code TextPosition.getYScale()} (folds Tm + CTM; the height axis so a
 * merely-condensed run isn't mistaken for tiny), taken as the maximum across a run's glyphs.
 */
final class CandidateTextStripper extends PDFTextStripper {

    private static final int RGB_MAX = 255;

    private final Map<TextPosition, float[]> fillRgbByGlyph = new IdentityHashMap<>();
    private final List<AnchorCandidate> candidates = new ArrayList<>();
    private final ScanOptions options;

    CandidateTextStripper(ScanOptions options) throws IOException {
        this.options = options;
        setSortByPosition(true);
        // Register the non-stroking colour operators so the fill colour leaves default black (§2.1).
        addOperator(new SetNonStrokingColorSpace(this));
        addOperator(new SetNonStrokingColor(this));
        addOperator(new SetNonStrokingColorN(this));
        addOperator(new SetNonStrokingDeviceRGBColor(this));
        addOperator(new SetNonStrokingDeviceGrayColor(this));
        addOperator(new SetNonStrokingDeviceCMYKColor(this));
    }

    /**
     * Captures each glyph's fill colour while the graphics state is still live. Under
     * {@code setSortByPosition(true)} the state is reset by the time {@link #writeString} runs, so
     * the colour is recorded here keyed by {@link TextPosition} identity (spec 004 §2.3, §10).
     */
    @Override
    protected void processTextPosition(TextPosition text) {
        // Graphics state is live here — capture the fill colour before the post-sort reset (§2.3).
        fillRgbByGlyph.put(text, toRgbOrNull(getGraphicsState().getNonStrokingColor()));
        super.processTextPosition(text);
    }

    /**
     * Classifies one assembled run: flags it as a candidate when it is tiny (effective on-page size
     * below the threshold) and/or near-white, reading the per-glyph fill colour captured by
     * {@link #processTextPosition} (spec 004 §2.2–§3).
     */
    @Override
    protected void writeString(String run, List<TextPosition> textPositions) {
        String anchorString = run == null ? "" : run.trim();
        if (anchorString.isEmpty() || textPositions.isEmpty()) {
            return;   // whitespace / empty runs never qualify (§3.4)
        }

        float effectiveSize = maxEffectiveSize(textPositions);
        if (effectiveSize <= 0f) {
            return;   // degenerate / zero-size runs are skipped (§3.1)
        }

        Color color = runColor(textPositions);

        EnumSet<CandidateReason> reasons = EnumSet.noneOf(CandidateReason.class);
        if (effectiveSize < options.maxFontSizePt()) {
            reasons.add(CandidateReason.TINY);
        }
        if (isNearWhite(color)) {
            reasons.add(CandidateReason.NEAR_WHITE);
        }
        if (reasons.isEmpty()) {
            return;
        }

        TextPosition first = textPositions.get(0);
        candidates.add(new AnchorCandidate(
                anchorString,
                getCurrentPageNo(),
                first.getXDirAdj(),
                first.getYDirAdj(),
                effectiveSize,
                color,
                TypeGuesser.guess(anchorString),
                reasons));
    }

    List<AnchorCandidate> getCandidates() {
        return candidates;
    }

    /** Maximum effective size across a run's glyphs: a run is tiny only if all of it is (§2.2). */
    private static float maxEffectiveSize(List<TextPosition> textPositions) {
        float max = 0f;
        for (TextPosition tp : textPositions) {
            max = Math.max(max, tp.getYScale());
        }
        return max;
    }

    /** The run's fill colour from the live-captured map; null if any glyph's colour is unknown. */
    private Color runColor(List<TextPosition> textPositions) {
        float[] rgb = fillRgbByGlyph.get(textPositions.get(0));
        if (rgb == null) {
            return null;
        }
        return new Color(
                clampChannel(rgb[0]),
                clampChannel(rgb[1]),
                clampChannel(rgb[2]));
    }

    private boolean isNearWhite(Color color) {
        if (color == null) {
            return false;
        }
        int floor = options.whiteThreshold();
        return color.getRed() >= floor && color.getGreen() >= floor && color.getBlue() >= floor;
    }

    private static int clampChannel(float value) {
        int scaled = Math.round(value * RGB_MAX);
        return Math.max(0, Math.min(RGB_MAX, scaled));
    }

    /** Converts a non-stroking colour to 0..1 RGB floats, or null if it cannot convert (§2.3). */
    static float[] toRgbOrNull(PDColor color) {
        if (color == null) {
            return null;
        }
        try {
            return color.getColorSpace().toRGB(color.getComponents());
        } catch (Exception e) {
            return null;   // separation / pattern space → unknown
        }
    }
}
