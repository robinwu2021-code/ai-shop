package ai.neargo.shop.spi.entitlement;

/** Core owns immutable brand scope and audience facts; Pay retains the original coupon asset authority. */
public interface BrandCouponSourcePort {
    void validateTemplate(String tenantNo, String issuerEntityNo, EntitlementDefinition definition);

    boolean permitsUse(String tenantNo, String issuerEntityNo, EntitlementDefinition definition,
                       String templateVersionNo, String consumerEntityNo, String storeNo);

    EntitlementIssueAudiencePort.AudienceSnapshot audience(String tenantNo, String issuerEntityNo,
            EntitlementDefinition definition, String templateVersionNo, String audienceFactRef, String actorNo, String commandNo);
}
