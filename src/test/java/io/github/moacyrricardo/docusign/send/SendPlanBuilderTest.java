package io.github.moacyrricardo.docusign.send;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SendPlanBuilderTest {

    private final SendPlanBuilder builder = new SendPlanBuilder();

    private RecipientRegistry registry(DeclaredRecipient... recipients) {
        RecipientRegistry reg = new RecipientRegistry();
        for (DeclaredRecipient r : recipients) {
            reg.add(r);
        }
        return reg;
    }

    @Test
    void groupsBindingsPerRecipient() {
        DeclaredRecipient moa = new DeclaredRecipient("Moacyr", "moa@x.com");
        DeclaredRecipient ana = new DeclaredRecipient("Ana", "ana@x.com");
        RecipientRegistry reg = registry(moa, ana);

        SendPlan plan = builder.build(Path.of("doc.pdf"), "Sign", reg, List.of(
                new AnchorSpec("_s1_", TabType.SIGNATURE, "moa@x.com"),
                new AnchorSpec("_d1_", TabType.DATE, "Moacyr"),
                new AnchorSpec("_s2_", TabType.SIGNATURE, "ana@x.com")));

        assertEquals(2, plan.bindingsFor(moa).size());
        assertEquals(1, plan.bindingsFor(ana).size());
        assertEquals(List.of(moa, ana), plan.recipients());
    }

    @Test
    void undeclaredRecipientErrorsAccumulate() {
        RecipientRegistry reg = registry(new DeclaredRecipient("Moacyr", "moa@x.com"));

        SendUsageException ex = assertThrows(SendUsageException.class,
                () -> builder.build(Path.of("doc.pdf"), "Sign", reg, List.of(
                        new AnchorSpec("_a_", TabType.SIGNATURE, "ghost@x.com"),
                        new AnchorSpec("_b_", TabType.DATE, "alsoghost@x.com"))));

        assertTrue(ex.getMessage().contains("ghost@x.com"));
        assertTrue(ex.getMessage().contains("alsoghost@x.com"), "errors should accumulate");
    }
}
