package io.github.moacyrricardo.docusign.auth;

import io.github.moacyrricardo.docusign.cli.CliException;

/**
 * The auth seam (spec 002 §7.1): supplies a valid bearer access token, silently minting/refreshing
 * and caching when it is missing or expired. Lives in the {@code auth} package as an interface so
 * the composition root can reference it without depending on 003's concrete classes.
 *
 * <p>003 supplies {@code CachingTokenProvider implements TokenProvider} and
 * {@code AuthException extends CliException}.
 */
public interface TokenProvider {

    /**
     * A valid bearer access token, silently minting/refreshing+caching when missing/expired.
     *
     * @throws CliException (an {@code AuthException} mapping to {@code ExitCode.NOAUTH}) if a token
     *     cannot be obtained without interactive consent. Never prompts.
     */
    String accessToken() throws CliException;
}
