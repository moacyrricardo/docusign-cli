package io.github.moacyrricardo.docusign.cli;

/**
 * Base unchecked CLI error carrying an {@link ExitCode} and a human-readable message (spec 002
 * §6). Commands signal failure by throwing a {@code CliException}; they never call
 * {@code System.exit}. {@link CliExceptionHandler} maps the carried code to the process exit
 * status and prints the message to stderr.
 */
public class CliException extends RuntimeException {

    private final ExitCode exitCode;

    public CliException(ExitCode exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    public CliException(ExitCode exitCode, String message, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    /** The exit code this failure maps to. */
    public ExitCode exitCode() {
        return exitCode;
    }
}
