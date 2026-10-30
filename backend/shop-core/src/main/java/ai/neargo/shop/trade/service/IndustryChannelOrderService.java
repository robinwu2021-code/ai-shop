package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Acceptance;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Cancellation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Preparation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Result;

/** Core owns receivable readiness, real order persistence and independent stock evidence. */
public interface IndustryChannelOrderService {
    Preparation prepare(Reference reference);

    Preparation get(Reference reference);

    Acceptance confirm(Reference reference);

    Cancellation cancel(Reference reference);

    /**
     * Existing scoped industry queries read the original channel binding without entering ordinary
     * quote/payment flows.
     */
    Result industryResult(Scope scope, String orderNo);
}
