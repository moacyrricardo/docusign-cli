package io.github.moacyrricardo.docusign.envelope;

/**
 * One recipient's status on an {@code envelope status --recipients} result (spec 007 §3). Shared by
 * the table and JSON renderers so they cannot drift.
 */
public record RecipientView(String name, String email, String status, String order) {
}
