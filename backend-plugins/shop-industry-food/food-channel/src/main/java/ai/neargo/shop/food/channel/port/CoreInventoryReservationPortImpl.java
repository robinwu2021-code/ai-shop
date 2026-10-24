package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.inventory.InventoryReservationInternalApi;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Calls the existing PLATFORM owner; no remote failure or missing reservation becomes a successful stock write. */
public class CoreInventoryReservationPortImpl implements InventoryReservationPort {
    private final Supplier<InventoryReservationInternalApi> api;
    public CoreInventoryReservationPortImpl(Supplier<InventoryReservationInternalApi> api) { this.api = api; }

    @Override public Snapshot reserve(Scope scope, List<Line> lines, long ttlSeconds, int lifecycleVersion) {
        var request = new InventoryReservationInternalApi.Reserve(scope, lines, ttlSeconds, lifecycleVersion);
        Snapshot result = invoke(scope, () -> api.get().reserve(request), false);
        if (result.lifecycleVersion() != lifecycleVersion || !quantities(result.originalLines()).equals(
                quantities(lines.stream().map(line -> new ReservedLine(line.skuNo(), line.qty(),
                        line.fulfillmentRef())).toList()))) throw unavailable();
        return result;
    }
    @Override public Snapshot acceptUnpaid(Scope scope) {
        Snapshot result = invoke(scope, () -> api.get().acceptUnpaid(new InventoryReservationInternalApi.Reference(scope)),
                false);
        if (result.lifecycleVersion() != 3 || result.state() == State.HELD || result.state() == State.RELEASED
                || result.state() == State.EXPIRED) throw unavailable();
        return result;
    }
    @Override public Snapshot allocatePaid(Scope scope) {
        Snapshot result = invoke(scope, () -> api.get().allocatePaid(new InventoryReservationInternalApi.Reference(scope)),
                false);
        if (!result.paymentAcknowledged()) throw unavailable();
        return result;
    }
    @Override public Snapshot issue(Scope scope, String fulfillmentRef) {
        var request = new InventoryReservationInternalApi.Issue(scope, fulfillmentRef);
        Snapshot result = invoke(scope, () -> api.get().issue(request), false);
        if (result.originalLines().stream().noneMatch(line -> fulfillmentRef.equals(line.fulfillmentRef()))
                || !List.of(State.PARTIAL_UNPAID, State.ISSUED_UNPAID, State.PARTIALLY_COMMITTED,
                State.COMMITTED, State.PARTIAL_RELEASED).contains(result.state())) throw unavailable();
        return result;
    }
    @Override public Snapshot cancelAll(Scope scope) {
        return invoke(scope, () -> api.get().cancelAll(new InventoryReservationInternalApi.Reference(scope)), false);
    }
    @Override public Snapshot query(Scope scope) {
        return invoke(scope, () -> api.get().query(new InventoryReservationInternalApi.Reference(scope)), true);
    }

    private Snapshot invoke(Scope scope, Supplier<Snapshot> call, boolean absentAllowed) {
        if (scope == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (!"MAIN".equals(scope.tenantNo())) throw BizException.of(ErrorCode.FORBIDDEN);
        try {
            Snapshot result = ServiceCalls.call(ServiceName.PLATFORM, call::get);
            if (result == null || !scope.equals(result.scope()) || !absentAllowed && result.state() == State.ABSENT
                    || result.state() != State.ABSENT && result.state() != State.RELEASED
                    && result.originalLines().isEmpty()) throw unavailable();
            if (List.of(State.ACCEPTED_UNPAID, State.PARTIAL_UNPAID, State.ISSUED_UNPAID).contains(result.state())
                    && (result.lifecycleVersion() != 3 || result.paymentAcknowledged())) throw unavailable();
            return result;
        } catch (ServiceCallException failure) {
            ErrorCode code = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                case 503 -> ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE;
                default -> null;
            };
            if (code != null) throw BizException.of(code);
            throw failure;
        }
    }

    private static Map<Key, Integer> quantities(List<ReservedLine> lines) {
        Map<Key, Integer> result = new HashMap<>();
        for (ReservedLine line : lines) {
            result.merge(new Key(line.skuNo(), line.fulfillmentRef()), line.qty(), Math::addExact);
        }
        return result;
    }
    private record Key(String skuNo, String fulfillmentRef) { }
    private static BizException unavailable() {
        return BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
    }
}
