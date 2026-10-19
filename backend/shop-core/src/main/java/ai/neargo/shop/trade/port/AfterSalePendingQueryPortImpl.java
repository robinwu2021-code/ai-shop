package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.AfterSalePendingQueryPort;
import ai.neargo.shop.trade.service.AfterSaleService;
import org.springframework.stereotype.Component;
import java.util.Collection;
import java.util.Set;

@Component
public class AfterSalePendingQueryPortImpl implements AfterSalePendingQueryPort {
    private final AfterSaleService service;
    public AfterSalePendingQueryPortImpl(AfterSaleService service) { this.service = service; }
    @Override public Set<String> pending(String merchant, String store, Collection<String> cases) {
        return service.merchantPendingNos(merchant, store, cases);
    }
}
