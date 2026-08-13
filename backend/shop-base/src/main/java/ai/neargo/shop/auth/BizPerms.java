package ai.neargo.shop.auth;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B 端权限码与角色定义。
 *
 * <p><b>与运营端的 {@link Perms} 刻意不合并</b>：两端的角色词表没有交集
 * （店长/店员 vs BD/客服），合成一个类只会让人每次都要先想「这是哪端的码」。
 * B 端的码一律带 {@code biz:} 前缀，一眼能分。
 *
 * <h2>分界线画在「出错的后果」上</h2>
 * 不按「功能重要性」这种模糊的东西分，而是问：这件事做错了会怎样。
 * <ul>
 *   <li><b>动钱</b>（结算、进件、积分开关）→ 只有老板</li>
 *   <li><b>改结构</b>（建店、停用、挂收款号）→ 只有老板</li>
 *   <li><b>改经营</b>（商品与价格、营销、评价、售后）→ 老板 + 店长</li>
 *   <li><b>日常履约</b> → 按「面对谁」再分三档，见下</li>
 * </ul>
 *
 * <h2>履约不是一件事，是三件</h2>
 * 分拣面对<b>货</b>、核销面对<b>顾客</b>、发货面对<b>收件人</b>。
 * 而接口早就分开了 —— {@code /biz/pickup/picking} 返回的分拣单
 * 只有商品与件数，<b>天然不含金额与买家</b>（当初为「按商品汇总点数」设计时
 * 顺带排除的）。所以拆成三个码不是硬切，是顺着已有的形状：
 * 拆开之后理货员与配送员才装得下。
 *
 * <h2>一人一店可多角色，权限取并集</h2>
 * 小店的常态是一人多岗 —— 站收银台的顺手把货送了。
 * 硬要一人一角色，结果只会是「都给店长」，那等于没分。
 * 并集也意味着<b>不需要为每种组合发明一个角色名</b>。
 */
public final class BizPerms {

    // ---------------------------------------------------------------- 履约（按「面对谁」分三档）

    /** 到货登记、分拣单、短少上报。只对货，不对人 —— 分拣单本就不含金额与买家 */
    public static final String RECEIVE = "biz:receive";
    /** 核销、批量核销、按码搜索。<b>交付确认，等于签收</b>，要面对顾客 */
    public static final String VERIFY = "biz:verify";
    /** 发货、标记自送送达。要看收件地址 */
    public static final String SHIP = "biz:ship";

    // ---------------------------------------------------------------- 经营

    /** 订单列表与详情、工作台待办。<b>含金额</b> */
    public static final String ORDER_VIEW = "biz:order:view";
    /** 改库存（含门店库存）。高频日常动作，且<b>不出钱</b> —— 与改价是两件事 */
    public static final String STOCK = "biz:stock";
    /** 建/改商品、上下架、规格模板、识图。<b>含价格</b> */
    public static final String GOODS = "biz:goods";
    /** 营销活动、开团、报价。让利与报价都是对钱的承诺 */
    public static final String CAMPAIGN = "biz:campaign";
    /** 评价回复、差评申诉。回复是<b>公开</b>的，代表店铺说话 */
    public static final String REVIEW = "biz:review";
    /** 售后同意/驳回/收货。同意仅退款 = 直接出钱 */
    public static final String AFTERSALE = "biz:aftersale";
    /** 顾客列表（含累计消费额）、经营数据。<b>客户资产</b> */
    public static final String CUSTOMER = "biz:customer";

    // ---------------------------------------------------------------- 门店与钱

    /**
     * 门店经营面：装修、配送规则、店铺码、分享物料。
     *
     * <p>与 {@link #STORE_ADMIN} 分开，抄的是运营端 {@code community:view} 与
     * {@code industry:manage} 分家那次的教训：<b>一个码同时管「日常经营」和
     * 「改结构」时，只要有任何一个角色需要前者，它就会被迫拿到后者。</b>
     * 店长该能改自己店的装修，不该能把这家店停掉。
     */
    public static final String STORE = "biz:store";
    /** 建店、改名、停用、设默认店、挂收款号。<b>改的是主体结构</b> */
    public static final String STORE_ADMIN = "biz:store:admin";
    /** 结算账单、费率卡、收款进件、积分开关。<b>钱</b> */
    public static final String FINANCE = "biz:finance";

    // ---------------------------------------------------------------- 角色

    public static final String OWNER = "OWNER";
    public static final String MANAGER = "MANAGER";
    public static final String CLERK = "CLERK";
    public static final String PICKER = "PICKER";
    public static final String COURIER = "COURIER";
    public static final String CS = "CS";

    /**
     * 角色 → 权限码。
     *
     * <p>几条容易被问到的判断：
     * <ul>
     *   <li><b>店长也碰不了钱。</b> 他不是主体负责人，结算账户绑的是执照。
     *       老板真要让某个店长看账，<b>给他加一个角色</b>，
     *       而不是放宽「店长」这个词的定义 —— 多角色模型让这个回答成立。</li>
     *   <li><b>店长不能管员工。</b> 授权别人 = 扩散权限，
     *       他能给自己加店、或把店员提成店长。</li>
     *   <li><b>店员能改库存但看不到顾客列表。</b> 看似不对称，其实一致：
     *       改库存是他今天的活；顾客列表含累计消费额，那是客户资产不是履约信息。</li>
     *   <li><b>理货员没有 ORDER_VIEW。</b> 分拣单本身够他用且不含金额，给了是多给。</li>
     * </ul>
     */
    private static final Map<String, List<String>> ROLE_PERMS = Map.of(
            OWNER, List.of("*"),
            MANAGER, List.of(RECEIVE, VERIFY, SHIP, ORDER_VIEW, STOCK,
                    GOODS, CAMPAIGN, REVIEW, AFTERSALE, CUSTOMER, STORE),
            CLERK, List.of(RECEIVE, VERIFY, SHIP, ORDER_VIEW, STOCK),
            PICKER, List.of(RECEIVE, STOCK),
            COURIER, List.of(SHIP, ORDER_VIEW),
            // 只对着顾客说话：回评价、处理售后、看得到那张单。不碰货、不碰钱
            CS, List.of(REVIEW, AFTERSALE, ORDER_VIEW));

    /**
     * 权限码 → 给店主看的一句话（V71）。
     *
     * <p><b>为什么要有它</b>：自定义角色之后，老板要在界面上勾权限码。
     * 而 `biz:order:view` 这种字符串对他没有意义 —— 他要看到的是「看订单与金额」。
     *
     * <p>说明只放这一份，由 {@code /biz/roles} 下发；<b>前端不抄</b>。
     * 抄一份的下场今天演过：文档里的角色表抄了三份，三处不一致。
     * {@code BizRoleSeedTest} 断言这里覆盖了全部权限码 —— 加码忘了加说明就红。
     */
    public static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry(RECEIVE, "到货登记与分拣"),
            Map.entry(VERIFY, "核销取货"),
            Map.entry(SHIP, "发货与标记送达"),
            Map.entry(ORDER_VIEW, "看订单与金额"),
            Map.entry(STOCK, "看商品、改库存"),
            Map.entry(GOODS, "建商品、改价、上下架"),
            Map.entry(CAMPAIGN, "营销活动与报价"),
            Map.entry(REVIEW, "回评价、差评申诉"),
            Map.entry(AFTERSALE, "处理售后"),
            Map.entry(CUSTOMER, "顾客列表与经营数据"),
            Map.entry(STORE, "店铺装修与配送规则"),
            Map.entry(STORE_ADMIN, "建店、停用、挂收款号、管员工"),
            Map.entry(FINANCE, "结算账单与收款进件"));

    /**
     * 权限码 → 给人看的一句话；认不出的码原样返回。
     *
     * <p>给 70006 的错误消息用：**「这一步需要什么」比「你不能做这件事」有用得多**。
     * 认不出时返回码本身而不是一句兜底文案 —— 前者至少能让人搜到，
     * 后者会让「码写错了」和「他确实没这个权限」长得一模一样。
     */
    public static String labelOf(String code) {
        return LABELS.getOrDefault(code, code);
    }

    /**
     * 商家<b>自定义</b>角色可以勾的权限码 —— 少了 {@link #STORE_ADMIN}。
     *
     * <p>那是「管人」的码：授出去等于让被授权的人能改所有人的授权、
     * 能给自己加任何角色，<b>一次授权就绕开了整个模型</b>。
     * 界面上它根本不出现，后端再校验一次 —— 两道都要，
     * 界面那道是体验，后端这道才是边界。
     */
    public static Set<String> assignableCodes() {
        return LABELS.keySet().stream()
                .filter(c -> !STORE_ADMIN.equals(c))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private BizPerms() {
    }

    /**
     * 这组角色合起来有没有这个权限 —— <b>取并集</b>。
     *
     * <p>不做「取最小」那种聪明事：老板给一个人同时勾了店员与配送，
     * 他要的就是这个人两样都能干。
     *
     * @param roles 当前门店上持有的全部角色；<b>空集合 = 零权限</b>，
     *              不是「默认店员」—— 认不出角色时给权限，是这类判定最坏的失败方式
     */
    public static boolean can(Set<String> roles, String code) {
        if (roles == null || roles.isEmpty() || code == null) {
            return false;
        }
        return roles.stream().anyMatch(r -> {
            List<String> granted = ROLE_PERMS.get(r);
            return granted != null && (granted.contains("*") || granted.contains(code));
        });
    }

    /**
     * 这个人的订单视图要不要<b>裁到配送员那一档</b>（不含金额、不含核销码）。
     *
     * <p>为什么不是「有没有 COURIER 这个角色」：小店的常态是一人多岗，
     * <b>店员兼配送</b>时他本来就该看到完整订单 —— 按角色存在与否判，
     * 会把店员的订单页也裁掉，而那是他站收银台要用的。
     *
     * <p>所以判的是：<b>给他 {@link #ORDER_VIEW} 的是不是只有 COURIER 这一个角色</b>。
     * 是 → 裁；只要还有别的角色也给了他这个码 → 不裁。这与 {@link #can} 的并集语义一致：
     * 并集里更宽的那一档说了算。
     *
     * <p>老板不走这里（他的角色集合是 {@code {OWNER}}，不含 COURIER）。
     */
    public static boolean onlyCourierOrderView(Set<String> roles) {
        if (roles == null || !roles.contains(COURIER)) {
            return false;
        }
        return roles.stream()
                .filter(r -> !COURIER.equals(r))
                .noneMatch(r -> {
                    List<String> granted = ROLE_PERMS.getOrDefault(r, List.of());
                    return granted.contains("*") || granted.contains(ORDER_VIEW);
                });
    }

    /** 这组角色合起来有哪些权限码 —— 下发给端上做入口裁剪 */
    public static Set<String> of(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        if (roles.stream().anyMatch(r -> ROLE_PERMS.getOrDefault(r, List.of()).contains("*"))) {
            return Set.of("*");
        }
        return roles.stream()
                .flatMap(r -> ROLE_PERMS.getOrDefault(r, List.of()).stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
