package io.github.moacyrricardo.docusign.envelope;

import com.docusign.esign.model.Envelope;
import com.docusign.esign.model.Recipients;
import com.docusign.esign.model.Signer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The renderer-neutral view of a single envelope's status (spec 007 §3). Both the table and JSON
 * outputs consume this so they stay in sync. {@code recipients} is {@code null} when
 * {@code --recipients} was not requested, and a (possibly empty) list otherwise.
 */
public record EnvelopeStatusView(
        String envelopeId,
        String status,
        String emailSubject,
        String createdDateTime,
        String sentDateTime,
        String completedDateTime,
        List<RecipientView> recipients) {

    /** Maps the SDK {@link Envelope} (recipients absent → {@code recipients == null}). */
    public static EnvelopeStatusView from(Envelope envelope) {
        return from(envelope, null);
    }

    /**
     * Maps the SDK {@link Envelope}; when {@code recipients} is non-null its signers are surfaced,
     * sorted by numeric routing order then name (spec 007 §3). v1 surfaces signers only.
     */
    public static EnvelopeStatusView from(Envelope envelope, Recipients recipients) {
        return new EnvelopeStatusView(
                envelope.getEnvelopeId(),
                envelope.getStatus(),
                envelope.getEmailSubject(),
                envelope.getCreatedDateTime(),
                envelope.getSentDateTime(),
                envelope.getCompletedDateTime(),
                recipients == null ? null : signerViews(recipients));
    }

    private static List<RecipientView> signerViews(Recipients recipients) {
        List<RecipientView> views = new ArrayList<>();
        if (recipients.getSigners() != null) {
            for (Signer signer : recipients.getSigners()) {
                views.add(new RecipientView(
                        signer.getName(),
                        signer.getEmail(),
                        signer.getStatus(),
                        signer.getRoutingOrder()));
            }
        }
        views.sort(Comparator
                .comparingLong((RecipientView r) -> orderOf(r.order()))
                .thenComparing(r -> r.name() == null ? "" : r.name()));
        return views;
    }

    /** Parses the SDK's string routing order to a number; unparseable orders sort last. */
    private static long orderOf(String order) {
        if (order == null || order.isBlank()) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(order.trim());
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}
