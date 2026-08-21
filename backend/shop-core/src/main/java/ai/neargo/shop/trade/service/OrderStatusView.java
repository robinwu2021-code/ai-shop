package ai.neargo.shop.trade.service;

import ai.neargo.shop.trade.entity.OrdSubOrder;

import java.util.List;

/**
 * 库状态 ↔ 契约状态的<b>名字对齐</b>。只做这一件事。
 *
 * <p><b>它曾经做的是另一件事</b>：库状态 × 履约方式 → 展示状态，
 * 产出 {@code ARRIVED} / {@code SHIPPED} 这类词。那个方向解决了真问题
 * （此前三端各写一套词且互不相同，页签一条也匹配不到），
 * 但把乘法的结果命名成了<b>状态</b> —— 于是状态集合会跟着履约数涨：
 * 接服务履约时差点又要加 {@code TO_USE} / {@code TO_SERVE}，
 * 再来一种「同城闪送」还要再加。
 *
 * <p>现在的模型（《订单状态-统一整理》）：<b>状态集合封闭、履约集合开放</b>。
 * 后端下发抽象状态 + 履约方式两个正交的字段，
 * 展示由端上 {@code orderView(status, fulfillment, info)} 决定，三端共用一份。
 * 页签因此变成<b>谓词</b>（状态 + 履约集合），加一种履约不动状态。
 */
public final class OrderStatusView {

    /** 待付款 */
    public static final String WAIT_PAY = "WAIT_PAY";
    /** 已付款、等交付方行动。库里叫 {@code WAIT_FULFILL}，同一件事 */
    public static final String PAID = "PAID";
    /**
     * 交付方已行动，等交接完成。<b>显示成什么由履约方式决定</b>，不再拆成两个状态。
     *
     * <p>写成字面量而不是 {@code OrdSubOrder.FULFILLING} 的引用：枚举对账扫的是
     * 字面量，写成引用它就看不见 —— 而「看不见」与「两端不一致」在报告里长得一样。
     */
    public static final String FULFILLING = "FULFILLING";
    /*
     * ⚠️ 这里曾有 `SHIPPED`（已发货）与 `ARRIVED`（已到自提点）两个常量。
     * 它们**不是状态**，是 `FULFILLING × 履约方式` 的组合冒充状态 ——
     * 代价是每加一种履约就要加一批（服务类差点又加了 TO_USE / TO_SERVE）。
     * 2026-08-17 三端一起迁到「抽象状态 + 履约方式」后删除。
     */
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String REFUNDED = "REFUNDED";

    private OrderStatusView() {
    }

    /**
     * 库状态 → <b>契约状态</b>（抽象，与履约无关）。
     *
     * <p>只做一件事：把库里的 {@code WAIT_FULFILL} 归一成端上契约的 {@code PAID}
     * （同一件事的两个名字），其余原样。<b>不再乘履约方式</b> ——
     * 那个乘法的结果（{@code ARRIVED} / {@code SHIPPED}）不是状态，
     * 是组合冒充状态，每加一种履约就要加一批。
     *
     * <p>展示由端上的 {@code orderView(status, fulfillment, info)} 决定，
     * 三端共用一份映射。见《订单状态-统一整理》。
     */
    public static String toContract(String status) {
        if (status == null) {
            return WAIT_PAY;
        }
        return OrdSubOrder.WAIT_FULFILL.equals(status) ? PAID : status;
    }


    /**
     * 反向：端上传来的展示状态 → 该查库里的哪些状态。
     *
     * <p>返回空集合表示<b>这个词不对应任何库状态</b>，调用方应当当作「不过滤」处理 ——
     * 而不是查出零条。未知筛选值让列表变空，看起来就是「一件都没有」，
     * 而真相是端上传了一个后端不认识的词（这正是这次要修的那个 bug 的形状）。
     */
    public static List<String> toStored(String view) {
        if (view == null || view.isBlank()) {
            return List.of();
        }
        return switch (view) {
            case PAID -> List.of(OrdSubOrder.WAIT_FULFILL);
            // FULFILLING 既是契约状态也是库状态，同名同义
            case OrdSubOrder.FULFILLING -> List.of(OrdSubOrder.FULFILLING);
            case WAIT_PAY, COMPLETED, CANCELLED, REFUNDED -> List.of(view);
            // 兼容直接传库状态的调用方（后端自己的测试、运维排查）
            case OrdSubOrder.WAIT_FULFILL -> List.of(OrdSubOrder.WAIT_FULFILL);
            default -> List.of();
        };
    }
}
