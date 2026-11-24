package ai.neargo.shop.print.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.neargo.shop.print.api.PdfOutputProfile;
import ai.neargo.shop.print.api.PrintDocument;
import ai.neargo.shop.print.api.PrintFact;
import ai.neargo.shop.print.api.PrintFactRef;
import ai.neargo.shop.print.api.PrintJobKind;
import ai.neargo.shop.print.api.PrintLayout;
import ai.neargo.shop.print.api.Printability;
import ai.neargo.shop.print.api.ResolvedTemplate;
import ai.neargo.shop.print.api.SystemPrintSlots;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ai.neargo.shop.print.api.PrintDrawing;
import ai.neargo.shop.print.api.PrintFailure;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 发票证明联（INVOICE_PROOF）：一维码与左右 QR 画成位图，PDF 里能落成图片；模板少了条码块不放行。 */
class InvoiceProofLayoutTest {

    private final ReceiptSoftwareAcceptanceTest base = new ReceiptSoftwareAcceptanceTest();

    @Test
    void businessProofPrintsBuyerTaxIdAndTitleWhileConsumerProofOmitsBoth() throws Exception {
        var example = (ObjectNode) ReceiptSoftwareAcceptanceTest.scenes.require("INVOICE_PROOF").example().deepCopy();
        var fields = (ObjectNode) example.path("fields");
        fields.put("buyerTaxId", "50765634");
        fields.put("buyerName", "創勢科技股份有限公司");
        var business = base.render(base.layout("INVOICE_PROOF", 58, example), PdfOutputProfile.ORIGINAL_ROLL);
        try (var doc = Loader.loadPDF(business.bytes())) {
            var text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("50765634"));
            assertTrue(text.replaceAll("\\s+", "").contains("買方抬頭:創勢科技股份有限公司"), () -> text);
        }
        fields.remove(List.of("buyerTaxId", "buyerName"));
        var consumer = base.render(base.layout("INVOICE_PROOF", 58, example), PdfOutputProfile.ORIGINAL_ROLL);
        try (var doc = Loader.loadPDF(consumer.bytes())) {
            var text = new PDFTextStripper().getText(doc);
            assertFalse(text.contains("50765634"));
            assertFalse(text.contains("買方抬頭"));
        }
    }

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

    @Test
    void reprintOfTheProofCarriesAVisibleBuYinMarkButTheOriginalDoesNot() {
        var original = base.layout("INVOICE_PROOF", 58);
        assertTrue(texts(original).stream().noneMatch("補印"::equals));
        var s = ReceiptSoftwareAcceptanceTest.scenes.require("INVOICE_PROOF");
        var json = ReceiptSoftwareAcceptanceTest.json;
        var payload = json.freeze(s.example());
        var ref = new PrintFactRef("T1", "E1", "S1", "INVOICE", "SAMPLE", "1", "INVOICE_PROOF", payload.sha256());
        var fact = new PrintFact(ref, null, null, "SAMPLE", "SAMPLE", Instant.parse("2026-11-21T06:00:00Z"), 1,
                payload, Printability.READY, null, List.of());
        var t = s.templates().get("STANDARD_58");
        var doc = ReceiptSoftwareAcceptanceTest.compiler.compile(fact,
                new ResolvedTemplate(null, null, t.path("definitionKey").asText(), 1, "INVOICE_PROOF", "STANDARD_58",
                        json.freeze(t.path("content")), json.freeze(Map.of())),
                new SystemPrintSlots("TEST001", PrintJobKind.INITIAL, 1, Instant.parse("2026-11-21T06:00:00Z"), null,
                        null, Set.of(), null));
        // 补印复用原文档、只换系统槽位 —— 与 PrintReprintServiceImpl 的做法一致
        var reprint = new PrintDocument(doc.documentKey(), doc.scene(), doc.payloadSchemaVersion(), doc.businessData(),
                new SystemPrintSlots("TEST002", PrintJobKind.REPRINT, 2, Instant.parse("2026-11-21T07:00:00Z"),
                        "TEST001", "卡紙", Set.of(), null),
                doc.requiredDataScopes());
        var layout = ReceiptSoftwareAcceptanceTest.layoutEngine.layout(reprint,
                ReceiptSoftwareAcceptanceTest.profiles.require("ROLL58", "1"));
        assertTrue(texts(layout).contains("補印"));
    }

    @Test
    void onlyTheProofRefusesExtraCopies() {
        var scenes = ReceiptSoftwareAcceptanceTest.scenes;
        assertFalse(scenes.extraCopiesAllowed("INVOICE_PROOF"));
        assertTrue(scenes.extraCopiesAllowed("INVOICE_DETAIL"));
        assertTrue(scenes.extraCopiesAllowed("ORDER_PAID"));
    }

    private static List<String> texts(PrintLayout layout) {
        return layout.pages().stream().flatMap(p -> p.drawings().stream())
                .filter(PrintDrawing.TextRun.class::isInstance).map(d -> ((PrintDrawing.TextRun) d).text()).toList();
    }
}
