package io.github.moacyrricardo.docusign.send;

/**
 * Parses a {@code --recipient "Name=email"} spec into a {@link DeclaredRecipient} (spec 005 §2).
 * Splits on the first {@code =}; both sides are trimmed and must be non-empty, and the email must
 * contain an {@code @} (light validation — DocuSign is authoritative).
 */
public final class RecipientSpecParser {

    /** Parses one spec; throws {@link SendUsageException} on malformed input. */
    public DeclaredRecipient parse(String raw) {
        if (raw == null) {
            throw malformed("null");
        }
        int eq = raw.indexOf('=');
        if (eq < 0) {
            throw malformed(raw);
        }
        String name = raw.substring(0, eq).trim();
        String email = raw.substring(eq + 1).trim();
        if (name.isEmpty() || email.isEmpty()) {
            throw malformed(raw);
        }
        if (!email.contains("@")) {
            throw new SendUsageException(
                    "Invalid --recipient \"" + raw + "\": \"" + email + "\" is not an email address.");
        }
        return new DeclaredRecipient(name, email);
    }

    private static SendUsageException malformed(String raw) {
        return new SendUsageException("Invalid --recipient \"" + raw + "\": expected \"Name=email\".");
    }
}
