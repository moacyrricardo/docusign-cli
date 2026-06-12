package io.github.moacyrricardo.docusign.envelope;

import io.github.moacyrricardo.docusign.cli.CliException;
import io.github.moacyrricardo.docusign.cli.ExitCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatusAliasTest {

    @Test
    void signedMapsToCompleted() {
        assertEquals("completed", StatusAlias.toDocuSign("signed"));
        assertEquals("completed", StatusAlias.toDocuSign("completed"));
    }

    @Test
    void draftMapsToCreated() {
        assertEquals("created", StatusAlias.toDocuSign("draft"));
        assertEquals("created", StatusAlias.toDocuSign("created"));
    }

    @Test
    void caseInsensitive() {
        assertEquals("voided", StatusAlias.toDocuSign("VoIdEd"));
    }

    @Test
    void passthroughStatuses() {
        assertEquals("sent", StatusAlias.toDocuSign("sent"));
        assertEquals("delivered", StatusAlias.toDocuSign("delivered"));
        assertEquals("declined", StatusAlias.toDocuSign("declined"));
    }

    @Test
    void unknownIsUsageError() {
        CliException ex = assertThrows(CliException.class, () -> StatusAlias.toDocuSign("bogus"));
        assertEquals(ExitCode.USAGE, ex.exitCode());
    }
}
