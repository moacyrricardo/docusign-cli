package io.github.moacyrricardo.docusign.anchor;

/**
 * Detects hidden anchor-string candidates in a PDF (tiny / near-white text). <b>Shell owned by
 * 004</b>; this spec (002) ships only the registered seam so the package compiles, with the
 * detection body landing in 004.
 */
public final class AnchorScanner {

    /**
     * Scans the given PDF for anchor candidates.
     *
     * @throws UnsupportedOperationException until 004 supplies the implementation.
     */
    public Object scan(Object pdf) {
        throw new UnsupportedOperationException("Anchor detection is implemented in spec 004");
    }
}
