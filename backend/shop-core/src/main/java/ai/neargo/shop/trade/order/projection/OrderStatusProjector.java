package ai.neargo.shop.trade.order.projection;

import ai.neargo.shop.trade.order.contract.ContractOrderStatus;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;

import java.util.Comparator;
import java.util.List;

/**
 * 库状态 → <b>对外契约状态</b>的投影与聚合（订单服务 2.0 的唯一处）。
 *
 * <p>收敛现状散在三处的逻辑：{@code OrderStatusView.toContract}（名字对齐）、
 * {@code OrderServiceImpl.payView} 的 headStatus（主单聚合）、端上 orderView。
 * 并修正两处现状缺陷：
 * <ul>
 *   <li><b>主单 {@code CLOSED} 不再原样下发</b>：超时关闭的订单对外是「已取消」（Q6）——
 *       现状 payView 直发 {@code CLOSED}，而三端类型/词条都没有它，导致 ops-web 徽标抛错、
 *       C 端详情头直出键名。</li>
 *   <li><b>跨商家聚合口径定死</b>（Q12）：付款后订单总状态 = 最落后的活跃子单，
 *       现状注释自称「聚合口径未定、回落 PAID」。</li>
 * </ul>
 *
 * <p>纯函数、无 IO。输出一律落在 {@link ContractOrderStatus#ALL} 内（封闭性由测试枚举全集保证）。
 */
public final class OrderStatusProjector {

    private OrderStatusProjector() {
    }

    /**
     * 单包裹（子单详情 / 单商家订单）投影：库的子单状态 + 主单状态 → 契约状态。
     *
     * @param subStatus  子单库状态（可为 null，视作尚未落子单 = 待付）
     * @param mainStatus 主单库状态
     */
    public static String contract(String subStatus, String mainStatus) {
        // 主单超时关闭：对外统一「已取消」（子单本就落 CANCELLED，口径一致）
        if (OrdOrder.CLOSED.equals(mainStatus)) {
            return ContractOrderStatus.CANCELLED;
        }
        if (OrdOrder.POINTS_PENDING.equals(mainStatus)) {
            return ContractOrderStatus.POINTS_PROCESSING;
        }
        // 线下支付等当面收款：主单说了算，子单那一列不动
        if (OrdOrder.WAIT_OFFLINE_PAY.equals(mainStatus)
                && ContractOrderStatus.WAIT_PAY.equals(subContract(subStatus))) {
            return ContractOrderStatus.WAIT_OFFLINE_PAY;
        }
        return subContract(subStatus);
    }

    /**
     * 订单（可能多子单）对外总状态 = 主单状态 + 全部子单状态的聚合。
     *
     * <p>付款前（主单非 PAID）总状态跟主单；付款后（主单 PAID，终态）聚合子单：
     * 取<b>最落后的活跃子单</b>；全部子单终态时，有退款则 REFUNDED，否则 CANCELLED。
     *
     * @param mainStatus   主单库状态
     * @param subStatuses  全部子单库状态（非空；单商家单即一个元素）
     */
    public static String aggregate(String mainStatus, List<String> subStatuses) {
        if (!OrdOrder.PAID.equals(mainStatus)) {
            // 付款前/取消/关闭：订单总状态由主单决定
            if (OrdOrder.CLOSED.equals(mainStatus)) {
                return ContractOrderStatus.CANCELLED;
            }
            if (OrdOrder.POINTS_PENDING.equals(mainStatus)) {
                return ContractOrderStatus.POINTS_PROCESSING;
            }
            if (OrdOrder.WAIT_OFFLINE_PAY.equals(mainStatus)) {
                return ContractOrderStatus.WAIT_OFFLINE_PAY;
            }
            if (OrdOrder.CANCELLED.equals(mainStatus)) {
                return ContractOrderStatus.CANCELLED;
            }
            return ContractOrderStatus.WAIT_PAY;
        }
        // 付款后：聚合子单。活跃 = 未取消未退款
        List<String> active = subStatuses.stream()
                .map(OrderStatusProjector::subContract)
                .filter(s -> !ContractOrderStatus.CANCELLED.equals(s)
                        && !ContractOrderStatus.REFUNDED.equals(s))
                .toList();
        if (!active.isEmpty()) {
            return active.stream()
                    .min(Comparator.comparingInt(OrderStatusProjector::rank))
                    .orElseThrow();
        }
        // 全部终态：有退款显示退款，否则取消
        boolean anyRefunded = subStatuses.stream().anyMatch(ContractOrderStatus.REFUNDED::equals);
        return anyRefunded ? ContractOrderStatus.REFUNDED : ContractOrderStatus.CANCELLED;
    }

    /** 子单库状态 → 契约状态（名字对齐）：null→待付；WAIT_FULFILL→PAID；其余原样。 */
    private static String subContract(String subStatus) {
        if (subStatus == null) {
            return ContractOrderStatus.WAIT_PAY;
        }
        return OrdSubOrder.WAIT_FULFILL.equals(subStatus) ? ContractOrderStatus.PAID : subStatus;
    }

    /** 活跃状态推进序：越小越落后。聚合取最落后，于是「一家没发货，整单显示待发货」。 */
    private static int rank(String contractStatus) {
        return switch (contractStatus) {
            case ContractOrderStatus.WAIT_PAY, ContractOrderStatus.WAIT_OFFLINE_PAY -> 0;
            case ContractOrderStatus.PAID -> 1;
            case ContractOrderStatus.FULFILLING -> 2;
            case ContractOrderStatus.COMPLETED -> 3;
            default -> 99;
        };
    }
}
