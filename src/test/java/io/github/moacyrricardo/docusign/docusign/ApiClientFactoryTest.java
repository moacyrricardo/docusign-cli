package io.github.moacyrricardo.docusign.docusign;

import com.docusign.esign.client.ApiClient;
import io.github.moacyrricardo.docusign.config.Config;
import io.github.moacyrricardo.docusign.config.ConfigPaths;
import io.github.moacyrricardo.docusign.config.Credentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiClientFactoryTest {

    private Config configWithBaseUri(Path root, String baseUri) {
        Config config = Config.open(ConfigPaths.at(root));
        Credentials.Builder builder = Credentials.builder().integrationKey("ik");
        if (baseUri != null) {
            builder.baseUri(baseUri);
        }
        config.writeCredentials(builder.build());
        return Config.open(ConfigPaths.at(root));
    }

    @Test
    void authenticatedUsesConfigBaseUriWhenSet(@TempDir Path root) {
        Config config = configWithBaseUri(root, "https://na3.docusign.net");
        ApiClientFactory factory = new ApiClientFactory(Environment.DEMO, config);

        ApiClient client = factory.authenticated("tok-abc");
        assertEquals("https://na3.docusign.net/restapi", client.getBasePath());
    }

    @Test
    void restBasePathDoesNotDoubleAppendRestapi(@TempDir Path root) {
        Config config = configWithBaseUri(root, "https://na3.docusign.net/restapi");
        ApiClientFactory factory = new ApiClientFactory(Environment.DEMO, config);
        assertEquals("https://na3.docusign.net/restapi", factory.restBasePath());
    }

    @Test
    void authenticatedFallsBackToEnvWhenBaseUriUnset(@TempDir Path root) {
        Config config = configWithBaseUri(root, null);
        ApiClientFactory factory = new ApiClientFactory(Environment.PROD, config);

        ApiClient client = factory.authenticated("tok-abc");
        assertEquals(Environment.PROD.restBasePath(), client.getBasePath());
    }

    @Test
    void authenticatedFallsBackToEnvWhenNoConfigFile(@TempDir Path root) {
        Config config = Config.open(ConfigPaths.at(root)); // no credentials written
        ApiClientFactory factory = new ApiClientFactory(Environment.DEMO, config);
        assertEquals(Environment.DEMO.restBasePath(), factory.authenticated("t").getBasePath());
    }

    @Test
    void authenticatedAppliesBearerHeader(@TempDir Path root) {
        Config config = configWithBaseUri(root, null);
        ApiClientFactory factory = new ApiClientFactory(Environment.DEMO, config);

        ApiClient client = factory.authenticated("tok-xyz");
        assertEquals("Bearer tok-xyz", defaultHeader(client, "Authorization"));
    }

    @Test
    void oauthClientTargetsEnvOAuthHostWithNoBearer(@TempDir Path root) {
        Config config = configWithBaseUri(root, "https://na3.docusign.net");
        ApiClientFactory factory = new ApiClientFactory(Environment.DEMO, config);

        ApiClient client = factory.oauthClient();
        assertEquals(Environment.DEMO.oAuthBasePath(), oauthBasePath(client));
        assertNull(defaultHeader(client, "Authorization"), "oauth client must carry no bearer");
    }

    // ---- reflection helpers (the SDK exposes these only as package-private fields) ----

    @SuppressWarnings("unchecked")
    private static String defaultHeader(ApiClient client, String name) {
        try {
            Field f = ApiClient.class.getDeclaredField("defaultHeaderMap");
            f.setAccessible(true);
            return ((Map<String, String>) f.get(client)).get(name);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static String oauthBasePath(ApiClient client) {
        try {
            Field f = ApiClient.class.getDeclaredField("oAuthBasePath");
            f.setAccessible(true);
            return (String) f.get(client);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
