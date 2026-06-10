package io.github.moacyrricardo.docusign.send;

import com.docusign.esign.model.EnvelopeDefinition;
import com.docusign.esign.model.EnvelopeSummary;
import io.github.moacyrricardo.docusign.cli.CliContext;
import io.github.moacyrricardo.docusign.cli.CliException;
import io.github.moacyrricardo.docusign.cli.ExitCode;
import io.github.moacyrricardo.docusign.cli.RootCommand;
import io.github.moacyrricardo.docusign.config.Config;
import io.github.moacyrricardo.docusign.config.ConfigPaths;
import io.github.moacyrricardo.docusign.config.Credentials;
import io.github.moacyrricardo.docusign.docusign.ApiClientFactory;
import io.github.moacyrricardo.docusign.docusign.Environment;
import io.github.moacyrricardo.docusign.output.OutputWriter;
import io.github.moacyrricardo.docusign.output.TableWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SendCommandTest {

    private static RootCommand rootWith(CliContext ctx) {
        return new RootCommand() {
            @Override
            public CliContext context() {
                return ctx;
            }
        };
    }

    private static CliContext context(Path home, OutputWriter out, boolean assumeYes) {
        Config config = Config.open(ConfigPaths.at(home));
        config.writeCredentials(Credentials.builder()
                .integrationKey("ik").userId("uid").accountId("acct-1")
                .baseUri("https://demo.docusign.net").build());
        config = Config.open(ConfigPaths.at(home));
        ApiClientFactory factory = new ApiClientFactory(Environment.DEMO, config);
        return new CliContext(Environment.DEMO, out, assumeYes, config, factory,
                () -> { throw new CliException(ExitCode.NOAUTH, "no live auth in test"); });
    }

    /** Writes a real one-page PDF with a tiny "_sig_363_" anchor the AnchorScanner can find. */
    private static Path pdfWithAnchor(Path dir) throws IOException {
        Path pdf = dir.resolve("doc.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 1.0f);
                cs.newLineAtOffset(72, 700);
                cs.showText("_sig_363_");
                cs.endText();
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private SendCommand command(Path home, Path pdf, OutputWriter out, boolean assumeYes,
                                AtomicReference<EnvelopeDefinition> captured) {
        SendCommand cmd = new SendCommand();
        cmd.root = rootWith(context(home, out, assumeYes));
        cmd.pdf = pdf;
        cmd.subject = "Please sign";
        cmd.recipientSpecs = List.of("Moacyr=moa@x.com");
        cmd.useEnvelopeCreator((account, env) -> {
            captured.set(env);
            EnvelopeSummary summary = new EnvelopeSummary();
            summary.setEnvelopeId("env-abc");
            summary.setStatus("sent");
            return summary;
        });
        return cmd;
    }

    @Test
    void modeASendsWithMappedTabs(@TempDir Path dir) throws IOException {
        Path pdf = pdfWithAnchor(dir);
        StringWriter sink = new StringWriter();
        AtomicReference<EnvelopeDefinition> captured = new AtomicReference<>();
        SendCommand cmd = command(dir, pdf, new TableWriter(sink), true, captured);
        cmd.anchorSpecs = List.of("_sig_363_=moa@x.com");

        assertEquals(ExitCode.OK.code(), cmd.call());
        EnvelopeDefinition env = captured.get();
        assertNotNull(env);
        assertEquals("sent", env.getStatus());
        assertEquals(1, env.getRecipients().getSigners().get(0).getTabs().getSignHereTabs().size());
        assertTrue(sink.toString().contains("env-abc"));
    }

    @Test
    void modeAWarnsOnAnchorMissingFromScanButStillSends(@TempDir Path dir) throws IOException {
        Path pdf = pdfWithAnchor(dir);   // contains _sig_363_ but NOT _ghost_
        StringWriter sink = new StringWriter();
        java.io.ByteArrayOutputStream warn = new java.io.ByteArrayOutputStream();
        AtomicReference<EnvelopeDefinition> captured = new AtomicReference<>();
        SendCommand cmd = command(dir, pdf, new TableWriter(sink), true, captured);
        cmd.anchorSpecs = List.of("_ghost_=moa@x.com");
        cmd.useWarnStream(new java.io.PrintStream(warn, true, java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(ExitCode.OK.code(), cmd.call());
        assertNotNull(captured.get(), "send proceeds despite the missing anchor");
        assertTrue(warn.toString(java.nio.charset.StandardCharsets.UTF_8).contains("_ghost_"));
    }

    @Test
    void modeBUsesScriptedAnswers(@TempDir Path dir) throws IOException {
        Path pdf = pdfWithAnchor(dir);
        StringWriter sink = new StringWriter();
        AtomicReference<EnvelopeDefinition> captured = new AtomicReference<>();
        SendCommand cmd = command(dir, pdf, new TableWriter(sink), true, captured);
        cmd.interactive = true;
        cmd.usePrompter(new ScriptedPrompter(
                new boolean[] {true},                          // "Is this an anchor?" → yes
                new String[] {"initials", "moa@x.com"}));      // type, recipient

        assertEquals(ExitCode.OK.code(), cmd.call());
        EnvelopeDefinition env = captured.get();
        assertNotNull(env);
        assertEquals(1,
                env.getRecipients().getSigners().get(0).getTabs().getInitialHereTabs().size());
    }

    @Test
    void modeAWithInteractiveIsRejected(@TempDir Path dir) throws IOException {
        Path pdf = pdfWithAnchor(dir);
        SendCommand cmd = command(dir, pdf, new TableWriter(new StringWriter()), true,
                new AtomicReference<>());
        cmd.interactive = true;
        cmd.anchorSpecs = List.of("_sig_363_=moa@x.com");

        CliException ex = assertThrows(CliException.class, cmd::call);
        assertEquals(ExitCode.USAGE, ex.exitCode());
    }

    @Test
    void modeANoAnchorsIsUsageError(@TempDir Path dir) throws IOException {
        Path pdf = pdfWithAnchor(dir);
        SendCommand cmd = command(dir, pdf, new TableWriter(new StringWriter()), true,
                new AtomicReference<>());
        cmd.anchorSpecs = List.of();

        CliException ex = assertThrows(CliException.class, cmd::call);
        assertEquals(ExitCode.USAGE, ex.exitCode());
    }

    @Test
    void missingPdfIsInputError(@TempDir Path dir) {
        SendCommand cmd = command(dir, dir.resolve("absent.pdf"),
                new TableWriter(new StringWriter()), true, new AtomicReference<>());
        cmd.anchorSpecs = List.of("_sig_363_=moa@x.com");

        CliException ex = assertThrows(CliException.class, cmd::call);
        assertEquals(ExitCode.INPUT, ex.exitCode());
    }

    @Test
    void declinedConfirmationAbortsWithoutSending(@TempDir Path dir) throws IOException {
        Path pdf = pdfWithAnchor(dir);
        StringWriter sink = new StringWriter();
        AtomicReference<EnvelopeDefinition> captured = new AtomicReference<>();
        SendCommand cmd = command(dir, pdf, new TableWriter(sink), false, captured); // not --yes
        cmd.anchorSpecs = List.of("_sig_363_=moa@x.com");
        cmd.usePrompter(new ScriptedPrompter(new boolean[] {false}, new String[] {})); // "Send?" → no

        assertEquals(ExitCode.OK.code(), cmd.call());
        assertEquals(null, captured.get(), "declining must not call the API");
        assertTrue(sink.toString().contains("Aborted"));
    }

    /** Scripted prompter: pops booleans for confirm(), strings for ask(), in call order. */
    private static final class ScriptedPrompter implements InteractivePrompter {
        private final Deque<Boolean> confirms = new ArrayDeque<>();
        private final Deque<String> answers = new ArrayDeque<>();

        ScriptedPrompter(boolean[] confirms, String[] answers) {
            for (boolean b : confirms) {
                this.confirms.add(b);
            }
            for (String s : answers) {
                this.answers.add(s);
            }
        }

        @Override
        public boolean confirm(String question, boolean defaultYes) {
            return confirms.isEmpty() ? defaultYes : confirms.poll();
        }

        @Override
        public String ask(String question, String defaultValue) {
            return answers.isEmpty() ? defaultValue : answers.poll();
        }
    }
}
