package io.github.moacyrricardo.docusign.envelope;

import com.docusign.esign.api.EnvelopesApi;
import io.github.moacyrricardo.docusign.cli.CliContext;
import io.github.moacyrricardo.docusign.cli.GlobalOptions;
import io.github.moacyrricardo.docusign.cli.ExitCode;
import io.github.moacyrricardo.docusign.output.OutputWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.PrintStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code docusign-cli envelopes list} — list the user's envelopes with date / status / subject /
 * document-name filters (spec 006). Server-side date+status query, client-side subject (cheap) and
 * document-name (deep) filtering with AND semantics, rendered as a table or {@code --json}.
 */
@Command(name = "list",
        description = "List envelopes, with optional date / status / subject / document-name filters.")
public final class EnvelopesListCommand implements Callable<Integer> {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int SUBJECT_WIDTH = 32;

    @ParentCommand
    EnvelopesCommand envelopes;

    @Mixin
    GlobalOptions globalOptions;

    @Option(names = "--doc-name",
            description = "Keep only envelopes with a document whose name contains this substring "
                    + "(case-insensitive). DEEP: one extra API call per envelope examined.")
    String docName;

    @Option(names = "--subject",
            description = "Keep only envelopes whose email subject contains this substring "
                    + "(case-insensitive). CHEAP: no extra API calls.")
    String subject;

    @Option(names = "--from",
            description = "Window start (inclusive). yyyy-MM-dd or ISO-8601 instant. Default: 30 days ago.")
    String from;

    @Option(names = "--to",
            description = "Window end (inclusive). Same formats as --from. Default: now.")
    String to;

    @Option(names = "--status",
            description = "Filter by status: signed, sent, delivered, voided, declined, created.")
    String status;

    @Option(names = "--limit", defaultValue = "100",
            description = "Maximum rows to return (default 100).")
    int limit;

    // ---- test seams ----
    private EnvelopeQuery queryOverride;
    private Instant nowOverride;
    private PrintStream notes = System.err;

    void useQuery(EnvelopeQuery query) {
        this.queryOverride = query;
    }

    void useNow(Instant now) {
        this.nowOverride = now;
    }

    void useNotesStream(PrintStream notes) {
        this.notes = notes;
    }

    @Override
    public Integer call() {
        CliContext context = envelopes.root().context();
        OutputWriter out = context.output();
        ZoneId zone = ZoneId.systemDefault();
        Instant now = nowOverride != null ? nowOverride : Instant.now();

        DateWindow window = DateWindow.resolve(from, to, now, zone);
        String docuSignStatus = (status != null && !status.isBlank())
                ? StatusAlias.toDocuSign(status) : null;

        if (!globalOptions.json) {
            if (window.fromDefaulted()) {
                notes.println("Listing envelopes since "
                        + LocalDateTime.ofInstant(window.from(), zone).format(DateTimeFormatter.ISO_DATE)
                        + " (default 30-day window; use --from to widen).");
            }
            if (docName != null && !docName.isBlank()) {
                notes.println("--doc-name fetches documents per envelope; "
                        + "narrow --from/--to to reduce API calls.");
            }
        }

        EnvelopeQuery query = (queryOverride != null)
                ? queryOverride
                : EnvelopeQuery.usingSdk(new EnvelopesApi(context.authenticatedApiClient()));
        EnvelopeLister.Result result = new EnvelopeLister(query).list(
                context.config().accountId(), window, docuSignStatus,
                blankToNull(subject), blankToNull(docName), limit);

        render(out, zone, result);
        return ExitCode.OK.code();
    }

    private void render(OutputWriter out, ZoneId zone, EnvelopeLister.Result result) {
        List<EnvelopeRow> rows = result.rows();
        out.object(toPayload(rows));

        if (rows.isEmpty()) {
            out.message("No envelopes found.");
            return;
        }

        List<String> headers = List.of(
                "ENVELOPE ID", "SUBJECT", "STATUS", "SENT", "LAST MODIFIED", "RECIPIENTS");
        List<List<String>> tableRows = new ArrayList<>();
        for (EnvelopeRow row : rows) {
            tableRows.add(List.of(
                    nullToDash(row.envelopeId()),
                    truncate(row.emailSubject(), SUBJECT_WIDTH),
                    nullToDash(row.status()),
                    formatDate(row.sentDateTime(), zone),
                    formatDate(row.lastModifiedDateTime(), zone),
                    recipientSummary(row.recipients())));
        }
        out.table(headers, tableRows);

        if (!globalOptions.json && rows.size() == limit && result.totalSetSize() > limit) {
            notes.println("Showing first " + limit + " of " + result.totalSetSize()
                    + "; raise --limit or narrow the date range.");
        }
    }

    private List<Map<String, Object>> toPayload(List<EnvelopeRow> rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (EnvelopeRow row : rows) {
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("envelopeId", row.envelopeId());
            obj.put("emailSubject", row.emailSubject());
            obj.put("status", row.status());
            obj.put("sentDateTime", row.sentDateTime());
            obj.put("lastModifiedDateTime", row.lastModifiedDateTime());
            if (row.completedDateTime() != null) {
                obj.put("completedDateTime", row.completedDateTime());
            }
            List<Map<String, Object>> recips = new ArrayList<>();
            for (RecipientSummary r : row.recipients()) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("name", r.name());
                rm.put("email", r.email());
                rm.put("recipientType", r.recipientType());
                rm.put("status", r.status());
                rm.put("routingOrder", r.routingOrder());
                recips.add(rm);
            }
            obj.put("recipients", recips);
            list.add(obj);
        }
        return list;
    }

    private static String recipientSummary(List<RecipientSummary> recipients) {
        if (recipients.isEmpty()) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(2, recipients.size());
        for (int i = 0; i < shown; i++) {
            RecipientSummary r = recipients.get(i);
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(r.name()).append(" <").append(r.email()).append(">: ")
                    .append(r.status() != null ? r.status() : "—");
        }
        if (recipients.size() > shown) {
            sb.append(" (+").append(recipients.size() - shown).append(" more)");
        }
        return sb.toString();
    }

    private static String formatDate(String iso, ZoneId zone) {
        if (iso == null || iso.isBlank()) {
            return "—";
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(iso), zone).format(DISPLAY);
        } catch (Exception e) {
            return iso;   // unexpected format — show it raw rather than crash
        }
    }

    private static String truncate(String value, int width) {
        if (value == null) {
            return "—";
        }
        return value.length() <= width ? value : value.substring(0, width - 1) + "…";
    }

    private static String nullToDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
