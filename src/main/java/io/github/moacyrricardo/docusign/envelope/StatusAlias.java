package io.github.moacyrricardo.docusign.envelope;

import io.github.moacyrricardo.docusign.cli.CliException;
import io.github.moacyrricardo.docusign.cli.ExitCode;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps the friendly {@code --status} aliases to DocuSign status query values (spec 006 §1.3).
 * Case-insensitive; an unrecognized value is a usage error.
 */
final class StatusAlias {

    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        ALIASES.put("signed", "completed");
        ALIASES.put("completed", "completed");
        ALIASES.put("sent", "sent");
        ALIASES.put("delivered", "delivered");
        ALIASES.put("voided", "voided");
        ALIASES.put("declined", "declined");
        ALIASES.put("created", "created");
        ALIASES.put("draft", "created");
    }

    private StatusAlias() {
    }

    /** Maps a friendly status to the DocuSign value; throws {@code USAGE} on an unknown alias. */
    static String toDocuSign(String friendly) {
        String key = friendly.trim().toLowerCase(Locale.ROOT);
        String value = ALIASES.get(key);
        if (value == null) {
            throw new CliException(ExitCode.USAGE,
                    "Unknown --status \"" + friendly + "\". Accepted: signed, sent, delivered, "
                            + "voided, declined, created.");
        }
        return value;
    }
}
