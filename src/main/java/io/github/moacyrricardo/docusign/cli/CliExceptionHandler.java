package io.github.moacyrricardo.docusign.cli;

import picocli.CommandLine;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.ParseResult;

import java.io.PrintWriter;

/**
 * Maps an exception thrown by a command to a process exit code and a stderr diagnostic
 * (spec 002 §6). {@link CliException} carries its own {@link ExitCode}; any other exception maps to
 * {@link ExitCode#SOFTWARE}. In {@code --json} mode an {@code {"error": "..."}} object is also
 * written to stdout so machine consumers see the failure. Full stack traces are printed only when
 * {@code --verbose}/{@code -v} or {@code DOCUSIGN_CLI_DEBUG=1} is set.
 */
public final class CliExceptionHandler implements IExecutionExceptionHandler {

    private static final String DEBUG_ENV = "DOCUSIGN_CLI_DEBUG";

    @Override
    public int handleExecutionException(Exception ex, CommandLine commandLine, ParseResult parseResult) {
        GlobalOptions options = resolveGlobalOptions(commandLine);
        boolean json = options != null && options.json;
        boolean verbose = (options != null && options.verbose) || "1".equals(System.getenv(DEBUG_ENV));

        ExitCode exitCode;
        String message;
        if (ex instanceof CliException cliException) {
            exitCode = cliException.exitCode();
            message = cliException.getMessage();
        } else {
            exitCode = ExitCode.SOFTWARE;
            message = ex.getMessage() != null ? ex.getMessage() : ex.toString();
        }

        PrintWriter err = commandLine.getErr();
        err.println("error: " + message);
        if (verbose) {
            ex.printStackTrace(err);
        }
        err.flush();

        if (json) {
            // Machine consumers reading stdout still see the failure as a clean JSON object.
            PrintWriter out = commandLine.getOut();
            out.println("{\"error\": " + jsonString(message) + "}");
            out.flush();
        }

        return exitCode.code();
    }

    /** Walks up to the root command to read its {@link GlobalOptions} mixin, if reachable. */
    private static GlobalOptions resolveGlobalOptions(CommandLine commandLine) {
        CommandLine current = commandLine;
        while (current != null) {
            Object command = current.getCommand();
            if (command instanceof RootCommand root) {
                return root.globalOptions();
            }
            current = current.getParent();
        }
        return null;
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
