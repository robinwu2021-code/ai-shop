package ai.neargo.shop.portal.biz.feature;

import ai.neargo.shop.auth.BizPerms;

import java.util.List;
import java.util.Set;

/**
 * 商户 App 的功能注册表（TDD-B端门店功能矩阵与方案准入 §2）。
 *
 * <p>这里只写<b>代码决定的事</b>：功能归哪个业务端权益码、要不要先开通收款、用哪些权限码。
 * <b>哪一档方案包含哪个权益码</b>是运营发布的目录数据，不在这里写死 —— 运营把 {@code END_RETAIL}
 * 放进 FREE，零售作业就免费，代码不用改。
 *
 * <p>声明顺序就是接口返回顺序，端上照顺序渲染。
 */
public enum StoreFeature {
    // ------------------------------------------------ 基础：每家店 FREE 起就有（END_BIZ_APP 是 FREE 必含端）
    COLLECT(Group.BASE, Codes.BIZ_APP, true, List.of(BizPerms.PAY_ORDER_VIEW), List.of(BizPerms.PAY_REFUND)),
    COLLECT_QR(Group.BASE, Codes.BIZ_APP, true, List.of(BizPerms.PAY_QR), List.of(BizPerms.PAY_QR)),
    CASHIER(Group.BASE, Codes.BIZ_APP, true, List.of(BizPerms.PAY_COLLECT), List.of(BizPerms.PAY_COLLECT)),
    PAY_STATEMENT(Group.BASE, Codes.BIZ_APP, true, List.of(BizPerms.PAY_STATEMENT), List.of()),
    PAY_ACTIVATION(Group.BASE, Codes.BIZ_APP, false, List.of(BizPerms.FINANCE), List.of(BizPerms.FINANCE)),
    STORE_PROFILE(Group.BASE, Codes.BIZ_APP, false, List.of(BizPerms.STORE), List.of(BizPerms.STORE_ADMIN)),
    STAFF(Group.BASE, Codes.BIZ_APP, false, List.of(BizPerms.STORE_ADMIN), List.of(BizPerms.STORE_ADMIN)),
    /** 门店成员都能看方案与到期；买、续、升级要采购权 */
    SUBSCRIPTION(Group.BASE, Codes.BIZ_APP, false, List.of(), List.of(BizPerms.STORE_ADMIN)),
    PRINT(Group.BASE, Codes.BIZ_APP, false, List.of(BizPerms.PRINT_READ), List.of(BizPerms.PRINT_CONFIG)),
    REVIEW(Group.BASE, Codes.BIZ_APP, false, List.of(BizPerms.REVIEW), List.of(BizPerms.REVIEW)),
    /** 商品与订单是各行业都要的基础卖货能力，FREE 即有（§4-2）；现有门店一直在免费用 */
    GOODS(Group.BASE, Codes.BIZ_APP, false, List.of(BizPerms.GOODS, BizPerms.STOCK), List.of(BizPerms.GOODS)),
    ORDERS(Group.BASE, Codes.BIZ_APP, false,
            List.of(BizPerms.ORDER_VIEW, BizPerms.RECEIVE, BizPerms.VERIFY, BizPerms.SHIP),
            List.of(BizPerms.AFTERSALE)),

    // ------------------------------------------------ 感应收款 / POS（已有端 END_APP_POS，入端权限 biz:receive）
    SOFTPOS(Group.POS, Codes.APP_POS, true, List.of(BizPerms.RECEIVE), List.of()),

    // ------------------------------------------------ 零售进阶
    INVENTORY(Group.RETAIL, Codes.RETAIL, false, List.of(BizPerms.STOCK), List.of(BizPerms.GOODS)),
    GROUP_BUY(Group.RETAIL, Codes.RETAIL, false, List.of(BizPerms.CAMPAIGN), List.of(BizPerms.CAMPAIGN)),

    // ------------------------------------------------ 餐饮：权限码与 /biz/x/food/* 已实现接口一致（food-system-design.json），不另造码
    /** 点餐单、排队叫号与入座：看用 biz:order:view，叫号入座用 biz:verify，退菜重做用 biz:aftersale */
    FNB_ORDERING(Group.FNB, Codes.FNB, false, List.of(BizPerms.ORDER_VIEW, BizPerms.VERIFY),
            List.of(BizPerms.AFTERSALE)),
    FNB_MENU(Group.FNB, Codes.FNB, false, List.of(BizPerms.ORDER_VIEW, BizPerms.GOODS), List.of(BizPerms.GOODS)),
    FNB_TABLES(Group.FNB, Codes.FNB, false, List.of(BizPerms.VERIFY, BizPerms.STORE), List.of(BizPerms.STORE)),
    /** 出品看板：开始 / 出餐 / 上菜是 biz:receive；作废、重做是 biz:aftersale */
    FNB_KITCHEN(Group.FNB, Codes.FNB, false, List.of(BizPerms.RECEIVE), List.of(BizPerms.AFTERSALE)),
    /** 营业日报看 biz:order:view；开业日、日结签核是 biz:finance */
    FNB_REPORT(Group.FNB, Codes.FNB, false, List.of(BizPerms.ORDER_VIEW), List.of(BizPerms.FINANCE)),

    // ------------------------------------------------ 服务业
    /** 与美业接口设计一致：读 = booking:view 或 booking:manage，写 = booking:manage */
    SVC_BOOKING(Group.SERVICE, Codes.SERVICE, false, List.of(BizPerms.BOOKING_VIEW, BizPerms.BOOKING_MANAGE),
            List.of(BizPerms.BOOKING_MANAGE)),
    SVC_CATALOG(Group.SERVICE, Codes.SERVICE, false, List.of(BizPerms.GOODS), List.of(BizPerms.GOODS)),
    SVC_SCHEDULE(Group.SERVICE, Codes.SERVICE, false, List.of(BizPerms.STORE), List.of(BizPerms.STORE)),
    SVC_REPORT(Group.SERVICE, Codes.SERVICE, false, List.of(BizPerms.CUSTOMER), List.of()),

    // ------------------------------------------------ 会员与营销
    MEMBERS(Group.MARKETING, Codes.MARKETING, false, List.of(BizPerms.CUSTOMER), List.of(BizPerms.CUSTOMER)),
    CAMPAIGN(Group.MARKETING, Codes.MARKETING, false, List.of(BizPerms.CAMPAIGN), List.of(BizPerms.CAMPAIGN)),

    // ------------------------------------------------ 连锁
    CHAIN_OPS(Group.CHAIN, Codes.CHAIN, false, List.of(BizPerms.STORE_ADMIN), List.of(BizPerms.STORE_ADMIN)),
    HR(Group.CHAIN, Codes.CHAIN, false, List.of(BizPerms.STORE_ADMIN), List.of(BizPerms.STORE_ADMIN));

    /** 功能分组，端上按组折叠。 */
    public enum Group { BASE, POS, RETAIL, FNB, SERVICE, MARKETING, CHAIN }

    /** 业务端权益码。前两个已在 {@code CommerceRoutePolicy}；其余是运营登记业务端时用的约定码。 */
    public static final class Codes {
        /** 商户 App 基础端：收款、商品、订单、门店、员工、列印、评价、方案。 */
        public static final String BIZ_APP = "END_BIZ_APP";
        /** 感应收款 / POS 端。 */
        public static final String APP_POS = "END_APP_POS";
        /** 零售进阶：进销存、团购（商品与订单是基础功能）。 */
        public static final String RETAIL = "END_RETAIL";
        /** 餐饮作业：点餐单、菜单、桌台、出品看板、报表。 */
        public static final String FNB = "END_FNB";
        /** 服务业作业：预约、服务项目、排班、报表。 */
        public static final String SERVICE = "END_SERVICE";
        /** 会员与营销。 */
        public static final String MARKETING = "END_MARKETING";
        /** 连锁：跨店管理、考勤工资。 */
        public static final String CHAIN = "END_CHAIN";

        private Codes() {
        }
    }

    private final Group group;
    private final String entitlementCode;
    private final boolean requiresPayActivation;
    private final List<String> usePerms;
    private final List<String> managePerms;

    /**
     * @param usePerms    有任一即可用；空 = 门店成员即可
     * @param managePerms 有任一即可管理；空 = 这个功能没有「管理」一说
     */
    StoreFeature(Group group, String entitlementCode, boolean requiresPayActivation, List<String> usePerms,
                 List<String> managePerms) {
        this.group = group;
        this.entitlementCode = entitlementCode;
        this.requiresPayActivation = requiresPayActivation;
        this.usePerms = usePerms;
        this.managePerms = managePerms;
    }

    public String code() {
        return name();
    }

    public Group group() {
        return group;
    }

    public String entitlementCode() {
        return entitlementCode;
    }

    public boolean requiresPayActivation() {
        return requiresPayActivation;
    }

    public List<String> usePerms() {
        return usePerms;
    }

    public List<String> managePerms() {
        return managePerms;
    }

    /** {@code perms} 是已取并集的权限码；{@code *} 为店主 */
    public boolean canUse(Set<String> perms) {
        return usePerms.isEmpty() || anyOf(perms, usePerms);
    }

    public boolean canManage(Set<String> perms) {
        return !managePerms.isEmpty() && anyOf(perms, managePerms);
    }

    private static boolean anyOf(Set<String> perms, List<String> codes) {
        return perms != null && (perms.contains("*") || codes.stream().anyMatch(perms::contains));
    }
}
