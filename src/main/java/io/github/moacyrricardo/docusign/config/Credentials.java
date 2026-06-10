package io.github.moacyrricardo.docusign.config;

import java.nio.file.Path;

/**
 * Typed, immutable view of the {@code credentials} file (spec 002 §4.1). {@code userId},
 * {@code accountId}, and {@code baseUri} may be {@code null} until {@code login} (003) fetches and
 * writes them; {@code privateKeyPath} and {@code redirectUri} fall back to sensible defaults.
 *
 * <p>Use {@link #builder()} to construct, and {@link #toBuilder()} to make a {@code with}-style
 * copy (e.g. login filling in {@code userId}/{@code accountId}).
 */
public final class Credentials {

    /** Default consent redirect URI when the config does not set one (spec 002 §4.1, 003 §5). */
    public static final String DEFAULT_REDIRECT_URI = "https://www.docusign.com";

    private final String integrationKey;
    private final String userId;
    private final String accountId;
    private final String baseUri;
    private final Path privateKeyPath;
    private final String redirectUri;

    private Credentials(Builder b) {
        this.integrationKey = b.integrationKey;
        this.userId = b.userId;
        this.accountId = b.accountId;
        this.baseUri = b.baseUri;
        this.privateKeyPath = b.privateKeyPath;
        this.redirectUri = (b.redirectUri != null && !b.redirectUri.isBlank())
                ? b.redirectUri
                : DEFAULT_REDIRECT_URI;
    }

    /** DocuSign integration (client) key / GUID. */
    public String integrationKey() {
        return integrationKey;
    }

    /** Impersonated API user GUID; nullable until login fetches it. */
    public String userId() {
        return userId;
    }

    /** DocuSign account ID; nullable until login fetches it. */
    public String accountId() {
        return accountId;
    }

    /** Account base URI / environment hint; nullable, then environment falls back to flags/DEMO. */
    public String baseUri() {
        return baseUri;
    }

    /** Path to the RSA private key; defaults to the config-root {@code private.key}. */
    public Path privateKeyPath() {
        return privateKeyPath;
    }

    /** Consent redirect URI; defaults to {@link #DEFAULT_REDIRECT_URI}. */
    public String redirectUri() {
        return redirectUri;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A mutable copy seeded with this instance's values for {@code with}-style edits. */
    public Builder toBuilder() {
        return new Builder()
                .integrationKey(integrationKey)
                .userId(userId)
                .accountId(accountId)
                .baseUri(baseUri)
                .privateKeyPath(privateKeyPath)
                .redirectUri(redirectUri);
    }

    /** Builder for {@link Credentials}; the {@code redirectUri} default is applied at build time. */
    public static final class Builder {
        private String integrationKey;
        private String userId;
        private String accountId;
        private String baseUri;
        private Path privateKeyPath;
        private String redirectUri;

        public Builder integrationKey(String v) {
            this.integrationKey = v;
            return this;
        }

        public Builder userId(String v) {
            this.userId = v;
            return this;
        }

        public Builder accountId(String v) {
            this.accountId = v;
            return this;
        }

        public Builder baseUri(String v) {
            this.baseUri = v;
            return this;
        }

        public Builder privateKeyPath(Path v) {
            this.privateKeyPath = v;
            return this;
        }

        public Builder redirectUri(String v) {
            this.redirectUri = v;
            return this;
        }

        public Credentials build() {
            return new Credentials(this);
        }
    }
}
