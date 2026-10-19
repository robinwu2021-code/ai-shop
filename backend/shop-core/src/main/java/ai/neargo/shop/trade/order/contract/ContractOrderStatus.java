package ai.neargo.shop.trade.order.contract;

import java.util.Set;

/**
 * 订单<b>对外契约状态</b>的唯一值域（订单服务 2.0）。
 *
 * <p>封闭集合：三端前端只认这 7 个值。库里的状态（{@code OrdOrder} / {@code OrdSubOrder}）
 * 经 {@link ai.neargo.shop.trade.order.projection.OrderStatusProjector} 投影到这里，
 * 任何库状态组合的投影结果都必须落在 {@link #ALL} 内（见 Projector 的封闭性测试）。
 *
 * <p>与 {@code trade.service.OrderStatusView} 的常量同义；此处是 2.0 契约层的自包含定义，
 * 不引用 trade 包，避免演进期两边互相牵动。{@code SHIPPED}/{@code ARRIVED} 不在其中 ——
 * 它们是「状态 × 履约」的展示组合，由端上 {@code orderView} 派生，不是状态。
 */
public final class ContractOrderStatus {

    /** 待付款 */
    public static final String WAIT_PAY = "WAIT_PAY";
    public static final String POINTS_PROCESSING = "POINTS_PROCESSING";
    /** 待当面收款（线下支付，由主单状态推出；库里子单没有这个值） */
    public static final String WAIT_OFFLINE_PAY = "WAIT_OFFLINE_PAY";
    /** 已付款、等交付方行动（库里子单叫 WAIT_FULFILL） */
    public static final String PAID = "PAID";
    /** 交付方已行动、等交接完成（显示成「已发货/已到自提点/待服务」由履约方式派生） */
    public static final String FULFILLING = "FULFILLING";
    /** 已完成 */
    public static final String COMPLETED = "COMPLETED";
    /** 已取消（含主单超时关闭 CLOSED 的对外口径，Q6） */
    public static final String CANCELLED = "CANCELLED";
    /** 已退款 */
    public static final String REFUNDED = "REFUNDED";

    /** 全部契约状态。投影/聚合的结果必须落在其中。 */
    public static final Set<String> ALL = Set.of(
            WAIT_PAY, POINTS_PROCESSING, WAIT_OFFLINE_PAY, PAID, FULFILLING, COMPLETED, CANCELLED, REFUNDED);

    private ContractOrderStatus() {
    }
}
