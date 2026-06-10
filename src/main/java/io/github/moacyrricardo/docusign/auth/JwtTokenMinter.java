package io.github.moacyrricardo.docusign.auth;

import io.github.moacyrricardo.docusign.config.Config;
import io.github.moacyrricardo.docusign.config.Token;
import io.github.moacyrricardo.docusign.docusign.ApiClientFactory;
import io.github.moacyrricardo.docusign.docusign.Environment;

/**
 * Mints a fresh access token via JWT Grant (RSA-signed assertion). <b>Shell owned by 003</b>: this
 * spec (002) ships only the registered seam so the composition root compiles; the body lands in
 * 003.
 */
public final class JwtTokenMinter {

    private final ApiClientFactory apiClientFactory;
    private final Config config;
    private final Environment environment;

    public JwtTokenMinter(ApiClientFactory apiClientFactory, Config config, Environment environment) {
        this.apiClientFactory = apiClientFactory;
        this.config = config;
        this.environment = environment;
    }

    /**
     * Mints a new token via JWT Grant.
     *
     * @throws UnsupportedOperationException until 003 supplies the implementation.
     */
    public Token mint() {
        throw new UnsupportedOperationException("JWT minting is implemented in spec 003");
    }
}
