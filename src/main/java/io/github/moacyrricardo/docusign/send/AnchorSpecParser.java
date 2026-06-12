package io.github.moacyrricardo.docusign.send;

/**
 * Parses an {@code anchorString=[type:]recipient} spec into an {@link AnchorSpec} (spec 005 §3).
 * Splits on the first {@code =}; if the right-hand side contains a {@code :} it splits on the first
 * {@code :} into a type token and recipient reference, otherwise the type defaults to
 * {@link TabType#SIGNATURE}. The {@code anchorString} is taken verbatim (it is the literal marker).
 */
public final class AnchorSpecParser {

    /** Parses one spec; throws {@link SendUsageException} on malformed input or an unknown type. */
    public AnchorSpec parse(String raw) {
        if (raw == null) {
            throw malformed("null");
        }
        int eq = raw.indexOf('=');
        if (eq < 0) {
            throw malformed(raw);
        }
        String anchorString = raw.substring(0, eq);   // verbatim — not trimmed/normalized
        String rhs = raw.substring(eq + 1);
        if (anchorString.isEmpty() || rhs.isBlank()) {
            throw malformed(raw);
        }

        TabType type = TabType.SIGNATURE;
        String recipientRef = rhs.trim();
        int colon = rhs.indexOf(':');
        if (colon >= 0) {
            String typeToken = rhs.substring(0, colon).trim();
            recipientRef = rhs.substring(colon + 1).trim();
            type = TabType.fromToken(typeToken)
                    .orElseThrow(() -> new SendUsageException(
                            "Unknown tab type \"" + typeToken + "\" in \"" + raw
                                    + "\". Supported: signature, initials, date, text."));
        }
        if (recipientRef.isEmpty()) {
            throw malformed(raw);
        }
        return new AnchorSpec(anchorString, type, recipientRef);
    }

    private static SendUsageException malformed(String raw) {
        return new SendUsageException(
                "Invalid anchor spec \"" + raw + "\": expected anchorString=[type:]recipient.");
    }
}
