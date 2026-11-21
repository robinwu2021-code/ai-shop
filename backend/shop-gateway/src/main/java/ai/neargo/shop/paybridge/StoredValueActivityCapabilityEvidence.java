package ai.neargo.shop.paybridge;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.config.MemberCodeProperties;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.service.ActivityCapabilityEvidence;
import ai.neargo.shop.spi.promotion.ActivityCapability;
import ai.neargo.shop.spi.storedvalue.StoredValueCapabilityPort;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Owner facts supplement tested core/member executors; neither client flags nor preset metadata attest pay. */
@Component
public class StoredValueActivityCapabilityEvidence implements ActivityCapabilityEvidence {
    private final StoredValueCapabilityPort pay;
    private final MemberCodeProperties memberCodes;
    public StoredValueActivityCapabilityEvidence(StoredValueCapabilityPort pay,MemberCodeProperties memberCodes) {
        this.pay=pay; this.memberCodes=memberCodes;
    }
    @Override public Set<ActivityCapability> verifiedFor(ActivityActor actor) {
        if(!"MAIN".equals(actor.tenantNo())) return Set.of();
        var ready=new HashSet<>(Set.of(ActivityCapability.ACTIVITY_VERSION,ActivityCapability.MARKETING_RESERVATION));
        if(memberCodes.isEnabled()) ready.add(ActivityCapability.MEMBER_IDENTITY);
        try {
            var status=pay.status(actor.tenantNo());
            if(status!=null && actor.tenantNo().equals(status.tenantNo())) {
                if(status.creditReady()) ready.add(ActivityCapability.PAY_CREDIT);
                if(status.redemptionReady()) ready.add(ActivityCapability.STORED_VALUE_REDEEM);
            }
        } catch(BizException unavailable) {
            if(unavailable.errorCode()!=ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE) throw unavailable;
        }
        return Set.copyOf(ready);
    }
}
