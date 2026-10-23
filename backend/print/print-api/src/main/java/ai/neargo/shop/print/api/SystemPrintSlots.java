package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record SystemPrintSlots(
        String ticketNo,
        PrintJobKind jobKind,
        int copyIndex,
        Instant generatedAt,
        String originalTicketNo,
        String reason,
        Set<String> warnings,
        OriginalTicketState originalTicketState) {
    public SystemPrintSlots {
        warnings = Set.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }
}
