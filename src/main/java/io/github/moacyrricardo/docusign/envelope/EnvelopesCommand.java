package io.github.moacyrricardo.docusign.envelope;

import io.github.moacyrricardo.docusign.cli.ExitCode;
import io.github.moacyrricardo.docusign.cli.GlobalOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

/**
 * {@code docusign-cli envelopes} — parent grouper that hosts {@code envelopes list} (006). Invoked
 * on its own it prints usage. The {@code list} leaf is registered here so the CLI wires up; its body
 * lands in 006.
 */
@Command(name = "envelopes",
        description = "Work with envelopes (list, filter).",
        subcommands = { EnvelopesListCommand.class })
public final class EnvelopesCommand implements Callable<Integer> {

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
