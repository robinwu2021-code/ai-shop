package ai.neargo.inventory.guard;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.service.Caller;

/**
 * 当前请求是谁发起的。由 {@link InventoryAuthFilter} 在认完凭证之后放进来、请求结束时清掉。
 *
 * <p>控制器只从这里取业主，<b>不从路径或请求体取</b> —— 业主取自凭证（TDD §2.4）。
 */
public final class InventoryRequestContext {

    private static final ThreadLocal<Current> CURRENT = new ThreadLocal<>();

    private InventoryRequestContext() {
    }

    static void set(InventoryPrincipal principal, String requestId, String traceId) {
        CURRENT.set(new Current(principal, requestId, traceId));
    }

    static void clear() {
        CURRENT.remove();
    }

    public static InventoryPrincipal principal() {
        return require().principal();
    }

    /** 领域写命令要的发起方：系统、业主、操作人、幂等键、链路号。 */
    public static Caller caller() {
        Current c = require();
        return new Caller(c.principal().systemCode(), c.principal().ownerId(), c.principal().actor(),
                c.requestId(), c.traceId());
    }

    private static Current require() {
        Current c = CURRENT.get();
        if (c == null) {
            // 走到这里说明某个入口没挂守卫过滤器 —— 宁可拒绝，也不让它以「无业主」身份往下跑
            throw InventoryException.of(InventoryError.UNAUTHORIZED);
        }
        return c;
    }

    private record Current(InventoryPrincipal principal, String requestId, String traceId) {
    }
}
