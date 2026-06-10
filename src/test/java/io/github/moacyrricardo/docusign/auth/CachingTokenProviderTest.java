package io.github.moacyrricardo.docusign.auth;

import io.github.moacyrricardo.docusign.cli.ExitCode;
import io.github.moacyrricardo.docusign.config.Config;
import io.github.moacyrricardo.docusign.config.ConfigPaths;
import io.github.moacyrricardo.docusign.config.Credentials;
import io.github.moacyrricardo.docusign.config.Token;
import io.github.moacyrricardo.docusign.docusign.ApiClientFactory;
import io.github.moacyrricardo.docusign.docusign.Environment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachingTokenProviderTest {

    private Config config(Path root) {
        Config config = Config.open(ConfigPaths.at(root));
        config.writeCredentials(Credentials.builder()
                .integrationKey("ik").userId("uid").build());
        return Config.open(ConfigPaths.at(root));
    }

    /** A minter that records call count and returns/throws on demand — no SDK, no network. */
    private static final class FakeMinter extends JwtTokenMinter {
        final AtomicInteger calls = new AtomicInteger();
        Token next;
        boolean consent;

        FakeMinter(Config config) {
            super(new ApiClientFactory(Environment.DEMO, config), config, Environment.DEMO);
        }

        @Override
        public Token mint() throws ConsentException {
            calls.incrementAndGet();
            if (consent) {
                throw new ConsentException("https://consent.example/url");
            }
            return next;
        }
    }

    @Test
    void validCachedTokenReturnedWithoutMinting(@TempDir Path root) {
        Config config = config(root);
        config.writeToken(new Token("cached-tok", "Bearer", Instant.now().plusSeconds(3600)));
        FakeMinter minter = new FakeMinter(config);

        String token = new CachingTokenProvider(config, minter).accessToken();

        assertEquals("cached-tok", token);
        assertEquals(0, minter.calls.get(), "valid cache must not invoke the minter");
    }

    @Test
    void expiredTokenTriggersMintAndIsCached(@TempDir Path root) {
        Config config = config(root);
        config.writeToken(new Token("stale", "Bearer", Instant.now().minusSeconds(10)));
        FakeMinter minter = new FakeMinter(config);
        minter.next = new Token("fresh", "Bearer", Instant.now().plusSeconds(3600));

        CachingTokenProvider provider = new CachingTokenProvider(config, minter);
        assertEquals("fresh", provider.accessToken());
        assertEquals(1, minter.calls.get());
        assertEquals("fresh", config.readToken().orElseThrow().accessToken());
    }

    @Test
    void missingCacheTriggersMint(@TempDir Path root) {
        Config config = config(root);
        FakeMinter minter = new FakeMinter(config);
        minter.next = new Token("minted", "Bearer", Instant.now().plusSeconds(3600));

        assertEquals("minted", new CachingTokenProvider(config, minter).accessToken());
        assertEquals(1, minter.calls.get());
    }

    @Test
    void consentOnRefreshPathSurfacesAsNoauthNeverPrompts(@TempDir Path root) {
        Config config = config(root);
        FakeMinter minter = new FakeMinter(config);
        minter.consent = true;

        AuthException ex = assertThrows(AuthException.class,
                () -> new CachingTokenProvider(config, minter).accessToken());
        assertEquals(ExitCode.NOAUTH, ex.exitCode());
        assertTrue(ex.getMessage().contains("login"));
    }
}
