package io.github.moacyrricardo.docusign.envelope;

import io.github.moacyrricardo.docusign.cli.GlobalOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.concurrent.Callable;

/**
 * {@code docusign-cli envelopes list} — list/filter envelopes. <b>Shell owned by 006</b>; this spec
 * registers it as the {@code list} leaf under {@code envelopes}.
 */
@Command(name = "list",
        description = "List envelopes, filterable by document name, subject, date, and status.")
public final class EnvelopesListCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions globalOptions;

    @Override
    public Integer call() {
        throw new UnsupportedOperationException("`envelopes list` is implemented in spec 006");
    }
}
