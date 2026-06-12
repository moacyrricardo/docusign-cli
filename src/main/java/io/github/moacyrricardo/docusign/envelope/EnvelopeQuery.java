package io.github.moacyrricardo.docusign.envelope;

import com.docusign.esign.api.EnvelopesApi;
import com.docusign.esign.client.ApiException;
import com.docusign.esign.model.EnvelopesInformation;

import java.util.List;

/**
 * The DocuSign read seam for {@code envelopes list} (spec 006 §3). Isolated so unit tests need no
 * live account: production wraps the SDK's {@code EnvelopesApi}, tests supply a stub.
 */
public interface EnvelopeQuery {

    /**
     * One page of status changes from {@code startPosition} (null = first page).
     *
     * @param status DocuSign status value (already mapped), or null for all
     */
    EnvelopesInformation listStatusChanges(String accountId, String fromDate, String toDate,
                                            String status, int pageSize, Integer startPosition)
            throws ApiException;

    /** Document names for one envelope (for {@code --doc-name} matching). */
    List<String> listDocumentNames(String accountId, String envelopeId) throws ApiException;

    /** Adapts the SDK {@code EnvelopesApi} to this seam. */
    static EnvelopeQuery usingSdk(EnvelopesApi api) {
        return new SdkEnvelopeQuery(api);
    }

    /** SDK-backed implementation; the inner-class options live here, away from the lister. */
    final class SdkEnvelopeQuery implements EnvelopeQuery {

        private final EnvelopesApi api;

        SdkEnvelopeQuery(EnvelopesApi api) {
            this.api = api;
        }

        @Override
        public EnvelopesInformation listStatusChanges(String accountId, String fromDate, String toDate,
                                                      String status, int pageSize, Integer startPosition)
                throws ApiException {
            EnvelopesApi.ListStatusChangesOptions opts = api.new ListStatusChangesOptions();
            opts.setFromDate(fromDate);
            if (toDate != null) {
                opts.setToDate(toDate);
            }
            if (status != null) {
                opts.setStatus(status);
            }
            opts.setCount(String.valueOf(pageSize));
            opts.setInclude("recipients");
            if (startPosition != null) {
                opts.setStartPosition(String.valueOf(startPosition));
            }
            return api.listStatusChanges(accountId, opts);
        }

        @Override
        public List<String> listDocumentNames(String accountId, String envelopeId) throws ApiException {
            var result = api.listDocuments(accountId, envelopeId);
            List<String> names = new java.util.ArrayList<>();
            if (result != null && result.getEnvelopeDocuments() != null) {
                result.getEnvelopeDocuments().forEach(d -> names.add(d.getName()));
            }
            return names;
        }
    }
}
