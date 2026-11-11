package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.spi.trade.IndustryChannelAfterSalePort.Authorize;
import ai.neargo.shop.spi.trade.IndustryChannelAfterSalePort.Facts;
import ai.neargo.shop.spi.trade.IndustryChannelAfterSalePort.Query;

/** Native channel after-sale authorization independent of treasury refunds. */
public interface NativeChannelAfterSaleService {
    Facts authorize(Authorize command, String actor, String key);
    Facts get(Query query);
    Facts releaseUnissued(Query query, String actor);
}
