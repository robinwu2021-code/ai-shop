package ai.neargo.shop.auth;

import java.util.List;
import java.util.Map;

/**
 * 运营端权限码与角色定义。
 *
 * <p>放在 auth 而不是 platform 域：权限码是**授权词表**，各域的运营面都要引用它
 * （商品审核在 product、订单查询在 trade）。留在 platform 域的话，这两个域为了写一个
 * 常量就得依赖整个平台域——S7 拆 OpsController 时这一点立刻暴露出来。
 *
 * <h2>2026-08-12：从 16 个粗码细化到 68 个</h2>
 *
 * <p>原来 16 个码盖 154 个端点，粗到<b>「只读商家档案」与「封禁商家」同一个权限</b>、
 * 「看结算单」与「标记已打款」同一个权限。后者不是粒度问题，是内控问题：
 * 财务里最该分离的制单与付款共用一把钥匙。
 *
 * <p>细码不是新设计的 —— <b>ops-web 的 {@code uiPermCode} 一直维护着一份与界面功能
 * 一一对应的码表，后端从来没采纳它</b>。这次采纳，另加了 24 个「后端有能力、
 * 界面没有入口」的码（保证金、进项发票、行业开关…）。
 *
 * <p>归属关系登记在 {@code scripts/perm-endpoint-map.mjs}，
 * 由 {@code packages/shared/tests/ops-perm-matrix.test.ts} 钉住：
 * 每个端点都要有归属、不能有死规则、<b>角色×端点可访问矩阵必须与基线逐格相同</b>。
 * 最后一条是这次改造能安全做的全部理由 —— 判紧了有人报错（看得见），
 * 判松了没人报错（看不见），所以等价性必须由机器验证，不能靠人比对 154 行。
 *
 * <h2>两条贯穿全表的规矩</h2>
 *
 * <p><b>① 读写分开。</b>不是洁癖，是实测过的代价：读写合一时，只要有任何一个角色
 * 需要看而不需要改，它就会被迫多拿一份写权限，或者干脆做不了本职工作 ——
 * 合并期的 {@code community:view} 让 BD 打开审核抽屉时「覆盖小区」一个选项都没有，
 * 而通过前又强制要求选一个，<b>招商的日常流程被自己的权限卡死</b>，
 * 页面上还看不出是权限问题（列表就是空的）。
 *
 * <p><b>② 只配后端真有的码。</b>风控的拦截/黑名单、履约调度、增长归因在后端
 * 还没有任何端点，所以那几个角色拿到的清单很短 —— 这是如实反映。
 * <b>凭空映射到一个语义相近的现有码，会让权限表看着是满的而实际什么都点不动</b>；
 * 更坏的是可能顺手给出远超职责的权限（风控要封禁，就把商家治理给它，于是风控还能批入驻）。
 *
 * <p><b>角色划分对着矩阵 §2.3 的运营岗位来。没有「运营」这种大角色</b> ——
 * 一个能审商家又能改价又能退款的角色，出事时无法定位是谁的职责。
 */
public final class Perms {

    // ── 售后 ────────────────────────────────────────────────────────────────
    public static final String AFTERSALE_TICKET_READ = "aftersale:ticket:read";

    /**
     * 裁决售后（可触发退款）。
     *
     * <p><b>细化前它挂在 {@code order:view} 下</b> —— 一个「看单」的码，
     * 11 个角色里 7 个持有。也就是说风控、社区运营、活动运营都能批退款。
     * 拆出来是为了让这件事能被单独收回。
     */
    public static final String AFTERSALE_TICKET_HANDLE = "aftersale:ticket:handle";

    public static final String AFTERSALE_REFUND_READ = "aftersale:refund:read";

    /** 极速退阈值配置。同上，细化前也在 {@code order:view} 下。 */
    public static final String AFTERSALE_REFUND_APPROVE = "aftersale:refund:approve";

    // ── 社区与网点 ──────────────────────────────────────────────────────────
    public static final String COMMUNITY_READ = "community:community:read";
    public static final String COMMUNITY_UPDATE = "community:community:update";
    public static final String COMMUNITY_PICKUP_READ = "community:pickup:read";
    public static final String COMMUNITY_PICKUP_UPDATE = "community:pickup:update";

    /** 行政区划主数据。读它是「选覆盖社区」的前置，与改社区分开 —— 见类注释规矩 ①。 */
    public static final String COMMUNITY_REGION_READ = "community:region:read";

    // ── 内容与素材 ──────────────────────────────────────────────────────────
    public static final String CONTENT_MATERIAL_READ = "content:material:read";

    /**
     * 种草内容审核。<b>不复用评价裁决的码</b>：那个给客服（裁决差评要看聊天记录与订单），
     * 内容审核在矩阵里归审核员，且它还管着榜单与素材 —— <b>客服不该能改首页榜单</b>。
     */
    public static final String CONTENT_MATERIAL_AUDIT = "content:material:audit";
    public static final String CONTENT_MATERIAL_UPDATE = "content:material:update";

    // ── 经营看板 ────────────────────────────────────────────────────────────
    public static final String DASHBOARD_OVERVIEW_READ = "dashboard:overview:read";

    // ── 结算与资金 ──────────────────────────────────────────────────────────
    public static final String FINANCE_SETTLE_READ = "finance:settle:read";

    /** 对账确认（制单）。与下面的付款分开 —— 这是这次细化最主要的收益之一。 */
    public static final String FINANCE_SETTLE_EXECUTE = "finance:settle:execute";

    /**
     * <b>登记付款。</b>它不划转资金，但它是财务在网银付款的依据。
     *
     * <p>细化前它与「看结算单」同码。制单与付款不分离是内控问题，不是粒度问题 ——
     * 一个人既能确认这笔该付、又能标记已付，中间没有第二双眼睛。
     */
    public static final String FINANCE_PAYOUT_EXECUTE = "finance:payout:execute";

    public static final String FINANCE_INVOICE_READ = "finance:invoice:read";

    /** 进项发票核验与标记无票。标记无票 = 接受这笔支出不能税前列支，是税务判断。 */
    public static final String FINANCE_INVOICE_VERIFY = "finance:invoice:verify";

    public static final String FINANCE_RATE_READ = "finance:rate:read";
    public static final String FINANCE_RATE_UPDATE = "finance:rate:update";
    public static final String FINANCE_RECON_READ = "finance:recon:read";

    /** 处理支付对账差异 —— 会改账，与只读覆盖率分开。 */
    public static final String FINANCE_RECON_RESOLVE = "finance:recon:resolve";

    // ── 团购与求团 ──────────────────────────────────────────────────────────
    public static final String GROUP_CAMPAIGN_READ = "group:campaign:read";

    /**
     * 中止违规拼团。这类「平台兜底干预」的权限容易被排在后面，因为正常流程不需要它 ——
     * 但它恰恰是<b>出事时唯一的手段</b>，而出事是迟早的。
     */
    public static final String GROUP_CAMPAIGN_AUDIT = "group:campaign:audit";

    public static final String GROUP_DEMAND_READ = "group:demand:read";

    /**
     * 平台改价与判定毁约。<b>不给客服</b>：判毁约会写进商家信用档案、影响后续准入，
     * 那是招商侧要承担后果的判断，不是接一通电话就能下的结论。
     */
    public static final String GROUP_DEMAND_ASSIGN = "group:demand:assign";

    // ── 员工与权限 ──────────────────────────────────────────────────────────
    public static final String IAM_STAFF_READ = "iam:staff:read";
    public static final String IAM_STAFF_UPDATE = "iam:staff:update";
    public static final String IAM_ROLE_READ = "iam:role:read";

    /** 改角色与角色的功能点。**能改它的人能给自己提权** —— 与只读员工列表必须分开。 */
    public static final String IAM_ROLE_GRANT = "iam:role:grant";

    public static final String IAM_AUDIT_READ = "iam:audit:read";

    // ── 营销 ────────────────────────────────────────────────────────────────
    public static final String MARKETING_COUPON_READ = "marketing:coupon:read";
    public static final String MARKETING_COUPON_UPDATE = "marketing:coupon:update";

    /** 手工发券 —— 直接产生对用户的负债，与改券模板分开。 */
    public static final String MARKETING_COUPON_ISSUE = "marketing:coupon:issue";

    public static final String MARKETING_CAMPAIGN_READ = "marketing:campaign:read";
    public static final String MARKETING_CAMPAIGN_UPDATE = "marketing:campaign:update";

    // ── 商家治理 ────────────────────────────────────────────────────────────
    public static final String MERCHANT_APPLY_AUDIT = "merchant:apply:audit";
    public static final String MERCHANT_READ = "merchant:merchant:read";

    /**
     * 违规处置与封禁（含归档）。
     *
     * <p><b>细化前它与「只读商家档案」是同一个码</b>，也就是「能查商家」等于「能封店」。
     * ops-web 那份码表早就把两者分开了，后端分不开 —— 这次改造的起点就是这一条。
     */
    public static final String MERCHANT_BAN = "merchant:merchant:ban";

    public static final String MERCHANT_CATEGORY_READ = "merchant:category:read";

    /**
     * 给某个商家授资质。类目上挂着 {@code required_code}，授资质等于放行一整类商品的准入。
     *
     * <p><b>与 {@link #PRODUCT_CATEGORY_UPDATE} 分开</b>：那个改的是「世界上有哪些资质码」，
     * 这个是「给这家店授哪几个」。第一版把两者归成一个码，自检发现它横跨了两个旧粗码 ——
     * 机械展开时会让只有其中一个的角色白拿另一半。
     */
    public static final String MERCHANT_CATEGORY_GRANT = "merchant:category:grant";

    public static final String MERCHANT_VERIFY_GRANT = "merchant:verify:grant";
    public static final String MERCHANT_MODE_READ = "merchant:mode:read";

    /** 门店经营模式切换。<b>细化前挂在结算下</b> —— 它根本不是结算。 */
    public static final String MERCHANT_MODE_UPDATE = "merchant:mode:update";

    public static final String MERCHANT_ADMISSION_READ = "merchant:admission:read";

    /** 保证金流水、支付额度、准入策略。**没有界面入口**，今天只能走接口。 */
    public static final String MERCHANT_ADMISSION_UPDATE = "merchant:admission:update";

    // ── 消息与客服 ──────────────────────────────────────────────────────────
    public static final String MESSAGE_TEMPLATE_READ = "message:template:read";
    public static final String MESSAGE_TEMPLATE_UPDATE = "message:template:update";
    public static final String MESSAGE_TICKET_READ = "message:ticket:read";

    /**
     * 回复、分派、关闭客服工单。<b>与看工单分开</b>：工单的回复内容会直接发给用户、
     * 署的是平台的名 —— 写错一句和看错一单，代价不在一个量级。
     */
    public static final String MESSAGE_TICKET_HANDLE = "message:ticket:handle";

    // ── 交易订单 ────────────────────────────────────────────────────────────
    public static final String ORDER_READ = "order:order:read";

    /**
     * 人工干预订单状态。<b>与看单分开</b>：看单客服天天做，改单是把系统的判断覆盖掉。
     * 给看单权限时顺手给了改单，等于每个客服都能把任意订单改成已完成。
     */
    public static final String ORDER_MODIFY = "order:order:modify";

    /** 代客下单与代客取消。 */
    public static final String ORDER_PROXY = "order:order:proxy";

    // ── 商品与类目 ──────────────────────────────────────────────────────────
    public static final String PRODUCT_SKU_READ = "product:sku:read";
    public static final String PRODUCT_SKU_AUDIT = "product:sku:audit";
    public static final String PRODUCT_CATEGORY_READ = "product:category:read";

    /**
     * 类目树与资质码字典的维护。
     *
     * <p>与审单件商品分开：类目上挂着 {@code required_code}（经营准入的判据），
     * 改它等于放宽或收紧一整类商品的准入门槛，影响面比审一件商品大一个量级。
     */
    public static final String PRODUCT_CATEGORY_UPDATE = "product:category:update";

    // ── 评价 ────────────────────────────────────────────────────────────────
    public static final String REVIEW_READ = "review:review:read";

    /**
     * 评价裁决与差评申诉。给客服 —— 裁决要看聊天记录与订单，那是客服每天在做的事。
     * 不给商品运营：他审的是商品能不能上架，而裁决差评影响的是<b>一家店的公开评分</b>。
     */
    public static final String REVIEW_AUDIT = "review:review:audit";

    public static final String REVIEW_SCORE_READ = "review:score:read";
    public static final String REVIEW_SCORE_UPDATE = "review:score:update";

    // ── 门店主页 ────────────────────────────────────────────────────────────
    /**
     * 店招公告审核。<b>整个门店主页域曾被记成「后端零实现」，而这两条一直在跑</b> ——
     * 那份三方对齐 review 的这一条是错的。
     *
     * <p>这里故意不拆读写（全表唯一的例外）：待审队列的「读」就是审核动作的一半，
     * 拆出来会得到一个只有审核员用、且必然同时持有的码。
     */
    public static final String STORE_PAGE_AUDIT = "store:page:audit";

    // ── 平台配置与主数据 ────────────────────────────────────────────────────
    public static final String SYSTEM_INDUSTRY_READ = "system:industry:read";

    /**
     * 行业与服务范围开关。<b>与外观/参数配置分开</b>：它改的是「哪些行业能开小微」，
     * 改错的后果是一批商家进件被拒 —— 那是通道规则的落点，不是招商能自行判断的事。
     */
    public static final String SYSTEM_INDUSTRY_UPDATE = "system:industry:update";

    public static final String SYSTEM_THEME_READ = "system:theme:read";

    /** 皮肤与规则文案 —— 下发给 C 端的东西。 */
    public static final String SYSTEM_THEME_UPDATE = "system:theme:update";

    public static final String SYSTEM_PARAM_READ = "system:param:read";

    /**
     * 功能开关、灰度、市场与汇率。改的是<b>全平台的行为</b> ——
     * 汇率错一位、灰度开关拨错一档，是所有人立刻受影响。
     */
    public static final String SYSTEM_PARAM_UPDATE = "system:param:update";

    /**
     * 角色 → 权限码。**对着矩阵 §2.3 的十一个岗位逐条配**。
     *
     * <p><b>本表 2026-08-12 先从 16 个粗码机械展开（阶段 A，等价，零行为变化），
     * 随后收紧了三处（阶段 B）</b>：
     *
     * <ol>
     *   <li>{@link #AFTERSALE_TICKET_HANDLE} 与 {@link #AFTERSALE_REFUND_APPROVE}
     *       原本 7 个角色都有（从 {@code order:view} 带过来的）—— 也就是风控、社区运营、
     *       活动运营都能批退款。收回到客服（裁决是它的本职）与财务（只保留极速退阈值，
     *       它承担资金后果）。<b>读没有一起收</b>：查售后单是排查恶意退款的最低限度，
     *       风险与写不在一个量级 —— 一次收两样会让人分不清是哪一样出的问题。</li>
     *   <li>{@link #MERCHANT_MODE_UPDATE} 从财务挪到 BD —— 经营模式是商家线的事，
     *       它此前落在财务手里只是因为挂在结算的粗码下。</li>
     *   <li>{@link #MERCHANT_BAN} 无需改配置：它只在 BD 手里，而商家治理本来就归 BD。
     *       细化本身就是收益 —— 从今天起它<b>能</b>被单独收回。</li>
     * </ol>
     *
     * <p><b>还有一条改配置解决不了</b>：{@link #FINANCE_SETTLE_EXECUTE}（制单）与
     * {@link #FINANCE_PAYOUT_EXECUTE}（付款）现在都在 FINANCE 一个角色上。
     * 真正的职责分离需要第二个财务岗，那是产品决定，不是权限表能单方面做的。
     * 码已经分开，等那个岗位存在时改一行就行。
     *
     * <p>阶段 A 的等价性由 {@code ops-perm-matrix.test.ts} 验证过（逐格相同）；
     * 阶段 B 让那条守卫变红，红的内容就是上面这三条 —— 审过之后更新的基线。
     *
     * <p>后端角色码与 ops-web 一致；已有的三个（BD / GOODS_OPS / SUPPORT）是历史遗留，
     * 在 ops-web 的 http 层翻译。
     *
     * <p><b>这张表现在是回落表</b>：判权的主路径是
     * {@code RolePermResolver} 读库（{@code sys_role_point → sys_function_point.perm_code}）。
     * 库里查不到这个角色时才用它 —— 「没配」与「配了零权限」必须分开，
     * 后者会让一个本该有权限的岗位静默失权。
     */
    private static final Map<String, List<String>> ROLE_PERMS = Map.ofEntries(
            Map.entry("SUPER_ADMIN", List.of("*")),

            Map.entry("BD", List.of(AFTERSALE_REFUND_READ, AFTERSALE_TICKET_READ,
                    COMMUNITY_READ,
                    DASHBOARD_OVERVIEW_READ, GROUP_DEMAND_ASSIGN, GROUP_DEMAND_READ,
                    MERCHANT_APPLY_AUDIT, MERCHANT_CATEGORY_GRANT, MERCHANT_CATEGORY_READ,
                    MERCHANT_BAN, MERCHANT_READ, MERCHANT_MODE_READ, MERCHANT_MODE_UPDATE,
                    MERCHANT_VERIFY_GRANT, ORDER_READ, STORE_PAGE_AUDIT)),

            Map.entry("GOODS_OPS", List.of(AFTERSALE_REFUND_READ,
                    AFTERSALE_TICKET_READ, COMMUNITY_READ,
                    DASHBOARD_OVERVIEW_READ, GROUP_CAMPAIGN_AUDIT, GROUP_CAMPAIGN_READ,
                    MARKETING_CAMPAIGN_READ, MARKETING_CAMPAIGN_UPDATE, MARKETING_COUPON_ISSUE,
                    MARKETING_COUPON_READ, MARKETING_COUPON_UPDATE, ORDER_READ,
                    PRODUCT_CATEGORY_READ, PRODUCT_CATEGORY_UPDATE, PRODUCT_SKU_AUDIT,
                    PRODUCT_SKU_READ)),

            Map.entry("SUPPORT", List.of(AFTERSALE_REFUND_APPROVE, AFTERSALE_REFUND_READ,
                    AFTERSALE_TICKET_HANDLE, AFTERSALE_TICKET_READ, COMMUNITY_READ,
                    DASHBOARD_OVERVIEW_READ, MESSAGE_TEMPLATE_READ, MESSAGE_TEMPLATE_UPDATE,
                    MESSAGE_TICKET_HANDLE, MESSAGE_TICKET_READ, ORDER_MODIFY, ORDER_PROXY,
                    ORDER_READ, REVIEW_AUDIT, REVIEW_READ, REVIEW_SCORE_READ,
                    REVIEW_SCORE_UPDATE)),

            Map.entry("CAMPAIGN_OPS", List.of(AFTERSALE_REFUND_READ,
                    AFTERSALE_TICKET_READ, COMMUNITY_READ,
                    CONTENT_MATERIAL_AUDIT, CONTENT_MATERIAL_READ, CONTENT_MATERIAL_UPDATE,
                    DASHBOARD_OVERVIEW_READ, GROUP_CAMPAIGN_AUDIT, GROUP_CAMPAIGN_READ,
                    MARKETING_CAMPAIGN_READ, MARKETING_CAMPAIGN_UPDATE, MARKETING_COUPON_ISSUE,
                    MARKETING_COUPON_READ, MARKETING_COUPON_UPDATE, ORDER_READ)),

            Map.entry("COMMUNITY_OPS", List.of(AFTERSALE_REFUND_READ,
                    AFTERSALE_TICKET_READ, COMMUNITY_READ,
                    COMMUNITY_UPDATE, COMMUNITY_PICKUP_READ, COMMUNITY_PICKUP_UPDATE,
                    COMMUNITY_REGION_READ, DASHBOARD_OVERVIEW_READ, ORDER_READ,
                    SYSTEM_INDUSTRY_READ, SYSTEM_INDUSTRY_UPDATE)),

            Map.entry("AUDITOR", List.of(COMMUNITY_READ, CONTENT_MATERIAL_AUDIT,
                    CONTENT_MATERIAL_READ, CONTENT_MATERIAL_UPDATE, PRODUCT_SKU_AUDIT,
                    PRODUCT_SKU_READ, REVIEW_AUDIT, REVIEW_READ, REVIEW_SCORE_READ,
                    REVIEW_SCORE_UPDATE)),

            Map.entry("FINANCE", List.of(AFTERSALE_REFUND_APPROVE, AFTERSALE_REFUND_READ,
                    AFTERSALE_TICKET_READ, DASHBOARD_OVERVIEW_READ,
                    FINANCE_INVOICE_READ, FINANCE_INVOICE_VERIFY, FINANCE_PAYOUT_EXECUTE,
                    FINANCE_RATE_READ, FINANCE_RATE_UPDATE, FINANCE_RECON_READ,
                    FINANCE_RECON_RESOLVE, FINANCE_SETTLE_EXECUTE, FINANCE_SETTLE_READ,
                    MERCHANT_ADMISSION_READ, MERCHANT_ADMISSION_UPDATE, ORDER_READ)),

            /*
             * 风控：矩阵给的是「刷单、异常裂变、恶意退款、黑名单」+ 拦截封禁。
             * 后端一个风控端点都没有，所以这份清单短得不正常 ——
             * 而其中的售后裁决与极速退阈值是从 order:view 带过来的，风控本不该有。
             */
            Map.entry("RISK", List.of(AFTERSALE_REFUND_READ, AFTERSALE_TICKET_READ,
                    DASHBOARD_OVERVIEW_READ, ORDER_READ)),

            /*
             * 数据分析：矩阵写明**只读脱敏**。故意不给 ORDER_READ ——
             * 那个码返回的是完整订单（金额、联系人），与「脱敏」正相反。
             * 真要做明细分析，缺的是一个脱敏读的码，不是把全量读权限发出去。
             */
            Map.entry("ANALYST", List.of(COMMUNITY_READ)),

            /* 技术运维：配置、灰度、日志。环境切换仍然没有端点。 */
            Map.entry("TECH_OPS", List.of(IAM_AUDIT_READ, SYSTEM_PARAM_READ, SYSTEM_PARAM_UPDATE,
                    SYSTEM_THEME_READ, SYSTEM_THEME_UPDATE)));

    private Perms() {
    }

    public static List<String> of(List<String> roles) {
        return roles == null ? List.of() : roles.stream()
                .flatMap(r -> ROLE_PERMS.getOrDefault(r, List.of()).stream())
                .distinct().toList();
    }
}
