package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.service.ActivityCapabilityEvidence;
import ai.neargo.shop.spi.promotion.ActivityCapability;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Only current merchant-owner facts can attest owner activity and store permission. */
@Component
public class MerchantActivityCapabilityEvidence implements ActivityCapabilityEvidence {
    private final MerchantScopeFactsPort merchants;
    public MerchantActivityCapabilityEvidence(MerchantScopeFactsPort merchants) { this.merchants = merchants; }
    @Override public Set<ActivityCapability> verifiedFor(ActivityActor actor) {
        Set<ActivityCapability> ready = new HashSet<>();
        if (merchants.entityActive(actor.tenantNo(), actor.entityNo())) {
            ready.add(ActivityCapability.MERCHANT_ACTIVE);
            if (merchants.promotionIndustryAdmitted(actor.tenantNo(), actor.entityNo())) {
                ready.add(ActivityCapability.INDUSTRY_ADMISSION);
            }
            if (actor.allowedStores() == null || !actor.allowedStores().isEmpty()
                    && merchants.storesOwned(actor.tenantNo(), actor.entityNo(), actor.allowedStores())) {
                ready.add(ActivityCapability.STORE_PERMISSION);
            }
        }
        return Set.copyOf(ready);
    }
}
