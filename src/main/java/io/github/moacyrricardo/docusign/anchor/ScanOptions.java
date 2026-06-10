package io.github.moacyrricardo.docusign.anchor;

/**
 * Immutable detection thresholds and page selection for a scan (spec 004 §4.1). Build via
 * {@link #defaults()} or {@link #builder()}. {@code startPage}/{@code endPage} are 1-based and
 * nullable (null = first / last page).
 */
public final class ScanOptions {

    /** Default tiny-text threshold in points (exclusive upper bound). */
    public static final float DEFAULT_MAX_FONT_SIZE_PT = 4.0f;
    /** Default per-channel near-white floor on the 0..255 scale (inclusive). */
    public static final int DEFAULT_WHITE_THRESHOLD = 245;

    private final float maxFontSizePt;
    private final int whiteThreshold;
    private final Integer startPage;
    private final Integer endPage;

    private ScanOptions(Builder b) {
        this.maxFontSizePt = b.maxFontSizePt;
        this.whiteThreshold = b.whiteThreshold;
        this.startPage = b.startPage;
        this.endPage = b.endPage;
    }

    /** Default options: 4.0pt tiny threshold, 245 white floor, all pages. */
    public static ScanOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Tiny-text threshold in points: runs strictly below this are flagged {@code TINY}. */
    public float maxFontSizePt() {
        return maxFontSizePt;
    }

    /** Per-channel near-white floor (0..255); fill channels at or above it are flagged {@code NEAR_WHITE}. */
    public int whiteThreshold() {
        return whiteThreshold;
    }

    /** First page to scan (1-based), or null for the first page. */
    public Integer startPage() {
        return startPage;
    }

    /** Last page to scan (1-based), or null for the last page. */
    public Integer endPage() {
        return endPage;
    }

    /** Builder for {@link ScanOptions}; unset values fall back to the documented defaults. */
    public static final class Builder {
        private float maxFontSizePt = DEFAULT_MAX_FONT_SIZE_PT;
        private int whiteThreshold = DEFAULT_WHITE_THRESHOLD;
        private Integer startPage;
        private Integer endPage;

        public Builder maxFontSizePt(float v) {
            this.maxFontSizePt = v;
            return this;
        }

        public Builder whiteThreshold(int v) {
            this.whiteThreshold = v;
            return this;
        }

        /** Sets the 1-based, inclusive page range; either bound may be null (open-ended). */
        public Builder pages(Integer start, Integer end) {
            this.startPage = start;
            this.endPage = end;
            return this;
        }

        public ScanOptions build() {
            return new ScanOptions(this);
        }
    }
}
