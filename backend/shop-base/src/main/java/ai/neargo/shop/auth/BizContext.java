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
 * @param merchantNo     我管理的商家（店员同样落这个字段，权限差别在 perms 而非作用域）
 * @param pickupNos      我承接的自提点
 * @param groupNos       我发起的团（邻里自提）
 * @param storeNos       我有权限的门店。老板 = 主体下全部；店员 = 被授权到的那几家
 * @param owner          是不是主体属主。**它决定「全部门店」是什么意思** ——
 *                       老板的「全部」是主体名下所有店（含多门店之前那些没有门店号的历史单），
 *                       店员的「全部」只是他被授权的那几家。
 *                       不区分的话，店员点一下「全部门店」就能看到别家店的单，而且不报错。
 * @param currentStoreNo <b>本次请求作用于哪家店</b>，由请求头 {@code X-Store-No} 指定，
 *                       不传时取默认店。
 *
 *                       <p><b>为什么要「当前门店」这个概念</b>：多门店之后，
 *                       「我的订单」「我的库存」这类问题没有主体级的答案 ——
 *                       老板问的从来是「文三路店今天几单」，不是「我名下所有店一共几单」。
 *                       没有它的话，能建三家店但所有页面仍按主体取数，订单混在一起。
 */
public record BizContext(String merchantNo, Set<String> pickupNos, Set<String> groupNos,
                         Set<String> storeNos, String currentStoreNo, boolean owner,
                         java.util.Map<String, Set<String>> rolesByStore) {

    private static final ThreadLocal<BizContext> HOLDER = new ThreadLocal<>();

    public static final BizContext NONE =
            new BizContext(null, Set.of(), Set.of(), Set.of(), null, false, java.util.Map.of());

    /** 兼容三段式构造：门店维度未接入的调用点照旧可用。 */
    public BizContext(String merchantNo, Set<String> pickupNos, Set<String> groupNos) {
        this(merchantNo, pickupNos, groupNos, Set.of(), null, false, java.util.Map.of());
    }

    /** 兼容六段式构造：多角色接入前的调用点照旧可用。 */
    public BizContext(String merchantNo, Set<String> pickupNos, Set<String> groupNos,
                      Set<String> storeNos, String currentStoreNo, boolean owner) {
        this(merchantNo, pickupNos, groupNos, storeNos, currentStoreNo, owner, java.util.Map.of());
    }

    /** 换一家当前门店（Filter 解析 X-Store-No 之后调）。 */
    public BizContext withStore(String storeNo) {
        return new BizContext(merchantNo, pickupNos, groupNos, storeNos, storeNo, owner,
                rolesByStore);
    }

    /**
     * 我在<b>当前门店</b>持有的角色。
     *
     * <p>角色跟着门店走，不跟着人走 —— {@code mch_store_role} 是
     * {@code (账号, 门店, 角色)} 三元组，同一个人可能在文三路店是店长、
     * 古墩路店是店员。所以这里按 {@link #currentStoreNo()} 查，
     * 而 {@code rolesByStore} 在登录时一次解析好，切店不用重查库。
     *
     * <p><b>老板恒为 OWNER</b>，他不在 {@code mch_store_role} 里。
     *
     * @return 空集合表示在这家店没有任何授权 —— <b>零权限，不是默认店员</b>
     */
    public Set<String> staffRoles() {
        if (owner) {
            return Set.of(BizPerms.OWNER);
        }
        if (currentStoreNo == null || currentStoreNo.isBlank()) {
            return Set.of();
        }
        return rolesByStore.getOrDefault(currentStoreNo, Set.of());
    }

    /**
     * 当前门店的角色合起来有没有这个权限（取并集）。
     *
     * <p><b>授权只在 Controller 层判</b>，与运营端同一条原则 ——
     * 散进 Service 的话，同一个业务方法被两个入口调用时就会漏掉一处。
     */
    public boolean can(String code) {
        return BizPerms.can(staffRoles(), code);
    }

    /**
     * 当前门店上，他的订单视图要不要裁到配送员那一档。
     *
     * <p>判权回答「能不能调这个接口」，这一条回答「同一个接口该给他看多少」——
     * <b>是第三类判断，与授权和数据范围都不同</b>：配送员有 {@code biz:order:view}，
     * 他能调 {@code /biz/order}；他也确实该看到本店待自送的单（数据范围没问题）；
     * 但他不该看到那些单的金额与核销码。
     *
     * @see BizPerms#onlyCourierOrderView(Set)
     */
    public boolean courierOnlyOrderView() {
        return BizPerms.onlyCourierOrderView(staffRoles());
    }

    /**
     * 「全部门店」对这个人意味着哪些店。
     *
     * @return 属主返回 {@code null}（= 不按门店过滤，含历史上没有门店号的单）；
     *         店员返回他被授权的门店集合
     */
    public Set<String> allowedStoresOrAll() {
        return owner ? null : storeNos;
    }

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

    /**
     * 取本次请求作用的门店；没有则 403。
     *
     * <p><b>不做「取不到就回落到默认店」</b>：回落的表现是
     * 「以为在看 A 店，其实看的是 B 店」，而这种错没有任何症状 ——
     * 数字是真的，只是不是他要的那家店的。
     */
    public static String requireStoreNo() {
        String no = current().currentStoreNo();
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
