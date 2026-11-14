package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.trade.IndustryChannelOrderFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourceInternalApi;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

import java.time.Clock;
import java.util.function.Supplier;

/**
 * Remote original FOOD owner preserves scoped source identity and actual inventory preparation
 * generation.
 */
public final class RemoteFoodChannelOrderSourcePort implements IndustryChannelOrderSourcePort {
    private final Supplier<IndustryChannelOrderSourceInternalApi> api;
    private final Clock clock;

    public RemoteFoodChannelOrderSourcePort(
            Supplier<IndustryChannelOrderSourceInternalApi> api, Clock clock) {
        this.api = api;
        this.clock = clock;
    }

    @Override
    public String sourceSystem() {
        return "FOOD";
    }

    @Override
    public Facts capture(Reference reference) {
        reference(reference);
        var result = ServiceCalls.call(ServiceName.FOOD, () -> api.get().capture(reference));
        IndustryChannelOrderFacts.validate(reference, result, clock);
        return result;
    }

    @Override
    public Plan demandPlan(Reference reference, String reservationRef, String subOrderNo) {
        reference(reference);
        var result =
                ServiceCalls.call(
                        ServiceName.FOOD,
                        () ->
                                api.get()
                                        .demand(
                                                new IndustryChannelOrderSourceInternalApi.Demand(
                                                        reference, reservationRef, subOrderNo)));
        InventoryDemandPlanFacts.validate(result);
        if (!reference.scope().entityNo().equals(result.scope().entityNo())
                || !reservationRef.equals(result.scope().externalRef()))
            throw new IllegalStateException("Wrong plan scope");
        return result;
    }

    @Override
    public CollectionFacts collection(Reference reference) {
        reference(reference);
        var result = ServiceCalls.call(ServiceName.FOOD, () -> api.get().collection(reference));
        if (result == null || !reference.equals(result.reference()))
            throw new IllegalStateException("Wrong source receipt");
        IndustryChannelOrderFacts.freshness(result.observedAt(), result.validUntil(), clock);
        return result;
    }

    @Override
    public PlatformAcceptance acceptance(Reference reference) {
        reference(reference);
        var result = ServiceCalls.call(ServiceName.FOOD, () -> api.get().acceptance(reference));
        if (result == null || !reference.equals(result.reference()))
            throw new IllegalStateException("Wrong source receipt");
        IndustryChannelOrderFacts.freshness(result.observedAt(), result.validUntil(), clock);
        return result;
    }

    @Override
    public Withdrawal withdrawal(Reference reference) {
        reference(reference);
        var result = ServiceCalls.call(ServiceName.FOOD, () -> api.get().withdrawal(reference));
        if (result == null || !reference.equals(result.reference()))
            throw new IllegalStateException("Wrong source receipt");
        IndustryChannelOrderFacts.freshness(result.observedAt(), result.validUntil(), clock);
        return result;
    }

    private static void reference(Reference reference) {
        IndustryChannelOrderFacts.reference(reference);
        if (!"FOOD".equals(reference.sourceSystem()))
            throw new IllegalArgumentException("Wrong source owner");
    }
}
