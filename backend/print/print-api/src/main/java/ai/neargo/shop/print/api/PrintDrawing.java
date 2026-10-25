package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

@com.fasterxml.jackson.annotation.JsonTypeInfo(
        use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,
        property = "kind")
@com.fasterxml.jackson.annotation.JsonSubTypes({
    @com.fasterxml.jackson.annotation.JsonSubTypes.Type(
            value = PrintDrawing.TextRun.class,
            name = "TEXT"),
    @com.fasterxml.jackson.annotation.JsonSubTypes.Type(
            value = PrintDrawing.RuleLine.class,
            name = "LINE"),
    @com.fasterxml.jackson.annotation.JsonSubTypes.Type(
            value = PrintDrawing.ImageRun.class,
            name = "IMAGE")
})
public sealed interface PrintDrawing
        permits PrintDrawing.TextRun, PrintDrawing.RuleLine, PrintDrawing.ImageRun {
    record TextRun(
            String text,
            FontRef font,
            BigDecimal pointSize,
            BigDecimal xMm,
            BigDecimal yMm,
            List<BigDecimal> advancesMm)
            implements PrintDrawing {
        public TextRun {
            advancesMm = List.copyOf(Objects.requireNonNull(advancesMm, "advancesMm"));
        }
    }

    record RuleLine(
            BigDecimal x1Mm, BigDecimal y1Mm, BigDecimal x2Mm, BigDecimal y2Mm, BigDecimal strokeMm)
            implements PrintDrawing {}

    record ImageRun(
            byte[] embeddedBytes,
            String contentHash,
            BigDecimal xMm,
            BigDecimal yMm,
            BigDecimal widthMm,
            BigDecimal heightMm)
            implements PrintDrawing {
        public ImageRun {
            embeddedBytes = Objects.requireNonNull(embeddedBytes, "embeddedBytes").clone();
        }

        @Override
        public byte[] embeddedBytes() {
            return embeddedBytes.clone();
        }
    }
}
