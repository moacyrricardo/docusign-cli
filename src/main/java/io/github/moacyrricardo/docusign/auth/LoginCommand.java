package io.github.moacyrricardo.docusign.auth;

import com.docusign.esign.client.ApiClient;
import com.docusign.esign.client.auth.OAuth;
import io.github.moacyrricardo.docusign.cli.CliContext;
import io.github.moacyrricardo.docusign.cli.ExitCode;
import io.github.moacyrricardo.docusign.cli.GlobalOptions;
import io.github.moacyrricardo.docusign.cli.RootCommand;
import io.github.moacyrricardo.docusign.config.Config;
import io.github.moacyrricardo.docusign.config.Credentials;
import io.github.moacyrricardo.docusign.config.Token;
import io.github.moacyrricardo.docusign.output.OutputWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;

import java.awt.Desktop;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code docusign-cli login} — JWT Grant consent + mint/cache token (spec 003 §5). Validates the
 * required config, mints a token via {@link JwtTokenMinter}, and on success fetches and persists the
 * {@code account_id}/{@code base_uri} from {@code /oauth/userinfo} and writes {@code token.json}. On
 * {@code consent_required} it prints/opens the consent URL and exits {@link ExitCode#CONSENT}.
 *
 * <p>{@code login} is the <b>only</b> command that may trigger consent or print interactive
 * instructions; every other command refreshes silently through {@link CachingTokenProvider}.
 */
@Command(name = "login",
        description = "Authenticate via JWT Grant: run the one-time consent then mint a token.")
public final class LoginCommand implements Callable<Integer> {

    @ParentCommand
    RootCommand root;

    @Mixin
    GlobalOptions globalOptions;

    /** Test seam: when set, used instead of building a minter from the context. */
    private JwtTokenMinter minterOverride;

    @Override
    public Integer call() {
        CliContext context = root.context();
        Config config = context.config();
        OutputWriter out = context.output();

        validateConfig(config);

        JwtTokenMinter minter = minter(context);
        Token token;
        try {
            token = minter.mint();
        } catch (ConsentException e) {
            return handleConsent(out, e.consentUrl());
        }

        ApiClient client = minter.oauthClient();
        OAuth.Account account = minter.fetchAccount(client, token.accessToken());

        persistAccount(config, account);
        config.writeToken(token);

        emitSuccess(out, account, token);
        return ExitCode.OK.code();
    }

    /** Built from the resolved {@link CliContext}; honors a test-injected override when present. */
    JwtTokenMinter minter(CliContext context) {
        if (minterOverride != null) {
            return minterOverride;
        }
        return new JwtTokenMinter(context.apiClientFactory(), context.config(), context.environment());
    }

    /** Test seam (package-private): inject a fake minter so {@code call()} runs without the SDK. */
    void useMinter(JwtTokenMinter minter) {
        this.minterOverride = minter;
    }

    private void validateConfig(Config config) {
        if (!config.exists()) {
            throw new AuthException(ExitCode.CONFIG,
                    "No credentials found. Create ~/.docusign-cli/credentials with `integration_key` and "
                            + "`user_id`, place your RSA private key at ~/.docusign-cli/private.key, then re-run.");
        }
        if (isBlank(config.integrationKey())) {
            throw new AuthException(ExitCode.CONFIG, "credentials is missing `integration_key`.");
        }
        if (isBlank(config.userId())) {
            throw new AuthException(ExitCode.CONFIG,
                    "credentials is missing `user_id` (the impersonated user's API GUID).");
        }
        // PrivateKeyLoader (invoked during mint) validates the key file existence/format.
    }

    private int handleConsent(OutputWriter out, String consentUrl) {
        out.message("Consent required. Grant this integration access to act on your behalf:");
        out.message("");
        out.message("  " + consentUrl);
        out.message("");
        out.message("Open the URL, sign in, click \"Allow\", then re-run `docusign-cli login`.");
        out.object(Map.of("status", "consent_required", "consent_url", consentUrl));
        tryOpenBrowser(consentUrl);
        return ExitCode.CONSENT.code();
    }

    private void persistAccount(Config config, OAuth.Account account) {
        Credentials updated = config.readCredentials().toBuilder()
                .accountId(account.getAccountId())
                .baseUri(account.getBaseUri())
                .build();
        config.writeCredentials(updated);
    }

    private void emitSuccess(OutputWriter out, OAuth.Account account, Token token) {
        out.message("Logged in.");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("account", account.getAccountId()
                + (account.getAccountName() != null ? "  (" + account.getAccountName() + ")" : ""));
        fields.put("base_uri", account.getBaseUri());
        fields.put("token_expires", token.expiresAt().toString());
        out.record(fields);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "logged_in");
        payload.put("account_id", account.getAccountId());
        payload.put("account_name", account.getAccountName());
        payload.put("base_uri", account.getBaseUri());
        payload.put("token_expires_at", token.expiresAt().toString());
        out.object(payload);
    }

    private static void tryOpenBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ignored) {
            // Headless/SSH or no browser: the URL was already printed, so this is non-fatal.
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
