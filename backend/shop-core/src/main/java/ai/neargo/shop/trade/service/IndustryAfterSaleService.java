package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.IndustryAfterSalePort;

public interface IndustryAfterSaleService {
    IndustryAfterSalePort.Result apply(IndustryAfterSalePort.Apply request);
    IndustryAfterSalePort.Lookup queryApply(IndustryAfterSalePort.QueryApply query);
    IndustryAfterSalePort.Result get(IndustryAfterSalePort.Reference reference);
    IndustryAfterSalePort.Result cancel(IndustryAfterSalePort.Reference reference);
}
