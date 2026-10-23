package ai.neargo.shop.trade.policy;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.dto.PricingConfigCommands.Context;
import org.springframework.stereotype.Component;

/** A store grant cannot change the effective tax policy of all stores in the merchant. */
@Component
public class PricingTaxAccessPolicy {
    private final PricingAccessPolicy pricing;

    public PricingTaxAccessPolicy(PricingAccessPolicy pricing) { this.pricing = pricing; }

    public Context platform() { return pricing.draft(true); }

    public Context merchant(boolean write) {
        Context context = pricing.draft(false);
        if (write && !BizContext.current().owner()) throw BizException.of(ErrorCode.FORBIDDEN);
        return context;
    }
}
