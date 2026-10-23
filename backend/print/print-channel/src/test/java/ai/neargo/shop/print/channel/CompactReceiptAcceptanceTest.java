package ai.neargo.shop.print.channel;

import static ai.neargo.shop.print.channel.ReceiptSoftwareAcceptanceTest.*;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.layout.PrintSystemSlotPolicy;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

class CompactReceiptAcceptanceTest {
    private static final Instant TIME = Instant.parse("2026-10-23T02:30:00Z");
    private static final PrintSystemSlotPolicy slots = new PrintSystemSlotPolicy(metrics, json);

    private PrintDocument document(String code, JsonNode payload, int width) {
        var scene = scenes.require(code);
        var frozen = json.freeze(payload);
        var ref =
                new PrintFactRef(
                        "T1",
                        "E1",
                        "S1",
                        scene.definition().path("sourceTypes").get(0).asText(),
                        "SAMPLE",
                        "1",
                        code,
                        frozen.sha256());
        var fact =
                new PrintFact(
                        ref,
                        null,
                        null,
                        "SAMPLE",
                        "SAMPLE",
                        TIME,
                        1,
                        frozen,
                        Printability.READY,
                        null,
                        List.of());
        var t = scene.templates().get("STANDARD_" + width);
        return compiler.compile(
                fact,
                new ResolvedTemplate(
                        null,
                        null,
                        t.path("definitionKey").asText(),
                        1,
                        code,
                        "STANDARD_" + width,
                        json.freeze(t.path("content")),
                        json.freeze(Map.of())),
                new SystemPrintSlots(
                        "TEST001",
                        PrintJobKind.INITIAL,
                        1,
                        TIME,
                        null,
                        null,
                        Set.of("模拟打印，非真实出票"),
                        null));
    }

    private PrintLayout layout(String code, JsonNode payload, int width) {
        return layoutEngine.layout(
                document(code, payload, width), profiles.require("ROLL" + width, "1"));
    }

    private String text(PrintLayout layout) {
        return layout.pages().stream()
                .flatMap(p -> p.drawings().stream())
                .filter(d -> d instanceof PrintDrawing.TextRun)
                .map(d -> ((PrintDrawing.TextRun) d).text())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private List<PrintDrawing> body(PrintPage p) {
        int start = json.tree(p.systemSlots().getFirst().geometry()).path("lineCount").asInt() + 1;
        int end = json.tree(p.systemSlots().get(1).geometry()).path("startIndex").asInt();
        return p.drawings().subList(start, end);
    }

    private void bounds(PrintLayout l) {
        for (var p : l.pages()) {
            assertTrue(p.heightMm().compareTo(BigDecimal.valueOf(270)) <= 0);
            for (var d : p.drawings())
                if (d instanceof PrintDrawing.TextRun t) {
                    assertTrue(t.xMm().compareTo(BigDecimal.valueOf(4)) >= 0, t.text());
                    assertTrue(
                            t.xMm()
                                            .add(
                                                    t.advancesMm().stream()
                                                            .reduce(
                                                                    BigDecimal.ZERO,
                                                                    BigDecimal::add))
                                            .compareTo(p.widthMm().subtract(BigDecimal.valueOf(4)))
                                    <= 0,
                            t.text());
                }
        }
    }

    @Test
    void paymentIsConciseAndAmountIsAlignedWithoutDiscardingSource() {
        var p = scenes.require("ORDER_PAID").example();
        var doc = document("ORDER_PAID", p, 58);
        var narrow = layout("ORDER_PAID", p, 58);
        var wide = layout("ORDER_PAID", p, 80);
        String text = text(narrow);
        assertEquals(1, text.split("订单支付凭证", -1).length - 1);
        for (String forbidden :
                List.of("业务事实号", "业务版本", "来源子单", "下单时间", "生成时间", "INITIAL", "LINE001", "CNY"))
            assertFalse(text.contains(forbidden), forbidden);
        assertTrue(text.contains("人民币"));
        assertTrue(text.contains("88.00"));
        assertFalse(text.contains("配送费"));
        assertEquals(
                p.path("source").path("factNo"),
                json.tree(doc.businessData()).path("sourceSnapshot").path("source").path("factNo"));
        assertTrue(narrow.pages().getFirst().heightMm().compareTo(BigDecimal.valueOf(190)) < 0);
        assertTrue(
                wide.pages().getFirst().heightMm().compareTo(narrow.pages().getFirst().heightMm())
                        < 0);
        assertTrue(
                body(narrow.pages().getFirst()).stream()
                                .filter(d -> d instanceof PrintDrawing.TextRun)
                                .map(d -> ((PrintDrawing.TextRun) d).yMm())
                                .min(BigDecimal::compareTo)
                                .orElseThrow()
                                .compareTo(BigDecimal.valueOf(20))
                        < 0);
        var amount =
                narrow.pages().getFirst().drawings().stream()
                        .filter(
                                d ->
                                        d instanceof PrintDrawing.TextRun t
                                                && t.text().equals("88.00"))
                        .map(d -> (PrintDrawing.TextRun) d)
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                0,
                amount.xMm()
                        .add(amount.advancesMm().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                        .compareTo(BigDecimal.valueOf(54)));
        bounds(narrow);
        bounds(wide);
    }

    @Test
    void zeroPaidAndRefundRemainVisibleWhileNonzeroFreightIsShown() {
        var p = (ObjectNode) scenes.require("ORDER_PAID").example().deepCopy();
        ((ObjectNode) p.path("fields").path("paidAmount")).put("minor", "0");
        ((ObjectNode) p.path("fields").path("freightAmount")).put("minor", "123");
        String t = text(layout("ORDER_PAID", p, 58));
        assertTrue(t.contains("实付金额"));
        assertTrue(t.contains("0.00"));
        assertTrue(t.contains("配送费"));
        assertTrue(t.contains("1.23"));
        var refund = (ObjectNode) scenes.require("REFUND_RECEIPT").example().deepCopy();
        ((ObjectNode) refund.path("fields").path("refundedAmount")).put("minor", "0");
        assertTrue(text(layout("REFUND_RECEIPT", refund, 58)).contains("0.00"));
    }

    @Test
    void currencyMismatchAndSuppressingProtectedZeroAreRejected() {
        var p = (ObjectNode) scenes.require("ORDER_PAID").example().deepCopy();
        ((ObjectNode) p.path("fields").path("paidAmount")).put("currency", "USD");
        assertThrows(PrintFailure.class, () -> layout("ORDER_PAID", p, 58));
        var content =
                scenes.require("ORDER_PAID")
                        .templates()
                        .get("STANDARD_58")
                        .path("content")
                        .deepCopy();
        for (var b : content.path("blocks"))
            if (b.path("binding").asText().equals("fields.paidAmount"))
                ((ObjectNode) b).put("omitIfZero", true);
        assertThrows(
                PrintFailure.class,
                () -> compiler.validateTemplate("ORDER_PAID", content.toString()));
    }

    @Test
    void copiesPreserveBusinessGeometryAndUseChineseProtectedMarks() throws Exception {
        for (int width : List.of(58, 80)) {
            var original = layout("ORDER_PAID", scenes.require("ORDER_PAID").example(), width);
            var copy =
                    slots.instantiateCopy(
                            original,
                            new SystemPrintSlots(
                                    "COPY001",
                                    PrintJobKind.REPRINT,
                                    1,
                                    TIME.plusSeconds(3600),
                                    "TEST001",
                                    "补交顾客",
                                    Set.of("原票结果未明，存在重复出纸风险", "模拟打印，非真实出票"),
                                    null));
            assertEquals(body(original.pages().getFirst()), body(copy.pages().getFirst()));
            String text = text(copy);
            assertTrue(text.contains("补打"));
            assertTrue(text.contains("原票结果待核实"));
            assertTrue(text.contains("TEST001"));
            assertTrue(text.contains("COPY001"));
            assertTrue(text.contains("补打时间"));
            assertFalse(text.contains("REPRINT"));
            bounds(copy);
            String output = System.getProperty("print.acceptance.output");
            if (output != null) {
                Files.createDirectories(Path.of(output));
                Files.write(
                        Path.of(output).resolve("ORDER_PAID-REPRINT-" + width + ".pdf"),
                        new ReceiptSoftwareAcceptanceTest()
                                .render(copy, PdfOutputProfile.ORIGINAL_ROLL)
                                .bytes());
            }
            var extra =
                    slots.instantiateCopy(
                            copy,
                            new SystemPrintSlots(
                                    "EXTRA001",
                                    PrintJobKind.EXTRA,
                                    2,
                                    TIME,
                                    "TEST001",
                                    null,
                                    Set.of(),
                                    null));
            assertTrue(text(extra).contains("加印"));
            assertFalse(text(extra).contains("COPY001"));
            assertEquals(body(original.pages().getFirst()), body(extra.pages().getFirst()));
        }
    }

    @Test
    void compactPdfKeepsTechnicalExportReferencesOnlyInMetadata() throws Exception {
        var l = layout("ORDER_PAID", scenes.require("ORDER_PAID").example(), 58);
        var bytes =
                new ReceiptSoftwareAcceptanceTest()
                        .render(l, PdfOutputProfile.ORIGINAL_ROLL)
                        .bytes();
        try (var doc = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            assertTrue(text.contains("PDF副本"));
            assertFalse(text.contains("SIMULATION_EXPORT"));
            assertFalse(text.contains("EXTEST001"));
            assertTrue(doc.getDocumentInformation().getTitle().contains("EXTEST001"));
        }
    }

    @Test
    void legacyFeieVerificationCannotAuthorizeCompactGeometry() {
        var adapter =
                new ai.neargo.shop.print.channel.feie.FeiePrintAdapter(
                        (operation, credentials, parameters) -> {
                            throw new AssertionError("No HTTP submission permitted");
                        },
                        null,
                        null,
                        true);
        var l = layout("ORDER_PAID", scenes.require("ORDER_PAID").example(), 58);
        var result =
                adapter.submit(
                        new PrintDispatch(
                                new PrintScope("T1", "E1", "S1"),
                                "PJ1",
                                "PA1",
                                "D1",
                                "P1",
                                1L,
                                1L,
                                "C1",
                                "FEIE",
                                "A1",
                                json.freeze(Map.of()),
                                l));
        assertEquals(DispatchOutcome.NOT_ACCEPTED, result.outcome());
        assertEquals("print.vendorCompactLayoutUnverified", result.errorKey());
        assertFalse(json.tree(result.evidence()).path("partialOutput").asBoolean());
        assertTrue(json.tree(result.evidence()).path("queueTerminated").asBoolean());
    }

    @Test
    void longNumericIdentifiersWrapButOversizedMoneyDoesNotSplit() {
        var p = (ObjectNode) scenes.require("REFUND_RECEIPT").example().deepCopy();
        String identifier = "1234567890".repeat(5);
        ((ObjectNode) p.path("source")).put("no", identifier);
        var l = layout("REFUND_RECEIPT", p, 58);
        bounds(l);
        assertTrue(text(l).replace("\n", "").contains(identifier));
        var paid = (ObjectNode) scenes.require("ORDER_PAID").example().deepCopy();
        ((ObjectNode) paid.path("fields").path("paidAmount")).put("minor", "9".repeat(30));
        assertThrows(PrintFailure.class, () -> layout("ORDER_PAID", paid, 58));
    }

    @Test
    void legacyFrozenCopySlotsKeepTheirGeometry() throws Exception {
        var d =
                new PrintDocument(
                        "OLD",
                        "ORDER_PAID",
                        1,
                        json.freeze(
                                Map.of(
                                        "rows",
                                        List.of(Map.of("text", "旧票业务内容", "size", 10)),
                                        "mandatoryNotice",
                                        "旧版凭证")),
                        new SystemPrintSlots(
                                "OLD001",
                                PrintJobKind.INITIAL,
                                1,
                                TIME,
                                null,
                                null,
                                Set.of(),
                                null),
                        Set.of());
        var old = layoutEngine.layout(d, profiles.require("ROLL58", "1"));
        assertEquals(8, old.pages().getFirst().systemSlots().size());
        try (var pdf =
                org.apache.pdfbox.Loader.loadPDF(
                        new ReceiptSoftwareAcceptanceTest()
                                .render(old, PdfOutputProfile.ORIGINAL_ROLL)
                                .bytes())) {
            assertNull(pdf.getDocumentInformation().getCustomMetadataValue("PrintArtifactKind"));
        }
        var copy =
                slots.instantiateCopy(
                        old,
                        new SystemPrintSlots(
                                "OLD002",
                                PrintJobKind.REPRINT,
                                1,
                                TIME,
                                "OLD001",
                                null,
                                Set.of(),
                                null));
        assertEquals(
                old.pages()
                        .getFirst()
                        .drawings()
                        .subList(8, old.pages().getFirst().drawings().size()),
                copy.pages()
                        .getFirst()
                        .drawings()
                        .subList(8, copy.pages().getFirst().drawings().size()));
    }

    @Test
    void longUnicodeItemsPaginateAndCopiesNeverOverlapFooter() {
        var payload = (ObjectNode) scenes.require("ORDER_PAID").example().deepCopy();
        var items = json.tree("[]").deepCopy();
        var list = (tools.jackson.databind.node.ArrayNode) items;
        for (int i = 0; i < 500; i++) {
            var line = (ObjectNode) payload.path("lines").get(0).deepCopy();
            line.put("title", String.format("条目%04d", i) + "超长商品名称与规格".repeat(8));
            list.add(line);
        }
        payload.set("lines", list);
        var layout = layout("ORDER_PAID", payload, 58);
        assertTrue(layout.pages().size() > 1);
        String text = text(layout);
        for (int i = 0; i < 500; i++) assertTrue(text.contains(String.format("条目%04d", i)));
        bounds(layout);
        var copy =
                slots.instantiateCopy(
                        layout,
                        new SystemPrintSlots(
                                "LONGCOPY",
                                PrintJobKind.REPRINT,
                                1,
                                TIME,
                                "TEST001",
                                "现场核验后补交",
                                Set.of("原票结果待核实", "模拟打印，非真实出票"),
                                null));
        bounds(copy);
        for (int i = 0; i < layout.pages().size(); i++)
            assertEquals(body(layout.pages().get(i)), body(copy.pages().get(i)));
    }
}
