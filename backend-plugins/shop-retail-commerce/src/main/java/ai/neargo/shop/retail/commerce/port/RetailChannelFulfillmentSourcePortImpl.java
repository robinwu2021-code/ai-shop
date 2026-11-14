package ai.neargo.shop.retail.commerce.port;

import ai.neargo.shop.spi.commerce.OriginalRetailChannelFulfillmentSourceAdapter;
import ai.neargo.shop.spi.trade.IndustryChannelFulfillmentSourcePort;

import java.time.Clock;
import java.util.function.Supplier;

/**
 * The native retail owner must have committed the exact action; no FOOD RPC or supplier receipt can
 * substitute.
 */
public final class RetailChannelFulfillmentSourcePortImpl
        extends OriginalRetailChannelFulfillmentSourceAdapter {
    public RetailChannelFulfillmentSourcePortImpl(
            Supplier<IndustryChannelFulfillmentSourcePort> source, Clock clock) {
        super(query -> source.get().read(query), clock);
    }
}
