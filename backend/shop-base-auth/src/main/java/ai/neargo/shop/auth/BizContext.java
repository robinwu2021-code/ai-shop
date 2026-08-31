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
                         java.util.Map<String, Set<String>> rolesByStore,
                         /**
                          * 每家店上我持有的**权限码并集**（V71）。
                          *
                          * <p>由 {@code BizIdentityResolver} 解析身份时一次查好 ——
                          * 角色可以是预置的，也可以是这家商家自己建的，
                          * 而判权那一刻不该再关心这个区别，它只需要一组码。
                          *
                          * <p>为空表示「这个调用点还没接入自定义角色」，
                          * {@link #can} 会回落到 {@link BizPerms} 的预置语义。
                          */
                         java.util.Map<String, Set<String>> permsByStore) {

    private static final ThreadLocal<BizContext> HOLDER = new ThreadLocal<>();

    public static final BizContext NONE =
            new BizContext(null, Set.of(), Set.of(), Set.of(), null, false,
                    java.util.Map.of(), java.util.Map.of());

    /** 兼容三段式构造：门店维度未接入的调用点照旧可用。 */
    public BizContext(String merchantNo, Set<String> pickupNos, Set<String> groupNos) {
        this(merchantNo, pickupNos, groupNos, Set.of(), null, false,
                java.util.Map.of(), java.util.Map.of());
    }

    /** 兼容六段式构造：多角色接入前的调用点照旧可用。 */
    public BizContext(String merchantNo, Set<String> pickupNos, Set<String> groupNos,
                      Set<String> storeNos, String currentStoreNo, boolean owner) {
        this(merchantNo, pickupNos, groupNos, storeNos, currentStoreNo, owner,
                java.util.Map.of(), java.util.Map.of());
    }

    /**
     * 兼容七段式构造：自定义角色（V71）接入前的调用点照旧可用 ——
     * 它们拿到的是预置角色语义（{@link #can} 回落 {@link BizPerms}）。
     */
    public BizContext(String merchantNo, Set<String> pickupNos, Set<String> groupNos,
                      Set<String> storeNos, String currentStoreNo, boolean owner,
                      java.util.Map<String, Set<String>> rolesByStore) {
        this(merchantNo, pickupNos, groupNos, storeNos, currentStoreNo, owner,
                rolesByStore, java.util.Map.of());
    }

    /** 换一家当前门店（Filter 解析 X-Store-No 之后调）。 */
    public BizContext withStore(String storeNo) {
        return new BizContext(merchantNo, pickupNos, groupNos, storeNos, storeNo, owner,
                rolesByStore, permsByStore);
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
     *
     * <h2>V71 起先看 {@code permsByStore}，回落 {@link BizPerms}</h2>
     * 商家可以自定义角色之后，「角色 → 权限码」不再是一张静态表 ——
     * 由 {@code BizIdentityResolver} 在解析身份时一次查好（含预置 + 自定义），
     * 放进 {@code permsByStore}。<b>判权这一刻仍然是纯内存比较，零查询。</b>
     *
     * <p>为什么不在这里查库：判权点在事务之外、每个 {@code @PreAuthorize} 都会走一次，
     * 在这里查等于把一次请求的判权次数变成查询次数。而放在解析那一步，
     * 每请求仍然只查一次 —— 且**改了角色权限下一次请求就生效**，
     * 不需要等他重新登录（收回权限必须立刻生效，这是这个选择的主要理由）。
     *
     * <p>回落到 {@code BizPerms} 是给两类调用者留的：<b>六段式构造</b>
     * （没有 permsByStore 的历史调用点）与单元测试。它们拿到的是预置角色的语义，
     * 与库里的预置角色由 {@code BizRoleSeedTest} 钉住一致。
     */
    public boolean can(String code) {
        /*
         * **currentStoreNo 可能是 null** —— 一个建了账号但一家店都没授权的员工就是这样。
         * 而 permsByStore 多数时候是 Map.of()，**不可变 Map 的 get(null) 直接抛 NPE**
         * （不是返回 null）。判权链路上抛异常的表现是 10500「系统开小差」，
         * 而这条路径本该是最干脆的一个 70006。
         *
         * 这一行是真实测试打出来的：`noGrantInThisStoreMeansNothing`（空角色 = 零权限）
         * 在改造后第一次跑就红 —— 而它恰好是安全性最关键的那条。
         */
        Set<String> perms = permsByStore == null || currentStoreNo == null || currentStoreNo.isBlank()
                ? null : permsByStore.get(currentStoreNo);
        if (owner) {
            // 老板恒为通配，不走任何一张表 —— 与 BizPerms 的第一条规则一致
            return code != null && !code.isBlank();
        }
        if (perms != null) {
            /*
             * **精确比对，只额外认一个裸 `*`（老板）—— B 端不支持模块通配。**
             *
             * 与运营端刻意不同：那边走 `Permissions.matches`，认 `merchant:*`
             * （68 个码，超管与模块负责人需要「这个模块全给」）。B 端 13 个码，
             * 而且给商家角色配模块通配等于把<b>以后新增的码也一并授出去</b> ——
             * 老板不会知道他哪天多授了一样东西。
             *
             * 所以这里不是「还没实现通配」，是**决定不实现**。
             * 按运营端的直觉往 `mch_role` 里写一个 `biz:*` 会静默变成零权限
             * （fail-closed，但不报错，表现是「授了角色什么都点不了」），
             * 种子那一层有守卫直接禁掉：`packages/shared/tests/biz-role-seed.test.ts`。
             */
            return code != null && (perms.contains("*") || perms.contains(code));
        }
        return BizPerms.can(staffRoles(), code);
    }

    /**
     * 当前门店上他<b>实际拥有的权限码</b> —— 下发给端上裁剪入口用。
     *
     * <p>与 {@link #can(String)} <b>必须是同一个来源</b>。此前 {@code /biz/context}
     * 自己按 {@code BizPerms.of(staffRoles())} 又算了一遍，那张表只认预置角色 ——
     * 于是一个只被授了自定义角色的人：后端放行，界面却什么入口都不显示。
     * <b>与「界面显示了、后端拒」是同一个病的两个方向</b>，而这个方向更隐蔽：
     * 没有任何报错，看起来就像「这个功能还没做」。
     *
     * @return 老板恒为 {@code ["*"]}；查不到当前门店的权限时回落预置角色的并集
     *         （六段式构造与单元测试走这条）
     */
    public Set<String> effectivePerms() {
        if (owner) {
            return Set.of("*");
        }
        // get(null) 在不可变 Map 上抛 NPE，不是返回 null —— 与 can() 同一条防线
        Set<String> perms = permsByStore == null || currentStoreNo == null || currentStoreNo.isBlank()
                ? null : permsByStore.get(currentStoreNo);
        return perms != null ? perms : BizPerms.of(staffRoles());
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
