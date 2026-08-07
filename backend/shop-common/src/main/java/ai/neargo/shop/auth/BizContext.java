package ai.neargo.shop.auth;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import java.util.Set;

/**
 * B 端上下文：当前用户在经营侧的三个正交作用域（TDD-backend §5.1）。
 *
 * <p>由 {@code BizContextFilter} 在 {@code /biz/**} 上解析并放入 ThreadLocal。
 * <b>Service 永远不自己查身份</b>，只读这里 —— 「我是不是这家店的人」这个判断只有一处实现，
 * 才可能被一个测试覆盖住。
 *
 * <p>三个作用域<b>不叠加</b>：一个人可以既是商家、又是自提点、又发起了团，
 * 但他能看到什么由<b>请求路径</b>决定，不由身份决定。
 *
 * @param merchantNo 我管理的商家（店员同样落这个字段，权限差别在 perms 而非作用域）
 * @param pickupNos  我承接的自提点
 * @param groupNos   我发起的团（邻里自提）
 */
public record BizContext(String merchantNo, Set<String> pickupNos, Set<String> groupNos) {

    private static final ThreadLocal<BizContext> HOLDER = new ThreadLocal<>();

    public static final BizContext NONE = new BizContext(null, Set.of(), Set.of());

    public static void set(BizContext ctx) {
        HOLDER.set(ctx);
    }

    public static BizContext current() {
        BizContext ctx = HOLDER.get();
        return ctx == null ? NONE : ctx;
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 取当前商家号；不是商家直接 403，避免每个 Service 各写一遍判空。 */
    public static String requireMerchantNo() {
        String no = current().merchantNo();
        if (no == null || no.isBlank()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return no;
    }

    public static String requirePickupNo(String pickupNo) {
        if (!current().pickupNos().contains(pickupNo)) {
            throw BizException.of(ErrorCode.NOT_THIS_PICKUP_POINT);
        }
        return pickupNo;
    }

    /** 团发起人作用域：只能操作自己发起的团（ADR-005，零报酬、单团）。 */
    public static String requireGroupNo(String groupNo) {
        if (!current().groupNos().contains(groupNo)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return groupNo;
    }

    public boolean isEmpty() {
        return (merchantNo == null || merchantNo.isBlank()) && pickupNos.isEmpty() && groupNos.isEmpty();
    }
}
