package io.github.moacyrricardo.docusign.output;

/** Selects the writer used for a command's primary output (spec 002 §5). */
public enum OutputFormat {
    /** Human-readable aligned table / record view. */
    TABLE,
    /** Machine JSON (one document per invocation). */
    JSON
}
