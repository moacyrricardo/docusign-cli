package io.github.moacyrricardo.docusign.cli;

import io.github.moacyrricardo.docusign.docusign.Environment;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Global options mixed into {@code RootCommand} and every subcommand so they may appear before or
 * after the subcommand name (spec 002 §3.2). {@code --demo}/{@code --prod} are mutually exclusive;
 * specifying neither leaves the environment to be resolved from config, defaulting to DEMO.
 */
public final class GlobalOptions {

    @Option(names = {"-h", "--help"}, usageHelp = true,
            description = "Show this help message and exit.")
    public boolean help;

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

    @Option(names = "--demo",
            description = "Use the DocuSign demo environment (default).")
    public boolean demo;

    @Option(names = "--prod",
            description = "Use the DocuSign production environment.")
    public boolean prod;

    @Spec(Spec.Target.MIXEE)
    CommandSpec mixee;

    /**
     * The environment explicitly selected by a flag, if any. Empty means "resolve from config,
     * then default to DEMO" (spec 002 §3.2, §7). {@code --demo} and {@code --prod} are mutually
     * exclusive; supplying both is a usage error.
     */
    public Optional<Environment> explicitEnvironment() {
        if (demo && prod) {
            throw new ParameterException(mixee.commandLine(),
                    "--demo and --prod are mutually exclusive.");
        }
        if (prod) {
            return Optional.of(Environment.PROD);
        }
        if (demo) {
            return Optional.of(Environment.DEMO);
        }
        return Optional.empty();
    }
}
