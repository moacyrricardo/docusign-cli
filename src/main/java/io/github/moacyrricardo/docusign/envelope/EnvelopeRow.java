package io.github.moacyrricardo.docusign.envelope;

import com.docusign.esign.model.Envelope;
import com.docusign.esign.model.Recipients;
import com.docusign.esign.model.Signer;

import java.util.ArrayList;
import java.util.List;

/**
 * The renderer-neutral row for one envelope (spec 006 §5). Both the table and JSON outputs consume
 * this so they cannot drift. Built from an SDK {@link Envelope} (with optionally-embedded
 * recipients).
 */
public final class EnvelopeRow {

    private final String envelopeId;
    private final String emailSubject;
    private final String status;
    private final String sentDateTime;
    private final String lastModifiedDateTime;
    private final String completedDateTime;
    private final List<RecipientSummary> recipients;

    private EnvelopeRow(String envelopeId, String emailSubject, String status, String sentDateTime,
                        String lastModifiedDateTime, String completedDateTime,
                        List<RecipientSummary> recipients) {
        this.envelopeId = envelopeId;
        this.emailSubject = emailSubject;
        this.status = status;
        this.sentDateTime = sentDateTime;
        this.lastModifiedDateTime = lastModifiedDateTime;
        this.completedDateTime = completedDateTime;
        this.recipients = List.copyOf(recipients);
    }

    /** Maps an SDK {@link Envelope} (recipients embedded when {@code include=recipients}) to a row. */
    public static EnvelopeRow from(Envelope envelope) {
        return new EnvelopeRow(
                envelope.getEnvelopeId(),
                envelope.getEmailSubject(),
                envelope.getStatus(),
                envelope.getSentDateTime(),
                envelope.getLastModifiedDateTime(),
                envelope.getCompletedDateTime(),
                signersOf(envelope));
    }

    private static List<RecipientSummary> signersOf(Envelope envelope) {
        List<RecipientSummary> summaries = new ArrayList<>();
        Recipients recipients = envelope.getRecipients();
        if (recipients == null || recipients.getSigners() == null) {
            return summaries;
        }
        for (Signer signer : recipients.getSigners()) {
            summaries.add(new RecipientSummary(
                    signer.getName(),
                    signer.getEmail(),
                    "signer",
                    signer.getStatus(),
                    signer.getRoutingOrder()));
        }
        return summaries;
    }

    public String envelopeId() {
        return envelopeId;
    }

    public String emailSubject() {
        return emailSubject;
    }

    public String status() {
        return status;
    }

    public String sentDateTime() {
        return sentDateTime;
    }

    public String lastModifiedDateTime() {
        return lastModifiedDateTime;
    }

    public String completedDateTime() {
        return completedDateTime;
    }

    public List<RecipientSummary> recipients() {
        return recipients;
    }
}
