package ai.neargo.commerce.core.catalog;

import java.time.Instant;

/** One trusted actor/time for every changed row and the same-transaction audit event. */
public record CatalogWriteContext(String principalNo, Instant occurredAt) {
    public CatalogWriteContext {
        CatalogSupport.text(principalNo, 64, "actor principal");
        java.util.Objects.requireNonNull(occurredAt);
    }
}
