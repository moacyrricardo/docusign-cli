package io.github.moacyrricardo.docusign.config;

import io.github.moacyrricardo.docusign.cli.CliException;
import io.github.moacyrricardo.docusign.cli.ExitCode;

/**
 * Raised for missing or invalid configuration, credentials, or key files (spec 002 §4.3, §6).
 * Always maps to {@link ExitCode#CONFIG}.
 */
public class ConfigException extends CliException {

    public ConfigException(String message) {
        super(ExitCode.CONFIG, message);
    }

    public ConfigException(String message, Throwable cause) {
        super(ExitCode.CONFIG, message, cause);
    }
}
