package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.trade.IndustryChannelOrderFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort;
import ai.neargo.shop.spi.trade.RetailChannelOrderSourceInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

import java.time.Clock;
import java.util.function.Supplier;

/**
 * Remote original RETAIL owner preserves scoped source identity and actual inventory preparation
 * generation.
 */
public final class RemoteRetailChannelOrderSourcePort implements IndustryChannelOrderSourcePort {
    private final Supplier<RetailChannelOrderSourceInternalApi> api;
    private final Clock clock;

    public RemoteRetailChannelOrderSourcePort(
            Supplier<RetailChannelOrderSourceInternalApi> api, Clock clock) {
        this.api = api;
        this.clock = clock;
    }

    @Override
    public String sourceSystem() {
        return "RETAIL";
    }

    @Override
    public Facts capture(Reference reference) {
        reference(reference);
        var result = ServiceCalls.call(ServiceName.PLATFORM, () -> api.get().capture(reference));
        IndustryChannelOrderFacts.validate(reference, result, clock);
        return result;
    }

    @Override
    public Plan demandPlan(Reference reference, String reservationRef, String subOrderNo) {
        reference(reference);
        var result =
                ServiceCalls.call(
                        ServiceName.PLATFORM,
                        () ->
                                api.get()
                                        .demand(
                                                new RetailChannelOrderSourceInternalApi.Demand(
                                                        reference, reservationRef, subOrderNo)));
        InventoryDemandPlanFacts.validate(result);
        if (!reference.scope().entityNo().equals(result.scope().entityNo())
                || !reference.scope().tenantNo().equals(result.scope().tenantNo())
                || !reservationRef.equals(result.scope().externalRef())
                || !reservationRef.equals(result.planNo())
                || result.branches().stream()
                        .anyMatch(
                                b ->
                                        !subOrderNo.equals(b.fulfillmentRef())
                                                || !reference
                                                        .scope()
                                                        .storeNo()
                                                        .equals(b.storeNo())))
            throw new IllegalStateException("Wrong plan scope");
        return result;
    }

    @Override
    public CollectionFacts collection(Reference reference) {
        reference(reference);
        var result = ServiceCalls.call(ServiceName.PLATFORM, () -> api.get().collection(reference));
        if (result == null || !reference.equals(result.reference()))
            throw new IllegalStateException("Wrong source receipt");
        IndustryChannelOrderFacts.freshness(result.observedAt(), result.validUntil(), clock);
        return result;
    }

    @Override
    public PlatformAcceptance acceptance(Reference reference) {
        reference(reference);
        var result = ServiceCalls.call(ServiceName.PLATFORM, () -> api.get().acceptance(reference));
        if (result == null || !reference.equals(result.reference()))
            throw new IllegalStateException("Wrong source receipt");
        IndustryChannelOrderFacts.freshness(result.observedAt(), result.validUntil(), clock);
        return result;
    }

    @Override
    public Withdrawal withdrawal(Reference reference) {
        reference(reference);
        var result = ServiceCalls.call(ServiceName.PLATFORM, () -> api.get().withdrawal(reference));
        if (result == null || !reference.equals(result.reference()))
            throw new IllegalStateException("Wrong source receipt");
        IndustryChannelOrderFacts.freshness(result.observedAt(), result.validUntil(), clock);
        return result;
    }

    private static void reference(Reference reference) {
        IndustryChannelOrderFacts.reference(reference);
        if (!"RETAIL".equals(reference.sourceSystem()))
            throw new IllegalArgumentException("Wrong source owner");
    }
}
