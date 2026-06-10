package io.github.moacyrricardo.docusign.send;

import io.github.moacyrricardo.docusign.cli.ExitCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParsersTest {

    private final AnchorSpecParser anchors = new AnchorSpecParser();
    private final RecipientSpecParser recipients = new RecipientSpecParser();

    // ---- anchor specs ----

    @Test
    void anchorDefaultsToSignature() {
        AnchorSpec spec = anchors.parse("_sig_363_=moacyr.ricardo@gmail.com");
        assertEquals("_sig_363_", spec.anchorString());
        assertEquals(TabType.SIGNATURE, spec.type());
        assertEquals("moacyr.ricardo@gmail.com", spec.recipientRef());
    }

    @Test
    void anchorExplicitTypeAndNameKey() {
        AnchorSpec spec = anchors.parse("_date_363_=date:Moacyr");
        assertEquals(TabType.DATE, spec.type());
        assertEquals("Moacyr", spec.recipientRef());
    }

    @Test
    void anchorInitialsCaseInsensitive() {
        assertEquals(TabType.INITIALS, anchors.parse("_sig_i_=INITIALS:a@b.com").type());
    }

    @Test
    void anchorStringTakenVerbatimWithFirstSplitSemantics() {
        // The anchor string itself may contain neither '=' nor ':' here; the first '=' splits.
        AnchorSpec spec = anchors.parse("_x_=text:key");
        assertEquals("_x_", spec.anchorString());
        assertEquals(TabType.TEXT, spec.type());
    }

    @Test
    void anchorMalformedNoEquals() {
        assertEquals(ExitCode.USAGE,
                assertThrows(SendUsageException.class, () -> anchors.parse("_sig_363_")).exitCode());
    }

    @Test
    void anchorUnknownTypeIsUsageError() {
        SendUsageException ex = assertThrows(SendUsageException.class,
                () -> anchors.parse("_x_=bogus:a@b.com"));
        assertEquals(ExitCode.USAGE, ex.exitCode());
    }

    @Test
    void anchorEmptyRecipientIsUsageError() {
        assertThrows(SendUsageException.class, () -> anchors.parse("_x_=date:"));
    }

    // ---- recipient specs ----

    @Test
    void recipientParsedAndResolvedByEmailAndName() {
        RecipientRegistry reg = new RecipientRegistry();
        reg.add(recipients.parse("Moacyr=Moacyr.Ricardo@gmail.com"));

        assertEquals("Moacyr", reg.resolve("moacyr").name());                       // by name key
        assertEquals("Moacyr", reg.resolve("MOACYR.RICARDO@GMAIL.COM").name());     // email, case-insensitive
        assertNull(reg.resolve("nobody@x.com"));
    }

    @Test
    void duplicateEmailRejected() {
        RecipientRegistry reg = new RecipientRegistry();
        reg.add(recipients.parse("A=dup@x.com"));
        assertThrows(SendUsageException.class, () -> reg.add(recipients.parse("B=DUP@x.com")));
    }

    @Test
    void recipientMalformedRejected() {
        assertThrows(SendUsageException.class, () -> recipients.parse("noequals"));
        assertThrows(SendUsageException.class, () -> recipients.parse("Name="));
        assertThrows(SendUsageException.class, () -> recipients.parse("Name=notanemail"));
    }
}
