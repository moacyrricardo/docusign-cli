package io.github.moacyrricardo.docusign.auth;

import io.github.moacyrricardo.docusign.cli.ExitCode;
import io.github.moacyrricardo.docusign.config.Config;
import io.github.moacyrricardo.docusign.config.Token;

import java.time.Duration;
import java.time.Instant;

/**
 * {@link TokenProvider} that serves the cached {@code token.json} and silently re-mints via
 * {@link JwtTokenMinter} when it is missing or expired (spec 003 §8). The refresh path is
 * <b>fully silent and never interactive</b>: a {@link ConsentException} from the minter is wrapped as
 * an {@link AuthException} mapping to {@link ExitCode#NOAUTH} ("run login first"), so headless API
 * commands stay headless. Only {@code login} handles consent interactively.
 */
public final class CachingTokenProvider implements TokenProvider {

    private static final Duration SKEW = Duration.ofSeconds(Token.DEFAULT_SKEW_SECONDS);

    private final Config config;
    private final JwtTokenMinter minter;

    public CachingTokenProvider(Config config, JwtTokenMinter minter) {
        this.config = config;
        this.minter = minter;
    }

    @Override
    public String accessToken() {
        Instant now = Instant.now();
        return config.readToken()
                .filter(t -> !t.isExpired(now, SKEW))
                .map(Token::accessToken)
                .orElseGet(this::mintAndCache);
    }

    private String mintAndCache() {
        Token token;
        try {
            token = minter.mint();
        } catch (ConsentException e) {
            // Never prompt on the silent path — surface as NOAUTH so the user runs `login`.
            throw new AuthException(ExitCode.NOAUTH,
                    "Not authorized. Run `docusign-cli login` first.");
        }
        config.writeToken(token);
        return token.accessToken();
    }
}
