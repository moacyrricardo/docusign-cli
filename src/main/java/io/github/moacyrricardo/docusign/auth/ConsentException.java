package io.github.moacyrricardo.docusign.auth;

/**
 * Signals that DocuSign returned {@code consent_required}: the impersonating integration has not yet
 * been granted consent to act on behalf of the configured user (spec 003 §5). It carries the
 * deterministically-built consent URL the user must visit.
 *
 * <p>This is deliberately <b>not</b> a {@code CliException}: the consent path is handled differently
 * by the two callers. {@code login} catches it, prints/opens the URL, and exits
 * {@code ExitCode.CONSENT}; the silent refresh path in {@link CachingTokenProvider} converts it into
 * an {@link AuthException} mapping to {@code ExitCode.NOAUTH} ("run login first") so headless
 * commands never prompt.
 */
public final class ConsentException extends Exception {

    private final String consentUrl;

    public ConsentException(String consentUrl) {
        super("Consent required to act on behalf of the configured user.");
        this.consentUrl = consentUrl;
    }

    /** The consent URL the user must visit and approve. */
    public String consentUrl() {
        return consentUrl;
    }
}
