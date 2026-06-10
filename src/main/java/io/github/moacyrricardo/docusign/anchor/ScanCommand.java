package io.github.moacyrricardo.docusign.anchor;

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

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code docusign-cli scan <pdf>} — detect candidate anchor strings (tiny / near-white text) and
 * print them (spec 004 §5). Never touches DocuSign. Emits a human table by default and a JSON array
 * with {@code --json}, both via the 002 output abstraction. No candidates is a successful outcome
 * (exit 0); an encrypted/unreadable PDF maps to {@code ExitCode.INPUT}.
 */
@Command(name = "scan",
        description = "Detect hidden anchor strings in a PDF and print the candidates.")
public final class ScanCommand implements Callable<Integer> {

    @ParentCommand
    RootCommand root;

    @Mixin
    GlobalOptions globalOptions;

    @Parameters(index = "0", paramLabel = "<pdf>", description = "PDF file to scan.")
    File pdf;

    @Option(names = "--max-font-size", paramLabel = "<pt>",
            description = "Flag text smaller than this (pt). Default: 4.0")
    float maxFontSize = ScanOptions.DEFAULT_MAX_FONT_SIZE_PT;

    @Option(names = "--white-threshold", paramLabel = "<0-255>",
            description = "Per-channel floor for near-white fill. Default: 245")
    int whiteThreshold = ScanOptions.DEFAULT_WHITE_THRESHOLD;

    @Option(names = "--pages", paramLabel = "<start[-end]>",
            description = "Restrict to a 1-based page range, e.g. 2 or 2-5. Default: all pages.")
    String pages;

    @Override
    public Integer call() {
        CliContext context = root.context();
        OutputWriter out = context.output();

        ScanOptions options = resolveOptions();

        List<AnchorCandidate> candidates;
        try {
            candidates = new AnchorScanner().scan(pdf, options);
        } catch (EncryptedPdfException e) {
            throw new CliException(ExitCode.INPUT, "Cannot scan password-protected PDF: " + pdf.getName());
        } catch (IOException e) {
            throw new CliException(ExitCode.INPUT,
                    "Could not read PDF " + pdf.getName() + ": " + e.getMessage());
        }

        render(out, candidates);
        return ExitCode.OK.code();
    }

    private ScanOptions resolveOptions() {
        ScanOptions.Builder builder = ScanOptions.builder()
                .maxFontSizePt(maxFontSize)
                .whiteThreshold(whiteThreshold);
        if (pages != null && !pages.isBlank()) {
            int[] range = parsePages(pages.trim());
            builder.pages(range[0], range[1] == 0 ? null : range[1]);
        }
        return builder.build();
    }

    /** Parses {@code "2"} or {@code "2-5"} into [start, end] (end 0 = open-ended); rejects inverted. */
    private int[] parsePages(String raw) {
        try {
            int dash = raw.indexOf('-');
            if (dash < 0) {
                int single = Integer.parseInt(raw);
                requirePositive(single);
                return new int[] {single, single};
            }
            int start = Integer.parseInt(raw.substring(0, dash).trim());
            int end = Integer.parseInt(raw.substring(dash + 1).trim());
            requirePositive(start);
            requirePositive(end);
            if (end < start) {
                throw new CliException(ExitCode.USAGE,
                        "invalid --pages range: end (" + end + ") is before start (" + start + ")");
            }
            return new int[] {start, end};
        } catch (NumberFormatException e) {
            throw new CliException(ExitCode.USAGE, "invalid --pages value: " + raw);
        }
    }

    private static void requirePositive(int page) {
        if (page < 1) {
            throw new CliException(ExitCode.USAGE, "page numbers are 1-based; got " + page);
        }
    }

    private void render(OutputWriter out, List<AnchorCandidate> candidates) {
        out.object(toPayload(candidates));   // JSON writer serializes this; table writer ignores it

        if (candidates.isEmpty()) {
            out.message("No candidate anchors found.");
            return;
        }
        List<String> headers = List.of("PAGE", "ANCHOR", "REASON", "FONT", "COLOR", "GUESS");
        List<List<String>> rows = new ArrayList<>();
        for (AnchorCandidate c : candidates) {
            rows.add(List.of(
                    String.valueOf(c.page()),
                    c.anchorString(),
                    reasonPhrase(c),
                    formatFont(c.fontSize()),
                    formatColor(c.color()),
                    c.guessedType().name().toLowerCase()));
        }
        out.table(headers, rows);
    }

    private List<Map<String, Object>> toPayload(List<AnchorCandidate> candidates) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AnchorCandidate c : candidates) {
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("anchorString", c.anchorString());
            obj.put("page", c.page());
            obj.put("x", c.x());
            obj.put("y", c.y());
            obj.put("fontSize", c.fontSize());
            obj.put("color", c.color() != null ? hex(c.color()) : null);
            obj.put("guessedType", c.guessedType().name().toLowerCase());
            List<String> reasons = new ArrayList<>();
            c.reason().forEach(r -> reasons.add(r.name().toLowerCase()));
            obj.put("reasons", reasons);
            list.add(obj);
        }
        return list;
    }

    private static String reasonPhrase(AnchorCandidate c) {
        boolean white = c.reason().contains(CandidateReason.NEAR_WHITE);
        boolean tiny = c.reason().contains(CandidateReason.TINY);
        if (white && tiny) {
            return "white, tiny";
        }
        if (white) {
            return "white";
        }
        return "tiny";
    }

    private static String formatFont(float size) {
        return String.format(java.util.Locale.ROOT, "%.1fpt", size);
    }

    private static String formatColor(Color color) {
        return color != null ? hex(color) : "(unknown)";
    }

    private static String hex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }
}
