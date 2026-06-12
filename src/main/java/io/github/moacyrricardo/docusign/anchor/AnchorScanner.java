package io.github.moacyrricardo.docusign.anchor;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The anchor detection engine (spec 004 §4): local PDF analysis only — no DocuSign auth or API. It
 * loads a PDF with PDFBox 3.x, runs {@link CandidateTextStripper} over the selected page range, and
 * returns the detected {@link AnchorCandidate}s in document order. {@code AnchorScanner.scan} is the
 * single integration point 005 depends on.
 */
public final class AnchorScanner {

    /**
     * Scans {@code pdf} for candidate anchor strings (tiny and/or near-white text).
     *
     * @param pdf  an existing, readable PDF file
     * @param opts detection thresholds and page selection; never null (use {@link ScanOptions#defaults()})
     * @return candidates in document order (page asc, then top-to-bottom, then left-to-right);
     *         empty if none — never null
     * @throws EncryptedPdfException if the PDF is password-protected
     * @throws IOException           if the file is missing, unreadable, or not a valid PDF
     */
    public List<AnchorCandidate> scan(File pdf, ScanOptions opts) throws IOException {
        PDDocument document;
        try {
            document = Loader.loadPDF(pdf);
        } catch (InvalidPasswordException e) {
            throw new EncryptedPdfException(
                    "Cannot scan password-protected PDF: " + pdf.getName(), e);
        }

        try (document) {
            int pageCount = document.getNumberOfPages();
            int start = clamp(opts.startPage(), 1, pageCount, 1);
            int end = clamp(opts.endPage(), 1, pageCount, pageCount);

            CandidateTextStripper stripper = new CandidateTextStripper(opts);
            stripper.setStartPage(start);
            stripper.setEndPage(end);
            stripper.writeText(document, new StringWriter());   // drives processing; output discarded

            List<AnchorCandidate> candidates = new ArrayList<>(stripper.getCandidates());
            candidates.sort(documentOrder());
            return candidates;
        }
    }

    /** Document order: page asc, then top-to-bottom (display-space y asc), then left-to-right (x asc). */
    private static Comparator<AnchorCandidate> documentOrder() {
        return Comparator.comparingInt(AnchorCandidate::page)
                .thenComparing(AnchorCandidate::y)
                .thenComparing(AnchorCandidate::x);
    }

    private static int clamp(Integer requested, int min, int max, int fallback) {
        int value = (requested != null) ? requested : fallback;
        return Math.max(min, Math.min(max, value));
    }
}
