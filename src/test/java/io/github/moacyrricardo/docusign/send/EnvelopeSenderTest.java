package io.github.moacyrricardo.docusign.send;

import com.docusign.esign.client.ApiException;
import com.docusign.esign.model.EnvelopeDefinition;
import com.docusign.esign.model.EnvelopeSummary;
import io.github.moacyrricardo.docusign.cli.CliException;
import io.github.moacyrricardo.docusign.cli.ExitCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvelopeSenderTest {

    private SendPlan planWith(Path pdf) {
        DeclaredRecipient moa = new DeclaredRecipient("Moacyr", "moa@x.com");
        DeclaredRecipient ana = new DeclaredRecipient("Ana", "ana@x.com");
        Map<DeclaredRecipient, List<AnchorSpec>> bindings = Map.of(
                moa, List.of(new AnchorSpec("_s1_", TabType.SIGNATURE, "moa@x.com"),
                        new AnchorSpec("_d1_", TabType.DATE, "moa@x.com")),
                ana, List.of(new AnchorSpec("_s2_", TabType.SIGNATURE, "ana@x.com")));
        return new SendPlan(pdf, "Please sign", List.of(moa, ana), bindings);
    }

    @Test
    void definitionHasBase64DocSequentialSignersAndSentStatus(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("invoice.pdf");
        Files.write(pdf, "%PDF-1.4 fake".getBytes());

        EnvelopeSender sender = new EnvelopeSender((account, env) -> new EnvelopeSummary());
        EnvelopeDefinition env = sender.buildDefinition(planWith(pdf));

        assertEquals("sent", env.getStatus());
        assertEquals("Please sign", env.getEmailSubject());
        assertEquals(1, env.getDocuments().size());
        assertEquals("invoice.pdf", env.getDocuments().get(0).getName());
        assertTrue(env.getDocuments().get(0).getDocumentBase64().length() > 0);

        var signers = env.getRecipients().getSigners();
        assertEquals(2, signers.size());
        assertEquals("1", signers.get(0).getRecipientId());
        assertEquals("2", signers.get(1).getRecipientId());
        assertEquals(2, signers.get(0).getTabs().getSignHereTabs().size()
                + signers.get(0).getTabs().getDateSignedTabs().size());
        assertEquals(1, signers.get(1).getTabs().getSignHereTabs().size());
    }

    @Test
    void apiExceptionCode0MapsToNetwork() {
        EnvelopeSender sender = new EnvelopeSender((account, env) -> {
            throw new ApiException(0, "Connection refused");
        });
        CliException ex = assertThrows(CliException.class,
                () -> sender.send("acct", new EnvelopeDefinition()));
        assertEquals(ExitCode.NETWORK, ex.exitCode());
    }

    @Test
    void apiExceptionNonZeroMapsToApi() {
        EnvelopeSender sender = new EnvelopeSender((account, env) -> {
            throw new ApiException(400, "{\"errorCode\":\"INVALID\"}");
        });
        CliException ex = assertThrows(CliException.class,
                () -> sender.send("acct", new EnvelopeDefinition()));
        assertEquals(ExitCode.API, ex.exitCode());
    }

    @Test
    void successReturnsSummary(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("d.pdf");
        Files.write(pdf, "%PDF fake".getBytes());
        EnvelopeSummary canned = new EnvelopeSummary();
        canned.setEnvelopeId("env-123");
        canned.setStatus("sent");

        EnvelopeSender sender = new EnvelopeSender((account, env) -> {
            assertEquals("acct-9", account);
            return canned;
        });
        EnvelopeSummary result = sender.send("acct-9", sender.buildDefinition(planWith(pdf)));
        assertEquals("env-123", result.getEnvelopeId());
    }
}
