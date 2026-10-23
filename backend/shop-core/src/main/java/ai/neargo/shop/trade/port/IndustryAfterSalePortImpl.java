package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.IndustryAfterSalePort;
import ai.neargo.shop.trade.service.IndustryAfterSaleService;
import org.springframework.stereotype.Component;

@Component
public class IndustryAfterSalePortImpl implements IndustryAfterSalePort {
    private final IndustryAfterSaleService service;
    public IndustryAfterSalePortImpl(IndustryAfterSaleService service) { this.service = service; }
    @Override public Result apply(Apply request) { return service.apply(request); }
    @Override public Lookup queryApply(QueryApply query) { return service.queryApply(query); }
    @Override public Result get(Reference reference) { return service.get(reference); }
    @Override public Result cancel(Reference reference) { return service.cancel(reference); }
}
