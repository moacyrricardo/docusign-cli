package io.github.moacyrricardo.docusign.envelope;

import com.docusign.esign.client.ApiException;
import com.docusign.esign.model.Envelope;
import com.docusign.esign.model.Recipients;
import io.github.moacyrricardo.docusign.auth.AuthException;
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
import io.github.moacyrricardo.docusign.output.JsonWriter;
import io.github.moacyrricardo.docusign.output.OutputWriter;
import io.github.moacyrricardo.docusign.output.TableWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.moacyrricardo.docusign.envelope.EnvelopeFixtures.recipients;
import static io.github.moacyrricardo.docusign.envelope.EnvelopeFixtures.signerWithOrder;
import static io.github.moacyrricardo.docusign.envelope.EnvelopeFixtures.statusEnvelope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvelopeStatusCommandTest {

    private static final String VALID_ID = "11111111-2222-3333-4444-555555555555";

    private static RootCommand rootWith(CliContext ctx) {
        return new RootCommand() {
            @Override
            public CliContext context() {
                return ctx;
            }
        };
    }

    private static CliContext context(Path home, OutputWriter out) {
        return context(home, out, () -> "tok-123");
    }

    private static CliContext context(Path home, OutputWriter out,
                                      io.github.moacyrricardo.docusign.auth.TokenProvider tokens) {
        Config config = Config.open(ConfigPaths.at(home));
        config.writeCredentials(Credentials.builder()
                .integrationKey("ik").userId("uid").accountId("acct-1")
                .baseUri("https://demo.docusign.net").build());
        config = Config.open(ConfigPaths.at(home));
        ApiClientFactory factory = new ApiClientFactory(Environment.DEMO, config);
        return new CliContext(Environment.DEMO, out, true, config, factory, tokens);
    }

    private EnvelopeStatusCommand command(CliContext ctx, EnvelopeStatusReader reader,
                                          ByteArrayOutputStream notes) {
        EnvelopeCommand parent = new EnvelopeCommand();
        parent.root = rootWith(ctx);
        EnvelopeStatusCommand cmd = new EnvelopeStatusCommand();
        cmd.envelope = parent;
        cmd.globalOptions = new GlobalOptions();
        cmd.envelopeId = VALID_ID;
        if (reader != null) {
            cmd.useReader(reader);
        }
        cmd.useNotesStream(new PrintStream(notes, true, StandardCharsets.UTF_8));
        return cmd;
    }

    /** A reader that returns a fixed envelope/recipients and can be told to throw. */
    private static final class StubReader implements EnvelopeStatusReader {
        Envelope envelope;
        Recipients recipientsResult;
        ApiException envelopeError;
        ApiException recipientsError;
        final AtomicBoolean envelopeCalled = new AtomicBoolean();
        final AtomicBoolean recipientsCalled = new AtomicBoolean();

        @Override
        public Envelope getEnvelope(String accountId, String envelopeId) throws ApiException {
            envelopeCalled.set(true);
            if (envelopeError != null) {
                throw envelopeError;
            }
            return envelope;
        }

        @Override
        public Recipients listRecipients(String accountId, String envelopeId) throws ApiException {
            recipientsCalled.set(true);
            if (recipientsError != null) {
                throw recipientsError;
            }
            return recipientsResult;
        }
    }

    // ---- ID validation ----------------------------------------------------

    @Test
    void invalidIdIsUsageErrorAndMakesNoApiCall(@TempDir Path home) {
        StubReader reader = new StubReader();
        EnvelopeStatusCommand cmd = command(context(home, new TableWriter(new StringWriter())),
                reader, new ByteArrayOutputStream());
        cmd.envelopeId = "not-a-guid";

        CliException ex = assertThrows(CliException.class, cmd::call);
        assertEquals(ExitCode.USAGE, ex.exitCode());
        assertFalse(reader.envelopeCalled.get(), "no API call on invalid id");
    }

    // ---- happy paths / rendering -----------------------------------------

    @Test
    void rendersEnvelopeBlockWithoutRecipients(@TempDir Path home) {
        StringWriter sink = new StringWriter();
        StubReader reader = new StubReader();
        reader.envelope = statusEnvelope(VALID_ID, "Q2 invoice", "completed",
                "2026-06-01T14:03:22Z", "2026-06-01T14:03:25Z", "2026-06-02T09:11:40Z");
        EnvelopeStatusCommand cmd = command(context(home, new TableWriter(sink)), reader,
                new ByteArrayOutputStream());

        assertEquals(ExitCode.OK.code(), cmd.call());
        String out = sink.toString();
        assertTrue(out.contains("Status") && out.contains("completed"));
        assertTrue(out.contains("Q2 invoice"));
        assertFalse(reader.recipientsCalled.get(), "no recipients call without --recipients");
        assertFalse(out.contains("Recipients"), "no recipients section without --recipients");
    }

    @Test
    void unsentEnvelopeRendersDashForNullTimestamps(@TempDir Path home) {
        StringWriter sink = new StringWriter();
        StubReader reader = new StubReader();
        reader.envelope = statusEnvelope(VALID_ID, "Draft", "created",
                "2026-06-01T14:03:22Z", null, null);
        EnvelopeStatusCommand cmd = command(context(home, new TableWriter(sink)), reader,
                new ByteArrayOutputStream());

        assertEquals(ExitCode.OK.code(), cmd.call());
        // The "Sent" and "Completed" rows must render a dash, not "null".
        assertTrue(sink.toString().lines().anyMatch(l -> l.startsWith("Sent") && l.contains("-")));
        assertFalse(sink.toString().contains("null"));
    }

    @Test
    void recipientsFlagRendersSortedRecipientTable(@TempDir Path home) {
        StringWriter sink = new StringWriter();
        StubReader reader = new StubReader();
        reader.envelope = statusEnvelope(VALID_ID, "Multi", "sent", "c", "s", null);
        reader.recipientsResult = recipients(
                signerWithOrder("Zed", "zed@x.com", "sent", "2"),
                signerWithOrder("Ann", "ann@x.com", "signed", "1"));
        EnvelopeStatusCommand cmd = command(context(home, new TableWriter(sink)), reader,
                new ByteArrayOutputStream());
        cmd.recipients = true;

        assertEquals(ExitCode.OK.code(), cmd.call());
        assertTrue(reader.recipientsCalled.get());
        String out = sink.toString();
        assertTrue(out.contains("Recipients"));
        assertTrue(out.indexOf("ann@x.com") < out.indexOf("zed@x.com"),
                "recipients sorted by routing order (Ann order 1 before Zed order 2)");
    }

    @Test
    void jsonOmitsRecipientsWhenFlagAbsent(@TempDir Path home) {
        StringWriter sink = new StringWriter();
        StubReader reader = new StubReader();
        reader.envelope = statusEnvelope(VALID_ID, "Q2", "completed", "c", "s", "p");
        CliContext ctx = context(home, new JsonWriter(sink));
        EnvelopeStatusCommand cmd = command(ctx, reader, new ByteArrayOutputStream());

        assertEquals(ExitCode.OK.code(), cmd.call());
        String json = sink.toString();
        assertTrue(json.contains("\"envelopeId\""));
        assertFalse(json.contains("\"recipients\""), "recipients omitted from JSON without the flag");
    }

    @Test
    void jsonIncludesRecipientsWhenFlagGiven(@TempDir Path home) {
        StringWriter sink = new StringWriter();
        StubReader reader = new StubReader();
        reader.envelope = statusEnvelope(VALID_ID, "Q2", "sent", "c", "s", null);
        reader.recipientsResult = recipients(signerWithOrder("Ann", "ann@x.com", "sent", "1"));
        EnvelopeStatusCommand cmd = command(context(home, new JsonWriter(sink)), reader,
                new ByteArrayOutputStream());
        cmd.recipients = true;

        assertEquals(ExitCode.OK.code(), cmd.call());
        String json = sink.toString();
        assertTrue(json.contains("\"recipients\""));
        assertTrue(json.contains("ann@x.com"));
    }

    // ---- error mapping ----------------------------------------------------

    @Test
    void notFoundMapsTo404Notfound(@TempDir Path home) {
        StubReader reader = new StubReader();
        reader.envelopeError = new ApiException(404, "ENVELOPE_DOES_NOT_EXIST");
        EnvelopeStatusCommand cmd = command(context(home, new TableWriter(new StringWriter())),
                reader, new ByteArrayOutputStream());

        CliException ex = assertThrows(CliException.class, cmd::call);
        assertEquals(ExitCode.NOTFOUND, ex.exitCode());
    }

    @Test
    void transportFailureCode0MapsToNetwork(@TempDir Path home) {
        StubReader reader = new StubReader();
        reader.envelopeError = new ApiException(0, "connection refused");
        EnvelopeStatusCommand cmd = command(context(home, new TableWriter(new StringWriter())),
                reader, new ByteArrayOutputStream());

        CliException ex = assertThrows(CliException.class, cmd::call);
        assertEquals(ExitCode.NETWORK, ex.exitCode());
    }

    @Test
    void otherHttpErrorsMapToApi(@TempDir Path home) {
        for (int code : new int[] {401, 500}) {
            StubReader reader = new StubReader();
            reader.envelopeError = new ApiException(code, "boom");
            EnvelopeStatusCommand cmd = command(context(home, new TableWriter(new StringWriter())),
                    reader, new ByteArrayOutputStream());

            CliException ex = assertThrows(CliException.class, cmd::call);
            assertEquals(ExitCode.API, ex.exitCode(), "HTTP " + code + " → API");
        }
    }

    @Test
    void notAuthenticatedPropagatesAsNoauth(@TempDir Path home) {
        // No reader override → the command builds the SDK client via authenticatedApiClient(),
        // whose token provider throws AuthException(NOAUTH).
        CliContext ctx = context(home, new TableWriter(new StringWriter()), () -> {
            throw new AuthException(ExitCode.NOAUTH, "Not authenticated; run 'docusign-cli login'.");
        });
        EnvelopeStatusCommand cmd = command(ctx, null, new ByteArrayOutputStream());

        CliException ex = assertThrows(CliException.class, cmd::call);
        assertEquals(ExitCode.NOAUTH, ex.exitCode());
    }

    @Test
    void recipientsFetchFailureStillRendersStatusThenExitsApi(@TempDir Path home) {
        StringWriter sink = new StringWriter();
        ByteArrayOutputStream notes = new ByteArrayOutputStream();
        StubReader reader = new StubReader();
        reader.envelope = statusEnvelope(VALID_ID, "Q2", "completed", "c", "s", "p");
        reader.recipientsError = new ApiException(500, "recips blew up");
        EnvelopeStatusCommand cmd = command(context(home, new TableWriter(sink)), reader, notes);
        cmd.recipients = true;

        CliException ex = assertThrows(CliException.class, cmd::call);
        assertEquals(ExitCode.API, ex.exitCode());
        assertTrue(sink.toString().contains("completed"), "envelope status still rendered");
        assertTrue(notes.toString(StandardCharsets.UTF_8).contains("Could not load recipients"));
    }
}
