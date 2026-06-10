package io.github.moacyrricardo.docusign.send;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Declared signers, resolvable by email (case-insensitive) or by declared name key (spec 005 §2).
 * Declaration order is preserved so {@code recipientId} is assigned sequentially. Duplicate emails
 * are rejected at registration.
 */
public final class RecipientRegistry {

    private final List<DeclaredRecipient> ordered = new ArrayList<>();
    private final Map<String, DeclaredRecipient> byEmail = new LinkedHashMap<>();
    private final Map<String, DeclaredRecipient> byName = new LinkedHashMap<>();

    /** Registers a recipient; throws {@link SendUsageException} on a duplicate email. */
    public void add(DeclaredRecipient recipient) {
        String emailKey = recipient.email().toLowerCase(Locale.ROOT);
        if (byEmail.containsKey(emailKey)) {
            throw new SendUsageException("Duplicate recipient email: " + recipient.email());
        }
        ordered.add(recipient);
        byEmail.put(emailKey, recipient);
        byName.put(recipient.name().toLowerCase(Locale.ROOT), recipient);
    }

    /** Resolves by email (case-insensitive) or by declared name key; null if not found. */
    public DeclaredRecipient resolve(String emailOrKey) {
        if (emailOrKey == null) {
            return null;
        }
        String key = emailOrKey.trim().toLowerCase(Locale.ROOT);
        DeclaredRecipient byMail = byEmail.get(key);
        return byMail != null ? byMail : byName.get(key);
    }

    /** All recipients in declaration order. */
    public List<DeclaredRecipient> all() {
        return List.copyOf(ordered);
    }

    /** Whether any recipient is declared. */
    public boolean isEmpty() {
        return ordered.isEmpty();
    }
}
