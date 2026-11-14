package ai.neargo.shop.print.channel;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.channel.port.font.*;
import ai.neargo.shop.print.channel.port.pdf.*;
import ai.neargo.shop.print.channel.port.storage.*;
import ai.neargo.shop.printing.center.layout.*;
import ai.neargo.shop.printing.center.support.*;
import ai.neargo.shop.printing.center.template.*;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;

class ReceiptSoftwareAcceptanceTest {
    static final PrintJsonCodec json = new PrintJsonCodec();
    static final PrintSceneRegistry scenes = new PrintSceneRegistry(json);
    static final PdfFontBundle fonts = new PdfFontBundle();
    static final PdfBoxFontMetrics metrics = new PdfBoxFontMetrics(fonts);
    static final LayoutProfileRegistry profiles = new LayoutProfileRegistry(metrics);
    static final DefaultPrintDocumentCompiler compiler =
            new DefaultPrintDocumentCompiler(json, scenes);
    static final DefaultPrintLayoutEngine layoutEngine =
            new DefaultPrintLayoutEngine(metrics, json);
    static final PdfReceiptRenderer pdf = new PdfReceiptRenderer(fonts);
    @TempDir Path temp;

    static Stream<String> scenarios() {
        return scenes.all().stream().map(PrintSceneRegistry.SceneDefinition::code).sorted();
    }

    PrintLayout layout(String code, int width) {
        var s = scenes.require(code);
        var payload = json.freeze(s.example());
        var ref =
                new PrintFactRef(
                        "T1",
                        "E1",
                        "S1",
                        s.definition().path("sourceTypes").get(0).asText(),
                        "SAMPLE",
                        "1",
                        code,
                        payload.sha256());
        var fact =
                new PrintFact(
                        ref,
                        null,
                        null,
                        "SAMPLE",
                        "SAMPLE",
                        Instant.parse("2026-10-23T02:30:00Z"),
                        1,
                        payload,
                        Printability.READY,
                        null,
                        List.of());
        var t = s.templates().get("STANDARD_" + width);
        var resolved =
                new ResolvedTemplate(
                        null,
                        null,
                        t.path("definitionKey").asText(),
                        1,
                        code,
                        "STANDARD_" + width,
                        json.freeze(t.path("content")),
                        json.freeze(Map.of()));
        var doc =
                compiler.compile(
                        fact,
                        resolved,
                        new SystemPrintSlots(
                                "TEST001",
                                PrintJobKind.INITIAL,
                                1,
                                Instant.parse("2026-10-23T02:30:00Z"),
                                null,
                                null,
                                Set.of("模拟打印，非真实出票"),
                                null));
        return layoutEngine.layout(doc, profiles.require("ROLL" + width, "1"));
    }

    PdfArtifact render(PrintLayout layout, PdfOutputProfile profile) {
        return pdf.render(
                new PdfRenderInput(
                        "EXTEST001",
                        PrintArtifactKind.SIMULATION_EXPORT,
                        profile,
                        List.of(layout),
                        "1",
                        fonts.ref().bundleVersion(),
                        Instant.parse("2026-10-23T02:30:00Z")));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void allScenesRenderBothWidthsWithProtectedMarks(String code) throws Exception {
        for (int width : new int[] {58, 80}) {
            compiler.validateTemplate(
                    code,
                    json.freeze(
                                    scenes.require(code)
                                            .templates()
                                            .get("STANDARD_" + width)
                                            .path("content"))
                            .canonicalJson());
            var layout = layout(code, width);
            for (var page : layout.pages())
                for (var drawing : page.drawings())
                    if (drawing instanceof PrintDrawing.TextRun run)
                        assertTrue(
                                run.advancesMm().stream()
                                                .reduce(
                                                        java.math.BigDecimal.ZERO,
                                                        java.math.BigDecimal::add)
                                                .compareTo(java.math.BigDecimal.valueOf(width - 8))
                                        <= 0,
                                "Text must fit printable width: " + run.text());
            var artifact = render(layout, PdfOutputProfile.ORIGINAL_ROLL);
            assertTrue(artifact.pageCount() > 0);
            try (var doc = Loader.loadPDF(artifact.bytes())) {
                String text = new PDFTextStripper().getText(doc);
                assertTrue(text.contains("PDF副本"));
                assertTrue(text.contains("模拟打印"));
                assertEquals(width * 72.0 / 25.4, doc.getPage(0).getMediaBox().getWidth(), 0.01);
            }
            String output = System.getProperty("print.acceptance.output");
            if (output != null) {
                Path path = Path.of(output).resolve(code + "-" + width + ".pdf");
                Files.createDirectories(path.getParent());
                Files.write(path, artifact.bytes());
            }
        }
    }

    @Test
    void moneyTokensStayOnOneLineOnNarrowReceipts() {
        var l = layout("ORDER_PAID", 58);
        assertTrue(
                l.pages().stream()
                        .flatMap(p -> p.drawings().stream())
                        .filter(d -> d instanceof PrintDrawing.TextRun)
                        .map(d -> ((PrintDrawing.TextRun) d).text())
                        .anyMatch(t -> t.equals("64.00")),
                "A money value must not split digits across lines");
    }

    @Test
    void renderRetryIsByteDeterministicAndA4DoesNotScaleBusinessGeometry() {
        var l = layout("ORDER_PAID", 58);
        assertArrayEquals(
                render(l, PdfOutputProfile.ORIGINAL_ROLL).bytes(),
                render(l, PdfOutputProfile.ORIGINAL_ROLL).bytes());
        assertEquals(l.pages().size(), render(l, PdfOutputProfile.A4_SHEET).pageCount());
    }

    @Test
    void privateArtifactsRejectReplacementTraversalAndForgedDownload() throws Exception {
        byte[] bytes = render(layout("ORDER_PAID", 80), PdfOutputProfile.ORIGINAL_ROLL).bytes();
        var store =
                new PrivateLocalArtifactStore(
                        temp.toString(),
                        Base64.getEncoder().encodeToString(new byte[32]),
                        "/private/print-artifacts");
        var a =
                new PrivateArtifact(
                        "T1/E1/S1/EX1.pdf", bytes, PrintHashes.sha256(bytes), "application/pdf");
        store.put(a);
        store.put(a);
        assertTrue(store.verify(a.objectKey()).exists());
        assertThrows(PrintFailure.class, () -> store.exists("../EX1.pdf"));
        assertThrows(
                PrintFailure.class,
                () ->
                        store.read(
                                a.objectKey(),
                                Instant.now().plusSeconds(30).getEpochSecond(),
                                "forged"));
        assertThrows(
                PrintFailure.class,
                () ->
                        store.put(
                                new PrivateArtifact(
                                        a.objectKey(),
                                        "%PDF-different".getBytes(),
                                        PrintHashes.sha256("%PDF-different"),
                                        "application/pdf")));
        var grant = store.signedDownload(a.objectKey(), Duration.ofSeconds(60));
        assertTrue(grant.url().toString().contains("signature="));
    }

    @Test
    void canonicalJsonRejectsDuplicateKeysAndChangesHashWithBusinessContent() {
        assertThrows(Exception.class, () -> json.freeze("{\"amount\":1,\"amount\":2}"));
        assertEquals(json.freeze("{\"b\":2,\"a\":1}"), json.freeze("{\"a\":1,\"b\":2}"));
        assertNotEquals(
                json.freeze(Map.of("amount", 1)).sha256(),
                json.freeze(Map.of("amount", 2)).sha256());
    }

    @Test
    void requiredBindingsCannotBeSatisfiedByLiteralOrNonRenderingBlocks() {
        for (String replacement : List.of("literal", "systemMarks", "separator")) {
            var content =
                    json.tree(
                                    scenes.require("ORDER_PAID")
                                            .templates()
                                            .get("STANDARD_58")
                                            .path("content"))
                            .deepCopy();
            for (var block : content.path("blocks"))
                if (block.path("binding").asText().equals("fields.paidAmount")) {
                    var object = (tools.jackson.databind.node.ObjectNode) block;
                    if (replacement.equals("literal")) object.put("literal", "0.00 CNY");
                    else object.put("type", replacement);
                }
            String text = content.toString();
            assertThrows(
                    PrintFailure.class,
                    () -> compiler.validateTemplate("ORDER_PAID", text),
                    replacement);
        }
    }

    @Test
    void longTicketsPaginateWithoutLosingRowsOrExceedingRollProfile() {
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 500; i++)
            rows.add(Map.of("text", String.format("ITEM_%04d 长单测试", i), "size", 12));
        var doc =
                new PrintDocument(
                        "LONG",
                        "ORDER_PAID",
                        1,
                        json.freeze(Map.of("rows", rows, "mandatoryNotice", "长单模拟")),
                        new SystemPrintSlots(
                                "LONG1",
                                PrintJobKind.INITIAL,
                                1,
                                Instant.parse("2026-10-23T02:30:00Z"),
                                null,
                                null,
                                Set.of("模拟"),
                                null),
                        Set.of());
        var layout = layoutEngine.layout(doc, profiles.require("ROLL58", "1"));
        assertTrue(layout.pages().size() > 1);
        assertTrue(
                layout.pages().stream()
                        .allMatch(
                                p ->
                                        p.heightMm().compareTo(java.math.BigDecimal.valueOf(270))
                                                <= 0),
                "Each page including its footer must fit the profile");
        String text =
                layout.pages().stream()
                        .flatMap(p -> p.drawings().stream())
                        .filter(d -> d instanceof PrintDrawing.TextRun)
                        .map(d -> ((PrintDrawing.TextRun) d).text())
                        .collect(java.util.stream.Collectors.joining("\n"));
        for (int i = 0; i < 500; i++) assertTrue(text.contains(String.format("ITEM_%04d", i)));
        assertEquals(
                layout.pages().size(), render(layout, PdfOutputProfile.ORIGINAL_ROLL).pageCount());
    }
}
