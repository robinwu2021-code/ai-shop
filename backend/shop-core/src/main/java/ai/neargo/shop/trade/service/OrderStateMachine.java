package ai.neargo.shop.trade.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;

import java.util.Map;
import java.util.Set;

/**
 * 订单状态机。**唯一一处允许判断「这个状态能不能变成那个状态」的地方**。
 *
 * <p>没有它，`if (status == ...)` 会散落在每个 Service 方法里，
 * 于是「已支付的订单被取消」「已取消的订单被支付回调改成已支付」这类问题只能靠人肉 review 发现。
 *
 * <p>特别地：**回调只推进、不回退**。支付回调乱序到达时（先到 SUCCESS 后到 TIMEOUT），
 * 先到的终态胜出 —— 这条由 {@link #canTransit} 表达：{@code PAID} 不在 {@code CANCELLED} 的可达集里。
 */
public final class OrderStateMachine {

    /** 主单允许的迁移。 */
    private static final Map<String, Set<String>> ORDER = Map.of(
            OrdOrder.WAIT_PAY, Set.of(OrdOrder.PAID, OrdOrder.CANCELLED, OrdOrder.CLOSED),
            OrdOrder.PAID, Set.of(),          // 已支付不再有主单级迁移，后续变化都在子单
            OrdOrder.CANCELLED, Set.of(),
            OrdOrder.CLOSED, Set.of());

    /** 子单允许的迁移。 */
    private static final Map<String, Set<String>> SUB_ORDER = Map.of(
            /*
             * 直接到 FULFILLING 是**服务类履约**（到店核销）：付款即出码，
             * 买家立刻能用，商家没有备货发货这一步。
             * 实物类仍走 WAIT_FULFILL —— 差别在履约方式，不在状态。
             */
            OrdSubOrder.WAIT_PAY,
            Set.of(OrdSubOrder.WAIT_FULFILL, OrdSubOrder.FULFILLING, OrdSubOrder.CANCELLED),
            OrdSubOrder.WAIT_FULFILL, Set.of(OrdSubOrder.FULFILLING, OrdSubOrder.COMPLETED, OrdSubOrder.REFUNDED),
            OrdSubOrder.FULFILLING, Set.of(OrdSubOrder.COMPLETED, OrdSubOrder.REFUNDED),
            OrdSubOrder.COMPLETED, Set.of(OrdSubOrder.REFUNDED),   // 售后可以发生在完成之后
            OrdSubOrder.CANCELLED, Set.of(),
            OrdSubOrder.REFUNDED, Set.of());

    /** 售后状态机（A2 §3.3）。终态：REFUNDED / REJECTED / CLOSED。 */
    private static final Map<String, Set<String>> AFTER_SALE = Map.of(
            "APPLIED", Set.of("REFUNDING", "REFUNDED", "REJECTED", "CLOSED"),
            "REFUNDING", Set.of("REFUNDED", "CLOSED"),
            // 驳回后用户可申诉；申诉由平台裁决，可能退也可能关闭
            "REJECTED", Set.of("ARBITRATING", "CLOSED"),
            "ARBITRATING", Set.of("REFUNDING", "REFUNDED", "CLOSED"),
            "REFUNDED", Set.of(),
            "CLOSED", Set.of());

    private OrderStateMachine() {
    }

    public static void assertAfterSaleTransit(String from, String to) {
        assertTransit(AFTER_SALE, from, to);
    }

    public static Map<String, Set<String>> afterSaleGraph() {
        return AFTER_SALE;
    }

    public static void assertOrderTransit(String from, String to) {
        assertTransit(ORDER, from, to);
    }

    public static void assertSubOrderTransit(String from, String to) {
        assertTransit(SUB_ORDER, from, to);
    }

    /**
     * <b>商家发起</b>的子单迁移。比 {@link #assertSubOrderTransit} 多一条：<b>不能从未付款出发</b>。
     *
     * <p>为什么要单开一个方法：{@code WAIT_PAY → FULFILLING} 这条边是**支付回调**要用的
     * （服务类履约付款即出码，没有备货发货这一步）。但同一条边一旦进了图，
     * 商家侧「发货」也就能从未付款直接推过去 —— 货先出去了，钱还没收。
     *
     * <p>「谁发起」这件事图本身表达不了：同一对 (from, to)，系统走得通、人走不通。
     * 与其把发起方编进状态名（WAIT_PAY_BY_SYSTEM…），不如在入口分两个断言。
     *
     * <p>这条闸是被 {@code OrderStateMachineTest.cannotFulfillBeforePaid} 挡下来的：
     * 加那条支付边时它立刻变红，而它守的正是「商家看着订单列表就去发货、
     * 那一单其实没付款」这个在真实世界发生过的事。
     */
    public static void assertMerchantSubOrderTransit(String from, String to) {
        if (OrdSubOrder.WAIT_PAY.equals(from) && !OrdSubOrder.WAIT_PAY.equals(to)) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        assertTransit(SUB_ORDER, from, to);
    }

    /** 幂等友好：目标状态与当前一致视为合法空操作，回调重放不会抛错。 */
    public static boolean canTransit(Map<String, Set<String>> graph, String from, String to) {
        return from.equals(to) || graph.getOrDefault(from, Set.of()).contains(to);
    }

    private static void assertTransit(Map<String, Set<String>> graph, String from, String to) {
        if (!canTransit(graph, from, to)) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
    }

    public static Map<String, Set<String>> orderGraph() {
        return ORDER;
    }

    public static Map<String, Set<String>> subOrderGraph() {
        return SUB_ORDER;
    }
}
