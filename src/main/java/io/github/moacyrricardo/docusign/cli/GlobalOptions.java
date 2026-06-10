package io.github.moacyrricardo.docusign.cli;

import io.github.moacyrricardo.docusign.docusign.Environment;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Global options mixed into {@code RootCommand} and every subcommand so they may appear before or
 * after the subcommand name (spec 002 §3.2). {@code --demo}/{@code --prod} are mutually exclusive;
 * specifying neither leaves the environment to be resolved from config, defaulting to DEMO.
 */
public final class GlobalOptions {

    @Option(names = "--json",
            description = "Emit machine JSON instead of the human table.")
    public boolean json;

    @Option(names = "--output", paramLabel = "<file>",
            description = "Write primary output to a file instead of stdout.")
    public Path output;

    @Option(names = {"--yes", "-y"},
            description = "Assume \"yes\" for all confirmation prompts (automation).")
    public boolean yes;

    @Option(names = {"--verbose", "-v"},
            description = "Print full stack traces on unexpected errors.")
    public boolean verbose;

    @ArgGroup(exclusive = true)
    public EnvironmentFlags environmentFlags = new EnvironmentFlags();

    /** Mutually-exclusive {@code --demo}/{@code --prod} pair (spec 002 §3.2). */
    public static final class EnvironmentFlags {
        @Option(names = "--demo",
                description = "Use the DocuSign demo environment.")
        public boolean demo;

        @Option(names = "--prod",
                description = "Use the DocuSign production environment.")
        public boolean prod;
    }

    /**
     * The environment explicitly selected by a flag, if any. Empty means "resolve from config,
     * then default to DEMO" (spec 002 §3.2, §7).
     */
    public Optional<Environment> explicitEnvironment() {
        if (environmentFlags != null && environmentFlags.prod) {
            return Optional.of(Environment.PROD);
        }
        if (environmentFlags != null && environmentFlags.demo) {
            return Optional.of(Environment.DEMO);
        }
        return Optional.empty();
    }
}
