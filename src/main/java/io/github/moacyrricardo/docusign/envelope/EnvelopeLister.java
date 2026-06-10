package io.github.moacyrricardo.docusign.envelope;

import com.docusign.esign.client.ApiException;
import com.docusign.esign.model.Envelope;
import com.docusign.esign.model.EnvelopesInformation;
import io.github.moacyrricardo.docusign.cli.CliException;
import io.github.moacyrricardo.docusign.cli.ExitCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Drives the {@code envelopes list} query (spec 006 §3–§4): pages the server-side window/status
 * query and applies the client-side {@code --subject} (cheap) then {@code --doc-name} (deep) filters
 * with AND semantics, accumulating up to {@code limit} rows. The DocuSign access is behind
 * {@link EnvelopeQuery} so this is fully unit-testable.
 */
public final class EnvelopeLister {

    static final int MAX_PAGE_SIZE = 100;

    private final EnvelopeQuery query;

    public EnvelopeLister(EnvelopeQuery query) {
        this.query = query;
    }

    /** A list result: the rows to render and the server's reported total set size (for truncation). */
    public record Result(List<EnvelopeRow> rows, long totalSetSize) {
    }

    /** Runs the query+filters; maps SDK failures to {@code API}/{@code NETWORK}. */
    public Result list(String accountId, DateWindow window, String docuSignStatus,
                       String subjectFilter, String docNameFilter, int limit) {
        int pageSize = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        List<EnvelopeRow> rows = new ArrayList<>();
        long totalSetSize = 0;
        Integer startPosition = null;
        int seen = 0;

        try {
            while (rows.size() < limit) {
                EnvelopesInformation info = query.listStatusChanges(accountId,
                        window.from().toString(),
                        window.to() != null ? window.to().toString() : null,
                        docuSignStatus, pageSize, startPosition);

                totalSetSize = parseLong(info.getTotalSetSize(), totalSetSize);
                List<Envelope> page = info.getEnvelopes();
                if (page == null || page.isEmpty()) {
                    break;
                }

                for (Envelope envelope : page) {
                    if (rows.size() >= limit) {
                        break;
                    }
                    if (matches(accountId, envelope, subjectFilter, docNameFilter)) {
                        rows.add(EnvelopeRow.from(envelope));
                    }
                }

                seen += page.size();
                Integer next = nextStartPosition(info, seen);
                if (next == null || rows.size() >= limit) {
                    break;
                }
                startPosition = next;
            }
        } catch (ApiException e) {
            throw mapApiException(e);
        }
        return new Result(rows, totalSetSize);
    }

    /** Subject (cheap) is evaluated first; doc-name (1 API call) runs only on survivors. */
    private boolean matches(String accountId, Envelope envelope, String subjectFilter,
                            String docNameFilter) throws ApiException {
        if (subjectFilter != null && !containsIgnoreCase(envelope.getEmailSubject(), subjectFilter)) {
            return false;
        }
        if (docNameFilter != null) {
            List<String> names = query.listDocumentNames(accountId, envelope.getEnvelopeId());
            return names.stream().anyMatch(n -> containsIgnoreCase(n, docNameFilter));
        }
        return true;
    }

    private static Integer nextStartPosition(EnvelopesInformation info, int seen) {
        String nextUri = info.getNextUri();
        if (nextUri == null || nextUri.isBlank()) {
            return null;
        }
        // Prefer the start_position carried in next_uri; fall back to the running count.
        int idx = nextUri.indexOf("start_position=");
        if (idx >= 0) {
            String tail = nextUri.substring(idx + "start_position=".length());
            int amp = tail.indexOf('&');
            String value = (amp >= 0) ? tail.substring(0, amp) : tail;
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                // fall through to the running count
            }
        }
        return seen;
    }

    private static CliException mapApiException(ApiException e) {
        if (e.getCode() == 0) {
            return new CliException(ExitCode.NETWORK, "could not reach DocuSign to list envelopes", e);
        }
        if (e.getCode() == 429) {
            return new CliException(ExitCode.API,
                    "DocuSign rate limit hit (HTTP 429) — narrow --from/--to or drop --doc-name.", e);
        }
        return new CliException(ExitCode.API,
                "DocuSign rejected the list request (HTTP " + e.getCode() + "): " + body(e), e);
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null
                && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
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
