package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintActor(
        String tenantNo,
        String entityNo,
        String storeNo,
        String actorType,
        String actorNo,
        String idempotencyKey,
        Set<String> dataScopes) {
    public PrintActor {
        dataScopes = Set.copyOf(Objects.requireNonNull(dataScopes, "dataScopes"));
        if (tenantNo == null || tenantNo.isBlank() || tenantNo.length() > 32)
            throw new IllegalArgumentException("tenantNo");
        if (entityNo == null || entityNo.isBlank() || entityNo.length() > 64)
            throw new IllegalArgumentException("entityNo");
        if (storeNo == null || storeNo.isBlank() || storeNo.length() > 64)
            throw new IllegalArgumentException("storeNo");
    }
}
