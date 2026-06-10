package io.github.moacyrricardo.docusign.envelope;

import com.docusign.esign.api.EnvelopesApi;
import com.docusign.esign.client.ApiException;
import com.docusign.esign.model.Envelope;
import com.docusign.esign.model.Recipients;

/**
 * The DocuSign read seam for {@code envelope status} (spec 007 §2). Isolated so unit tests need no
 * live account: production wraps the SDK's {@code EnvelopesApi}, tests supply a stub. Mirrors the
 * {@link EnvelopeQuery} seam used by {@code envelopes list} (006).
 */
public interface EnvelopeStatusReader {

    /** Fetch one envelope's status (no recipients embedded by default). */
    Envelope getEnvelope(String accountId, String envelopeId) throws ApiException;

    /** Fetch the envelope's recipients (only when {@code --recipients} is given). */
    Recipients listRecipients(String accountId, String envelopeId) throws ApiException;

    /** Adapts the SDK {@code EnvelopesApi} to this seam. */
    static EnvelopeStatusReader usingSdk(EnvelopesApi api) {
        return new SdkEnvelopeStatusReader(api);
    }

    /** SDK-backed implementation. */
    final class SdkEnvelopeStatusReader implements EnvelopeStatusReader {

        private final EnvelopesApi api;

        SdkEnvelopeStatusReader(EnvelopesApi api) {
            this.api = api;
        }

        @Override
        public Envelope getEnvelope(String accountId, String envelopeId) throws ApiException {
            return api.getEnvelope(accountId, envelopeId);
        }

        @Override
        public Recipients listRecipients(String accountId, String envelopeId) throws ApiException {
            return api.listRecipients(accountId, envelopeId);
        }
    }
}
