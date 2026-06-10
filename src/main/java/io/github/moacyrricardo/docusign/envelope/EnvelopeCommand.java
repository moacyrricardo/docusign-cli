package io.github.moacyrricardo.docusign.envelope;

import io.github.moacyrricardo.docusign.cli.ExitCode;
import io.github.moacyrricardo.docusign.cli.GlobalOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

/**
 * {@code docusign-cli envelope} — parent grouper that hosts {@code envelope status} (007). Invoked
 * on its own it prints usage. The {@code status} leaf is registered here so the CLI wires up; its
 * body lands in 007.
 */
@Command(name = "envelope",
        description = "Work with a single envelope (status).",
        subcommands = { EnvelopeStatusCommand.class })
public final class EnvelopeCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions globalOptions;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.err);
        return ExitCode.USAGE.code();
    }
}
