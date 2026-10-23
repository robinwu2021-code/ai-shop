package ai.neargo.shop.print.channel.pdf;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.channel.font.PdfFontBundle;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.*;
import org.springframework.stereotype.Component;

import java.io.*;
import java.math.BigDecimal;
import java.util.*;

@Component
public final class PdfReceiptRenderer implements PrintPdfPort {
    private final PdfFontBundle fonts;

    public PdfReceiptRenderer(PdfFontBundle fonts) {
        this.fonts = fonts;
    }

    private static float pt(BigDecimal mm) {
        return mm.floatValue() * 72f / 25.4f;
    }

    public PdfArtifact render(PdfRenderInput input) {
        if (input.layouts().isEmpty() || input.layouts().size() > 50)
            throw new PrintFailure("print.exportLimit");
        try (var doc = new PDDocument();
                var output = new ByteArrayOutputStream()) {
            var font = fonts.load(doc);
            var widths = new ArrayList<BigDecimal>();
            int pages = 0;
            var metadata = new PDDocumentInformation();
            metadata.setTitle("Receipt " + input.exportNo());
            metadata.setProducer("ai-shop-print/" + input.rendererVersion());
            if (input.layouts().stream().anyMatch(l -> l.profileVersion().endsWith("_COMPACT_V2")))
                metadata.setCustomMetadataValue("PrintArtifactKind", input.artifactKind().name());
            var date = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            date.setTimeInMillis(input.exportCreatedAt().toEpochMilli());
            metadata.setCreationDate(date);
            metadata.setModificationDate(date);
            doc.setDocumentInformation(metadata);
            var id = new org.apache.pdfbox.cos.COSArray();
            byte[] digest =
                    java.util.HexFormat.of()
                            .parseHex(
                                    PrintHashes.sha256(
                                            input.exportNo() + ":" + input.exportCreatedAt()));
            id.add(new org.apache.pdfbox.cos.COSString(Arrays.copyOf(digest, 16)));
            id.add(new org.apache.pdfbox.cos.COSString(Arrays.copyOf(digest, 16)));
            doc.getDocument().setDocumentID(id);
            for (var layout : input.layouts()) {
                if (!layout.fontBundleVersion().equals(fonts.ref().bundleVersion())
                        || !layout.fontHash().equals(fonts.ref().sha256()))
                    throw new PrintFailure("print.fontVersionMismatch");
                for (var page : layout.pages()) {
                    if (++pages > 200 || page.heightMm().compareTo(BigDecimal.valueOf(270)) > 0)
                        throw new PrintFailure("print.exportLimit");
                    boolean a4 = input.profile() == PdfOutputProfile.A4_SHEET;
                    var rect =
                            a4
                                    ? PDRectangle.A4
                                    : new PDRectangle(
                                            pt(page.widthMm()),
                                            pt(
                                                    page.heightMm()
                                                            .add(
                                                                    BigDecimal.valueOf(
                                                                            layout.profileVersion()
                                                                                            .endsWith(
                                                                                                    "_COMPACT_V2")
                                                                                    ? 6
                                                                                    : 10))));
                    var target = new PDPage(rect);
                    doc.addPage(target);
                    widths.add(a4 ? BigDecimal.valueOf(210) : page.widthMm());
                    float left = a4 ? pt(BigDecimal.TEN) : 0;
                    float top = a4 ? pt(BigDecimal.TEN) : 0;
                    try (var stream = new PDPageContentStream(doc, target)) {
                        for (var drawing : page.drawings()) {
                            if (drawing instanceof PrintDrawing.TextRun t) {
                                fonts.verify(t.font());
                                stream.beginText();
                                stream.setFont(font, t.pointSize().floatValue());
                                stream.newLineAtOffset(
                                        left + pt(t.xMm()),
                                        rect.getHeight()
                                                - top
                                                - pt(t.yMm())
                                                - t.pointSize().floatValue());
                                stream.showText(t.text());
                                stream.endText();
                            } else if (drawing instanceof PrintDrawing.RuleLine l) {
                                stream.setLineWidth(pt(l.strokeMm()));
                                stream.moveTo(
                                        left + pt(l.x1Mm()), rect.getHeight() - top - pt(l.y1Mm()));
                                stream.lineTo(
                                        left + pt(l.x2Mm()), rect.getHeight() - top - pt(l.y2Mm()));
                                stream.stroke();
                            } else throw new PrintFailure("print.imageDrawingUnsupported");
                        }
                        stream.beginText();
                        stream.setFont(font, 7);
                        stream.newLineAtOffset(
                                left + pt(BigDecimal.valueOf(4)),
                                rect.getHeight() - top - pt(page.heightMm()) - 8);
                        stream.showText(
                                layout.profileVersion().endsWith("_COMPACT_V2")
                                        ? "PDF副本"
                                        : input.artifactKind()
                                                + " / "
                                                + input.exportNo()
                                                + " / PDF副本");
                        stream.endText();
                    }
                }
            }
            doc.save(output);
            byte[] bytes = output.toByteArray();
            if (bytes.length > 20 * 1024 * 1024) throw new PrintFailure("print.exportLimit");
            return new PdfArtifact(
                    bytes,
                    PrintHashes.sha256(bytes),
                    pages,
                    widths,
                    input.rendererVersion(),
                    input.fontBundleVersion());
        } catch (IOException e) {
            throw new PrintFailure("print.pdfRenderFailed");
        }
    }
}
