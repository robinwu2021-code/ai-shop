package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintOpsActor(
        String tenantNo,
        String staffNo,
        Set<String> entityNos,
        Set<String> storeNos,
        Set<String> dataScopes,
        String idempotencyKey) {
    public PrintOpsActor {
        entityNos = Set.copyOf(Objects.requireNonNull(entityNos, "entityNos"));
        storeNos = Set.copyOf(Objects.requireNonNull(storeNos, "storeNos"));
        dataScopes = Set.copyOf(Objects.requireNonNull(dataScopes, "dataScopes"));
    }
}
