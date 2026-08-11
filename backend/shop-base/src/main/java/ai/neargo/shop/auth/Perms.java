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

    private static final Map<String, List<String>> ROLE_PERMS = Map.of(
            "SUPER_ADMIN", List.of("*"),
            // BD 要读社区才能审核（选覆盖小区），但不该改社区主数据
            "BD", List.of(MERCHANT_AUDIT, ORDER_VIEW, COMMUNITY_VIEW, QUOTE_GOVERN),
            "GOODS_OPS", List.of(GOODS_AUDIT, CATEGORY_MANAGE, ORDER_VIEW, COMMUNITY_VIEW),
            "SUPPORT", List.of(ORDER_VIEW, REVIEW_GOVERN, ORDER_INTERVENE, COMMUNITY_VIEW,
                    TICKET_HANDLE));

    private Perms() {
    }

    public static List<String> of(List<String> roles) {
        return roles == null ? List.of() : roles.stream()
                .flatMap(r -> ROLE_PERMS.getOrDefault(r, List.of()).stream())
                .distinct().toList();
    }
}
