package io.github.moacyrricardo.docusign.envelope;

import io.github.moacyrricardo.docusign.cli.GlobalOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.concurrent.Callable;

/**
 * {@code docusign-cli envelope status <id>} — single envelope status. <b>Shell owned by 007</b>;
 * this spec registers it as the {@code status} leaf under {@code envelope}.
 */
@Command(name = "status",
        description = "Show the status of a single envelope by ID.")
public final class EnvelopeStatusCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions globalOptions;

    @Override
    public Integer call() {
        throw new UnsupportedOperationException("`envelope status` is implemented in spec 007");
    }
}
