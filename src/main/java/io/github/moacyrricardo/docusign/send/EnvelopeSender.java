package io.github.moacyrricardo.docusign.send;

import com.docusign.esign.client.ApiException;
import com.docusign.esign.model.Document;
import com.docusign.esign.model.EnvelopeDefinition;
import com.docusign.esign.model.EnvelopeSummary;
import com.docusign.esign.model.Recipients;
import com.docusign.esign.model.Signer;
import com.docusign.esign.model.Tabs;
import io.github.moacyrricardo.docusign.cli.CliException;
import io.github.moacyrricardo.docusign.cli.ExitCode;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Builds a DocuSign {@link EnvelopeDefinition} from a {@link SendPlan} and creates it via the
 * {@link EnvelopeCreator} seam (spec 005 §7). The transport is isolated so unit tests need no live
 * account. SDK {@link ApiException}s are mapped: transport (code 0) → {@code NETWORK}, otherwise
 * → {@code API}.
 */
public final class EnvelopeSender {

    private static final String DOCUMENT_ID = "1";
    private static final String STATUS_SENT = "sent";

    private final EnvelopeCreator creator;

    public EnvelopeSender(EnvelopeCreator creator) {
        this.creator = creator;
    }

    /** Builds the envelope definition from the plan (pure; the document is read here and base64'd). */
    public EnvelopeDefinition buildDefinition(SendPlan plan) {
        byte[] pdfBytes;
        try {
            pdfBytes = Files.readAllBytes(plan.pdf());
        } catch (IOException e) {
            throw new CliException(ExitCode.INPUT,
                    "PDF not found or unreadable: " + plan.pdf());
        }

        Document doc = new Document();
        doc.setDocumentBase64(Base64.getEncoder().encodeToString(pdfBytes));
        doc.setName(plan.pdf().getFileName().toString());
        doc.setFileExtension("pdf");
        doc.setDocumentId(DOCUMENT_ID);

        TabFactory tabFactory = new TabFactory();
        List<Signer> signers = new ArrayList<>();
        int recipientId = 1;
        for (DeclaredRecipient recipient : plan.recipients()) {
            Signer signer = new Signer();
            signer.setRecipientId(String.valueOf(recipientId++));
            signer.setName(recipient.name());
            signer.setEmail(recipient.email());

            Tabs tabs = new Tabs();
            for (AnchorSpec spec : plan.bindingsFor(recipient)) {
                tabFactory.applyTo(tabs, spec);
            }
            signer.setTabs(tabs);
            signers.add(signer);
        }

        Recipients recipients = new Recipients();
        recipients.setSigners(signers);

        EnvelopeDefinition env = new EnvelopeDefinition();
        env.setEmailSubject(plan.subject());
        env.setDocuments(List.of(doc));
        env.setRecipients(recipients);
        env.setStatus(STATUS_SENT);
        return env;
    }

    /** Creates the envelope on {@code accountId}; maps SDK failures to the right exit code. */
    public EnvelopeSummary send(String accountId, EnvelopeDefinition env) {
        try {
            return creator.create(accountId, env);
        } catch (ApiException e) {
            if (e.getCode() == 0) {
                throw new CliException(ExitCode.NETWORK,
                        "could not reach DocuSign to create the envelope", e);
            }
            throw new CliException(ExitCode.API,
                    "DocuSign rejected the envelope (HTTP " + e.getCode() + "): " + body(e), e);
        }
    }

    private static String body(ApiException e) {
        String responseBody = e.getResponseBody();
        if (responseBody != null && !responseBody.isBlank()) {
            return responseBody.strip();
        }
        return e.getMessage() != null ? e.getMessage() : "no detail";
    }
}
