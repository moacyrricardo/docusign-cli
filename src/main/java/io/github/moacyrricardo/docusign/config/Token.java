package io.github.moacyrricardo.docusign.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The cached access token persisted as {@code token.json} (spec 002 §4.2). An absolute
 * {@code expires_at} instant is stored (not a relative {@code expires_in}) so freshness is a pure
 * comparison. The default clock skew used by callers is {@value #DEFAULT_SKEW_SECONDS}s.
 */
public final class Token {

    /** Default skew applied when checking freshness, in seconds (spec 002 §4.2). */
    public static final long DEFAULT_SKEW_SECONDS = 60L;

    private final String accessToken;
    private final String tokenType;
    private final Instant expiresAt;

    @JsonCreator
    public Token(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_at") Instant expiresAt) {
        this.accessToken = accessToken;
        this.tokenType = tokenType;
        this.expiresAt = expiresAt;
    }

    @JsonProperty("access_token")
    public String accessToken() {
        return accessToken;
    }

    @JsonProperty("token_type")
    public String tokenType() {
        return tokenType;
    }

    @JsonProperty("expires_at")
    public Instant expiresAt() {
        return expiresAt;
    }

    /**
     * Whether this token is no longer safe to reuse at {@code now} given {@code skew} headroom.
     *
     * @return {@code true} if {@code now + skew >= expiresAt}.
     */
    public boolean isExpired(Instant now, Duration skew) {
        return !now.plus(skew).isBefore(expiresAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Token other)) {
            return false;
        }
        return Objects.equals(accessToken, other.accessToken)
                && Objects.equals(tokenType, other.tokenType)
                && Objects.equals(expiresAt, other.expiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accessToken, tokenType, expiresAt);
    }
}
