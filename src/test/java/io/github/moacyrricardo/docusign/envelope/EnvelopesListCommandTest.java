package io.github.moacyrricardo.docusign.envelope;

import com.docusign.esign.model.EnvelopesInformation;
import io.github.moacyrricardo.docusign.cli.CliContext;
import io.github.moacyrricardo.docusign.cli.CliException;
import io.github.moacyrricardo.docusign.cli.ExitCode;
import io.github.moacyrricardo.docusign.cli.GlobalOptions;
import io.github.moacyrricardo.docusign.cli.RootCommand;
import io.github.moacyrricardo.docusign.config.Config;
import io.github.moacyrricardo.docusign.config.ConfigPaths;
import io.github.moacyrricardo.docusign.config.Credentials;
import io.github.moacyrricardo.docusign.docusign.ApiClientFactory;
import io.github.moacyrricardo.docusign.docusign.Environment;
import io.github.moacyrricardo.docusign.output.OutputWriter;
import io.github.moacyrricardo.docusign.output.TableWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static io.github.moacyrricardo.docusign.envelope.EnvelopeFixtures.envelope;
import static io.github.moacyrricardo.docusign.envelope.EnvelopeFixtures.page;
import static io.github.moacyrricardo.docusign.envelope.EnvelopeFixtures.signer;
import static io.github.moacyrricardo.docusign.envelope.EnvelopeFixtures.withSigners;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Command-level coverage of {@code envelopes list} rendering (spec 006 §5, §7): the recipient-summary
 * string (0/1/2/3+ with {@code (+N more)}), the truncation footer, the no-results path, and the
 * default-window stderr note. The DocuSign read seam is stubbed; no live calls.
 */
class EnvelopesListCommandTest {

    private static RootCommand rootWith(CliContext ctx) {
        return new RootCommand() {
            @Override
            public CliContext context() {
                return ctx;
            }
        };
    }

    private static CliContext context(Path home, OutputWriter out) {
        Config config = Config.open(ConfigPaths.at(home));
        config.writeCredentials(Credentials.builder()
                .integrationKey("ik").userId("uid").accountId("acct-1")
                .baseUri("https://demo.docusign.net").build());
        config = Config.open(ConfigPaths.at(home));
        ApiClientFactory factory = new ApiClientFactory(Environment.DEMO, config);
        return new CliContext(Environment.DEMO, out, true, config, factory,
                () -> { throw new CliException(ExitCode.NOAUTH, "no live auth in test"); });
    }

    /** A stub seam that always returns one fixed page and never fetches documents. */
    private static EnvelopeQuery oneShot(EnvelopesInformation onePage) {
        return new EnvelopeQuery() {
            @Override
            public EnvelopesInformation listStatusChanges(String accountId, String fromDate,
                    String toDate, String status, int pageSize, Integer startPosition) {
                return startPosition == null ? onePage : page("0", null);
            }

            @Override
            public List<String> listDocumentNames(String accountId, String envelopeId) {
                return List.of();
            }
        };
    }

    private EnvelopesListCommand command(Path home, OutputWriter out, EnvelopeQuery query,
                                         ByteArrayOutputStream notes) {
        EnvelopesCommand parent = new EnvelopesCommand();
        parent.root = rootWith(context(home, out));
        EnvelopesListCommand cmd = new EnvelopesListCommand();
        cmd.envelopes = parent;
        cmd.globalOptions = new GlobalOptions();
        cmd.limit = 100;
        cmd.from = "2026-05-01";
        cmd.useQuery(query);
        cmd.useNow(Instant.parse("2026-06-10T00:00:00Z"));
        cmd.useNotesStream(new PrintStream(notes, true, StandardCharsets.UTF_8));
        return cmd;
    }

    @Test
    void rendersSingleRecipientSummary(@TempDir Path home) {
        StringWriter sink = new StringWriter();
        EnvelopeQuery query = oneShot(page("1", null,
                withSigners(envelope("e1", "Invoice", "sent"),
                        signer("Ann", "ann@x.com", "completed"))));

        EnvelopesListCommand cmd = command(home, new TableWriter(sink), query,
                new ByteArrayOutputStream());
        assertEquals(ExitCode.OK.code(), cmd.call());
        assertTrue(sink.toString().contains("Ann <ann@x.com>: completed"));
    }

    @Test
    void joinsTwoRecipientsWithSemicolon(@TempDir Path home) {
        StringWriter sink = new StringWriter();
        EnvelopeQuery query = oneShot(page("1", null,
                withSigners(envelope("e1", "Deal", "sent"),
                        signer("Ann", "ann@x.com", "sent"),
                        signer("Bob", "bob@x.com", "delivered"))));

        EnvelopesListCommand cmd = command(home, new TableWriter(sink), query,
                new ByteArrayOutputStream());
        assertEquals(ExitCode.OK.code(), cmd.call());
        String out = sink.toString();
        assertTrue(out.contains("Ann <ann@x.com>: sent; Bob <bob@x.com>: delivered"),
                "two recipients joined with '; '");
        assertFalse(out.contains("more)"), "no overflow marker for exactly two recipients");
    }

    @Test
    void collapsesThreePlusRecipientsWithOverflowMarker(@TempDir Path home) {
        StringWriter sink = new StringWriter();
        EnvelopeQuery query = oneShot(page("1", null,
                withSigners(envelope("e1", "Board", "sent"),
                        signer("Ann", "ann@x.com", "sent"),
                        signer("Bob", "bob@x.com", "sent"),
                        signer("Cara", "cara@x.com", "sent"),
                        signer("Dan", "dan@x.com", "sent"))));

        EnvelopesListCommand cmd = command(home, new TableWriter(sink), query,
                new ByteArrayOutputStream());
        assertEquals(ExitCode.OK.code(), cmd.call());
        assertTrue(sink.toString().contains("(+2 more)"),
                "four recipients show the first two plus '(+2 more)'");
    }

    @Test
    void emptyResultPrintsNoEnvelopesFound(@TempDir Path home) {
        StringWriter sink = new StringWriter();
        ByteArrayOutputStream notes = new ByteArrayOutputStream();
        EnvelopesListCommand cmd = command(home, new TableWriter(sink),
                oneShot(page("0", null)), notes);

        assertEquals(ExitCode.OK.code(), cmd.call());
        assertTrue(sink.toString().contains("No envelopes found."));
    }

    @Test
    void truncationFooterFiresWhenTotalExceedsLimit(@TempDir Path home) {
        StringWriter sink = new StringWriter();
        ByteArrayOutputStream notes = new ByteArrayOutputStream();
        // total set size 9, but limit 2 → footer with "first 2 of 9".
        EnvelopeQuery query = oneShot(page("9", null,
                envelope("e1", "A", "sent"), envelope("e2", "B", "sent")));
        EnvelopesListCommand cmd = command(home, new TableWriter(sink), query, notes);
        cmd.limit = 2;

        assertEquals(ExitCode.OK.code(), cmd.call());
        assertTrue(notes.toString(StandardCharsets.UTF_8).contains("Showing first 2 of 9"),
                "truncation footer must fire when totalSetSize > limit and rows == limit");
    }

    @Test
    void defaultWindowNotePrintedWhenFromOmitted(@TempDir Path home) {
        StringWriter sink = new StringWriter();
        ByteArrayOutputStream notes = new ByteArrayOutputStream();
        EnvelopesListCommand cmd = command(home, new TableWriter(sink),
                oneShot(page("0", null)), notes);
        cmd.from = null;   // trigger the 30-day default

        assertEquals(ExitCode.OK.code(), cmd.call());
        assertTrue(notes.toString(StandardCharsets.UTF_8).contains("default 30-day window"),
                "omitting --from prints the default-window note to stderr");
    }
}
