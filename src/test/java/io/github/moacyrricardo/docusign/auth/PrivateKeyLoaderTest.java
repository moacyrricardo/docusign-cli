package io.github.moacyrricardo.docusign.auth;

import io.github.moacyrricardo.docusign.cli.ExitCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PrivateKeyLoaderTest {

    private final PrivateKeyLoader loader = new PrivateKeyLoader();

    @Test
    void validPemBytesReturnedVerbatim(@TempDir Path dir) throws Exception {
        byte[] pem = ("-----BEGIN RSA PRIVATE KEY-----\nQUJD\n-----END RSA PRIVATE KEY-----\n")
                .getBytes(StandardCharsets.UTF_8);
        Path key = dir.resolve("private.key");
        Files.write(key, pem);

        assertArrayEquals(pem, loader.load(key));
    }

    @Test
    void missingFileThrowsConfigError(@TempDir Path dir) {
        AuthException ex = assertThrows(AuthException.class,
                () -> loader.load(dir.resolve("absent.key")));
        assertEquals(ExitCode.CONFIG, ex.exitCode());
    }

    @Test
    void emptyFileThrowsConfigError(@TempDir Path dir) throws Exception {
        Path key = dir.resolve("empty.key");
        Files.write(key, new byte[0]);
        AuthException ex = assertThrows(AuthException.class, () -> loader.load(key));
        assertEquals(ExitCode.CONFIG, ex.exitCode());
    }

    @Test
    void nonPemContentThrowsConfigError(@TempDir Path dir) throws Exception {
        Path key = dir.resolve("garbage.key");
        Files.write(key, "not a pem file at all".getBytes(StandardCharsets.UTF_8));
        AuthException ex = assertThrows(AuthException.class, () -> loader.load(key));
        assertEquals(ExitCode.CONFIG, ex.exitCode());
    }

    @Test
    void nullPathThrowsConfigError() {
        AuthException ex = assertThrows(AuthException.class, () -> loader.load(null));
        assertEquals(ExitCode.CONFIG, ex.exitCode());
    }
}
