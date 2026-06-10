package io.github.moacyrricardo.docusign.auth;

import com.docusign.esign.client.ApiClient;
import com.docusign.esign.client.auth.OAuth;
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

class LoginCommandTest {

    private Config loggedOutConfig(Path root) {
        Config config = Config.open(ConfigPaths.at(root));
        config.writeCredentials(Credentials.builder()
                .integrationKey("ik").userId("uid").build());
        return Config.open(ConfigPaths.at(root));
    }

    /** A RootCommand whose context is hand-built against a temp Config and given output sink. */
    private static RootCommand rootWith(CliContext context) {
        return new RootCommand() {
            @Override
            public CliContext context() {
                return context;
            }
        };
    }

    private static CliContext contextFor(Config config, OutputWriter out) {
        ApiClientFactory factory = new ApiClientFactory(Environment.DEMO, config);
        return new CliContext(Environment.DEMO, out, false, config, factory,
                new CachingTokenProvider(config, new JwtTokenMinter(factory, config, Environment.DEMO)));
    }

    /** A minter that returns a fixed token and account without any SDK/network call. */
    private static JwtTokenMinter fakeMinter(Config config, Token token, OAuth.Account account,
                                             boolean consent) {
        return new JwtTokenMinter(new ApiClientFactory(Environment.DEMO, config), config, Environment.DEMO) {
            @Override
            public Token mint() throws ConsentException {
                if (consent) {
                    throw new ConsentException("https://account-d.docusign.com/oauth/auth?client_id=ik");
                }
                return token;
            }

            @Override
            public ApiClient oauthClient() {
                return new ApiClient();
            }

            @Override
            protected OAuth.Account fetchAccount(ApiClient client, String accessToken) {
                return account;
            }
        };
    }

    private static LoginCommand loginWith(RootCommand root, JwtTokenMinter minter) {
        LoginCommand cmd = new LoginCommand();
        cmd.root = root;
        cmd.useMinter(minter);
        return cmd;
    }

    @Test
    void successPersistsAccountAndWritesToken(@TempDir Path root) {
        Config config = loggedOutConfig(root);
        StringWriter sink = new StringWriter();
        OutputWriter out = new TableWriter(sink);
        CliContext ctx = contextFor(config, out);

        Token token = new Token("acc-tok", "Bearer", Instant.now().plusSeconds(3600));
        OAuth.Account account = new OAuth.Account();
        account.setAccountId("acct-123");
        account.setAccountName("Acme Inc");
        account.setBaseUri("https://demo.docusign.net");
        account.setIsDefault("true");

        LoginCommand login = loginWith(rootWith(ctx), fakeMinter(config, token, account, false));
        int exit = login.call();

        assertEquals(ExitCode.OK.code(), exit);
        Config reread = Config.open(ConfigPaths.at(root));
        assertEquals("acct-123", reread.accountId());
        assertEquals("https://demo.docusign.net", reread.baseUri());
        assertEquals("acc-tok", reread.readToken().orElseThrow().accessToken());
        assertTrue(sink.toString().contains("Logged in"));
    }

    @Test
    void consentPathPrintsUrlAndReturnsConsentExit(@TempDir Path root) {
        Config config = loggedOutConfig(root);
        StringWriter sink = new StringWriter();
        CliContext ctx = contextFor(config, new TableWriter(sink));

        LoginCommand login = loginWith(rootWith(ctx), fakeMinter(config, null, null, true));
        int exit = login.call();

        assertEquals(ExitCode.CONSENT.code(), exit);
        assertTrue(sink.toString().contains("Consent required"));
        assertTrue(sink.toString().contains("/oauth/auth"));
        assertTrue(config.readToken().isEmpty(), "consent path must not write a token");
    }

    @Test
    void missingUserIdFailsConfig(@TempDir Path root) {
        Config config = Config.open(ConfigPaths.at(root));
        config.writeCredentials(Credentials.builder().integrationKey("ik").build()); // no user_id
        config = Config.open(ConfigPaths.at(root));
        CliContext ctx = contextFor(config, new TableWriter(new StringWriter()));

        Token token = new Token("t", "Bearer", Instant.now().plusSeconds(60));
        LoginCommand login = loginWith(rootWith(ctx),
                fakeMinter(config, token, new OAuth.Account(), false));

        AuthException ex = org.junit.jupiter.api.Assertions.assertThrows(
                AuthException.class, login::call);
        assertEquals(ExitCode.CONFIG, ex.exitCode());
    }
}
