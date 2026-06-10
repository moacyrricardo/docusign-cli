package io.github.moacyrricardo.docusign.send;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates parsed anchor specs against the declared recipients and groups them per recipient into a
 * {@link SendPlan} (spec 005 §3.3/§5). Validation is eager and accumulating: every spec referencing
 * an undeclared recipient is collected and reported together as one {@link SendUsageException}
 * before any network call.
 */
public final class SendPlanBuilder {

    /**
     * Builds a plan; throws {@link SendUsageException} (→ {@code USAGE}) if any anchor references an
     * undeclared recipient.
     */
    public SendPlan build(Path pdf, String subject, RecipientRegistry recipients,
                          List<AnchorSpec> anchorSpecs) {
        Map<DeclaredRecipient, List<AnchorSpec>> bindings = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        for (AnchorSpec spec : anchorSpecs) {
            DeclaredRecipient recipient = recipients.resolve(spec.recipientRef());
            if (recipient == null) {
                errors.add("Anchor \"" + spec.anchorString() + "\" references undeclared recipient \""
                        + spec.recipientRef() + "\". Declare it with --recipient.");
                continue;
            }
            bindings.computeIfAbsent(recipient, r -> new ArrayList<>()).add(spec);
        }

        if (!errors.isEmpty()) {
            throw new SendUsageException(String.join("\n", errors));
        }
        return new SendPlan(pdf, subject, recipients.all(), bindings);
    }
}
