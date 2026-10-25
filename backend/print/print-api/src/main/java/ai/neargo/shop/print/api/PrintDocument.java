package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintDocument(
        String documentKey,
        String scene,
        int payloadSchemaVersion,
        FrozenJson businessData,
        SystemPrintSlots systemSlots,
        Set<String> requiredDataScopes) {
    public PrintDocument {
        requiredDataScopes =
                Set.copyOf(Objects.requireNonNull(requiredDataScopes, "requiredDataScopes"));
    }
}
