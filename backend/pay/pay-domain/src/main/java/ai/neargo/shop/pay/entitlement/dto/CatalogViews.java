package ai.neargo.shop.pay.entitlement.dto;

import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import java.time.Instant;

/** Catalog DTOs deliberately omit internal primary keys, authorization tokens and audit identities. */
public final class CatalogViews {
    private CatalogViews() {
    }

    /** The draft can differ from the immutable currently published version. */
    public record TemplateView(String templateNo, String status, long revision, String currentVersionNo,
                               EntitlementDefinition draft) {
    }

    /** Historical grants bind this version, never the mutable template head. */
    public record PublishedVersionView(String versionNo, String templateNo, int versionIndex,
                                       EntitlementDefinition definition, String snapshotHash, Instant publishedAt) {
    }
}
