package ai.neargo.shop.platform;

import java.util.List;
import java.util.Map;

/**
 * 权限码与角色定义（一期硬编码，M9b 接 DB）。
 *
 * <p>角色划分对着矩阵 §2.3 的运营岗位来：BD 只审商家、商品运营只审商品、
 * 客服只看订单与工单。**没有「运营」这种大角色** —— 一个能审商家又能改价又能退款的角色，
 * 出事时无法定位是谁的职责。
 */
public final class Perms {

    public static final String STAFF_MANAGE = "staff:manage";
    public static final String AUDIT_LOG_VIEW = "audit:view";
    public static final String MERCHANT_AUDIT = "merchant:audit";
    public static final String GOODS_AUDIT = "goods:audit";
    public static final String ORDER_VIEW = "order:view";

    /**
     * 行业主数据维护。<b>不给 BD</b> —— 它改的是「哪些行业能开小微」，
     * 那是通道规则的落点，不是招商能自行判断的事；改错的后果是一批商家进件被拒。
     */
    public static final String INDUSTRY_MANAGE = "industry:manage";

    private static final Map<String, List<String>> ROLE_PERMS = Map.of(
            "SUPER_ADMIN", List.of("*"),
            "BD", List.of(MERCHANT_AUDIT, ORDER_VIEW),
            "GOODS_OPS", List.of(GOODS_AUDIT, ORDER_VIEW),
            "SUPPORT", List.of(ORDER_VIEW));

    private Perms() {
    }

    public static List<String> of(List<String> roles) {
        return roles == null ? List.of() : roles.stream()
                .flatMap(r -> ROLE_PERMS.getOrDefault(r, List.of()).stream())
                .distinct().toList();
    }
}
