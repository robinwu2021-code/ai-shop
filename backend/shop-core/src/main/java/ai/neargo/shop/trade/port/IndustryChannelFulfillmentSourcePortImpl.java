package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.IndustryChannelFulfillmentSourcePort;
import ai.neargo.shop.trade.service.impl.ChannelFulfillmentEvidenceStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Exports sealed fulfillment reads without exposing transaction-local evidence writes. */
@Component
@ConditionalOnProperty(name = "shop.trade.channel.enabled", havingValue = "true")
public final class IndustryChannelFulfillmentSourcePortImpl implements IndustryChannelFulfillmentSourcePort {
    private final ChannelFulfillmentEvidenceStore store;

    public IndustryChannelFulfillmentSourcePortImpl(ChannelFulfillmentEvidenceStore store) {
        this.store = store;
    }

    @Override public Facts read(Query query) {
        return store.read(query);
    }
}
