package io.github.moacyrricardo.docusign.envelope;

import com.docusign.esign.client.ApiException;
import com.docusign.esign.model.EnvelopesInformation;
import io.github.moacyrricardo.docusign.cli.CliException;
import io.github.moacyrricardo.docusign.cli.ExitCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.moacyrricardo.docusign.envelope.EnvelopeFixtures.envelope;
import static io.github.moacyrricardo.docusign.envelope.EnvelopeFixtures.page;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvelopeListerTest {

    private static final DateWindow WINDOW =
            DateWindow.resolve("2026-05-01", null, Instant.parse("2026-06-10T00:00:00Z"), ZoneId.of("UTC"));

    /** A scripted query: returns queued pages and records listDocumentNames calls. */
    private static final class StubQuery implements EnvelopeQuery {
        final Deque<EnvelopesInformation> pages = new ArrayDeque<>();
        final Map<String, List<String>> docsByEnvelope = new HashMap<>();
        final AtomicInteger listDocsCalls = new AtomicInteger();
        ApiException toThrow;

        @Override
        public EnvelopesInformation listStatusChanges(String accountId, String fromDate, String toDate,
                                                      String status, int pageSize, Integer startPosition)
                throws ApiException {
            if (toThrow != null) {
                throw toThrow;
            }
            return pages.isEmpty() ? page("0", null) : pages.poll();
        }

        @Override
        public List<String> listDocumentNames(String accountId, String envelopeId) {
            listDocsCalls.incrementAndGet();
            return docsByEnvelope.getOrDefault(envelopeId, List.of());
        }
    }

    @Test
    void returnsRowsAndTotalSetSize() {
        StubQuery query = new StubQuery();
        query.pages.add(page("2", null, envelope("e1", "A", "sent"), envelope("e2", "B", "completed")));

        EnvelopeLister.Result result = new EnvelopeLister(query)
                .list("acct", WINDOW, null, null, null, 100);

        assertEquals(2, result.rows().size());
        assertEquals(2L, result.totalSetSize());
    }

    @Test
    void subjectFilterIsCaseInsensitiveAndMakesNoDocCalls() {
        StubQuery query = new StubQuery();
        query.pages.add(page("3", null,
                envelope("e1", "Quarterly Invoice", "sent"),
                envelope("e2", "Welcome", "sent"),
                envelope("e3", "INVOICE final", "sent")));

        EnvelopeLister.Result result = new EnvelopeLister(query)
                .list("acct", WINDOW, null, "invoice", null, 100);

        assertEquals(2, result.rows().size());
        assertEquals(0, query.listDocsCalls.get(), "--subject must not fetch documents");
    }

    @Test
    void docNameFilterMatchesViaListDocuments() {
        StubQuery query = new StubQuery();
        query.pages.add(page("2", null, envelope("e1", "A", "sent"), envelope("e2", "B", "sent")));
        query.docsByEnvelope.put("e1", List.of("Contract.pdf"));
        query.docsByEnvelope.put("e2", List.of("Receipt.pdf"));

        EnvelopeLister.Result result = new EnvelopeLister(query)
                .list("acct", WINDOW, null, null, "contract", 100);

        assertEquals(1, result.rows().size());
        assertEquals("e1", result.rows().get(0).envelopeId());
    }

    @Test
    void combinedSubjectThenDocNameRunsDocFetchOnlyOnSubjectSurvivors() {
        StubQuery query = new StubQuery();
        query.pages.add(page("2", null,
                envelope("e1", "Invoice March", "sent"),     // subject matches
                envelope("e2", "Greeting", "sent")));         // subject does NOT match
        query.docsByEnvelope.put("e1", List.of("invoice.pdf"));

        EnvelopeLister.Result result = new EnvelopeLister(query)
                .list("acct", WINDOW, null, "invoice", "invoice", 100);

        assertEquals(1, result.rows().size());
        assertEquals(1, query.listDocsCalls.get(),
                "doc fetch should run only for the subject-surviving envelope");
    }

    @Test
    void paginationAccumulatesUpToLimitThenStops() {
        StubQuery query = new StubQuery();
        query.pages.add(page("5", "https://x/?start_position=2",
                envelope("e1", "A", "sent"), envelope("e2", "B", "sent")));
        query.pages.add(page("5", "https://x/?start_position=4",
                envelope("e3", "C", "sent"), envelope("e4", "D", "sent")));
        query.pages.add(page("5", null, envelope("e5", "E", "sent")));

        EnvelopeLister.Result result = new EnvelopeLister(query)
                .list("acct", WINDOW, null, null, null, 3);

        assertEquals(3, result.rows().size());
        assertEquals(5L, result.totalSetSize());
    }

    @Test
    void apiExceptionCode0MapsToNetwork() {
        StubQuery query = new StubQuery();
        query.toThrow = new ApiException(0, "connection refused");
        CliException ex = assertThrows(CliException.class,
                () -> new EnvelopeLister(query).list("acct", WINDOW, null, null, null, 10));
        assertEquals(ExitCode.NETWORK, ex.exitCode());
    }

    @Test
    void apiExceptionNonZeroMapsToApi() {
        StubQuery query = new StubQuery();
        query.toThrow = new ApiException(500, "boom");
        CliException ex = assertThrows(CliException.class,
                () -> new EnvelopeLister(query).list("acct", WINDOW, null, null, null, 10));
        assertEquals(ExitCode.API, ex.exitCode());
    }
}
