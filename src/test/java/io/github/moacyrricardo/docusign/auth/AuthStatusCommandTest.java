package io.github.moacyrricardo.docusign.auth;

import io.github.moacyrricardo.docusign.cli.CliContext;
import io.github.moacyrricardo.docusign.cli.ExitCode;
import io.github.moacyrricardo.docusign.cli.RootCommand;
import io.github.moacyrricardo.docusign.config.Config;
import io.github.moacyrricardo.docusign.config.ConfigPaths;
import io.github.moacyrricardo.docusign.config.Credentials;
import io.github.moacyrricardo.docusign.config.Token;
import io.github.moacyrricardo.docusign.docusign.ApiClientFactory;
import io.github.moacyrricardo.docusign.docusign.Environment;
import io.github.moacyrricardo.docusign.output.OutputWriter;
import io.github.moacyrricardo.docusign.output.TableWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthStatusCommandTest {

    private static AuthStatusCommand statusFor(CliContext ctx, StringWriter sink) {
        RootCommand root = new RootCommand() {
            @Override
            public CliContext context() {
                return ctx;
            }
        };
        AuthCommand auth = new AuthCommand();
        // wire the parent chain: status -> auth -> root
        setRoot(auth, root);
        AuthStatusCommand status = new AuthStatusCommand();
        status.auth = auth;
        return status;
    }

    private static void setRoot(AuthCommand auth, RootCommand root) {
        try {
            var f = AuthCommand.class.getDeclaredField("root");
            f.setAccessible(true);
            f.set(auth, root);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static CliContext ctx(Config config, Environment env, OutputWriter out) {
        ApiClientFactory factory = new ApiClientFactory(env, config);
        return new CliContext(env, out, false, config, factory,
                new CachingTokenProvider(config,
                        new JwtTokenMinter(factory, config, env)));
    }

    private Config configWith(Path root, String accountId, String baseUri) {
        Config config = Config.open(ConfigPaths.at(root));
        Credentials.Builder b = Credentials.builder().integrationKey("ik").userId("uid");
        if (accountId != null) {
            b.accountId(accountId);
        }
        if (baseUri != null) {
            b.baseUri(baseUri);
        }
        config.writeCredentials(b.build());
        return Config.open(ConfigPaths.at(root));
    }

    @Test
    void loggedInWithValidTokenReportsOk(@TempDir Path root) {
        Config config = configWith(root, "acct-1", "https://demo.docusign.net");
        config.writeToken(new Token("t", "Bearer", Instant.now().plusSeconds(1800)));
        StringWriter sink = new StringWriter();

        int exit = statusFor(ctx(config, Environment.DEMO, new TableWriter(sink)), sink).call();

        assertEquals(ExitCode.OK.code(), exit);
        String text = sink.toString();
        assertTrue(text.contains("acct-1"));
        assertTrue(text.contains("valid"));
    }

    @Test
    void loggedInWithExpiredTokenStillReportsOk(@TempDir Path root) {
        Config config = configWith(root, "acct-1", "https://demo.docusign.net");
        config.writeToken(new Token("t", "Bearer", Instant.now().minusSeconds(60)));
        StringWriter sink = new StringWriter();

        int exit = statusFor(ctx(config, Environment.DEMO, new TableWriter(sink)), sink).call();

        assertEquals(ExitCode.OK.code(), exit);
        assertTrue(sink.toString().contains("expired"));
    }

    @Test
    void notLoggedInReportsNoauth(@TempDir Path root) {
        Config config = Config.open(ConfigPaths.at(root)); // no credentials at all
        StringWriter sink = new StringWriter();

        int exit = statusFor(ctx(config, Environment.DEMO, new TableWriter(sink)), sink).call();

        assertEquals(ExitCode.NOAUTH.code(), exit);
        assertTrue(sink.toString().contains("not logged in"));
    }

    @Test
    void hostMismatchProducesWarning(@TempDir Path root) {
        // base_uri is a prod host, but the selected environment is demo.
        Config config = configWith(root, "acct-1", "https://www.docusign.net");
        StringWriter sink = new StringWriter();

        int exit = statusFor(ctx(config, Environment.DEMO, new TableWriter(sink)), sink).call();

        assertEquals(ExitCode.OK.code(), exit);
        assertTrue(sink.toString().contains("Warning"));
    }
}
