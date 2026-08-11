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

    /**
     * 评价治理与差评裁决。给客服 —— 裁决要看聊天记录与订单，那是客服每天在做的事。
     *
     * <p>不给商品运营：他审的是商品能不能上架，而裁决差评影响的是**一家店的公开评分**，
     * 两件事出错的后果完全不同（一个是少卖一件货，一个是一家店的口碑）。
     */
    public static final String REVIEW_GOVERN = "review:govern";

    /**
     * 人工干预订单状态（含代客取消）。
     *
     * <p>与 {@link #ORDER_VIEW} 分开：看单和改单是两件事 —— 前者客服天天做，
     * 后者是把系统的判断覆盖掉。给客服看单的权限时顺手给了改单，
     * 等于每个客服都能把任意订单改成已完成。
     */
    public static final String ORDER_INTERVENE = "order:intervene";

    /**
     * **读**社区列表。与 {@link #INDUSTRY_MANAGE} 分开，因为它们是两件事：
     * 维护主数据（改规则）不该给 BD，但**入驻审核必须先选覆盖社区**，
     * 而选之前得先能读到列表。
     *
     * <p>合在一起的代价是实测出来的：BD 打开审核抽屉，「覆盖小区」下一个选项都没有，
     * 而通过前又强制要求选一个 —— **招商的日常流程被自己的权限卡死**，
     * 页面上也看不出是权限问题（列表就是空的，没有任何提示）。
     *
     * <p>读写分开是这类问题的通解：一个权限码同时管「能看」和「能改」时，
     * 只要有任何一个角色需要看而不需要改，它就会被迫多拿一份不该有的写权限，
     * 或者像这次一样，干脆做不了自己的本职工作。
     */
    public static final String COMMUNITY_VIEW = "community:view";

    /**
     * 处理客服工单：回复、分派、关闭。
     *
     * <p>与 {@link #ORDER_VIEW} 分开的理由和订单那对一样：**看单和回话是两件事**。
     * 工单的回复内容会**直接发给用户**，署的是平台的名——写错一句和看错一单，
     * 代价不在一个量级。
     *
     * <p>给客服（SUPPORT）。BD 与商品运营不给：他们看不到用户的完整上下文，
     * 回出来的话大概率要再被客服纠正一次。
     */
    public static final String TICKET_HANDLE = "ticket:handle";

    /**
     * 报价治理：平台改价与判定毁约（P-8.2.4 / P-8.2.5）。
     *
     * <p>给 BD —— 报价发生在招商拉来的商家与用户之间，纠纷也由他们跟进。
     * <b>不给客服</b>：判毁约会写进商家信用档案、影响后续准入，
     * 那是招商侧要承担后果的判断，不是接一通电话就能下的结论。
     */
    public static final String QUOTE_GOVERN = "quote:govern";

    /**
     * 营销治理：中止违规拼团、停发问题优惠券（P-8.1）。
     *
     * <p>给商品运营 —— 判断「这个团/这张券对不对」要看的是商品与价格，
     * 那是他每天在看的东西。
     *
     * <p>这类「平台兜底干预」的权限容易被排在后面，因为正常流程不需要它。
     * 但它恰恰是**出事时唯一的手段**，而出事是迟早的。
     */
    public static final String MARKETING_GOVERN = "marketing:govern";

    /**
     * 结算与应付账款：对账确认、登记付款、标记无票。
     *
     * <p><b>只给超管与财务岗。</b>这三个动作直接对应真金白银出账——登记付款虽然不划转资金，
     * 但它是财务在网银付款的依据；标记无票则意味着接受「这笔支出不能税前列支」。
     * 与 {@link #ORDER_VIEW} 这类查看权限不同，它没有「顺手给一下」的空间。
     */
    public static final String SETTLE_MANAGE = "settle:manage";

    /**
     * 平台自身的配置：皮肤下发、功能开关与灰度、规则文案、市场与汇率。
     *
     * <p><b>与 {@link #INDUSTRY_MANAGE} 分开</b>：那个管的是「哪些行业能开小微」，
     * 影响面是新入驻的商家；而这一个改的是<b>全平台的行为</b> ——
     * 汇率错一位、灰度开关拨错一档，是所有人立刻受影响。
     * 共用一把钥匙意味着「能维护行业主数据」的人顺手就能改汇率。
     */
    public static final String PLATFORM_CONFIG = "platform:config";

    /**
     * 角色 → 权限码。**对着矩阵 §2.3 的十一个岗位逐条配**。
     *
     * <p>此前只有四个，而 ops-web 的角色下拉有十一个 —— 差的那七个不是前端多造的，
     * 是后端少做的：矩阵里一个不多一个不少，砍前端等于砍需求。
     *
     * <p><b>只配后端真有的码</b>。风控的「拦截/黑名单」、技术运维的「灰度/环境切换」、
     * 数据分析的「脱敏读」在后端都还没有对应能力 —— 那几个角色因此拿到的是
     * 一份很短的清单，并在下面逐条写明少的是什么。<b>凭空映射到一个语义相近的现有码，
     * 会让「这个角色能干什么」在权限表上看着是满的，而实际上什么都点不动</b>；
     * 更坏的是可能顺手给出远超职责的权限（风控要封禁，就把 merchant:audit 给它，
     * 于是风控还能批入驻）。
     *
     * <p>后端角色码与 ops-web 一致，新加的七个不再起别名 —— 已有的三个
     * （BD / GOODS_OPS / SUPPORT）是历史遗留，在 ops-web 的 http 层翻译。
     */
    private static final Map<String, List<String>> ROLE_PERMS = Map.ofEntries(
            Map.entry("SUPER_ADMIN", List.of("*")),
            // BD 要读社区才能审核（选覆盖小区），但不该改社区主数据
            Map.entry("BD", List.of(MERCHANT_AUDIT, ORDER_VIEW, COMMUNITY_VIEW, QUOTE_GOVERN)),
            Map.entry("GOODS_OPS", List.of(GOODS_AUDIT, CATEGORY_MANAGE, ORDER_VIEW, COMMUNITY_VIEW,
                    MARKETING_GOVERN)),
            Map.entry("SUPPORT", List.of(ORDER_VIEW, REVIEW_GOVERN, ORDER_INTERVENE, COMMUNITY_VIEW,
                    TICKET_HANDLE)),

            // ── 以下七个是这次补的（矩阵 §2.3 有、后端此前没有）──

            /*
             * 活动运营：券、满减、限时、拼团。要看单 —— 判断一个活动对不对，
             * 得看它实际产生了什么订单，只看活动配置看不出「一分钱买走一百件」。
             * 不给 CATEGORY_MANAGE：活动挂在商品上，改类目树是商品运营的事。
             */
            Map.entry("CAMPAIGN_OPS", List.of(MARKETING_GOVERN, ORDER_VIEW, COMMUNITY_VIEW)),

            /*
             * 社区运营：社区网格、自提点建档与启停 —— 那些端点用的都是 INDUSTRY_MANAGE。
             * 履约调度要看单，所以带 ORDER_VIEW。
             */
            Map.entry("COMMUNITY_OPS", List.of(INDUSTRY_MANAGE, COMMUNITY_VIEW, ORDER_VIEW)),

            /*
             * 审核员：商品、评价、内容、凭证。**不给 MERCHANT_AUDIT** ——
             * 商家资质与入驻审核在矩阵里归 BD，那条线要承担后续的商家关系，
             * 与「这张图能不能过」不是一回事。
             */
            Map.entry("AUDITOR", List.of(GOODS_AUDIT, REVIEW_GOVERN, COMMUNITY_VIEW)),

            /*
             * 财务/结算：矩阵里它的高危权限就是打款与分账。
             * SETTLE_MANAGE 的注释此前写着「只给超管」—— 那是四个角色时代的写法，
             * 财务岗一旦存在，它本来就该是这个码的主人（见该常量的说明）。
             */
            Map.entry("FINANCE", List.of(SETTLE_MANAGE, ORDER_VIEW)),

            /*
             * 风控：矩阵给的是「刷单、异常裂变、恶意退款、黑名单」+ 拦截封禁。
             * 后端**一个风控端点都没有**（无黑名单、无规则、无拦截），
             * 所以这里只能给「看单」——它是排查刷单的最低限度。
             * 补齐风控域时，新码加在这里，而不是把 merchant:audit 挪过来充数。
             */
            Map.entry("RISK", List.of(ORDER_VIEW)),

            /*
             * 数据分析：矩阵写明**只读脱敏**。
             * **故意不给 ORDER_VIEW** —— 那个码返回的是完整订单（金额、联系人），
             * 与「脱敏」正相反。看板类端点本来就不受权限约束，分析岗照常能用；
             * 真要做明细分析，缺的是一个脱敏读的码，不是把全量读权限发出去。
             */
            Map.entry("ANALYST", List.of(COMMUNITY_VIEW)),

            /*
             * 技术运维：矩阵给的是配置、灰度、日志、环境切换。
             * 矩阵那一行（配置、灰度、日志）现在对上了两条：审计日志与平台配置。
             * 环境切换仍然没有端点（ops-web 里 system:env:switch 还标着 UNIMPLEMENTED）。
             */
            Map.entry("TECH_OPS", List.of(AUDIT_LOG_VIEW, PLATFORM_CONFIG)));

    private Perms() {
    }

    public static List<String> of(List<String> roles) {
        return roles == null ? List.of() : roles.stream()
                .flatMap(r -> ROLE_PERMS.getOrDefault(r, List.of()).stream())
                .distinct().toList();
    }
}
