package ai.neargo.shop.pay.storedvalue.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCreditService;
import ai.neargo.shop.pay.storedvalue.service.StoredValuePostingService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreditRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import org.springframework.stereotype.Service;

/** An already received promise must be credited even while new business is disabled or core is unavailable. */
@Service
public class StoredValueCreditServiceImpl implements StoredValueCreditService {
    private final StoredValuePostingService postings;
    public StoredValueCreditServiceImpl(StoredValuePostingService postings) { this.postings = postings; }
    @Override public RechargeView credit(EntitlementContext ctx, CreditRecharge c) {
        if (!"MAIN".equals(ctx.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        return postings.creditReceived(ctx, c);
    }
}
