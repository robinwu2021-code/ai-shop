package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record LayoutProfile(
        String code,
        String version,
        BigDecimal paperWidthMm,
        BigDecimal printableWidthMm,
        BigDecimal leftMarginMm,
        BigDecimal rightMarginMm,
        BigDecimal maxHeightMm,
        int dpi,
        FontRef font) {}
