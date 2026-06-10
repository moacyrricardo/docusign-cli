package io.github.moacyrricardo.docusign.anchor;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class EncryptedPdfTest {

    private File passwordProtected(Path dir) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            AccessPermission ap = new AccessPermission();
            StandardProtectionPolicy spp = new StandardProtectionPolicy("owner-pw", "user-pw", ap);
            spp.setEncryptionKeyLength(128);
            doc.protect(spp);
            File file = dir.resolve("encrypted.pdf").toFile();
            doc.save(file);
            return file;
        }
    }

    @Test
    void encryptedPdfThrowsEncryptedPdfException(@TempDir Path dir) throws IOException {
        File pdf = passwordProtected(dir);
        assertThrows(EncryptedPdfException.class,
                () -> new AnchorScanner().scan(pdf, ScanOptions.defaults()));
    }
}
