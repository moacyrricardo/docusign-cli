package io.github.moacyrricardo.docusign.send;

import java.util.Locale;
import java.util.Optional;

/**
 * The signing-tab types supported in v1 (spec 005 §3.2/§4). Extension (fullname/company/title/
 * checkbox per 001 §5.3) adds an enum constant plus a {@code case} in {@code TabFactory}.
 */
public enum TabType {
    SIGNATURE, INITIALS, DATE, TEXT;

    /** Parses a type token case-insensitively; empty if it names no supported type. */
    public static Optional<TabType> fromToken(String token) {
        if (token == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(TabType.valueOf(token.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
