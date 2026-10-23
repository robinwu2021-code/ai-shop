package ai.neargo.shop.print.layout;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.support.PrintJsonCodec;

import org.springframework.stereotype.Component;

import java.math.*;
import java.util.*;

@Component
public final class DefaultPrintLayoutEngine implements PrintLayoutEngine {
    private final PrintFontMetricsPort metrics;
    private final PrintJsonCodec json;

    public DefaultPrintLayoutEngine(PrintFontMetricsPort m, PrintJsonCodec j) {
        metrics = m;
        json = j;
    }

    private static final BigDecimal MM_PER_PT = new BigDecimal("0.3527777778");

    public PrintLayout layout(PrintDocument d, LayoutProfile p) {
        var pages = new ArrayList<PrintPage>();
        var drawings = new ArrayList<PrintDrawing>();
        var rows = json.tree(d.businessData()).path("rows");
        var headers = headers(d);
        BigDecimal y = BigDecimal.valueOf(5);
        drawings.addAll(systemDrawings(d, p));
        y = BigDecimal.valueOf(40);
        for (var row : rows) {
            var size = BigDecimal.valueOf(row.path("size").asInt(10));
            for (String line : wrap(row.path("text").asText(), p, size)) {
                BigDecimal height = size.multiply(MM_PER_PT).add(BigDecimal.valueOf(1.5));
                if (y.add(height).compareTo(p.maxHeightMm().subtract(BigDecimal.valueOf(7))) > 0) {
                    if (pages.size() >= 199) throw new PrintFailure("print.pageLimit");
                    pages.add(page(p, pages.size() + 1, drawings, y));
                    drawings = new ArrayList<>();
                    y = BigDecimal.valueOf(5);
                    drawings.addAll(systemDrawings(d, p));
                    y = BigDecimal.valueOf(40);
                }
                drawings.add(text(line, p, size, y));
                y = y.add(height);
            }
        }
        pages.add(page(p, pages.size() + 1, drawings, y));
        if (pages.size() > 200) throw new PrintFailure("print.pageLimit");
        return new PrintLayout(
                p.code() + "_V" + p.version(),
                p.font().bundleVersion(),
                p.font().sha256(),
                p.paperWidthMm(),
                pages,
                json.freeze(pages).sha256());
    }

    private List<String> headers(PrintDocument d) {
        var s = d.systemSlots();
        var out = new ArrayList<String>();
        out.add("小票号: " + s.ticketNo() + "  " + s.jobKind() + " #" + s.copyIndex());
        if (s.originalTicketNo() != null) out.add("原票: " + s.originalTicketNo());
        if (s.reason() != null) out.add("原因: " + s.reason());
        out.addAll(new TreeSet<>(s.warnings()));
        return out;
    }

    private List<PrintDrawing> systemDrawings(PrintDocument d, LayoutProfile p) {
        var marks = new ArrayList<String>();
        for (var h : headers(d)) marks.addAll(wrap(h, p, BigDecimal.valueOf(8)));
        if (marks.size() > 7) throw new PrintFailure("print.systemMarkLimit");
        var out = new ArrayList<PrintDrawing>();
        out.add(
                text(
                        json.tree(d.businessData()).path("mandatoryNotice").asText(),
                        p,
                        BigDecimal.valueOf(8),
                        BigDecimal.valueOf(5)));
        for (int i = 0; i < 7; i++)
            out.add(
                    text(
                            i < marks.size() ? marks.get(i) : "",
                            p,
                            BigDecimal.valueOf(8),
                            BigDecimal.valueOf(9 + 4 * i)));
        return out;
    }

    private PrintPage page(LayoutProfile p, int index, List<PrintDrawing> items, BigDecimal y) {
        var draws = new ArrayList<>(items);
        draws.add(text("联号: " + index, p, BigDecimal.valueOf(8), y.add(BigDecimal.ONE)));
        return new PrintPage(
                index,
                p.paperWidthMm(),
                y.add(BigDecimal.valueOf(7)),
                draws,
                java.util.stream.IntStream.range(0, 8)
                        .mapToObj(
                                i ->
                                        new LayoutSlot(
                                                i == 0 ? "NOTICE" : "DYNAMIC_MARKS",
                                                json.freeze(java.util.Map.of("drawingIndex", i)),
                                                json.freeze(java.util.Map.of("pointSize", 8)),
                                                i == 0
                                                        ? java.util.Set.of(
                                                                items.getFirst()
                                                                                instanceof
                                                                                PrintDrawing.TextRun
                                                                                        t
                                                                        ? t.text()
                                                                        : "")
                                                        : java.util.Set.<String>of()))
                        .toList());
    }

    private PrintDrawing.TextRun text(String s, LayoutProfile p, BigDecimal size, BigDecimal y) {
        var advances = new ArrayList<BigDecimal>();
        for (int cp : s.codePoints().toArray())
            advances.add(measure(new String(Character.toChars(cp)), p, size));
        return new PrintDrawing.TextRun(s, p.font(), size, p.leftMarginMm(), y, advances);
    }

    private BigDecimal measure(String s, LayoutProfile p, BigDecimal size) {
        var m = metrics.measure(s, p.font(), size);
        if (!m.missingCodePoints().isEmpty()) throw new PrintFailure("print.missingGlyph");
        return m.advancePt().multiply(MM_PER_PT);
    }

    private List<String> wrap(String text, LayoutProfile p, BigDecimal size) {
        var units = new ArrayList<String>();
        var matcher =
                java.util.regex.Pattern.compile(
                                "[+-]?[0-9]+(?:[.][0-9]+)?"
                                        + " [A-Z]{3}|[A-Za-z0-9][A-Za-z0-9_.:/-]*|[\\s\\S]")
                        .matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (measure(token, p, size).compareTo(p.printableWidthMm()) <= 0) units.add(token);
            else if (token.matches("[+-]?[0-9]+(?:[.][0-9]+)? [A-Z]{3}"))
                throw new PrintFailure("print.moneyTooWide");
            else token.codePoints().forEach(cp -> units.add(new String(Character.toChars(cp))));
        }
        var out = new ArrayList<String>();
        var current = new StringBuilder();
        BigDecimal width = BigDecimal.ZERO;
        for (String token : units) {
            if (token.equals("\n")) {
                out.add(current.toString());
                current.setLength(0);
                width = BigDecimal.ZERO;
                continue;
            }
            var advance = measure(token, p, size);
            if (advance.compareTo(p.printableWidthMm()) > 0)
                throw new PrintFailure("print.glyphTooWide");
            if (width.add(advance).compareTo(p.printableWidthMm()) > 0) {
                out.add(current.toString());
                current.setLength(0);
                width = BigDecimal.ZERO;
            }
            current.append(token);
            width = width.add(advance);
        }
        if (!current.isEmpty() || out.isEmpty()) out.add(current.toString());
        return out;
    }
}
