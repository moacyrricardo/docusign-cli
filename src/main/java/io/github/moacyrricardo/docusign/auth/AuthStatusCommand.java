package io.github.moacyrricardo.docusign.auth;

import io.github.moacyrricardo.docusign.cli.CliContext;
import io.github.moacyrricardo.docusign.cli.ExitCode;
import io.github.moacyrricardo.docusign.cli.GlobalOptions;
import io.github.moacyrricardo.docusign.config.Config;
import io.github.moacyrricardo.docusign.config.Token;
import io.github.moacyrricardo.docusign.docusign.Environment;
import io.github.moacyrricardo.docusign.output.OutputWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code docusign-cli auth status} — show the resolved environment, the persisted account/base URI,
 * and the cached token's validity (spec 003 §10). <b>Read-only:</b> performs no network calls and
 * never mints. Exits {@link ExitCode#OK} when logged in (even on an expired token — a status report
 * still succeeds) and {@link ExitCode#NOAUTH} when there is no account at all.
 */
@Command(name = "status",
        description = "Show the current authentication and account state.")
public final class AuthStatusCommand implements Callable<Integer> {

    @ParentCommand
    AuthCommand auth;

    @Mixin
    GlobalOptions globalOptions;

    @Override
    public Integer call() {
        CliContext context = auth.root().context();
        Config config = context.config();
        Environment environment = context.environment();
        OutputWriter out = context.output();

        String accountId = config.exists() ? config.accountId() : null;
        String baseUri = config.exists() ? config.baseUri() : null;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("environment", environment.name().toLowerCase());
        payload.put("oauth_host", environment.oAuthBasePath());

        out.message("Environment: " + environment.name().toLowerCase()
                + " (" + environment.oAuthBasePath() + ")");

        if (accountId == null) {
            out.message("Account:     not logged in — run `docusign-cli login`");
            payload.put("logged_in", false);
            out.object(payload);
            return ExitCode.NOAUTH.code();
        }

        out.message("Account:     " + accountId);
        out.message("Base URI:    " + (baseUri != null ? baseUri : "(unknown)"));
        payload.put("logged_in", true);
        payload.put("account_id", accountId);
        payload.put("base_uri", baseUri);

        reportToken(out, config, payload);
        reportHostMismatch(out, environment, baseUri, payload);

        out.object(payload);
        return ExitCode.OK.code();
    }

    private void reportToken(OutputWriter out, Config config, Map<String, Object> payload) {
        Optional<Token> cached = config.readToken();
        if (cached.isEmpty()) {
            out.message("Token:       none — run `docusign-cli login`");
            payload.put("token_present", false);
            return;
        }
        Token token = cached.get();
        Instant now = Instant.now();
        boolean expired = token.isExpired(now, Duration.ZERO);
        payload.put("token_present", true);
        payload.put("token_valid", !expired);
        payload.put("token_expires_at", token.expiresAt().toString());

        if (expired) {
            out.message("Token:       expired (" + token.expiresAt() + ") — run `docusign-cli login`");
        } else {
            Duration remaining = Duration.between(now, token.expiresAt());
            out.message("Token:       valid, expires in " + humanDuration(remaining)
                    + " (" + token.expiresAt() + ")");
        }
    }

    private void reportHostMismatch(OutputWriter out, Environment environment, String baseUri,
                                    Map<String, Object> payload) {
        if (baseUri == null) {
            return;
        }
        boolean prodBaseUri = baseUri.toLowerCase().contains("www.docusign.net");
        boolean mismatch = (environment == Environment.DEMO && prodBaseUri)
                || (environment == Environment.PROD && !prodBaseUri);
        payload.put("host_mismatch", mismatch);
        if (mismatch) {
            out.message("Warning:     selected environment (" + environment.name().toLowerCase()
                    + ") does not match the stored base URI's host — log in again with the right "
                    + "--demo/--prod flag.");
        }
    }

    private static String humanDuration(Duration d) {
        long minutes = d.toMinutes();
        if (minutes >= 60) {
            return (minutes / 60) + "h" + (minutes % 60) + "m";
        }
        if (minutes >= 1) {
            return minutes + "m";
        }
        return Math.max(d.toSeconds(), 0) + "s";
    }
}
