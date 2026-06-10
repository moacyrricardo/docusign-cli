package io.github.moacyrricardo.docusign.envelope;

import com.docusign.esign.api.EnvelopesApi;
import com.docusign.esign.client.ApiException;
import com.docusign.esign.model.Envelope;
import com.docusign.esign.model.Recipients;
import io.github.moacyrricardo.docusign.cli.CliContext;
import io.github.moacyrricardo.docusign.cli.CliException;
import io.github.moacyrricardo.docusign.cli.ExitCode;
import io.github.moacyrricardo.docusign.cli.GlobalOptions;
import io.github.moacyrricardo.docusign.output.OutputWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * {@code docusign-cli envelope status <envelopeId>} — fetch and display one envelope's status, with
 * optional per-recipient detail (spec 007). Read-only: a pre-flight GUID check, one
 * {@code getEnvelope} call, and (with {@code --recipients}) a second {@code listRecipients} call,
 * rendered as a table or {@code --json}. The DocuSign access is behind {@link EnvelopeStatusReader}
 * so this is fully unit-testable.
 */
@Command(name = "status",
        description = "Show the status of a single envelope by ID.")
public final class EnvelopeStatusCommand implements Callable<Integer> {

    private static final Pattern GUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @ParentCommand
    EnvelopeCommand envelope;

    @Mixin
    GlobalOptions globalOptions;

    @Parameters(index = "0", paramLabel = "<envelopeId>",
            description = "DocuSign envelope ID (GUID).")
    String envelopeId;

    @Option(names = "--recipients",
            description = "Also list per-recipient status (name, email, status, order).")
    boolean recipients;

    // ---- test seams (package-private) ----
    private EnvelopeStatusReader readerOverride;
    private PrintStream notes = System.err;

    void useReader(EnvelopeStatusReader reader) {
        this.readerOverride = reader;
    }

    void useNotesStream(PrintStream notes) {
        this.notes = notes;
    }

    @Override
    public Integer call() {
        if (envelopeId == null || !GUID.matcher(envelopeId.trim()).matches()) {
            throw new CliException(ExitCode.USAGE,
                    "Invalid envelope ID: " + envelopeId + " (expected a GUID).");
        }
        String id = envelopeId.trim();

        CliContext context = envelope.root().context();
        OutputWriter out = context.output();
        String accountId = context.config().accountId();

        // authenticatedApiClient() throws AuthException (→ NOAUTH) when no token is available (003).
        EnvelopeStatusReader reader = (readerOverride != null)
                ? readerOverride
                : EnvelopeStatusReader.usingSdk(new EnvelopesApi(context.authenticatedApiClient()));

        Envelope env;
        try {
            env = reader.getEnvelope(accountId, id);
        } catch (ApiException e) {
            throw mapApiException(e, id);
        }

        if (!recipients) {
            render(out, EnvelopeStatusView.from(env));
            return ExitCode.OK.code();
        }

        // --recipients: a secondary call whose failure must not lose the primary status (§5).
        Recipients recips;
        try {
            recips = reader.listRecipients(accountId, id);
        } catch (ApiException e) {
            render(out, EnvelopeStatusView.from(env));
            notes.println("Could not load recipients: " + body(e));
            throw new CliException(ExitCode.API,
                    "envelope status loaded, but recipients could not be fetched (HTTP "
                            + e.getCode() + ").", e);
        }
        render(out, EnvelopeStatusView.from(env, recips));
        return ExitCode.OK.code();
    }

    private void render(OutputWriter out, EnvelopeStatusView view) {
        out.object(toPayload(view));

        Map<String, String> block = new LinkedHashMap<>();
        block.put("Envelope", dash(view.envelopeId()));
        block.put("Status", dash(view.status()));
        block.put("Subject", dash(view.emailSubject()));
        block.put("Created", dash(view.createdDateTime()));
        block.put("Sent", dash(view.sentDateTime()));
        block.put("Completed", dash(view.completedDateTime()));
        out.record(block);

        if (view.recipients() != null) {
            out.message("");
            out.message("Recipients");
            List<String> headers = List.of("Order", "Name", "Email", "Status");
            List<List<String>> rows = new ArrayList<>();
            for (RecipientView r : view.recipients()) {
                rows.add(List.of(dash(r.order()), dash(r.name()), dash(r.email()), dash(r.status())));
            }
            out.table(headers, rows);
        }
    }

    private Map<String, Object> toPayload(EnvelopeStatusView view) {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("envelopeId", view.envelopeId());
        obj.put("status", view.status());
        obj.put("emailSubject", view.emailSubject());
        obj.put("createdDateTime", view.createdDateTime());
        obj.put("sentDateTime", view.sentDateTime());
        obj.put("completedDateTime", view.completedDateTime());
        if (view.recipients() != null) {   // §3.2: omit when --recipients not given
            List<Map<String, Object>> recips = new ArrayList<>();
            for (RecipientView r : view.recipients()) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("name", r.name());
                rm.put("email", r.email());
                rm.put("status", r.status());
                rm.put("order", r.order());
                recips.add(rm);
            }
            obj.put("recipients", recips);
        }
        return obj;
    }

    private static CliException mapApiException(ApiException e, String envelopeId) {
        if (e.getCode() == 0) {
            return new CliException(ExitCode.NETWORK,
                    "could not reach DocuSign to fetch envelope " + envelopeId, e);
        }
        if (e.getCode() == 404) {
            return new CliException(ExitCode.NOTFOUND, "Envelope not found: " + envelopeId, e);
        }
        return new CliException(ExitCode.API,
                "DocuSign API error (" + e.getCode() + "): " + body(e), e);
    }

    private static String body(ApiException e) {
        String responseBody = e.getResponseBody();
        if (responseBody != null && !responseBody.isBlank()) {
            return responseBody.strip();
        }
        return e.getMessage() != null ? e.getMessage() : "no detail";
    }

    private static String dash(String value) {
        return (value == null || value.isBlank()) ? "-" : value;
    }
}
