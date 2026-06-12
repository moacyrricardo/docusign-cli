package io.github.moacyrricardo.docusign.auth;

import io.github.moacyrricardo.docusign.cli.GlobalOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

/**
 * {@code docusign-cli auth} — parent grouper that hosts {@code auth status} (003). Invoked on its
 * own it prints usage. <b>Shell owned by 003</b>; this spec registers it with its {@code status}
 * leaf so the CLI wires up.
 */
@Command(name = "auth",
        description = "Inspect authentication / account state.",
        subcommands = { AuthStatusCommand.class })
public final class AuthCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions globalOptions;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        // No leaf chosen: show usage like the root does for a bare invocation.
        spec.commandLine().usage(System.err);
        return io.github.moacyrricardo.docusign.cli.ExitCode.USAGE.code();
    }
}
