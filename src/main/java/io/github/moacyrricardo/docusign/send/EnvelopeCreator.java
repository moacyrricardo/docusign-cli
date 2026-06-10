package io.github.moacyrricardo.docusign.send;

import com.docusign.esign.client.ApiException;
import com.docusign.esign.model.EnvelopeDefinition;
import com.docusign.esign.model.EnvelopeSummary;

/**
 * The single DocuSign network seam for {@code send} (spec 005 §7/§9): create an envelope on an
 * account. Backed by the SDK's {@code EnvelopesApi.createEnvelope} in production
 * ({@link #usingSdk}); a lambda in tests so no live account is needed.
 */
@FunctionalInterface
public interface EnvelopeCreator {

    EnvelopeSummary create(String accountId, EnvelopeDefinition envelope) throws ApiException;

    /** Adapts the SDK's {@code EnvelopesApi} to this seam. */
    static EnvelopeCreator usingSdk(com.docusign.esign.api.EnvelopesApi api) {
        return api::createEnvelope;
    }
}
