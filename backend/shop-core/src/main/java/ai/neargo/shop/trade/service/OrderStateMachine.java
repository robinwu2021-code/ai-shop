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
            OrdSubOrder.WAIT_PAY, Set.of(OrdSubOrder.WAIT_FULFILL, OrdSubOrder.CANCELLED),
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
