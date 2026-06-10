package io.github.moacyrricardo.docusign.anchor;

import java.io.IOException;

/**
 * Thrown by {@link AnchorScanner#scan} when the PDF is password-protected and cannot be opened
 * without a password (spec 004 §7). v1 does not prompt for a password; the {@code scan} command
 * maps this to {@code ExitCode.INPUT}.
 */
public final class EncryptedPdfException extends IOException {

    public EncryptedPdfException(String message, Throwable cause) {
        super(message, cause);
    }
}
