package io.github.moacyrricardo.docusign.cli;

import com.docusign.esign.client.ApiClient;
import io.github.moacyrricardo.docusign.auth.TokenProvider;
import io.github.moacyrricardo.docusign.config.Config;
import io.github.moacyrricardo.docusign.docusign.ApiClientFactory;
import io.github.moacyrricardo.docusign.docusign.Environment;
import io.github.moacyrricardo.docusign.output.OutputWriter;

import java.util.Objects;

/**
 * Immutable runtime context built by the composition root ({@code RootCommand}) from the resolved
 * global options, and handed to subcommands (spec 002 §3.3). Subcommands depend on this, never on
 * the root's raw Picocli option fields, so 003-007 share one resolution path for
 * environment/output/confirmation.
 *
 * <p>API commands (005/006/007) obtain their client exclusively via {@link #authenticatedApiClient()}
 * — they never build an {@link ApiClient} themselves nor touch {@link TokenProvider} directly.
 */
public final class CliContext {

    private final Environment environment;
    private final OutputWriter output;
    private final boolean assumeYes;
    private final Config config;
    private final ApiClientFactory apiClientFactory;
    private final TokenProvider tokenProvider;

    public CliContext(
            Environment environment,
            OutputWriter output,
            boolean assumeYes,
            Config config,
            ApiClientFactory apiClientFactory,
            TokenProvider tokenProvider) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.output = Objects.requireNonNull(output, "output");
        this.assumeYes = assumeYes;
        this.config = Objects.requireNonNull(config, "config");
        this.apiClientFactory = Objects.requireNonNull(apiClientFactory, "apiClientFactory");
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
    }

    /** Resolved environment (DEMO | PROD) after override resolution (spec 002 §3.2, §7). */
    public Environment environment() {
        return environment;
    }

    /** The selected writer per {@code --json}/{@code --output} (spec 002 §5). */
    public OutputWriter output() {
        return output;
    }

    /** Whether {@code --yes} was given (skip confirmation prompts). */
    public boolean assumeYes() {
        return assumeYes;
    }

    /** The config gateway (spec 002 §4). */
    public Config config() {
        return config;
    }

    /** The DocuSign client factory (spec 002 §7). */
    public ApiClientFactory apiClientFactory() {
        return apiClientFactory;
    }

    /** The auth seam (spec 002 §7 / 003). */
    public TokenProvider tokenProvider() {
        return tokenProvider;
    }

    /**
     * A REST-ready {@link ApiClient} authenticated with a freshly resolved bearer token. Triggers a
     * silent mint/refresh through {@link TokenProvider}; if no token can be obtained without consent
     * the provider throws an {@code AuthException} (mapping to {@code ExitCode.NOAUTH}, owned by 003).
     */
    public ApiClient authenticatedApiClient() {
        return apiClientFactory.authenticated(tokenProvider.accessToken());
    }
}
