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
 * {@code docusign-cli envelopes} — parent grouper that hosts {@code envelopes list} (006). Invoked
 * on its own it prints usage. Carries the {@link RootCommand} so its {@code list} leaf can reach the
 * shared {@link io.github.moacyrricardo.docusign.cli.CliContext}.
 */
@Command(name = "envelopes",
        description = "Work with envelopes (list, filter).",
        subcommands = { EnvelopesListCommand.class })
public final class EnvelopesCommand implements Callable<Integer> {

    @ParentCommand
    RootCommand root;

    @Mixin
    GlobalOptions globalOptions;

    @Spec
    CommandSpec spec;

    /** The composition root, so {@code envelopes list} can reach the runtime context. */
    public RootCommand root() {
        return root;
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(System.err);
        return ExitCode.USAGE.code();
    }
}
