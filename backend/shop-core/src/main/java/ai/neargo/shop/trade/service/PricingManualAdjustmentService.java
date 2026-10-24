package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.PricingManualAdjustmentPort;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.trade.dto.PricingManualPolicyCommands.PolicyVO;
import ai.neargo.shop.trade.dto.PricingManualPolicyCommands.Save;
import ai.neargo.shop.trade.dto.PricingPreparation.Prepared;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Context;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.QuoteVO;

/** Real seller authorization owner and shared-engine quote adapter, with no payment or cash plan execution. */
public interface PricingManualAdjustmentService extends PricingManualAdjustmentPort {
    PolicyVO getPolicy(Scope scope,String actor,String currency,String reason,ManualKind kind);
    PolicyVO savePolicy(Scope scope,String actor,String currency,String reason,ManualKind kind,Save request,String key);
    boolean handles(QuoteVO stored);
    void verify(Prepared current,Context context,QuoteVO stored);
}
