package io.github.moacyrricardo.docusign.anchor;

import io.github.moacyrricardo.docusign.cli.GlobalOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.concurrent.Callable;

/**
 * {@code docusign-cli scan <pdf>} — detect + print anchor candidates. <b>Shell owned by 004</b>;
 * this spec registers it as a root subcommand. {@code scan} never touches DocuSign.
 */
@Command(name = "scan",
        description = "Detect hidden anchor strings in a PDF and print the candidates.")
public final class ScanCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions globalOptions;

    @Override
    public Integer call() {
        throw new UnsupportedOperationException("`scan` is implemented in spec 004");
    }
}
