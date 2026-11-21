package ai.neargo.shop.invbridge.port;

import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.client.InventoryReservationApi;
import ai.neargo.inventory.client.InventoryReservationClient;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.embed.InventoryErrors;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;

import java.util.List;
import java.util.function.Supplier;

/**
 * 已翻到独立服务的商家：预留经客户端调库存服务（ADR-046 M8 T8.3）。
 *
 * <p>与内嵌实现同一份语义：业主按商家号（系统凭证 + 业主头），物料按 SKU 号、库位按门店号由服务自己换号；
 * 错误码与内嵌时期同一套翻译（{@link InventoryErrors}）。服务不可用 → 拒单，不放行（Q7）。
 */
public class ServiceInventoryReservationPort implements InventoryReservationPort {

    /** 审计里的操作人：订单链路没有「点按钮的人」 */
    private static final String ACTOR = "SYSTEM:order";

    private final InventoryReservationClient client;

    public ServiceInventoryReservationPort(InventoryReservationClient client) {
        this.client = client;
    }

    @Override
    public Snapshot reserve(Scope scope, List<Line> lines, long ttlSeconds, int lifecycleVersion) {
        String owner = owner(scope);
        List<InventoryReservationApi.Line> mapped = lines.stream()
                .map(l -> new InventoryReservationApi.Line(null, null, l.skuNo(), l.storeNo(), l.qty(),
                        l.fulfillmentRef()))
                .toList();
        return snapshot(scope, translate(() -> client.reserve(owner, ACTOR,
                new InventoryReservationApi.Reserve(scope.externalRef(), mapped, ttlSeconds, lifecycleVersion))));
    }

    @Override
    public Snapshot acceptUnpaid(Scope scope) {
        return snapshot(scope, translate(() -> client.acceptUnpaid(owner(scope), ACTOR, scope.externalRef())));
    }

    @Override
    public Snapshot allocatePaid(Scope scope) {
        return snapshot(scope, translate(() -> client.allocatePaid(owner(scope), ACTOR, scope.externalRef())));
    }

    @Override
    public Snapshot issue(Scope scope, String fulfillmentRef) {
        return snapshot(scope, translate(() -> client.issue(owner(scope), ACTOR, scope.externalRef(),
                fulfillmentRef)));
    }

    @Override
    public Snapshot cancelAll(Scope scope) {
        return snapshot(scope, translate(() -> client.cancel(owner(scope), ACTOR, scope.externalRef())));
    }

    @Override
    public Snapshot query(Scope scope) {
        String owner = owner(scope);
        return translate(() -> client.query(owner, scope.externalRef()))
                .map(v -> snapshot(scope, v))
                .orElseGet(() -> new Snapshot(scope, null, State.ABSENT, 0, false, List.of()));
    }

    private static String owner(Scope scope) {
        if (scope == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (!"MAIN".equals(scope.tenantNo())) throw BizException.of(ErrorCode.FORBIDDEN);
        return scope.entityNo();
    }

    private static Snapshot snapshot(Scope scope, InventoryReservationApi.View v) {
        List<ReservedLine> original = v.lines().stream().map(line -> {
            // 物料反查不到 SKU（一个物料挂多个 SKU）：与内嵌实现同样拒绝，不猜
            if (line.itemRef() == null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            return new ReservedLine(line.itemRef(), line.qty(), line.fulfillmentRef());
        }).toList();
        return new Snapshot(scope, v.reservationId(), State.valueOf(v.status()), v.lifecycleVersion(),
                v.paymentAcknowledged(), original);
    }

    private static <T> T translate(Supplier<T> call) {
        try {
            return call.get();
        } catch (InventoryException e) {
            throw InventoryErrors.toShop(e);
        }
    }
}
