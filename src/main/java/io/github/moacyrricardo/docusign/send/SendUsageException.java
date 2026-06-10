package io.github.moacyrricardo.docusign.send;

import io.github.moacyrricardo.docusign.cli.CliException;
import io.github.moacyrricardo.docusign.cli.ExitCode;

/**
 * A user-input error in {@code send} (bad recipient/anchor spec, undeclared recipient, mode
 * conflict) (spec 005 §3.3/§8). Maps to {@link ExitCode#USAGE} — the same outcome the spec's
 * {@code ParameterException} would produce — and is caught/reported by the root handler.
 */
public final class SendUsageException extends CliException {

    public SendUsageException(String message) {
        super(ExitCode.USAGE, message);
    }
}
