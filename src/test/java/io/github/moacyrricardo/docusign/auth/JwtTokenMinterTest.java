package io.github.moacyrricardo.docusign.auth;

import com.docusign.esign.client.ApiClient;
import com.docusign.esign.client.ApiException;
import com.docusign.esign.client.auth.OAuth;
import io.github.moacyrricardo.docusign.cli.ExitCode;
import io.github.moacyrricardo.docusign.config.Config;
import io.github.moacyrricardo.docusign.config.ConfigPaths;
import io.github.moacyrricardo.docusign.config.Credentials;
import io.github.moacyrricardo.docusign.config.Token;
import io.github.moacyrricardo.docusign.docusign.ApiClientFactory;
import io.github.moacyrricardo.docusign.docusign.Environment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenMinterTest {

    private static final byte[] PEM =
            "-----BEGIN RSA PRIVATE KEY-----\nQUJD\n-----END RSA PRIVATE KEY-----\n"
                    .getBytes(StandardCharsets.UTF_8);

    private Config configWithKey(Path root) throws Exception {
        Path key = root.resolve("private.key");
        Files.write(key, PEM);
        Config config = Config.open(ConfigPaths.at(root));
        config.writeCredentials(Credentials.builder()
                .integrationKey("ik-guid").userId("user-guid").privateKeyPath(key).build());
        return Config.open(ConfigPaths.at(root));
    }

    /** Captures the exact 5.x requestJWTUserToken arguments and returns a canned token. */
    private static final class CapturingApiClient extends ApiClient {
        String clientId;
        String userId;
        List<String> scopes;
        byte[] key;
        long expires;
        ApiException toThrow;

        @Override
        public OAuth.OAuthToken requestJWTUserToken(String clientId, String userId,
                                                    List<String> scopes, byte[] rsaPrivateKey,
                                                    long expiresIn) throws ApiException {
            this.clientId = clientId;
            this.userId = userId;
            this.scopes = scopes;
            this.key = rsaPrivateKey;
            this.expires = expiresIn;
            if (toThrow != null) {
                throw toThrow;
            }
            OAuth.OAuthToken token = new OAuth.OAuthToken();
            token.setAccessToken("minted-access-token");
            token.setExpiresIn(3600L);
            return token;
        }
    }

    private static JwtTokenMinter minterWith(Config config, CapturingApiClient client) {
        ApiClientFactory factory = new ApiClientFactory(Environment.DEMO, config);
        return new JwtTokenMinter(factory, config, Environment.DEMO) {
            @Override
            protected OAuth.OAuthToken requestJwt(ApiClient ignored, byte[] key) throws ConsentException {
                return super.requestJwt(client, key);
            }
        };
    }

    @Test
    void mintPassesFiveXArgsInOrderAndStoresRawExpiry(@TempDir Path root) throws Exception {
        Config config = configWithKey(root);
        CapturingApiClient client = new CapturingApiClient();
        Instant before = Instant.now();

        Token token = minterWith(config, client).mint();

        assertEquals("ik-guid", client.clientId);
        assertEquals("user-guid", client.userId);
        assertEquals(List.of("signature", "impersonation"), client.scopes);
        assertEquals(JwtTokenMinter.TOKEN_LIFETIME_SECONDS, client.expires);
        assertTrue(client.key.length > 0, "raw PEM bytes must be passed through");

        assertEquals("minted-access-token", token.accessToken());
        assertEquals("Bearer", token.tokenType());
        // Raw (un-skewed) expiry ~ now + 3600s.
        assertTrue(!token.expiresAt().isBefore(before.plusSeconds(3590)));
        assertTrue(token.expiresAt().isBefore(before.plusSeconds(3700)));
    }

    @Test
    void consentRequiredResponseMapsToConsentExceptionWithUrl(@TempDir Path root) throws Exception {
        Config config = configWithKey(root);
        CapturingApiClient client = new CapturingApiClient();
        client.toThrow = new ApiException(400, "{\"error\":\"consent_required\"}");
        // ApiException(code, body) — body carries the marker.

        ConsentException ex = assertThrows(ConsentException.class,
                () -> minterWith(config, client).mint());
        assertNotNull(ex.consentUrl());
        assertTrue(ex.consentUrl().contains("/oauth/auth"));
        assertTrue(ex.consentUrl().contains("client_id=ik-guid"));
    }

    @Test
    void networkFailureMapsToNetworkExit(@TempDir Path root) throws Exception {
        Config config = configWithKey(root);
        CapturingApiClient client = new CapturingApiClient();
        client.toThrow = null;
        JwtTokenMinter minter = new JwtTokenMinter(
                new ApiClientFactory(Environment.DEMO, config), config, Environment.DEMO) {
            @Override
            protected OAuth.OAuthToken requestJwt(ApiClient ignored, byte[] key) throws ConsentException {
                throw new AuthException(ExitCode.NETWORK, "could not reach host");
            }
        };
        AuthException ex = assertThrows(AuthException.class, minter::mint);
        assertEquals(ExitCode.NETWORK, ex.exitCode());
    }
}
