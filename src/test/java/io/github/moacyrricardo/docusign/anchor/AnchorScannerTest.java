package io.github.moacyrricardo.docusign.anchor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnchorScannerTest {

    private final AnchorScanner scanner = new AnchorScanner();

    private List<AnchorCandidate> scan(File pdf) throws IOException {
        return scanner.scan(pdf, ScanOptions.defaults());
    }

    @Test
    void tinyBlackTextIsFlaggedTiny(@TempDir Path dir) throws IOException {
        File pdf = PdfFixtures.simple(dir, "tiny.pdf", "_sig_363_", 1.0f, Color.BLACK);
        List<AnchorCandidate> candidates = scan(pdf);

        assertEquals(1, candidates.size());
        AnchorCandidate c = candidates.get(0);
        assertEquals("_sig_363_", c.anchorString());
        assertEquals(1, c.page());
        assertTrue(c.reason().contains(CandidateReason.TINY));
        assertTrue(c.fontSize() < 2.0f, "effective size near 1pt, was " + c.fontSize());
        assertEquals(GuessedType.SIGNATURE, c.guessedType());
    }

    @Test
    void nearWhiteNormalSizeTextIsFlaggedNearWhite(@TempDir Path dir) throws IOException {
        // 12pt so it is NOT tiny; white fill so NEAR_WHITE must fire (regression: colour operators).
        File pdf = PdfFixtures.simple(dir, "white.pdf", "_sig_i_363_", 12.0f, Color.WHITE);
        List<AnchorCandidate> candidates = scan(pdf);

        assertEquals(1, candidates.size());
        AnchorCandidate c = candidates.get(0);
        assertTrue(c.reason().contains(CandidateReason.NEAR_WHITE),
                "white text must be flagged — colour operators must be registered");
        assertTrue(!c.reason().contains(CandidateReason.TINY), "12pt is not tiny");
        assertTrue(c.color().getRed() >= 245);
        assertEquals(GuessedType.INITIALS, c.guessedType());
    }

    @Test
    void tinyAndWhiteCarriesBothReasons(@TempDir Path dir) throws IOException {
        File pdf = PdfFixtures.simple(dir, "both.pdf", "_date_363_", 1.0f, Color.WHITE);
        List<AnchorCandidate> candidates = scan(pdf);

        assertEquals(1, candidates.size());
        AnchorCandidate c = candidates.get(0);
        assertTrue(c.reason().contains(CandidateReason.TINY));
        assertTrue(c.reason().contains(CandidateReason.NEAR_WHITE));
        assertEquals(GuessedType.DATE, c.guessedType());
    }

    @Test
    void normalBlackTextProducesNoCandidate(@TempDir Path dir) throws IOException {
        File pdf = PdfFixtures.simple(dir, "normal.pdf", "Please sign here", 12.0f, Color.BLACK);
        assertTrue(scan(pdf).isEmpty(), "12pt black text is neither tiny nor white");
    }

    @Test
    void ctmScaledTinyTextIsFlaggedViaEffectiveSize(@TempDir Path dir) throws IOException {
        // Declared 12pt under a 0.5 CTM scale → on-page 6pt. getFontSizeInPt() would report 12 and
        // miss it; getYScale() reports ~6 (regression for spike finding §10.4). Use a 7pt threshold.
        File pdf = PdfFixtures.scaled(dir, "scaled.pdf", "_sig_363_", 12.0f, 0.5f, Color.BLACK);
        List<AnchorCandidate> candidates = scanner.scan(pdf,
                ScanOptions.builder().maxFontSizePt(7.0f).build());

        assertEquals(1, candidates.size(), "CTM-scaled text must be sized by getYScale, not getFontSizeInPt");
        AnchorCandidate c = candidates.get(0);
        assertTrue(c.reason().contains(CandidateReason.TINY));
        assertTrue(c.fontSize() < 7.0f && c.fontSize() > 4.0f,
                "effective on-page size ~6pt, was " + c.fontSize());
    }

    @Test
    void deviceCmykWhiteConvertsAndFlagsNearWhite(@TempDir Path dir) throws IOException {
        File pdf = PdfFixtures.cmykWhite(dir, "cmyk.pdf", "_sig_363_", 12.0f);
        List<AnchorCandidate> candidates = scan(pdf);

        assertEquals(1, candidates.size());
        AnchorCandidate c = candidates.get(0);
        assertTrue(c.reason().contains(CandidateReason.NEAR_WHITE),
                "DeviceCMYK white (0,0,0,0) must convert to RGB white and fire NEAR_WHITE");
    }

    @Test
    void multiPageReturnsDocumentOrderAndRangeFilters(@TempDir Path dir) throws IOException {
        File pdf = PdfFixtures.twoStrings(dir, "multi.pdf", "_sig_1_", "_sig_3_", 1.0f, Color.BLACK);

        List<AnchorCandidate> all = scan(pdf);
        assertEquals(2, all.size());
        assertEquals(1, all.get(0).page());
        assertEquals(3, all.get(1).page());

        List<AnchorCandidate> onlyPage3 = scanner.scan(pdf,
                ScanOptions.builder().pages(3, 3).build());
        assertEquals(1, onlyPage3.size());
        assertEquals(3, onlyPage3.get(0).page());
        assertEquals("_sig_3_", onlyPage3.get(0).anchorString());
    }
}
