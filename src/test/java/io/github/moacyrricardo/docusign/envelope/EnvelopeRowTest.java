package io.github.moacyrricardo.docusign.envelope;

import com.docusign.esign.model.Envelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvelopeRowTest {

    @Test
    void mapsCoreFieldsAndSigners() {
        Envelope e = EnvelopeFixtures.withSigners(
                EnvelopeFixtures.envelope("env-1", "Invoice", "completed"),
                EnvelopeFixtures.signer("Moacyr", "moa@x.com", "completed"));

        EnvelopeRow row = EnvelopeRow.from(e);
        assertEquals("env-1", row.envelopeId());
        assertEquals("Invoice", row.emailSubject());
        assertEquals("completed", row.status());
        assertEquals(1, row.recipients().size());
        assertEquals("moa@x.com", row.recipients().get(0).email());
        assertEquals("signer", row.recipients().get(0).recipientType());
    }

    @Test
    void handlesEnvelopeWithoutRecipients() {
        EnvelopeRow row = EnvelopeRow.from(EnvelopeFixtures.envelope("env-2", "No recips", "sent"));
        assertTrue(row.recipients().isEmpty());
    }
}
