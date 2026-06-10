package io.github.moacyrricardo.docusign.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenTest {

    private static final Instant EXPIRES = Instant.parse("2026-06-09T18:42:00Z");
    private static final Duration SKEW = Duration.ofSeconds(Token.DEFAULT_SKEW_SECONDS);

    private static Token tokenExpiringAt(Instant expiresAt) {
        return new Token("ey", "Bearer", expiresAt);
    }

    @Test
    void freshWellBeforeExpiry() {
        Instant now = EXPIRES.minusSeconds(3600);
        assertFalse(tokenExpiringAt(EXPIRES).isExpired(now, SKEW));
    }

    @Test
    void freshJustInsideSkewBoundary() {
        // now + 60s skew is still strictly before expiry => not expired
        Instant now = EXPIRES.minus(SKEW).minusSeconds(1);
        assertFalse(tokenExpiringAt(EXPIRES).isExpired(now, SKEW));
    }

    @Test
    void expiredExactlyAtSkewBoundary() {
        // now + 60s == expiry => expired (now + skew >= expiresAt)
        Instant now = EXPIRES.minus(SKEW);
        assertTrue(tokenExpiringAt(EXPIRES).isExpired(now, SKEW));
    }

    @Test
    void expiredJustPastSkewBoundary() {
        Instant now = EXPIRES.minus(SKEW).plusSeconds(1);
        assertTrue(tokenExpiringAt(EXPIRES).isExpired(now, SKEW));
    }

    @Test
    void expiredWhenNowAfterExpiry() {
        assertTrue(tokenExpiringAt(EXPIRES).isExpired(EXPIRES.plusSeconds(1), SKEW));
    }

    @Test
    void defaultSkewIsSixtySeconds() {
        assertEquals(60L, Token.DEFAULT_SKEW_SECONDS);
    }
}
