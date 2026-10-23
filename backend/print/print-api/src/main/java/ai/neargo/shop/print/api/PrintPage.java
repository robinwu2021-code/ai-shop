package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintPage(
        int pageIndex,
        BigDecimal widthMm,
        BigDecimal heightMm,
        List<PrintDrawing> drawings,
        List<LayoutSlot> systemSlots) {
    public PrintPage {
        drawings = List.copyOf(Objects.requireNonNull(drawings, "drawings"));
        systemSlots = List.copyOf(Objects.requireNonNull(systemSlots, "systemSlots"));
    }
}
