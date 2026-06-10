package io.github.moacyrricardo.docusign.auth;

import io.github.moacyrricardo.docusign.cli.CliException;
import io.github.moacyrricardo.docusign.config.Config;

/**
 * {@link TokenProvider} that serves the cached {@code token.json} and silently re-mints via
 * {@link JwtTokenMinter} when it is missing or expired. <b>Shell owned by 003</b>: this spec (002)
 * ships the registered seam so the composition root compiles; the caching/minting body lands in
 * 003.
 */
public final class CachingTokenProvider implements TokenProvider {

    private final Config config;
    private final JwtTokenMinter minter;

    public CachingTokenProvider(Config config, JwtTokenMinter minter) {
        this.config = config;
        this.minter = minter;
    }

    @Override
    public String accessToken() throws CliException {
        throw new UnsupportedOperationException("Token caching/minting is implemented in spec 003");
    }
}
