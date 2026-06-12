package io.github.moacyrricardo.docusign.envelope;

import io.github.moacyrricardo.docusign.cli.CliException;
import io.github.moacyrricardo.docusign.cli.ExitCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateWindowTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");

    @Test
    void omittedFromDefaultsTo30DaysAgo() {
        DateWindow w = DateWindow.resolve(null, null, NOW, UTC);
        assertTrue(w.fromDefaulted());
        assertEquals(NOW.minus(30, ChronoUnit.DAYS), w.from());
        assertNull(w.to());
    }

    @Test
    void dateOnlyFromResolvesToStartOfDay() {
        DateWindow w = DateWindow.resolve("2026-06-01", null, NOW, UTC);
        assertFalse(w.fromDefaulted());
        assertEquals(Instant.parse("2026-06-01T00:00:00Z"), w.from());
    }

    @Test
    void dateOnlyToResolvesToEndOfDay() {
        DateWindow w = DateWindow.resolve("2026-06-01", "2026-06-02", NOW, UTC);
        // end-of-day is just before the next day's start
        assertTrue(w.to().isAfter(Instant.parse("2026-06-02T23:59:00Z")));
        assertTrue(w.to().isBefore(Instant.parse("2026-06-03T00:00:00Z")));
    }

    @Test
    void isoInstantParsed() {
        DateWindow w = DateWindow.resolve("2026-06-01T08:30:00Z", null, NOW, UTC);
        assertEquals(Instant.parse("2026-06-01T08:30:00Z"), w.from());
    }

    @Test
    void fromAfterToRejected() {
        CliException ex = assertThrows(CliException.class,
                () -> DateWindow.resolve("2026-06-05", "2026-06-01", NOW, UTC));
        assertEquals(ExitCode.USAGE, ex.exitCode());
    }

    @Test
    void unparseableDateRejected() {
        assertThrows(CliException.class, () -> DateWindow.resolve("not-a-date", null, NOW, UTC));
    }
}
