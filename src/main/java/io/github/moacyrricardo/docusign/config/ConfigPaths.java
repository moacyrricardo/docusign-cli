package io.github.moacyrricardo.docusign.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves the on-disk locations under {@code ~/.docusign-cli/} (spec 002 §4.3). The root may be
 * overridden via the {@code DOCUSIGN_CLI_HOME} environment variable, which is the seam tests use
 * to point at a temporary directory.
 */
public final class ConfigPaths {

    /** Environment variable that overrides the config root (used by tests). */
    public static final String HOME_ENV = "DOCUSIGN_CLI_HOME";

    private final Path root;

    private ConfigPaths(Path root) {
        this.root = root;
    }

    /** Default paths, honoring {@code DOCUSIGN_CLI_HOME}, else {@code ~/.docusign-cli}. */
    public static ConfigPaths defaults() {
        String override = System.getenv(HOME_ENV);
        Path resolvedRoot = (override != null && !override.isBlank())
                ? Paths.get(override)
                : Paths.get(System.getProperty("user.home"), ".docusign-cli");
        return new ConfigPaths(resolvedRoot.toAbsolutePath());
    }

    /** Build paths rooted at an explicit directory (test seam). */
    public static ConfigPaths at(Path root) {
        return new ConfigPaths(root.toAbsolutePath());
    }

    /** The config root directory ({@code ~/.docusign-cli}). */
    public Path root() {
        return root;
    }

    /** The credentials properties file. */
    public Path credentials() {
        return root.resolve("credentials");
    }

    /** The default RSA private-key location. */
    public Path privateKey() {
        return root.resolve("private.key");
    }

    /** The cached-token JSON file. */
    public Path token() {
        return root.resolve("token.json");
    }
}
