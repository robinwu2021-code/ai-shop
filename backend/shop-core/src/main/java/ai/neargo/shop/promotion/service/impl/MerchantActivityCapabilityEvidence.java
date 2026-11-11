package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.service.ActivityCapabilityEvidence;
import ai.neargo.shop.spi.promotion.ActivityCapability;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.marketing.BrandPromotionScopePort;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Only current merchant-owner facts can attest owner activity and store permission. */
@Component
public class MerchantActivityCapabilityEvidence implements ActivityCapabilityEvidence {
    @Autowired(required = false) private BrandPromotionScopePort brands;
    private final MerchantScopeFactsPort merchants;
    public MerchantActivityCapabilityEvidence(MerchantScopeFactsPort merchants) { this.merchants = merchants; }
    @Override public Set<ActivityCapability> verifiedFor(ActivityActor actor) {
        Set<ActivityCapability> ready = new HashSet<>();
        if (actor.brandNo() != null) {
            var brand = brands == null ? null : brands.find(actor.tenantNo(), actor.managementNo(),
                    actor.brandNo(), actor.entityNo());
            if (brand == null || actor.allowedStores() == null || actor.allowedStores().isEmpty()) return Set.of();
            var stores = brand.stores().stream().filter(s -> actor.allowedStores().contains(s.storeNo())).toList();
            if (stores.size() != actor.allowedStores().size()) return Set.of();
            if (merchants.entityActive(actor.tenantNo(), actor.entityNo()) && stores.stream()
                    .allMatch(s -> merchants.entityActive(actor.tenantNo(), s.entityNo()))) {
                ready.add(ActivityCapability.MERCHANT_ACTIVE);
                if (stores.stream().allMatch(s -> merchants.promotionIndustryAdmitted(actor.tenantNo(), s.entityNo()))) {
                    ready.add(ActivityCapability.INDUSTRY_ADMISSION);
                }
                if (stores.stream().allMatch(s -> merchants.storesOwned(actor.tenantNo(), s.entityNo(),
                        Set.of(s.storeNo())))) ready.add(ActivityCapability.STORE_PERMISSION);
            }
            return Set.copyOf(ready);
        }
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
