package ai.neargo.shop.print.channel.font;

import ai.neargo.shop.print.api.*;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Component
public final class PdfBoxFontMetrics implements PrintFontMetricsPort, AutoCloseable {
    private final PdfFontBundle bundle;
    private final PDDocument document = new PDDocument();
    private PDType0Font font;
    private final Map<Integer, Float> advances = new HashMap<>();

    public PdfBoxFontMetrics(PdfFontBundle b) {
        bundle = b;
    }

    public synchronized GlyphMetrics measure(String text, FontRef ref, BigDecimal size) {
        bundle.verify(ref);
        if (font == null) font = bundle.load(document);
        var missing = new HashSet<Integer>();
        BigDecimal width = BigDecimal.ZERO;
        for (int cp : text.codePoints().toArray()) {
            try {
                Float advance = advances.get(cp);
                if (advance == null) {
                    advance = font.getStringWidth(new String(Character.toChars(cp)));
                    advances.put(cp, advance);
                }
                width =
                        width.add(
                                new BigDecimal(Float.toString(advance))
                                        .multiply(size)
                                        .movePointLeft(3));
            } catch (Exception ex) {
                missing.add(cp);
            }
        }
        return new GlyphMetrics(width, new FrozenJson("{}", PrintHashes.sha256("{}")), missing);
    }

    @Override
    public void close() throws java.io.IOException {
        document.close();
    }
}
