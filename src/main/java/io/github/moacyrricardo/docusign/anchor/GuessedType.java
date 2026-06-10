package io.github.moacyrricardo.docusign.anchor;

/**
 * A heuristic guess of the DocuSign tab type an anchor string denotes (spec 004 §6). Used only for
 * display and as the interactive default in 005 — <b>never</b> authoritative.
 */
public enum GuessedType {
    SIGNATURE, INITIALS, DATE, TEXT, UNKNOWN
}
