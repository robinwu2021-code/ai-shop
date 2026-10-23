package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintLayout(
        String profileVersion,
        String fontBundleVersion,
        String fontHash,
        BigDecimal pageWidthMm,
        List<PrintPage> pages,
        String contentHash) {
    public PrintLayout {
        pages = List.copyOf(Objects.requireNonNull(pages, "pages"));
    }
}
