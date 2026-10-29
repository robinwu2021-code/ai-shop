package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.StoreBusinessAvailabilityInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

import java.time.Clock;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Explicit remote owner routing never falls back across FOOD/RETAIL or turns an absent owner into
 * closure/zero stock.
 */
public final class RemoteStoreBusinessAvailabilitySource implements ChannelAvailabilitySourcePort {
    private final Owner owner;
    private final Supplier<StoreBusinessAvailabilityInternalApi> api;
    private final Clock clock;

    public RemoteStoreBusinessAvailabilitySource(
            Owner owner, Supplier<StoreBusinessAvailabilityInternalApi> api, Clock clock) {
        if (owner != Owner.FOOD && owner != Owner.RETAIL)
            throw new IllegalArgumentException("Original business owner required");
        this.owner = owner;
        this.api = api;
        this.clock = clock;
    }

    @Override
    public Kind kind() {
        return Kind.STORE;
    }

    @Override
    public Owner owner() {
        return owner;
    }

    @Override
    public Facts capture(Query query) {
        require(
                query != null
                        && query.kind() == Kind.STORE
                        && query.owner() == owner
                        && query.storeMode() != null);
        var result =
                ServiceCalls.call(
                        owner == Owner.FOOD ? ServiceName.FOOD : ServiceName.PLATFORM,
                        () -> api.get().capture(owner == Owner.FOOD ? "food" : "retail", query));
        require(
                result != null
                        && result.data() != null
                        && result.data().state() != null
                        && query.equals(result.data().query())
                        && result.observedAt() != null
                        && !result.observedAt().isAfter(clock.instant())
                        && result.validUntil() != null
                        && result.validUntil().isAfter(clock.instant())
                        && !result.validUntil().isAfter(result.observedAt().plusSeconds(30))
                        && result.data().quantity() == null
                        && result.data().unit() == null);
        if (Set.of(State.OPEN, State.CLOSED, State.SUSPENDED).contains(result.data().state()))
            require(
                    result.originalReceiptRef() != null
                            && !result.originalReceiptRef().isBlank()
                            && result.data().sourceRevision() != null
                            && (result.data().nextChangeAt() == null
                                    || !result.validUntil().isAfter(result.data().nextChangeAt())));
        return result;
    }

    private static void require(boolean valid) {
        if (!valid)
            throw new IllegalStateException("Wrong original store business source evidence");
    }
}
