package ai.neargo.shop.promotion.dto;

import ai.neargo.shop.spi.entitlement.ContractChecks;

/** Public claim has no recipient, quantity, permit, source, decision or money supplied by the consumer. */
public record BenefitClaimCommand(String templateVersionNo, String activityNo, String activityVersionNo,
                                  String entityNo, String storeNo) {
    public BenefitClaimCommand {
        ContractChecks.id(templateVersionNo); ContractChecks.id(entityNo); ContractChecks.id(storeNo);
        ContractChecks.require((activityNo == null) == (activityVersionNo == null));
        ContractChecks.optionalId(activityNo); ContractChecks.optionalId(activityVersionNo);
    }
}
