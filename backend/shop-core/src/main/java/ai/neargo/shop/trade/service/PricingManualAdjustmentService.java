package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.PricingCheckoutPort;
import ai.neargo.shop.spi.trade.PricingManualAdjustmentPort.AdjustmentVO;
import ai.neargo.shop.spi.trade.PricingManualAdjustmentPort.ManualKind;
import ai.neargo.shop.spi.trade.PricingManualAdjustmentPort.Request;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.trade.dto.PricingManualPolicyCommands.PolicyVO;
import ai.neargo.shop.trade.dto.PricingManualPolicyCommands.Save;
import ai.neargo.shop.trade.dto.PricingPreparation.Prepared;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Context;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.QuoteVO;

/** Real seller authorization owner and shared-engine quote adapter, with no payment or cash plan execution. */
public interface PricingManualAdjustmentService {
    AdjustmentVO propose(Scope scope, String actorNo, Request request, String key);
    AdjustmentVO get(Scope scope, String actorNo, String adjustmentNo);
    AdjustmentVO approve(Scope scope, String actorNo, String adjustmentNo, long version, String key);
    PricingCheckoutPort.QuoteVO quote(Scope scope, String actorNo, String adjustmentNo, String key);
    PolicyVO getPolicy(Scope scope,String actor,String currency,String reason,ManualKind kind);
    PolicyVO savePolicy(Scope scope,String actor,String currency,String reason,ManualKind kind,Save request,String key);
    boolean handles(QuoteVO stored);
    void verify(Prepared current,Context context,QuoteVO stored);
}
