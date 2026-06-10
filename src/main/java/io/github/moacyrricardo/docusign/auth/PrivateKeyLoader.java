package io.github.moacyrricardo.docusign.auth;

import io.github.moacyrricardo.docusign.cli.ExitCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads a PEM-encoded RSA private key as the raw bytes the DocuSign SDK expects (spec 003 §4.2). The
 * SDK's {@code requestJWTUserToken} parses the PEM internally with BouncyCastle, so this loader does
 * <b>no</b> RSA parsing: it only reads the file and validates that it exists, is non-empty, and looks
 * like PEM. Any failure is surfaced as an {@link AuthException} mapping to {@link ExitCode#CONFIG}.
 */
final class PrivateKeyLoader {

    private static final String PEM_PREFIX = "-----BEGIN";

    /**
     * Reads {@code keyPath} and returns its raw bytes (PEM armor included).
     *
     * @throws AuthException ({@link ExitCode#CONFIG}) if the file is missing, unreadable, empty, or
     *     not PEM-armored.
     */
    byte[] load(Path keyPath) {
        if (keyPath == null) {
            throw new AuthException(ExitCode.CONFIG,
                    "No private key path configured — see setup steps in `docusign-cli login --help`.");
        }
        if (!Files.isRegularFile(keyPath) || !Files.isReadable(keyPath)) {
            throw new AuthException(ExitCode.CONFIG, "private key not found at " + keyPath);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(keyPath);
        } catch (IOException e) {
            throw new AuthException(ExitCode.CONFIG, "could not read private key at " + keyPath, e);
        }
        if (bytes.length == 0) {
            throw new AuthException(ExitCode.CONFIG, "private key at " + keyPath + " is empty");
        }
        String head = new String(bytes, 0, Math.min(bytes.length, 64), StandardCharsets.US_ASCII);
        if (!head.stripLeading().startsWith(PEM_PREFIX)) {
            throw new AuthException(ExitCode.CONFIG,
                    "private key at " + keyPath + " is not PEM-encoded (expected a `-----BEGIN ...` header)");
        }
        return bytes;
    }
}
