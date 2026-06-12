package io.github.moacyrricardo.docusign.cli;

/**
 * Authoritative exit-code catalog for the whole CLI (spec 002 §6.1). All command specs
 * (003-007) map their outcomes onto these constants and never invent their own numbers.
 */
public enum ExitCode {
    /** Success. */
    OK(0),
    /** Bad CLI usage / parse error (Picocli default). */
    USAGE(2),
    /** Missing/invalid config, credentials, or key file. */
    CONFIG(3),
    /** No cached token and cannot mint silently — run {@code login}. */
    NOAUTH(4),
    /** Consent required; human action needed ({@code login} only). */
    CONSENT(5),
    /** Resource not found (e.g. envelope 404). */
    NOTFOUND(6),
    /** DocuSign API error (wraps an SDK ApiException). */
    API(7),
    /** Network/transport failure reaching DocuSign. */
    NETWORK(8),
    /** Bad input not catchable by Picocli (unreadable/encrypted PDF, nothing to send). */
    INPUT(9),
    /** Unexpected/internal error (uncaught). */
    SOFTWARE(70);

    private final int code;

    ExitCode(int code) {
        this.code = code;
    }

    /** The numeric process exit status for this outcome. */
    public int code() {
        return code;
    }
}
