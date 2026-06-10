package io.github.moacyrricardo.docusign.anchor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TypeGuesserTest {

    @Test
    void initialsTakePrecedenceOverSignature() {
        assertEquals(GuessedType.INITIALS, TypeGuesser.guess("_sig_i_363_"));
    }

    @Test
    void signatureRecognized() {
        assertEquals(GuessedType.SIGNATURE, TypeGuesser.guess("_sig_363_"));
        assertEquals(GuessedType.SIGNATURE, TypeGuesser.guess("X_signature_1"));
    }

    @Test
    void dateAndTextRecognized() {
        assertEquals(GuessedType.DATE, TypeGuesser.guess("_date_363_"));
        assertEquals(GuessedType.TEXT, TypeGuesser.guess("_text_1"));
        assertEquals(GuessedType.TEXT, TypeGuesser.guess("_name_1"));
    }

    @Test
    void caseInsensitive() {
        assertEquals(GuessedType.INITIALS, TypeGuesser.guess("_SIG_I_363_"));
    }

    @Test
    void unknownFallback() {
        assertEquals(GuessedType.UNKNOWN, TypeGuesser.guess("plain text"));
        assertEquals(GuessedType.UNKNOWN, TypeGuesser.guess(null));
    }
}
