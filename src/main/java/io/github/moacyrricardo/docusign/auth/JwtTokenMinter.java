package io.github.moacyrricardo.docusign.auth;

import com.docusign.esign.client.ApiClient;
import com.docusign.esign.client.ApiException;
import com.docusign.esign.client.auth.OAuth;
import io.github.moacyrricardo.docusign.cli.ExitCode;
import io.github.moacyrricardo.docusign.config.Config;
import io.github.moacyrricardo.docusign.config.Token;
import io.github.moacyrricardo.docusign.docusign.ApiClientFactory;
import io.github.moacyrricardo.docusign.docusign.Environment;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Mints a fresh access token via JWT Grant (spec 003 §4.3). Wraps the DocuSign SDK's
 * {@code ApiClient.requestJWTUserToken} (5.x: {@code (clientId, userId, scopes, key, expires)} — five
 * args, OAuth base path set on the client beforehand by {@link ApiClientFactory#oauthClient()}). A
 * {@code consent_required} response is translated to a {@link ConsentException} carrying the consent
 * URL; transport/key failures become {@link AuthException}s with the right {@link ExitCode}.
 *
 * <p>The single SDK call ({@link #requestJwt(ApiClient, byte[])}) and the userinfo lookup
 * ({@link #fetchAccount(ApiClient, String)}) are isolated as overridable seams so tests can inject a
 * fake without a live DocuSign endpoint (spec 003 §12).
 */
public class JwtTokenMinter {

    static final List<String> SCOPES = List.of("signature", "impersonation");
    static final long TOKEN_LIFETIME_SECONDS = 3600L;   // 1h — SDK max for JWT user tokens
    static final String CONSENT_REQUIRED_MARKER = "consent_required";

    private final ApiClientFactory apiClientFactory;
    private final Config config;
    private final Environment environment;
    private final PrivateKeyLoader privateKeyLoader = new PrivateKeyLoader();

    public JwtTokenMinter(ApiClientFactory apiClientFactory, Config config, Environment environment) {
        this.apiClientFactory = apiClientFactory;
        this.config = config;
        this.environment = environment;
    }

    /**
     * Mints a fresh token via JWT Grant.
     *
     * @throws ConsentException if DocuSign responds {@code consent_required} (caller decides how to
     *     handle: {@code login} prompts, the refresh path wraps it as {@code NOAUTH}).
     * @throws AuthException for missing/invalid key ({@code CONFIG}), network failure ({@code NETWORK}),
     *     or any other API/clock failure.
     */
    public Token mint() throws ConsentException {
        byte[] key = privateKeyLoader.load(config.privateKeyPath());
        ApiClient client = apiClientFactory.oauthClient();   // OAuth base path already set
        OAuth.OAuthToken token = requestJwt(client, key);
        Instant expiresAt = Instant.now().plusSeconds(token.getExpiresIn());
        return new Token(token.getAccessToken(), "Bearer", expiresAt);
    }

    /**
     * The raw SDK JWT call, isolated so tests can stub it. Maps the SDK's checked exceptions onto the
     * spec's error matrix (§11).
     */
    protected OAuth.OAuthToken requestJwt(ApiClient client, byte[] key) throws ConsentException {
        try {
            return client.requestJWTUserToken(
                    config.integrationKey(),
                    config.userId(),
                    SCOPES,
                    key,
                    TOKEN_LIFETIME_SECONDS);
        } catch (ApiException e) {
            if (isConsentRequired(e)) {
                throw new ConsentException(buildConsentUrl());
            }
            throw new AuthException(ExitCode.CONFIG,
                    "JWT request rejected by DocuSign: " + describe(e)
                            + " (check the private key, integration key, and user id)", e);
        } catch (IOException e) {
            throw new AuthException(ExitCode.NETWORK,
                    "could not reach " + environment.oAuthBasePath() + " to mint a token", e);
        } catch (IllegalArgumentException e) {
            throw new AuthException(ExitCode.CONFIG,
                    "private key is invalid or not RSA: " + e.getMessage(), e);
        }
    }

    /**
     * Looks up the default account via {@code /oauth/userinfo} (spec 003 §4.4). Isolated as a seam so
     * tests can supply the account without a network call.
     */
    protected OAuth.Account fetchAccount(ApiClient client, String accessToken) {
        OAuth.UserInfo userInfo;
        try {
            userInfo = client.getUserInfo(accessToken);
        } catch (ApiException e) {
            if (e.getCode() == 0) {
                throw new AuthException(ExitCode.NETWORK,
                        "could not reach " + environment.oAuthBasePath() + " for account info", e);
            }
            throw new AuthException(ExitCode.CONFIG, "userinfo lookup failed: " + describe(e), e);
        }
        List<OAuth.Account> accounts = userInfo != null ? userInfo.getAccounts() : null;
        if (accounts == null || accounts.isEmpty()) {
            throw new AuthException(ExitCode.CONFIG, "user has no DocuSign account");
        }
        return accounts.stream()
                .filter(a -> "true".equalsIgnoreCase(a.getIsDefault()))
                .findFirst()
                .orElse(accounts.get(0));
    }

    /** A configured OAuth-host client used by {@code login} for the userinfo step. */
    ApiClient oauthClient() {
        return apiClientFactory.oauthClient();
    }

    /** The deterministic consent URL (spec 003 §5); also used by {@code login} when re-prompting. */
    String buildConsentUrl() {
        return "https://" + environment.oAuthBasePath() + "/oauth/auth"
                + "?response_type=code"
                + "&scope=signature%20impersonation"
                + "&client_id=" + config.integrationKey()
                + "&redirect_uri=" + config.redirectUri();
    }

    private static boolean isConsentRequired(ApiException e) {
        String body = e.getResponseBody();
        if (body != null && body.contains(CONSENT_REQUIRED_MARKER)) {
            return true;
        }
        String message = e.getMessage();
        return message != null && message.contains(CONSENT_REQUIRED_MARKER);
    }

    private static String describe(ApiException e) {
        String body = e.getResponseBody();
        if (body != null && !body.isBlank()) {
            return body.strip();
        }
        return e.getMessage() != null ? e.getMessage() : ("HTTP " + e.getCode());
    }
}
