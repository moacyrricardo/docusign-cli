package io.github.moacyrricardo.docusign.envelope;

/**
 * A single recipient's summary on an envelope row (spec 006 §5). Used by both the table and JSON
 * renderers so they stay in sync.
 */
public record RecipientSummary(String name, String email, String recipientType, String status,
                               String routingOrder) {
}
