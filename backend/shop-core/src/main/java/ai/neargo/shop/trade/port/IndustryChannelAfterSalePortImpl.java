package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.IndustryChannelAfterSalePort;
import ai.neargo.shop.trade.service.impl.NativeChannelAfterSaleService;
import org.springframework.stereotype.Component;

/** Industry entry point to native channel after-sale authorization. */
@Component
public final class IndustryChannelAfterSalePortImpl implements IndustryChannelAfterSalePort {
    private final NativeChannelAfterSaleService service;

    public IndustryChannelAfterSalePortImpl(NativeChannelAfterSaleService service) {
        this.service = service;
    }

    @Override public Facts authorize(Authorize command, String actor, String key) {
        return service.authorize(command, actor, key);
    }

    @Override public Facts get(Query query) {
        return service.get(query);
    }

    @Override public Facts releaseUnissued(Query query, String actor) {
        return service.releaseUnissued(query, actor);
    }
}
