package io.github.moacyrricardo.docusign.anchor;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Reproducible PDF fixtures built with PDFBox itself (spec 004 §8). Each helper writes a one- or
 * multi-page PDF with text at known size/colour/transform so detection can be asserted.
 */
final class PdfFixtures {

    private static final PDType1Font HELVETICA = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    private PdfFixtures() {
    }

    /** A page with one text run at the given font size and RGB colour, no transform. */
    static File simple(Path dir, String name, String text, float fontSize, Color rgb) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(HELVETICA, fontSize);
                cs.setNonStrokingColor(rgb);
                cs.newLineAtOffset(72, 700);
                cs.showText(text);
                cs.endText();
            }
            return save(doc, dir, name);
        }
    }

    /** A page with one run drawn at {@code declaredSize} under a uniform CTM scale (on-page size halves). */
    static File scaled(Path dir, String name, String text, float declaredSize, float scale, Color rgb)
            throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(HELVETICA, declaredSize);
                cs.setNonStrokingColor(rgb);
                cs.setTextMatrix(Matrix.getScaleInstance(scale, scale)
                        .multiply(Matrix.getTranslateInstance(72, 700)));
                cs.showText(text);
                cs.endText();
            }
            return save(doc, dir, name);
        }
    }

    /** A page with one run filled in DeviceCMYK white (0,0,0,0 → on-screen white). */
    static File cmykWhite(Path dir, String name, String text, float fontSize) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(HELVETICA, fontSize);
                PDColor white = new PDColor(new float[] {0f, 0f, 0f, 0f}, PDDeviceCMYK.INSTANCE);
                cs.setNonStrokingColor(white);
                cs.newLineAtOffset(72, 700);
                cs.showText(text);
                cs.endText();
            }
            return save(doc, dir, name);
        }
    }

    /** Two pages: {@code firstText} on page 1, {@code thirdText} on page 3 (page 2 blank). */
    static File twoStrings(Path dir, String name, String page1Text, String page3Text,
                           float fontSize, Color rgb) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            addTextPage(doc, page1Text, fontSize, rgb);
            doc.addPage(new PDPage());                 // blank page 2
            addTextPage(doc, page3Text, fontSize, rgb);
            return save(doc, dir, name);
        }
    }

    private static void addTextPage(PDDocument doc, String text, float fontSize, Color rgb)
            throws IOException {
        PDPage page = new PDPage();
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(HELVETICA, fontSize);
            cs.setNonStrokingColor(rgb);
            cs.newLineAtOffset(72, 700);
            cs.showText(text);
            cs.endText();
        }
    }

    private static File save(PDDocument doc, Path dir, String name) throws IOException {
        File file = dir.resolve(name).toFile();
        doc.save(file);
        return file;
    }
}
