package io.github.moacyrricardo.docusign.auth;

import io.github.moacyrricardo.docusign.cli.GlobalOptions;
import io.github.moacyrricardo.docusign.cli.RootCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

/**
 * {@code docusign-cli login} — JWT consent + mint/cache token. <b>Shell owned by 003</b>; this spec
 * registers it as a root subcommand so the CLI wires up.
 */
@Command(name = "login",
        description = "Authenticate via JWT Grant: run the one-time consent then mint a token.")
public final class LoginCommand implements Callable<Integer> {

    @ParentCommand
    RootCommand root;

    @Mixin
    GlobalOptions globalOptions;

    @Override
    public Integer call() {
        throw new UnsupportedOperationException("`login` is implemented in spec 003");
    }
}
