package ai.neargo.shop.print.layout;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.support.PrintJsonCodec;

import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** V2 keeps business coordinates immutable; only protected header/footer slots change on copy. */
final class CompactPrintLayout {
    private static final BigDecimal MM = new BigDecimal("0.3527777778");
    private final PrintFontMetricsPort metrics;
    private final PrintJsonCodec json;

    CompactPrintLayout(PrintFontMetricsPort metrics, PrintJsonCodec json) {
        this.metrics = metrics;
        this.json = json;
    }

    PrintLayout layout(PrintDocument document, LayoutProfile profile) {
        String notice = json.tree(document.businessData()).path("mandatoryNotice").asText();
        String zone = json.tree(document.businessData()).path("timezone").asText("UTC");
        FontRef font = profile.font();
        BigDecimal width = profile.paperWidthMm();
        int headerLines =
                wrap("补打 · " + notice, font, 8, width.subtract(BigDecimal.valueOf(8))).size();
        if (headerLines > 3) throw new PrintFailure("print.systemMarkLimit");
        BigDecimal top = BigDecimal.valueOf(3 + headerLines * 3.5 + 4.5), y = top;
        var pages = new ArrayList<PrintPage>();
        var body = new ArrayList<PrintDrawing>();
        for (var row : json.tree(document.businessData()).path("rows")) {
            if (row.path("type").asText().equals("rule")) {
                if (y.add(BigDecimal.valueOf(3))
                                .compareTo(profile.maxHeightMm().subtract(BigDecimal.valueOf(44)))
                        > 0) {
                    if (pages.size() >= 199) throw new PrintFailure("print.pageLimit");
                    pages.add(
                            page(
                                    pages.size() + 1,
                                    width,
                                    font,
                                    notice,
                                    zone,
                                    headerLines,
                                    body,
                                    y,
                                    document.systemSlots()));
                    body = new ArrayList<>();
                    y = top;
                }
                body.add(
                        new PrintDrawing.RuleLine(
                                BigDecimal.valueOf(4),
                                y.add(BigDecimal.ONE),
                                width.subtract(BigDecimal.valueOf(4)),
                                y.add(BigDecimal.ONE),
                                new BigDecimal("0.15")));
                y = y.add(BigDecimal.valueOf(3));
                continue;
            }
            int size = row.path("size").asInt(10);
            String left = row.path("text").asText(), right = row.path("right").asText();
            BigDecimal available = width.subtract(BigDecimal.valueOf(8));
            var lines = new ArrayList<>(wrap(left, font, size, available));
            // Values are indivisible when possible; a long identifier may wrap rather than
            // truncate.
            var rightLines =
                    right.isBlank()
                            ? List.<String>of()
                            : wrap(
                                    right,
                                    font,
                                    size,
                                    available,
                                    row.path("atomicRight").asBoolean());
            boolean together =
                    !right.isBlank()
                            && rightLines.size() == 1
                            && lines.size() == 1
                            && measure(left, font, size)
                                            .add(measure(right, font, size))
                                            .add(BigDecimal.valueOf(2))
                                            .compareTo(available)
                                    <= 0;
            var visual = new ArrayList<String[]>(lines.size() + rightLines.size());
            if (together) visual.add(new String[] {left, right});
            else {
                for (String line : lines) visual.add(new String[] {line, ""});
                for (String line : rightLines) visual.add(new String[] {"", line});
            }
            for (var pair : visual) {
                BigDecimal height =
                        BigDecimal.valueOf(size).multiply(MM).add(BigDecimal.valueOf(1));
                if (y.add(height).compareTo(profile.maxHeightMm().subtract(BigDecimal.valueOf(44)))
                        > 0) {
                    if (pages.size() >= 199) throw new PrintFailure("print.pageLimit");
                    pages.add(
                            page(
                                    pages.size() + 1,
                                    width,
                                    font,
                                    notice,
                                    zone,
                                    headerLines,
                                    body,
                                    y,
                                    document.systemSlots()));
                    body = new ArrayList<>();
                    y = top;
                }
                String align = row.path("align").asText("left");
                if (!pair[0].isEmpty())
                    body.add(
                            text(
                                    pair[0],
                                    font,
                                    size,
                                    align.equals("center")
                                            ? BigDecimal.valueOf(4)
                                                    .add(
                                                            available
                                                                    .subtract(
                                                                            measure(
                                                                                    pair[0], font,
                                                                                    size))
                                                                    .divide(BigDecimal.valueOf(2)))
                                            : BigDecimal.valueOf(4),
                                    y));
                if (!pair[1].isEmpty())
                    body.add(
                            text(
                                    pair[1],
                                    font,
                                    size,
                                    width.subtract(BigDecimal.valueOf(4))
                                            .subtract(measure(pair[1], font, size)),
                                    y));
                y = y.add(height);
            }
        }
        pages.add(
                page(
                        pages.size() + 1,
                        width,
                        font,
                        notice,
                        zone,
                        headerLines,
                        body,
                        y,
                        document.systemSlots()));
        return new PrintLayout(
                profile.code() + "_COMPACT_V2",
                font.bundleVersion(),
                font.sha256(),
                width,
                pages,
                json.freeze(pages).sha256());
    }

    private PrintPage page(
            int index,
            BigDecimal width,
            FontRef font,
            String notice,
            String zone,
            int headerLines,
            List<PrintDrawing> body,
            BigDecimal y,
            SystemPrintSlots marks) {
        var drawings = new ArrayList<PrintDrawing>();
        for (int i = 0; i < headerLines + 1; i++)
            drawings.add(text("", font, 8, BigDecimal.valueOf(4), BigDecimal.valueOf(3 + i * 3.5)));
        drawings.addAll(body);
        BigDecimal anchor = y.add(BigDecimal.valueOf(2));
        var slots =
                List.of(
                        new LayoutSlot(
                                "COMPACT_HEADER",
                                json.freeze(Map.of("lineCount", headerLines, "notice", notice)),
                                json.freeze(Map.of()),
                                Set.of(notice)),
                        new LayoutSlot(
                                "COMPACT_FOOTER",
                                json.freeze(
                                        Map.of(
                                                "startIndex",
                                                drawings.size(),
                                                "anchorY",
                                                anchor,
                                                "zone",
                                                zone)),
                                json.freeze(Map.of()),
                                Set.of()));
        return apply(new PrintPage(index, width, anchor, drawings, slots), marks);
    }

    PrintLayout instantiateCopy(PrintLayout source, SystemPrintSlots marks) {
        var pages = source.pages().stream().map(p -> apply(p, marks)).toList();
        return new PrintLayout(
                source.profileVersion(),
                source.fontBundleVersion(),
                source.fontHash(),
                source.pageWidthMm(),
                pages,
                json.freeze(pages).sha256());
    }

    private PrintPage apply(PrintPage page, SystemPrintSlots marks) {
        var header = json.tree(page.systemSlots().get(0).geometry());
        var footer = json.tree(page.systemSlots().get(1).geometry());
        int count = header.path("lineCount").asInt(), start = footer.path("startIndex").asInt();
        var font = ((PrintDrawing.TextRun) page.drawings().getFirst()).font();
        var available = page.widthMm().subtract(BigDecimal.valueOf(8));
        String kind =
                switch (marks.jobKind()) {
                    case INITIAL -> "";
                    case REPRINT -> "补打 · ";
                    case EXTRA -> "加印 · ";
                    case VOID -> "冲销 · ";
                    case TEST -> "测试 · ";
                };
        var titles = wrap(kind + header.path("notice").asText(), font, 8, available);
        if (titles.size() > count) throw new PrintFailure("print.systemMarkLimit");
        var drawings = new ArrayList<PrintDrawing>(page.drawings().subList(0, start));
        for (int i = 0; i < count; i++)
            drawings.set(
                    i,
                    text(
                            i < titles.size() ? titles.get(i) : "",
                            font,
                            8,
                            BigDecimal.valueOf(4),
                            BigDecimal.valueOf(3 + i * 3.5)));
        String warning =
                marks.warnings().stream()
                                .anyMatch(
                                        w ->
                                                w.contains("原票")
                                                        && (w.contains("核实")
                                                                || w.contains("未明")
                                                                || w.contains("未知")))
                        ? "原票结果待核实"
                        : marks.warnings().stream().anyMatch(w -> w.contains("模拟"))
                                ? "模拟打印，非真实出票"
                                : marks.warnings().isEmpty() ? "" : "票据提示见下方";
        drawings.set(
                count,
                text(warning, font, 8, BigDecimal.valueOf(4), BigDecimal.valueOf(3 + count * 3.5)));
        var lines = new ArrayList<String>();
        lines.add(
                "小票号："
                        + marks.ticketNo()
                        + (marks.copyIndex() > 1 ? " · 第" + marks.copyIndex() + "联" : ""));
        if (marks.originalTicketNo() != null) lines.add("原票号：" + marks.originalTicketNo());
        if (marks.jobKind() != PrintJobKind.INITIAL)
            lines.add(
                    (marks.jobKind() == PrintJobKind.REPRINT ? "补打时间：" : "加印时间：")
                            + DateTimeFormatter.ofPattern("MM-dd HH:mm")
                                    .withZone(ZoneId.of(footer.path("zone").asText()))
                                    .format(marks.generatedAt()));
        for (String w : new TreeSet<>(marks.warnings())) if (!w.equals(warning)) lines.add(w);
        if (marks.reason() != null && !marks.reason().isBlank()) lines.add("原因：" + marks.reason());
        var wrapped = new ArrayList<String>();
        for (String line : lines) wrapped.addAll(wrap(line, font, 8, available));
        if (wrapped.size() > 9) throw new PrintFailure("print.systemMarkLimit");
        BigDecimal y = footer.path("anchorY").decimalValue();
        for (String line : wrapped) {
            drawings.add(text(line, font, 8, BigDecimal.valueOf(4), y));
            y = y.add(BigDecimal.valueOf(3.5));
        }
        if (page.pageIndex() > 1) {
            drawings.add(text("续页 " + page.pageIndex(), font, 8, BigDecimal.valueOf(4), y));
            y = y.add(BigDecimal.valueOf(3.5));
        }
        BigDecimal height = y.add(BigDecimal.valueOf(3));
        if (height.compareTo(BigDecimal.valueOf(270)) > 0)
            throw new PrintFailure("print.systemMarkLimit");
        return new PrintPage(
                page.pageIndex(), page.widthMm(), height, drawings, page.systemSlots());
    }

    private PrintDrawing.TextRun text(
            String value, FontRef font, int size, BigDecimal x, BigDecimal y) {
        var advances = new ArrayList<BigDecimal>();
        value.codePoints()
                .forEach(
                        cp -> advances.add(measure(new String(Character.toChars(cp)), font, size)));
        return new PrintDrawing.TextRun(value, font, BigDecimal.valueOf(size), x, y, advances);
    }

    private BigDecimal measure(String s, FontRef f, int size) {
        var m = metrics.measure(s, f, BigDecimal.valueOf(size));
        if (!m.missingCodePoints().isEmpty()) throw new PrintFailure("print.missingGlyph");
        return m.advancePt().multiply(MM);
    }

    private List<String> wrap(String value, FontRef f, int size, BigDecimal limit) {
        return wrap(value, f, size, limit, false);
    }

    private List<String> wrap(
            String value, FontRef f, int size, BigDecimal limit, boolean atomicNumber) {
        var out = new ArrayList<String>();
        var line = new StringBuilder();
        BigDecimal width = BigDecimal.ZERO;
        var matcher =
                java.util.regex.Pattern.compile(
                                "[+-]?[0-9]+(?:[.][0-9]+)?|[A-Za-z0-9][A-Za-z0-9_.:/-]*|[\\s\\S]")
                        .matcher(value);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.equals("\n")) {
                out.add(line.toString());
                line.setLength(0);
                width = BigDecimal.ZERO;
                continue;
            }
            var tokens = new ArrayList<String>();
            if (measure(token, f, size).compareTo(limit) > 0) {
                if (atomicNumber && token.matches("[+-]?[0-9]+(?:[.][0-9]+)?"))
                    throw new PrintFailure("print.moneyTooWide");
                token.codePoints().forEach(cp -> tokens.add(new String(Character.toChars(cp))));
            } else tokens.add(token);
            for (String unit : tokens) {
                BigDecimal w = measure(unit, f, size);
                if (w.compareTo(limit) > 0) throw new PrintFailure("print.glyphTooWide");
                if (width.add(w).compareTo(limit) > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                    width = BigDecimal.ZERO;
                }
                line.append(unit);
                width = width.add(w);
            }
        }
        if (!line.isEmpty() || out.isEmpty()) out.add(line.toString());
        return out;
    }
}
