package io.github.moacyrricardo.docusign.docusign;

import io.github.moacyrricardo.docusign.cli.CliException;
import io.github.moacyrricardo.docusign.cli.ExitCode;

/**
 * Wraps an SDK {@code ApiException} (or transport failure) as a {@link CliException} (spec 002 §6).
 * The carried {@link ExitCode} is chosen by the caller: {@link ExitCode#NOTFOUND} for a 404,
 * {@link ExitCode#NETWORK} for a transport failure, otherwise {@link ExitCode#API}. The SDK error
 * body (DocuSign returns {@code {errorCode, message}}) is surfaced in the message so the printed
 * diagnostic is actionable.
 */
public class DocuSignException extends CliException {

    public DocuSignException(ExitCode exitCode, String message) {
        super(exitCode, message);
    }

    public DocuSignException(ExitCode exitCode, String message, Throwable cause) {
        super(exitCode, message, cause);
    }

    /** A generic DocuSign API error ({@link ExitCode#API}). */
    public static DocuSignException api(String message, Throwable cause) {
        return new DocuSignException(ExitCode.API, message, cause);
    }

    /** A 404 / missing-resource error ({@link ExitCode#NOTFOUND}). */
    public static DocuSignException notFound(String message, Throwable cause) {
        return new DocuSignException(ExitCode.NOTFOUND, message, cause);
    }

    /** A transport/network failure reaching DocuSign ({@link ExitCode#NETWORK}). */
    public static DocuSignException network(String message, Throwable cause) {
        return new DocuSignException(ExitCode.NETWORK, message, cause);
    }
}
