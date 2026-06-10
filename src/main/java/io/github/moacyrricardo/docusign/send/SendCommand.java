package io.github.moacyrricardo.docusign.send;

import io.github.moacyrricardo.docusign.cli.GlobalOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.concurrent.Callable;

/**
 * {@code docusign-cli send <pdf> ...} — send a PDF with anchor params (Mode A) or
 * {@code --interactive} (Mode B). <b>Shell owned by 005</b>; this spec registers it as a root
 * subcommand.
 */
@Command(name = "send",
        description = "Send a PDF for signature, binding detected anchors to signing tabs.")
public final class SendCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions globalOptions;

    @Override
    public Integer call() {
        throw new UnsupportedOperationException("`send` is implemented in spec 005");
    }
}
