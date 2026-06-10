package io.github.moacyrricardo.docusign.auth;

import io.github.moacyrricardo.docusign.cli.CliException;
import io.github.moacyrricardo.docusign.cli.ExitCode;

/**
 * An authentication/authorization failure raised by the {@code auth} package (spec 003 §11). It is a
 * {@link CliException} carrying an explicit {@link ExitCode} so the same type can map to
 * {@code CONFIG} (bad credentials/key), {@code NOAUTH} (no silent token available), {@code NETWORK}
 * (could not reach the OAuth host), or {@code SOFTWARE} (unexpected). {@link ConsentException} is a
 * separate sibling because only {@code login} handles it interactively.
 */
public final class AuthException extends CliException {

    public AuthException(ExitCode exitCode, String message) {
        super(exitCode, message);
    }

    public AuthException(ExitCode exitCode, String message, Throwable cause) {
        super(exitCode, message, cause);
    }
}
