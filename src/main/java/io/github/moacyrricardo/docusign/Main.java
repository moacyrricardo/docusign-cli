package io.github.moacyrricardo.docusign;

import io.github.moacyrricardo.docusign.cli.CliExceptionHandler;
import io.github.moacyrricardo.docusign.cli.RootCommand;
import picocli.CommandLine;

/**
 * JVM entry point (spec 002 §1.4). Holds no logic beyond wiring; all behavior lives in
 * {@link RootCommand} and its subcommands.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new RootCommand())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler(new CliExceptionHandler())
                .execute(args);
        System.exit(exit);
    }
}
