package ai.neargo.shop.invbridge.port;

import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;

import java.util.List;

/**
 * 按商家路由（ADR-046 M8 T8.3、T8.5）：这个商家的账由谁写，预留就交给谁。
 *
 * <ul>
 *   <li>{@code EMBEDDED} 或还没开过账 → 内嵌实现（与切流前一字不差）；</li>
 *   <li>{@code SVC} → 库存服务（客户端）；没配客户端 = 配置漏了，拒单而不是悄悄走内嵌 ——
 *       内嵌这时已经不认这个业主，走过去也只会报错，不如在这里说清楚；</li>
 *   <li>{@code FLIPPING} → 翻转对账期间两边都拒写，拒单（几秒）。</li>
 * </ul>
 *
 * 每次调用现查写入方：翻转是在线做的，不能缓存。
 */
public class RoutingInventoryReservationPort implements InventoryReservationPort {

    private final InventoryAclService acl;
    private final InventoryReservationPort embedded;
    private final InventoryReservationPort service;

    /** @param service 没配库存服务地址时为空 */
    public RoutingInventoryReservationPort(InventoryAclService acl, InventoryReservationPort embedded,
                                           InventoryReservationPort service) {
        this.acl = acl;
        this.embedded = embedded;
        this.service = service;
    }

    @Override
    public Snapshot reserve(Scope scope, List<Line> lines, long ttlSeconds, int lifecycleVersion) {
        return route(scope).reserve(scope, lines, ttlSeconds, lifecycleVersion);
    }

    @Override
    public Snapshot acceptUnpaid(Scope scope) {
        return route(scope).acceptUnpaid(scope);
    }

    @Override
    public Snapshot allocatePaid(Scope scope) {
        return route(scope).allocatePaid(scope);
    }

    @Override
    public Snapshot issue(Scope scope, String fulfillmentRef) {
        return route(scope).issue(scope, fulfillmentRef);
    }

    @Override
    public Snapshot cancelAll(Scope scope) {
        return route(scope).cancelAll(scope);
    }

    @Override
    public Snapshot query(Scope scope) {
        return route(scope).query(scope);
    }

    private InventoryReservationPort route(Scope scope) {
        String authority = scope == null ? null : acl.writeAuthorityOf(scope.entityNo());
        if (authority == null || InvEnums.WriteAuthority.EMBEDDED.equals(authority)) {
            return embedded;
        }
        if (InvEnums.WriteAuthority.SVC.equals(authority) && service != null) {
            return service;
        }
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
