package io.github.moacyrricardo.docusign.auth;

import io.github.moacyrricardo.docusign.cli.GlobalOptions;
import io.github.moacyrricardo.docusign.cli.RootCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

/**
 * {@code docusign-cli auth} — parent grouper that hosts {@code auth status} (003). Invoked on its
 * own it prints usage. Carries the {@link RootCommand} reference so its {@code status} leaf can reach
 * the shared {@link io.github.moacyrricardo.docusign.cli.CliContext}.
 */
@Command(name = "auth",
        description = "Inspect authentication / account state.",
        subcommands = { AuthStatusCommand.class })
public final class AuthCommand implements Callable<Integer> {

    @ParentCommand
    RootCommand root;

    @Mixin
    GlobalOptions globalOptions;

    @Spec
    CommandSpec spec;

    /** The composition root, so {@code auth status} can reach the runtime context. */
    public RootCommand root() {
        return root;
    }

    @Override
    public Integer call() {
        // No leaf chosen: show usage like the root does for a bare invocation.
        spec.commandLine().usage(System.err);
        return io.github.moacyrricardo.docusign.cli.ExitCode.USAGE.code();
    }
}
