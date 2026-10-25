package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record GlyphMetrics(
        BigDecimal advancePt, FrozenJson boundsPt, Set<Integer> missingCodePoints) {
    public GlyphMetrics {
        missingCodePoints =
                Set.copyOf(Objects.requireNonNull(missingCodePoints, "missingCodePoints"));
    }
}
