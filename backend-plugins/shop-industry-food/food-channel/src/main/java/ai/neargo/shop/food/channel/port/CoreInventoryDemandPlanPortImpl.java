package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanInternalApi;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanInternalApi.Change;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanInternalApi.Reference;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanInternalApi.Reserve;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanInternalApi.Reverse;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Scope;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.List;
import java.util.function.Supplier;

/** Typed remote owner; errors and unknown state are never manufactured into success. */
public class CoreInventoryDemandPlanPortImpl implements InventoryDemandPlanPort {
    private final Supplier<InventoryDemandPlanInternalApi> api;

    public CoreInventoryDemandPlanPortImpl(Supplier<InventoryDemandPlanInternalApi> api) {
        this.api = api;
    }

    @Override
    public Receipt reserve(Plan p, long ttl, int version) {
        InventoryDemandPlanFacts.validate(p);
        return call(p.scope(), () -> api.get().reserve(new Reserve(p, ttl, version)));
    }

    @Override
    public Receipt query(Scope s) {
        return call(s, () -> api.get().query(new Reference(s)));
    }

    @Override
    public Receipt acceptUnpaid(Scope s) {
        return call(s, () -> api.get().acceptUnpaid(new Reference(s)));
    }

    @Override
    public Receipt allocatePaid(Scope s) {
        return call(s, () -> api.get().allocatePaid(new Reference(s)));
    }

    @Override
    public Receipt issue(Scope s, String op, String h, String phase, List<Target> ts) {
        return call(s, () -> api.get().issueDelta(new Change(s, op, h, phase, ts)));
    }

    @Override
    public Receipt issueTo(Scope s, String op, String h, String phase, List<Target> ts) {
        return call(s, () -> api.get().issue(new Change(s, op, h, phase, ts)));
    }

    @Override
    public Receipt releaseTo(Scope s, String op, String h, List<Target> ts) {
        return call(s, () -> api.get().release(new Change(s, op, h, "RELEASE", ts)));
    }

    @Override
    public Receipt supplement(Scope s, String op, String h, List<Target> ts) {
        return call(s, () -> api.get().supplement(new Change(s, op, h, "SUPPLEMENT", ts)));
    }

    @Override
    public Receipt reverse(Scope s, String op, String original, String h) {
        return call(s, () -> api.get().reverse(new Reverse(s, op, original, h)));
    }

    private Receipt call(Scope s, Supplier<Receipt> work) {
        return ServiceCalls.call(
                ServiceName.PLATFORM,
                () -> {
                    var r = work.get();
                    if (r == null || !s.equals(r.scope()))
                        throw new ServiceCallException(
                                CallOutcome.REMOTE_ERROR,
                                ServiceName.PLATFORM,
                                0,
                                "Invalid exact inventory receipt");
                    return r;
                });
    }
}
