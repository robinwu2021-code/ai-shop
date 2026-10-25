package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintControlFact(
        PrintScope scope,
        String orderNo,
        String subOrderNo,
        String sourceControlFactNo,
        String controlType,
        String sourceRevision,
        Instant occurredAt,
        List<PrintFactRef> affectedFacts) {
    public PrintControlFact {
        affectedFacts = List.copyOf(Objects.requireNonNull(affectedFacts, "affectedFacts"));
    }
}
