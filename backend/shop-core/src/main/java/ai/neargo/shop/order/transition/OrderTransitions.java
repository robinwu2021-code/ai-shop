package ai.neargo.shop.order.transition;

import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.service.OrderStateMachine;

import java.time.LocalDateTime;

/**
 * 订单状态<b>唯一写入门</b>（订单服务 2.0）。所有 {@code setStatus} 都应经这里：
 * 状态机断言 → 写状态 → 写流水。收口现状散落 5 处的裸 {@code setStatus}
 * （closeOne / autoConfirmReceipt / markArrived / complete 的降级分支 / intervene），
 * 接线阶段由 ArchUnit 守卫"除本类外不得出现 setStatus(状态)"。
 *
 * <p>本阶段（strangler 第一步）：建好 + 纯逻辑单测，<b>暂不替换</b>现有 5 处裸写入，
 * 不接请求链路。持久化经 {@link OrderStateStore}（便于单测用 fake）。
 *
 * <p>本阶段<b>不发状态事件</b>：事件发布机制在接线阶段接入（避免现在引入跨模块依赖）。
 * 主单状态变更不写 {@code ord_status_log}（日志是子单粒度，与现状一致）。
 */
public class OrderTransitions {

    private static final String TENANT = "MAIN";

    private final OrderStateStore store;

    public OrderTransitions(OrderStateStore store) {
        this.store = store;
    }

    /** 主单迁移：断言 → 写状态。{@code from==to} 为幂等空操作（不写）。 */
    public void applyOrder(OrdOrder order, String to, Operator op, String reason) {
        String from = order.getStatus();
        OrderStateMachine.assertOrderTransit(from, to);
        if (to.equals(from)) {
            return;
        }
        order.setStatus(to);
        store.saveOrderStatus(order);
    }

    /** 子单迁移（系统/买家侧）：断言 → 写状态 → 写流水。 */
    public void applySub(OrdSubOrder sub, String to, Operator op, String reason) {
        transitSub(sub, to, op, reason, false);
    }

    /** 子单迁移（<b>商家发起</b>）：多一条"不能从未付款出发"的商家闸。 */
    public void applyMerchantSub(OrdSubOrder sub, String to, Operator op, String reason) {
        transitSub(sub, to, op, reason, true);
    }

    private void transitSub(OrdSubOrder sub, String to, Operator op, String reason, boolean merchant) {
        String from = sub.getStatus();
        if (merchant) {
            OrderStateMachine.assertMerchantSubOrderTransit(from, to);
        } else {
            OrderStateMachine.assertSubOrderTransit(from, to);
        }
        if (to.equals(from)) {
            return;   // 幂等空操作：不写状态、不重复记流水
        }
        sub.setStatus(to);
        store.saveSubStatus(sub);
        store.appendLog(buildLog(sub.getSubOrderNo(), to, op, reason));
    }

    private OrdStatusLog buildLog(String subOrderNo, String status, Operator op, String reason) {
        OrdStatusLog row = new OrdStatusLog();
        row.setSubOrderNo(subOrderNo);
        row.setStatus(status);
        row.setLabel(reason);
        row.setOperatorType(op == null ? OrdStatusLog.BY_SYSTEM : op.type());
        row.setOperatorNo(op == null ? null : op.no());
        row.setAt(System.currentTimeMillis());
        row.setTenantNo(TENANT);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }
}
