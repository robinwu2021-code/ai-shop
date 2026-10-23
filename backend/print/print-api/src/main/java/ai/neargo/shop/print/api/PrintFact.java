package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintFact(
        PrintFactRef ref,
        String orderNo,
        String subOrderNo,
        String triggerType,
        String triggerInstance,
        Instant occurredAt,
        int payloadSchemaVersion,
        FrozenJson immutablePayload,
        Printability printability,
        String blockCode,
        List<String> diagnostics) {
    public PrintFact {
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }
}
