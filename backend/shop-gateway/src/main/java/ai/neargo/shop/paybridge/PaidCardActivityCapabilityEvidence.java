package ai.neargo.shop.paybridge;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.config.MemberCodeProperties;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.service.ActivityCapabilityEvidence;
import ai.neargo.shop.spi.entitlement.EntitlementCapabilityPort;
import ai.neargo.shop.spi.promotion.ActivityCapability;
import ai.neargo.shop.trade.entitlement.config.EntitlementAuthorizationProperties;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Product sale is core-owned, whereas grant, ledger, validity and deduction are attested by pay. */
@Component
public class PaidCardActivityCapabilityEvidence implements ActivityCapabilityEvidence {
    private final EntitlementCapabilityPort pay;
    private final MemberCodeProperties members;
    private final EntitlementAuthorizationProperties authorizations;
    private final boolean saleEnabled;

    public PaidCardActivityCapabilityEvidence(EntitlementCapabilityPort pay, MemberCodeProperties members,
            EntitlementAuthorizationProperties authorizations,
            @Value("${shop.trade.paid-card-sale.enabled:false}") boolean saleEnabled) {
        this.pay = pay; this.members = members; this.authorizations = authorizations; this.saleEnabled = saleEnabled;
    }
    @Override public Set<ActivityCapability> verifiedFor(ActivityActor actor) {
        if (!"MAIN".equals(actor.tenantNo())) return Set.of();
        var ready = new HashSet<ActivityCapability>();
        try {
            var status = pay.status(actor.tenantNo());
            if (status == null || !actor.tenantNo().equals(status.tenantNo())) return Set.of();
            if (status.catalogReady() && status.paidCardReady()) ready.add(ActivityCapability.CARD_PRODUCT);
            if (saleEnabled && status.paidCardReady()) ready.add(ActivityCapability.PAID_ORDER);
            if (status.issueReady() && status.paidCardReady()) {
                ready.add(ActivityCapability.IDEMPOTENT_CARD_ISSUE);
                ready.add(ActivityCapability.ENTITLEMENT_LEDGER);
                ready.add(ActivityCapability.VALIDITY_POLICY);
            }
            if (status.useReady() && members.isEnabled() && authorizations.isEnabled()) {
                ready.add(ActivityCapability.ATOMIC_USE_DEDUCT);
            }
        } catch (BizException unavailable) {
            if (unavailable.errorCode() != ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE) throw unavailable;
        }
        return Set.copyOf(ready);
    }
}
