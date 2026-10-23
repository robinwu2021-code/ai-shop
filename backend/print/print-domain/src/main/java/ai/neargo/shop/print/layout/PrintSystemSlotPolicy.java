package ai.neargo.shop.print.layout;

import static ai.neargo.shop.print.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.persistence.entity.*;
import ai.neargo.shop.print.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.print.support.*;
import ai.neargo.shop.print.template.*;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class PrintSystemSlotPolicy {
    private final PrintFontMetricsPort metrics;
    private final PrintJsonCodec json;

    public PrintSystemSlotPolicy(PrintFontMetricsPort m, PrintJsonCodec j) {
        metrics = m;
        json = j;
    }

    public SystemPrintSlots initial(PrintFact f, String no, int copy) {
        return new SystemPrintSlots(
                no, PrintJobKind.INITIAL, copy, Instant.now(), null, null, Set.of(), null);
    }

    public PrintLayout instantiateCopy(PrintLayout source, SystemPrintSlots marks) {
        var out = new ArrayList<PrintPage>();
        for (var p : source.pages()) {
            require(p.systemSlots().size() == 8, "print.copySlotsUnavailable");
            var first = (PrintDrawing.TextRun) p.drawings().getFirst();
            var rows = new ArrayList<String>();
            rows.add(marks.jobKind() + " " + marks.ticketNo() + " #" + marks.copyIndex());
            if (marks.originalTicketNo() != null) rows.add("原票: " + marks.originalTicketNo());
            rows.addAll(new TreeSet<>(marks.warnings()));
            if (marks.reason() != null) rows.add("原因: " + marks.reason());
            var wrapped = new ArrayList<String>();
            BigDecimal limit = source.pageWidthMm().subtract(BigDecimal.valueOf(8));
            for (var row : rows) {
                StringBuilder line = new StringBuilder();
                BigDecimal width = BigDecimal.ZERO;
                for (int cp : row.codePoints().toArray()) {
                    String glyph = new String(Character.toChars(cp));
                    var measured = metrics.measure(glyph, first.font(), first.pointSize());
                    require(measured.missingCodePoints().isEmpty(), "print.missingGlyph");
                    BigDecimal advance =
                            measured.advancePt().multiply(new BigDecimal("0.3527777778"));
                    if (width.add(advance).compareTo(limit) > 0) {
                        wrapped.add(line.toString());
                        line.setLength(0);
                        width = BigDecimal.ZERO;
                    }
                    line.append(glyph);
                    width = width.add(advance);
                }
                wrapped.add(line.toString());
            }
            require(wrapped.size() <= 7, "print.systemMarkLimit");
            var drawings = new ArrayList<PrintDrawing>(p.drawings());
            for (int i = 1; i < 8; i++) {
                var old = (PrintDrawing.TextRun) drawings.get(i);
                String text = i - 1 < wrapped.size() ? wrapped.get(i - 1) : "";
                var advances = new ArrayList<BigDecimal>();
                for (int cp : text.codePoints().toArray())
                    advances.add(
                            metrics.measure(
                                            new String(Character.toChars(cp)),
                                            old.font(),
                                            old.pointSize())
                                    .advancePt()
                                    .multiply(new BigDecimal("0.3527777778")));
                drawings.set(
                        i,
                        new PrintDrawing.TextRun(
                                text, old.font(), old.pointSize(), old.xMm(), old.yMm(), advances));
            }
            out.add(
                    new PrintPage(
                            p.pageIndex(), p.widthMm(), p.heightMm(), drawings, p.systemSlots()));
        }
        return new PrintLayout(
                source.profileVersion(),
                source.fontBundleVersion(),
                source.fontHash(),
                source.pageWidthMm(),
                out,
                json.freeze(out).sha256());
    }
}
