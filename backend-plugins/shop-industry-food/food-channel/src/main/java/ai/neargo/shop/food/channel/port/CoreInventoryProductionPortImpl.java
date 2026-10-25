package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.spi.inventory.InventoryProductionFacts;
import ai.neargo.shop.spi.inventory.InventoryProductionInternalApi;
import ai.neargo.shop.spi.inventory.InventoryProductionPort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.List;
import java.util.function.Supplier;

/**
 * The real inventory host owns posting and query; remote uncertainty is never converted into
 * success.
 */
public class CoreInventoryProductionPortImpl implements InventoryProductionPort {
    private final Supplier<InventoryProductionInternalApi> api;

    public CoreInventoryProductionPortImpl(Supplier<InventoryProductionInternalApi> api) {
        this.api = api;
    }

    @Override
    public Receipt consume(Command c) {
        return call(c, () -> api.get().consume(c));
    }

    @Override
    public Receipt reverse(Reverse c) {
        return call(c.original(), () -> api.get().reverse(c));
    }

    @Override
    public Receipt query(Command c) {
        return call(c, () -> api.get().query(c));
    }

    private Receipt call(Command c, Supplier<Receipt> invocation) {
        return ServiceCalls.call(
                ServiceName.PLATFORM,
                () -> {
                    var r = invocation.get();
                    if (r == null
                            || !c.eventNo().equals(r.eventNo())
                            || r.requestHash() == null
                            || !InventoryProductionFacts.hash(c).equals(r.requestHash())
                            || !List.of("ABSENT", "UNKNOWN", "CONFIRMED", "REVERSED")
                                    .contains(r.status())
                            || "CONFIRMED".equals(r.status())
                                    && (r.documentNo() == null || r.lines().isEmpty())) {
                        throw new ServiceCallException(
                                CallOutcome.REMOTE_ERROR,
                                ServiceName.PLATFORM,
                                0,
                                "Invalid original production receipt");
                    }
                    return r;
                });
    }
}
