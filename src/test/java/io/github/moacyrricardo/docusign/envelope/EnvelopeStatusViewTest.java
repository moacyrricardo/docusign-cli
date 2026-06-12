package io.github.moacyrricardo.docusign.envelope;

import com.docusign.esign.model.Envelope;
import com.docusign.esign.model.Recipients;
import org.junit.jupiter.api.Test;

import static io.github.moacyrricardo.docusign.envelope.EnvelopeFixtures.recipients;
import static io.github.moacyrricardo.docusign.envelope.EnvelopeFixtures.signerWithOrder;
import static io.github.moacyrricardo.docusign.envelope.EnvelopeFixtures.statusEnvelope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EnvelopeStatusViewTest {

    @Test
    void mapsEnvelopeFieldsVerbatimWithNullRecipientsWhenNotRequested() {
        Envelope e = statusEnvelope("env-1", "Q2 invoice", "completed",
                "2026-06-01T14:03:22Z", "2026-06-01T14:03:25Z", "2026-06-02T09:11:40Z");

        EnvelopeStatusView view = EnvelopeStatusView.from(e);

        assertEquals("env-1", view.envelopeId());
        assertEquals("completed", view.status());
        assertEquals("Q2 invoice", view.emailSubject());
        assertEquals("2026-06-01T14:03:22Z", view.createdDateTime());
        assertEquals("2026-06-01T14:03:25Z", view.sentDateTime());
        assertEquals("2026-06-02T09:11:40Z", view.completedDateTime());
        assertNull(view.recipients(), "recipients omitted (null) when not requested");
    }

    @Test
    void preservesNullTimestampsForUnsentEnvelope() {
        Envelope e = statusEnvelope("env-2", "Draft", "created",
                "2026-06-01T14:03:22Z", null, null);

        EnvelopeStatusView view = EnvelopeStatusView.from(e);

        assertNull(view.sentDateTime());
        assertNull(view.completedDateTime());
    }

    @Test
    void sortsRecipientsByNumericRoutingOrderThenName() {
        Recipients r = recipients(
                signerWithOrder("Zed", "zed@x.com", "sent", "2"),
                signerWithOrder("Ann", "ann@x.com", "signed", "1"),
                signerWithOrder("Bob", "bob@x.com", "delivered", "2"));   // tie on order → name

        EnvelopeStatusView view = EnvelopeStatusView.from(
                statusEnvelope("env-3", "Multi", "sent", "c", "s", null), r);

        assertEquals(3, view.recipients().size());
        assertEquals("Ann", view.recipients().get(0).name());   // order 1
        assertEquals("Bob", view.recipients().get(1).name());   // order 2, name before Zed
        assertEquals("Zed", view.recipients().get(2).name());
    }

    @Test
    void emptyRecipientsRequestedYieldsEmptyListNotNull() {
        EnvelopeStatusView view = EnvelopeStatusView.from(
                statusEnvelope("env-4", "None", "sent", "c", "s", null), new Recipients());
        assertEquals(0, view.recipients().size());
    }
}
