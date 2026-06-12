package io.github.moacyrricardo.docusign.send;

import com.docusign.esign.api.EnvelopesApi;
import com.docusign.esign.model.EnvelopeDefinition;
import com.docusign.esign.model.EnvelopeSummary;
import io.github.moacyrricardo.docusign.anchor.AnchorCandidate;
import io.github.moacyrricardo.docusign.anchor.AnchorScanner;
import io.github.moacyrricardo.docusign.anchor.ScanOptions;
import io.github.moacyrricardo.docusign.cli.CliContext;
import io.github.moacyrricardo.docusign.cli.CliException;
import io.github.moacyrricardo.docusign.cli.ExitCode;
import io.github.moacyrricardo.docusign.cli.GlobalOptions;
import io.github.moacyrricardo.docusign.cli.RootCommand;
import io.github.moacyrricardo.docusign.output.OutputWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code docusign-cli send <pdf> ...} — build and send an envelope whose tabs are positioned by
 * anchor strings (spec 005). Mode A binds tabs from positional {@code anchor=type:recipient} args;
 * Mode B ({@code --interactive}) scans the PDF and confirms each candidate. Auth, scanning, and the
 * API call all run through the existing seams (003 token, 004 scanner, 002 client factory).
 */
@Command(name = "send",
        description = "Send a PDF for signature, binding detected anchors to signing tabs.")
public final class SendCommand implements Callable<Integer> {

    @ParentCommand
    RootCommand root;

    @Mixin
    GlobalOptions globalOptions;

    @Parameters(index = "0", paramLabel = "<pdf>", description = "Path to the PDF to send.")
    Path pdf;

    @Option(names = "--subject", required = true, description = "Email subject for the envelope.")
    String subject;

    @Option(names = "--recipient", paramLabel = "Name=email", required = true,
            description = "Declare a signer. Repeatable.")
    List<String> recipientSpecs = new ArrayList<>();

    @Parameters(index = "1..*", arity = "0..*", paramLabel = "anchor=type:recipient",
            description = "Anchor→tab bindings (Mode A). Repeatable.")
    List<String> anchorSpecs = new ArrayList<>();

    @Option(names = "--interactive",
            description = "Mode B: scan the PDF and confirm each candidate interactively.")
    boolean interactive;

    // ---- test seams (package-private) -------------------------------------
    private final RecipientSpecParser recipientParser = new RecipientSpecParser();
    private final AnchorSpecParser anchorParser = new AnchorSpecParser();
    private final SendPlanBuilder planBuilder = new SendPlanBuilder();
    private AnchorScanner scanner = new AnchorScanner();
    private InteractivePrompter prompter = new ConsoleInteractivePrompter();
    private EnvelopeCreator creatorOverride;   // when set, used instead of the SDK EnvelopesApi
    private PrintStream warn = System.err;

    void useScanner(AnchorScanner scanner) {
        this.scanner = scanner;
    }

    void usePrompter(InteractivePrompter prompter) {
        this.prompter = prompter;
    }

    void useEnvelopeCreator(EnvelopeCreator creator) {
        this.creatorOverride = creator;
    }

    void useWarnStream(PrintStream warn) {
        this.warn = warn;
    }

    @Override
    public Integer call() {
        CliContext context = root.context();
        OutputWriter out = context.output();

        validateModeAndPdf();

        RecipientRegistry recipients = parseRecipients();
        List<AnchorSpec> specs = interactive
                ? buildSpecsInteractively(recipients)
                : buildSpecsFromArgs(recipients);

        SendPlan plan = planBuilder.build(pdf, subject, recipients, specs);

        EnvelopeCreator creator = (creatorOverride != null)
                ? creatorOverride
                : EnvelopeCreator.usingSdk(new EnvelopesApi(context.authenticatedApiClient()));
        EnvelopeSender sender = new EnvelopeSender(creator);
        EnvelopeDefinition env = sender.buildDefinition(plan);

        if (!confirmSend(context, plan)) {
            out.message("Aborted; nothing sent.");
            return ExitCode.OK.code();
        }

        EnvelopeSummary summary = sender.send(context.config().accountId(), env);
        renderResult(out, summary);
        return ExitCode.OK.code();
    }

    private void validateModeAndPdf() {
        if (interactive && !anchorSpecs.isEmpty()) {
            throw new SendUsageException("Cannot combine positional anchor args with --interactive.");
        }
        if (!interactive && anchorSpecs.isEmpty()) {
            throw new SendUsageException(
                    "No anchor bindings given. Provide anchorString=type:recipient args, or use --interactive.");
        }
        if (pdf == null || !Files.isReadable(pdf)) {
            throw new CliException(ExitCode.INPUT, "PDF not found or unreadable: " + pdf);
        }
    }

    private RecipientRegistry parseRecipients() {
        RecipientRegistry registry = new RecipientRegistry();
        for (String spec : recipientSpecs) {
            registry.add(recipientParser.parse(spec));
        }
        return registry;
    }

    /** Mode A: parse positional specs and warn on anchors absent from a verification scan (§5). */
    private List<AnchorSpec> buildSpecsFromArgs(RecipientRegistry recipients) {
        List<AnchorSpec> specs = new ArrayList<>();
        for (String raw : anchorSpecs) {
            specs.add(anchorParser.parse(raw));
        }
        warnMissingAnchors(specs);
        return specs;
    }

    private void warnMissingAnchors(List<AnchorSpec> specs) {
        Set<String> detected = new LinkedHashSet<>();
        try {
            for (AnchorCandidate c : scanner.scan(pdf.toFile(), ScanOptions.defaults())) {
                detected.add(c.anchorString());
            }
        } catch (IOException e) {
            warn.println("warning: could not scan " + pdf.getFileName() + " to verify anchors: "
                    + e.getMessage());
            return;   // scan never blocks the send in Mode A
        }
        for (AnchorSpec spec : specs) {
            if (!detected.contains(spec.anchorString())) {
                warn.println("warning: anchor \"" + spec.anchorString() + "\" not found in "
                        + pdf.getFileName() + "; DocuSign will reject placement if absent.");
            }
        }
    }

    /** Mode B: scan, then prompt per candidate (§6). */
    private List<AnchorSpec> buildSpecsInteractively(RecipientRegistry recipients) {
        List<AnchorCandidate> candidates;
        try {
            candidates = scanner.scan(pdf.toFile(), ScanOptions.defaults());
        } catch (IOException e) {
            throw new CliException(ExitCode.INPUT,
                    "Could not read PDF " + pdf.getFileName() + ": " + e.getMessage());
        }
        if (candidates.isEmpty()) {
            throw new CliException(ExitCode.INPUT,
                    "No anchor candidates detected in " + pdf.getFileName() + ". Nothing to place.");
        }

        List<AnchorSpec> specs = new ArrayList<>();
        for (AnchorCandidate candidate : candidates) {
            String header = "Found \"" + candidate.anchorString() + "\" ("
                    + describeColor(candidate) + ", " + candidate.fontSize() + "pt) on page "
                    + candidate.page() + ". Is this an anchor?";
            if (!prompter.confirm(header, true)) {
                continue;
            }
            TabType type = promptType(candidate);
            DeclaredRecipient recipient = promptRecipient(recipients);
            specs.add(new AnchorSpec(candidate.anchorString(), type, recipient.email()));
        }
        if (specs.isEmpty()) {
            throw new CliException(ExitCode.INPUT, "No anchors confirmed. Nothing to place.");
        }
        return specs;
    }

    private TabType promptType(AnchorCandidate candidate) {
        String def = defaultTypeFor(candidate).name().toLowerCase();
        while (true) {
            String answer = prompter.ask("Type? [" + def + "]/signature/initials/date/text:", def);
            Optional<TabType> type = TabType.fromToken(answer);
            if (type.isPresent()) {
                return type.get();
            }
            warn.println("Unknown type \"" + answer + "\". Choose signature, initials, date, or text.");
        }
    }

    private DeclaredRecipient promptRecipient(RecipientRegistry recipients) {
        String soleDefault = recipients.all().size() == 1 ? recipients.all().get(0).email() : null;
        while (true) {
            String answer = prompter.ask("Recipient (email or name):", soleDefault);
            DeclaredRecipient recipient = recipients.resolve(answer);
            if (recipient != null) {
                return recipient;
            }
            warn.println("Unknown recipient \"" + answer + "\". Declared: "
                    + recipients.all().stream().map(DeclaredRecipient::email).toList());
        }
    }

    private static TabType defaultTypeFor(AnchorCandidate candidate) {
        return switch (candidate.guessedType()) {
            case SIGNATURE -> TabType.SIGNATURE;
            case INITIALS -> TabType.INITIALS;
            case DATE -> TabType.DATE;
            case TEXT -> TabType.TEXT;
            case UNKNOWN -> TabType.SIGNATURE;
        };
    }

    private boolean confirmSend(CliContext context, SendPlan plan) {
        // --yes is resolved into the context by the root (002 §3.3); honour that single source.
        if (context.assumeYes()) {
            return true;
        }
        StringBuilder summary = new StringBuilder("\nAbout to send:\n");
        summary.append("  Document: ").append(plan.pdf().getFileName()).append('\n');
        summary.append("  Subject:  ").append(plan.subject()).append('\n');
        for (DeclaredRecipient r : plan.recipients()) {
            summary.append("  Signer:   ").append(r.name()).append(" <").append(r.email())
                    .append("> — ").append(plan.bindingsFor(r).size()).append(" tab(s)\n");
        }
        warn.println(summary);
        return prompter.confirm("Send envelope?", false);
    }

    private void renderResult(OutputWriter out, EnvelopeSummary summary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("envelopeId", summary.getEnvelopeId());
        payload.put("status", summary.getStatus());
        out.object(payload);
        out.table(List.of("ENVELOPE ID", "STATUS"),
                List.of(List.of(nullToEmpty(summary.getEnvelopeId()), nullToEmpty(summary.getStatus()))));
    }

    private static String describeColor(AnchorCandidate candidate) {
        if (candidate.color() == null) {
            return "unknown colour";
        }
        java.awt.Color c = candidate.color();
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
