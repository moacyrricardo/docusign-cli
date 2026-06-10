package io.github.moacyrricardo.docusign.send;

/**
 * A parsed anchor→tab binding: place a {@code type} tab for {@code recipientRef} wherever
 * {@code anchorString} appears in the PDF (spec 005 §3). {@code anchorString} is the literal
 * embedded marker, taken verbatim.
 */
public record AnchorSpec(String anchorString, TabType type, String recipientRef) {
}
