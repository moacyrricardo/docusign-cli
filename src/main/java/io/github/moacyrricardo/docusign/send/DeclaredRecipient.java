package io.github.moacyrricardo.docusign.send;

/**
 * A signer declared on the command line via {@code --recipient "Name=email"} (spec 005 §2). v1
 * recipients are signers only — no routing order, no CC.
 */
public record DeclaredRecipient(String name, String email) {
}
