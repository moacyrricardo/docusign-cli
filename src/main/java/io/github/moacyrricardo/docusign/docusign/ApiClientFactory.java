package io.github.moacyrricardo.docusign.docusign;

import com.docusign.esign.client.ApiClient;
import io.github.moacyrricardo.docusign.config.Config;

/**
 * Builds a configured SDK {@link ApiClient} (spec 002 §7). An <b>instance</b> constructed by the
 * composition root with the resolved {@link Environment} and {@link Config} — not a bag of statics.
 *
 * <p>{@link #authenticated(String)} is the single seam API commands reach (via
 * {@code CliContext.authenticatedApiClient()}); {@link #oauthClient()} is used only by 003's JWT
 * mint.
 */
public final class ApiClientFactory {

    private static final String RESTAPI_SUFFIX = "/restapi";

    private final Environment environment;
    private final Config config;

    public ApiClientFactory(Environment environment, Config config) {
        this.environment = environment;
        this.config = config;
    }

    /**
     * OAuth-host client with no bearer; used by 003's JWT mint ({@code requestJWTUserToken}). Points
     * only at {@link Environment#oAuthBasePath()}.
     */
    public ApiClient oauthClient() {
        ApiClient client = new ApiClient();
        client.setOAuthBasePath(environment.oAuthBasePath());
        return client;
    }

    /**
     * REST client ready for {@code EnvelopesApi}: base path is
     * {@code config.baseUri()+"/restapi"} when {@code base_uri} is set (written by login, 003),
     * else {@link Environment#restBasePath()}; the given bearer token is applied as the
     * {@code Authorization} header.
     */
    public ApiClient authenticated(String accessToken) {
        ApiClient client = new ApiClient();
        client.setBasePath(restBasePath());
        client.setOAuthBasePath(environment.oAuthBasePath());
        client.addDefaultHeader("Authorization", "Bearer " + accessToken);
        return client;
    }

    /** The resolved REST base path (account-specific {@code base_uri} preferred, env fallback). */
    String restBasePath() {
        String baseUri = config.exists() ? config.baseUri() : null;
        if (baseUri != null && !baseUri.isBlank()) {
            String trimmed = stripTrailingSlash(baseUri.trim());
            return trimmed.endsWith(RESTAPI_SUFFIX) ? trimmed : trimmed + RESTAPI_SUFFIX;
        }
        return environment.restBasePath();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
