package io.github.moacyrricardo.docusign.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Single read/write gateway for on-disk credentials and the cached token (spec 002 §4.3). Does no
 * network or crypto (that is 003). Credentials are stored as a {@link Properties}-style file; the
 * token as JSON. Directories are created {@code 0700} and files {@code 0600} (best-effort on
 * non-POSIX filesystems), and writes are atomic (temp file + {@code ATOMIC_MOVE}).
 */
public final class Config {

    // Credential property keys (spec 002 §4.1).
    static final String KEY_INTEGRATION_KEY = "integration_key";
    static final String KEY_USER_ID = "user_id";
    static final String KEY_ACCOUNT_ID = "account_id";
    static final String KEY_BASE_URI = "base_uri";
    static final String KEY_PRIVATE_KEY_PATH = "private_key_path";
    static final String KEY_REDIRECT_URI = "redirect_uri";

    private static final Set<PosixFilePermission> DIR_PERMS =
            PosixFilePermissions.fromString("rwx------");   // 0700
    private static final Set<PosixFilePermission> FILE_PERMS =
            PosixFilePermissions.fromString("rw-------");   // 0600

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final ConfigPaths paths;
    private Credentials cachedCredentials;

    private Config(ConfigPaths paths) {
        this.paths = paths;
    }

    /** Open using the default paths (honoring {@code DOCUSIGN_CLI_HOME}). */
    public static Config open() {
        return new Config(ConfigPaths.defaults());
    }

    /** Open against explicit paths (test seam). */
    public static Config open(ConfigPaths paths) {
        return new Config(paths);
    }

    /** The resolved paths backing this config. */
    public ConfigPaths paths() {
        return paths;
    }

    /** Whether the credentials file is present on disk. */
    public boolean exists() {
        return Files.isRegularFile(paths.credentials());
    }

    // ---- credentials -------------------------------------------------------

    /**
     * Reads and parses the credentials file.
     *
     * @throws ConfigException if the file is missing or unreadable.
     */
    public Credentials readCredentials() {
        if (cachedCredentials != null) {
            return cachedCredentials;
        }
        Path file = paths.credentials();
        if (!Files.isRegularFile(file)) {
            throw new ConfigException(
                    "No credentials found at " + file + " — run `docusign-cli login` after setup.");
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            throw new ConfigException("Could not read credentials at " + file, e);
        }

        String integrationKey = trimToNull(props.getProperty(KEY_INTEGRATION_KEY));
        if (integrationKey == null) {
            throw new ConfigException(
                    "credentials is missing required key `" + KEY_INTEGRATION_KEY + "` (" + file + ")");
        }

        String keyPathProp = trimToNull(props.getProperty(KEY_PRIVATE_KEY_PATH));
        Path privateKeyPath = (keyPathProp != null) ? Path.of(keyPathProp) : paths.privateKey();

        cachedCredentials = Credentials.builder()
                .integrationKey(integrationKey)
                .userId(trimToNull(props.getProperty(KEY_USER_ID)))
                .accountId(trimToNull(props.getProperty(KEY_ACCOUNT_ID)))
                .baseUri(trimToNull(props.getProperty(KEY_BASE_URI)))
                .privateKeyPath(privateKeyPath)
                .redirectUri(trimToNull(props.getProperty(KEY_REDIRECT_URI)))
                .build();
        return cachedCredentials;
    }

    /** Writes credentials atomically, creating the root dir (0700) and file (0600). */
    public void writeCredentials(Credentials c) {
        ensureRoot();
        Properties props = new Properties();
        putIfPresent(props, KEY_INTEGRATION_KEY, c.integrationKey());
        putIfPresent(props, KEY_USER_ID, c.userId());
        putIfPresent(props, KEY_ACCOUNT_ID, c.accountId());
        putIfPresent(props, KEY_BASE_URI, c.baseUri());
        if (c.privateKeyPath() != null) {
            props.setProperty(KEY_PRIVATE_KEY_PATH, c.privateKeyPath().toString());
        }
        putIfPresent(props, KEY_REDIRECT_URI, c.redirectUri());

        Path target = paths.credentials();
        Path tmp = tempSibling(target);
        try (var out = Files.newOutputStream(tmp)) {
            props.store(out, "docusign-cli credentials");
        } catch (IOException e) {
            throw new ConfigException("Could not write credentials to " + target, e);
        }
        restrictFile(tmp);
        atomicMove(tmp, target);
        cachedCredentials = c;
    }

    // ---- flat convenience accessors (spec 002 §4.3) ------------------------

    public String integrationKey() {
        return readCredentials().integrationKey();
    }

    public String userId() {
        return readCredentials().userId();
    }

    public String accountId() {
        return readCredentials().accountId();
    }

    public String baseUri() {
        return readCredentials().baseUri();
    }

    public Path privateKeyPath() {
        return readCredentials().privateKeyPath();
    }

    public String redirectUri() {
        return readCredentials().redirectUri();
    }

    // ---- token -------------------------------------------------------------

    /** Reads the cached token, or empty if {@code token.json} is absent or unparseable. */
    public Optional<Token> readToken() {
        Path file = paths.token();
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(file.toFile(), Token.class));
        } catch (IOException e) {
            // Garbage/partial token is treated as "no token" so a fresh mint can recover.
            return Optional.empty();
        }
    }

    /** Writes the token atomically with file mode 0600. */
    public void writeToken(Token t) {
        ensureRoot();
        Path target = paths.token();
        Path tmp = tempSibling(target);
        try {
            MAPPER.writeValue(tmp.toFile(), t);
        } catch (IOException e) {
            throw new ConfigException("Could not write token to " + target, e);
        }
        restrictFile(tmp);
        atomicMove(tmp, target);
    }

    /** Deletes {@code token.json} (logout / forced re-mint); a no-op if absent. */
    public void clearToken() {
        try {
            Files.deleteIfExists(paths.token());
        } catch (IOException e) {
            throw new ConfigException("Could not delete token at " + paths.token(), e);
        }
    }

    // ---- filesystem helpers ------------------------------------------------

    private void ensureRoot() {
        Path root = paths.root();
        try {
            if (!Files.isDirectory(root)) {
                Files.createDirectories(root);
            }
            restrictDir(root);
        } catch (IOException e) {
            throw new ConfigException("Could not create config root " + root, e);
        }
    }

    private static Path tempSibling(Path target) {
        Path parent = target.getParent();
        return parent.resolve("." + target.getFileName() + ".tmp");
    }

    private static void atomicMove(Path tmp, Path target) {
        try {
            try {
                Files.move(tmp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailure) {
                // Fall back to a non-atomic replace where ATOMIC_MOVE is unsupported.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }

    private static void restrictDir(Path dir) {
        applyPerms(dir, DIR_PERMS);
    }

    private static void restrictFile(Path file) {
        applyPerms(file, FILE_PERMS);
    }

    private static void applyPerms(Path path, Set<PosixFilePermission> perms) {
        var view = Files.getFileAttributeView(path,
                java.nio.file.attribute.PosixFileAttributeView.class);
        if (view == null) {
            return; // non-POSIX filesystem (e.g. Windows): best-effort, skip
        }
        try {
            Files.setPosixFilePermissions(path, EnumSet.copyOf(perms));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void putIfPresent(Properties props, String key, String value) {
        if (value != null && !value.isBlank()) {
            props.setProperty(key, value);
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
