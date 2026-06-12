package io.github.moacyrricardo.docusign.auth;

import io.github.moacyrricardo.docusign.cli.GlobalOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.concurrent.Callable;

/**
 * {@code docusign-cli auth status} — show current auth/account. <b>Shell owned by 003</b>; this spec
 * registers it as the {@code status} leaf under {@code auth}.
 */
@Command(name = "status",
        description = "Show the current authentication and account state.")
public final class AuthStatusCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions globalOptions;

    @Override
    public Integer call() {
        throw new UnsupportedOperationException("`auth status` is implemented in spec 003");
    }
}
