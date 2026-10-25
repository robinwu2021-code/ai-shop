package ai.neargo.shop.printing.center.layout;

import ai.neargo.shop.spi.printing.*;

import ai.neargo.shop.print.api.*;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public final class LayoutProfileRegistry {
    private volatile FontRef font;
    private final PrintFontMetricsPort metrics;

    public LayoutProfileRegistry(PrintFontMetricsPort metrics) {
        this.metrics = metrics;
    }

    private synchronized FontRef font() {
        if (font == null) {
            var candidate = new FontRef("NOTO_SC_V1", fontHash(), "NotoSansSC");
            metrics.measure("打印", candidate, BigDecimal.TEN);
            font = candidate;
        }
        return font;
    }

    private String fontHash() {
        try (var in = getClass().getResourceAsStream("/print/fonts/NotoSansSC-Regular.ttf")) {
            if (in == null) throw new IllegalStateException("Bundled print font missing");
            return PrintHashes.sha256(in.readAllBytes());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public LayoutProfile require(String code, String version) {
        int width = code.startsWith("ROLL58") ? 58 : code.startsWith("ROLL80") ? 80 : 0;
        if (width == 0 || !"1".equals(version)) throw new PrintFailure("print.profileUnavailable");
        return new LayoutProfile(
                code,
                "1",
                BigDecimal.valueOf(width),
                BigDecimal.valueOf(width - 8),
                BigDecimal.valueOf(4),
                BigDecimal.valueOf(4),
                BigDecimal.valueOf(270),
                203,
                font());
    }
}
