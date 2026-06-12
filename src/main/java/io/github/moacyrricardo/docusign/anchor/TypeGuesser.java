package io.github.moacyrricardo.docusign.anchor;

import java.util.Locale;

/**
 * Maps an anchor string to a {@link GuessedType} by common naming conventions (spec 004 §6). The
 * match is case-insensitive and order-sensitive: {@code _sig_i_} (initials) is tested before
 * {@code _sig_} (signature) because it contains the latter as a substring. Heuristic only — never
 * authoritative.
 */
public final class TypeGuesser {

    private TypeGuesser() {
    }

    /** Guesses the tab type denoted by {@code anchorString}; {@link GuessedType#UNKNOWN} if no rule matches. */
    public static GuessedType guess(String anchorString) {
        if (anchorString == null) {
            return GuessedType.UNKNOWN;
        }
        String s = anchorString.toLowerCase(Locale.ROOT);
        if (s.contains("_sig_i_")) {
            return GuessedType.INITIALS;   // initials before signature (substring precedence)
        }
        if (s.contains("_sig_") || s.contains("_signature_")) {
            return GuessedType.SIGNATURE;
        }
        if (s.contains("_date_")) {
            return GuessedType.DATE;
        }
        if (s.contains("_text_") || s.contains("_name_")) {
            return GuessedType.TEXT;
        }
        return GuessedType.UNKNOWN;
    }
}
