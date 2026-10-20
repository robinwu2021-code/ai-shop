package ai.neargo.shop.spi.entitlement;

/** Core references published rights through this contract; it never reads pay entities or writes their rules. */
public interface EntitlementCatalogPort {
    /** Read immutable historical rights even after new issuance has been stopped. */
    PublishedDefinition getPublishedVersion(String tenantNo, String entityNo, String versionNo);

    /** Validate ownership, currency, issuance state and purpose before creating a new activity/product binding. */
    PublishedDefinition validateReference(ReferenceQuery query);

    /** Constructed from trusted source context, not arbitrary consumer-supplied owner identifiers. */
    record ReferenceQuery(String tenantNo, String entityNo, String versionNo, String currency, String purpose) {
    }

    /** The complete immutable snapshot and its digest travel together across module boundaries. */
    record PublishedDefinition(String templateNo, String versionNo, EntitlementDefinition definition,
                               String snapshotHash) {
    }
}
