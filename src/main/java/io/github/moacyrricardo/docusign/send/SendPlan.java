package io.github.moacyrricardo.docusign.send;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A validated, ready-to-send envelope plan (spec 005 §5/§7): the document, subject, the ordered
 * signers, and each signer's resolved anchor bindings. Built by {@link SendPlanBuilder} after all
 * input is validated, before any network call.
 */
public final class SendPlan {

    private final Path pdf;
    private final String subject;
    private final List<DeclaredRecipient> recipients;
    private final Map<DeclaredRecipient, List<AnchorSpec>> bindingsByRecipient;

    public SendPlan(Path pdf, String subject, List<DeclaredRecipient> recipients,
                    Map<DeclaredRecipient, List<AnchorSpec>> bindingsByRecipient) {
        this.pdf = pdf;
        this.subject = subject;
        this.recipients = List.copyOf(recipients);
        this.bindingsByRecipient = Map.copyOf(bindingsByRecipient);
    }

    public Path pdf() {
        return pdf;
    }

    public String subject() {
        return subject;
    }

    /** Signers in declaration order; {@code recipientId} is the 1-based index in this list. */
    public List<DeclaredRecipient> recipients() {
        return recipients;
    }

    /** The anchor bindings for a recipient (empty if none). */
    public List<AnchorSpec> bindingsFor(DeclaredRecipient recipient) {
        return bindingsByRecipient.getOrDefault(recipient, List.of());
    }
}
