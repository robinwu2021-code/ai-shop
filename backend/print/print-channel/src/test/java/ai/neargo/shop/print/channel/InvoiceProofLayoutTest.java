package ai.neargo.shop.print.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.neargo.shop.print.api.PdfOutputProfile;
import ai.neargo.shop.print.api.PrintDrawing;
import ai.neargo.shop.print.api.PrintFailure;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 发票证明联（INVOICE_PROOF）：一维码与左右 QR 画成位图，PDF 里能落成图片；模板少了条码块不放行。 */
class InvoiceProofLayoutTest {

    private final ReceiptSoftwareAcceptanceTest base = new ReceiptSoftwareAcceptanceTest();

    @Test
    void proofDrawsBarcodeAndTwoQrCodesAsImagesThatSurviveIntoPdf() throws Exception {
        var layout = base.layout("INVOICE_PROOF", 58);
        long images = layout.pages().stream().flatMap(p -> p.drawings().stream())
                .filter(PrintDrawing.ImageRun.class::isInstance).count();
        assertEquals(3, images, "Code39 + left QR + right QR");
        for (var p : layout.pages())
            for (var d : p.drawings())
                if (d instanceof PrintDrawing.ImageRun img)
                    assertTrue(img.xMm().add(img.widthMm()).doubleValue() <= 58, "symbol must fit the roll");
        var artifact = base.render(layout, PdfOutputProfile.ORIGINAL_ROLL);
        try (var doc = Loader.loadPDF(artifact.bytes())) {
            int xobjects = 0;
            for (var page : doc.getPages())
                for (var ignored : page.getResources().getXObjectNames()) xobjects++;
            assertEquals(3, xobjects);
        }
    }

    @Test
    void proofTemplateWithoutQrPairIsRejected() {
        var scene = ReceiptSoftwareAcceptanceTest.scenes.require("INVOICE_PROOF");
        var content = (ObjectNode) scene.templates().get("STANDARD_58").path("content")
                .deepCopy();
        var blocks = (ArrayNode) content.path("blocks");
        blocks.remove(blocks.size() - 1);
        var json = ReceiptSoftwareAcceptanceTest.json;
        var failure = assertThrows(PrintFailure.class, () -> ReceiptSoftwareAcceptanceTest.compiler
                .validateTemplate("INVOICE_PROOF", json.freeze(content).canonicalJson()));
        assertEquals("print.mandatoryPrintFieldMissing", failure.key());
    }
}
