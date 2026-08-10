package ai.neargo.shop.auth;

import java.util.List;
import java.util.Map;

/**
 * 权限码与角色定义（一期硬编码，M9b 接 DB）。
 *
 * <p>放在 auth 而不是 platform 域：权限码是**授权词表**，各域的运营面都要引用它
 * （商品审核在 product、订单查询在 trade）。留在 platform 域的话，这两个域为了写一个
 * 常量就得依赖整个平台域——S7 拆 OpsController 时这一点立刻暴露出来。
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

    /**
     * 类目树维护。给商品运营 —— 它改的是「商家上架时能归到哪」，与审商品是同一件事的两面。
     *
     * <p>与 {@link #GOODS_AUDIT} 分开而不是复用：类目上挂着
     * {@code required_code}（经营准入的判据），改它等于放宽或收紧一整类商品的准入门槛，
     * 影响面比审单件商品大一个量级，值得能单独收回。
     */
    public static final String CATEGORY_MANAGE = "category:manage";

    private static final Map<String, List<String>> ROLE_PERMS = Map.of(
            "SUPER_ADMIN", List.of("*"),
            "BD", List.of(MERCHANT_AUDIT, ORDER_VIEW),
            "GOODS_OPS", List.of(GOODS_AUDIT, CATEGORY_MANAGE, ORDER_VIEW),
            "SUPPORT", List.of(ORDER_VIEW));

    private Perms() {
    }

    public static List<String> of(List<String> roles) {
        return roles == null ? List.of() : roles.stream()
                .flatMap(r -> ROLE_PERMS.getOrDefault(r, List.of()).stream())
                .distinct().toList();
    }
}
