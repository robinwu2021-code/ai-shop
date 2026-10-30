package ai.neargo.shop.food.commerce.port;

import ai.neargo.shop.food.policy.DiningChannelFulfillmentSignalPolicy;
import ai.neargo.shop.food.service.DiningChannelFulfillmentSignalPort;
import ai.neargo.shop.food.spi.DiningChannelFulfillmentSignalInternalApi;
import ai.neargo.shop.spi.food.DiningServiceEvent;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

import java.time.Clock;
import java.util.function.Supplier;

/**
 * Explicit FOOD access preserves exact original event/committed custody scope and never substitutes
 * a local source.
 */
public final class RemoteDiningChannelFulfillmentSignalPort
        implements DiningChannelFulfillmentSignalPort {
    private final Supplier<DiningChannelFulfillmentSignalInternalApi> api;
    private final Clock clock;

    public RemoteDiningChannelFulfillmentSignalPort(
            Supplier<DiningChannelFulfillmentSignalInternalApi> api, Clock clock) {
        this.api = api;
        this.clock = clock;
    }

    @Override
    public Signal resolve(DiningServiceEvent event) {
        return DiningChannelFulfillmentSignalPolicy.validate(
                event, ServiceCalls.call(ServiceName.FOOD, () -> api.get().resolve(event)), clock);
    }
}
