package io.github.moacyrricardo.docusign.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    private Config configAt(Path root) {
        return Config.open(ConfigPaths.at(root));
    }

    @Test
    void credentialsRoundTrip(@TempDir Path root) {
        Config config = configAt(root);
        assertFalse(config.exists());

        Credentials written = Credentials.builder()
                .integrationKey("int-key-123")
                .userId("user-guid")
                .accountId("acct-1")
                .baseUri("https://demo.docusign.net")
                .privateKeyPath(root.resolve("private.key"))
                .build();
        config.writeCredentials(written);

        Config reopened = configAt(root);
        assertTrue(reopened.exists());
        Credentials read = reopened.readCredentials();
        assertEquals("int-key-123", read.integrationKey());
        assertEquals("user-guid", read.userId());
        assertEquals("acct-1", read.accountId());
        assertEquals("https://demo.docusign.net", read.baseUri());
        assertEquals(root.resolve("private.key"), read.privateKeyPath());
        // default redirect_uri applied when absent
        assertEquals(Credentials.DEFAULT_REDIRECT_URI, read.redirectUri());
    }

    @Test
    void flatAccessorsDelegateToCredentials(@TempDir Path root) {
        Config config = configAt(root);
        config.writeCredentials(Credentials.builder()
                .integrationKey("ik")
                .accountId("acct-9")
                .build());
        Config reopened = configAt(root);
        assertEquals("ik", reopened.integrationKey());
        assertEquals("acct-9", reopened.accountId());
    }

    @Test
    void readCredentialsThrowsWhenMissing(@TempDir Path root) {
        ConfigException ex = assertThrows(ConfigException.class, () -> configAt(root).readCredentials());
        assertEquals(io.github.moacyrricardo.docusign.cli.ExitCode.CONFIG, ex.exitCode());
    }

    @Test
    void rootDirAndCredentialsHaveRestrictivePosixPerms(@TempDir Path root) {
        assumePosix(root);
        Config config = configAt(root);
        config.writeCredentials(Credentials.builder().integrationKey("ik").build());

        assertEquals("rwx------", permString(config.paths().root()));
        assertEquals("rw-------", permString(config.paths().credentials()));
    }

    @Test
    void tokenRoundTripAndPerms(@TempDir Path root) {
        Config config = configAt(root);
        assertTrue(config.readToken().isEmpty());

        Token token = new Token("ey.aaa", "Bearer", Instant.parse("2026-06-09T18:42:00Z"));
        config.writeToken(token);

        Optional<Token> read = config.readToken();
        assertTrue(read.isPresent());
        assertEquals(token, read.get());

        if (hasPosix(root)) {
            assertEquals("rw-------", permString(config.paths().token()));
        }
    }

    @Test
    void readTokenReturnsEmptyOnGarbage(@TempDir Path root) throws Exception {
        Config config = configAt(root);
        Files.createDirectories(root);
        Files.writeString(config.paths().token(), "not json at all {{{");
        assertTrue(config.readToken().isEmpty());
    }

    @Test
    void clearTokenRemovesFileAndIsIdempotent(@TempDir Path root) {
        Config config = configAt(root);
        config.writeToken(new Token("t", "Bearer", Instant.now().plusSeconds(3600)));
        assertTrue(config.readToken().isPresent());
        config.clearToken();
        assertTrue(config.readToken().isEmpty());
        config.clearToken(); // no-op, must not throw
    }

    @Test
    void atomicWriteLeavesNoTempArtifacts(@TempDir Path root) throws Exception {
        Config config = configAt(root);
        config.writeCredentials(Credentials.builder().integrationKey("ik").build());
        config.writeToken(new Token("t", "Bearer", Instant.now().plusSeconds(60)));
        try (Stream<Path> entries = Files.list(config.paths().root())) {
            boolean hasTemp = entries.anyMatch(p -> p.getFileName().toString().endsWith(".tmp"));
            assertFalse(hasTemp, "no .tmp files should remain after atomic writes");
        }
    }

    // ---- helpers -----------------------------------------------------------

    private static boolean hasPosix(Path path) {
        return Files.getFileAttributeView(path, PosixFileAttributeView.class) != null;
    }

    private static void assumePosix(Path path) {
        org.junit.jupiter.api.Assumptions.assumeTrue(hasPosix(path), "POSIX filesystem required");
    }

    private static String permString(Path path) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
            return PosixFilePermissions.toString(perms);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
