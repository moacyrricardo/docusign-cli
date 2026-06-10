package io.github.moacyrricardo.docusign.anchor;

import io.github.moacyrricardo.docusign.cli.CliContext;
import io.github.moacyrricardo.docusign.cli.CliException;
import io.github.moacyrricardo.docusign.cli.ExitCode;
import io.github.moacyrricardo.docusign.cli.RootCommand;
import io.github.moacyrricardo.docusign.config.Config;
import io.github.moacyrricardo.docusign.config.ConfigPaths;
import io.github.moacyrricardo.docusign.docusign.ApiClientFactory;
import io.github.moacyrricardo.docusign.docusign.Environment;
import io.github.moacyrricardo.docusign.output.OutputWriter;
import io.github.moacyrricardo.docusign.output.TableWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanCommandTest {

    private static RootCommand rootWith(CliContext ctx) {
        return new RootCommand() {
            @Override
            public CliContext context() {
                return ctx;
            }
        };
    }

    private static CliContext contextWith(Path root, OutputWriter out) {
        Config config = Config.open(ConfigPaths.at(root));
        ApiClientFactory factory = new ApiClientFactory(Environment.DEMO, config);
        return new CliContext(Environment.DEMO, out, false, config, factory,
                () -> { throw new CliException(ExitCode.NOAUTH, "no auth in scan"); });
    }

    private ScanCommand command(Path home, OutputWriter out, File pdf) {
        ScanCommand cmd = new ScanCommand();
        cmd.root = rootWith(contextWith(home, out));
        cmd.pdf = pdf;
        return cmd;
    }

    @Test
    void rendersTableForCandidates(@TempDir Path dir) throws IOException {
        File pdf = PdfFixtures.simple(dir, "scan.pdf", "_sig_363_", 1.0f, Color.WHITE);
        StringWriter sink = new StringWriter();
        ScanCommand cmd = command(dir, new TableWriter(sink), pdf);

        assertEquals(ExitCode.OK.code(), cmd.call());
        String text = sink.toString();
        assertTrue(text.contains("_sig_363_"));
        assertTrue(text.contains("signature"));
        assertTrue(text.contains("PAGE"));
    }

    @Test
    void noCandidatesIsSuccessWithMessage(@TempDir Path dir) throws IOException {
        File pdf = PdfFixtures.simple(dir, "empty.pdf", "Normal text here", 12.0f, Color.BLACK);
        StringWriter sink = new StringWriter();
        ScanCommand cmd = command(dir, new TableWriter(sink), pdf);

        assertEquals(ExitCode.OK.code(), cmd.call());
        assertTrue(sink.toString().contains("No candidate anchors found."));
    }

    @Test
    void invertedPageRangeIsUsageError(@TempDir Path dir) throws IOException {
        File pdf = PdfFixtures.simple(dir, "x.pdf", "_sig_363_", 1.0f, Color.WHITE);
        ScanCommand cmd = command(dir, new TableWriter(new StringWriter()), pdf);
        cmd.pages = "5-2";

        CliException ex = org.junit.jupiter.api.Assertions.assertThrows(CliException.class, cmd::call);
        assertEquals(ExitCode.USAGE, ex.exitCode());
    }

    @Test
    void unreadablePdfMapsToInput(@TempDir Path dir) {
        File missing = dir.resolve("does-not-exist.pdf").toFile();
        ScanCommand cmd = command(dir, new TableWriter(new StringWriter()), missing);

        CliException ex = org.junit.jupiter.api.Assertions.assertThrows(CliException.class, cmd::call);
        assertEquals(ExitCode.INPUT, ex.exitCode());
    }
}
