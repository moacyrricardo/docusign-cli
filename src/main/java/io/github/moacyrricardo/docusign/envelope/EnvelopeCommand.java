package io.github.moacyrricardo.docusign.envelope;

import io.github.moacyrricardo.docusign.cli.ExitCode;
import io.github.moacyrricardo.docusign.cli.GlobalOptions;
import io.github.moacyrricardo.docusign.cli.RootCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

/**
 * {@code docusign-cli envelope} — parent grouper that hosts {@code envelope status} (007). Invoked on
 * its own it prints usage. Carries the {@link RootCommand} so its {@code status} leaf can reach the
 * shared {@link io.github.moacyrricardo.docusign.cli.CliContext} (mirrors {@link EnvelopesCommand}).
 */
@Command(name = "envelope",
        description = "Work with a single envelope (status).",
        subcommands = { EnvelopeStatusCommand.class })
public final class EnvelopeCommand implements Callable<Integer> {

    @ParentCommand
    RootCommand root;

    @Mixin
    GlobalOptions globalOptions;

    @Spec
    CommandSpec spec;

    /** The composition root, so {@code envelope status} can reach the runtime context. */
    public RootCommand root() {
        return root;
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(System.err);
        return ExitCode.USAGE.code();
    }
}
