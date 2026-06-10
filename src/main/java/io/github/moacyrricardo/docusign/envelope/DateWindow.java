package io.github.moacyrricardo.docusign.envelope;

import io.github.moacyrricardo.docusign.cli.CliException;
import io.github.moacyrricardo.docusign.cli.ExitCode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * The resolved {@code from}/{@code to} query window (spec 006 §3.2). {@code from} is always present
 * (the API requires {@code from_date}); it defaults to 30 days ago. {@code to} is optional. Inputs
 * may be a date-only {@code yyyy-MM-dd} (resolved to start-of-day for from / end-of-day for to in the
 * system zone) or a full ISO-8601 instant.
 */
final class DateWindow {

    static final int DEFAULT_WINDOW_DAYS = 30;

    private final Instant from;
    private final Instant to;          // nullable
    private final boolean fromDefaulted;

    private DateWindow(Instant from, Instant to, boolean fromDefaulted) {
        this.from = from;
        this.to = to;
        this.fromDefaulted = fromDefaulted;
    }

    /** Resolves the window from raw {@code --from}/{@code --to}; validates format and ordering. */
    static DateWindow resolve(String fromArg, String toArg, Instant now, ZoneId zone) {
        boolean fromDefaulted = (fromArg == null || fromArg.isBlank());
        Instant from = fromDefaulted
                ? now.minus(java.time.Duration.ofDays(DEFAULT_WINDOW_DAYS))
                : parse(fromArg, zone, false);
        Instant to = (toArg == null || toArg.isBlank()) ? null : parse(toArg, zone, true);
        if (to != null && from.isAfter(to)) {
            throw new CliException(ExitCode.USAGE, "--from (" + fromArg + ") is after --to (" + toArg + ").");
        }
        return new DateWindow(from, to, fromDefaulted);
    }

    Instant from() {
        return from;
    }

    Instant to() {
        return to;
    }

    boolean fromDefaulted() {
        return fromDefaulted;
    }

    private static Instant parse(String raw, ZoneId zone, boolean endOfDay) {
        String value = raw.trim();
        // Try date-only first (yyyy-MM-dd), then a full ISO-8601 instant.
        try {
            LocalDate date = LocalDate.parse(value);
            return endOfDay
                    ? date.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1)
                    : date.atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException notADate) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException notAnInstant) {
                throw new CliException(ExitCode.USAGE,
                        "Invalid date \"" + raw + "\": use yyyy-MM-dd or an ISO-8601 instant "
                                + "(e.g. 2026-06-01T00:00:00Z).");
            }
        }
    }
}
