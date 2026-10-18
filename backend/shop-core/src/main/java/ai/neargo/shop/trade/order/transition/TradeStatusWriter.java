package ai.neargo.shop.trade.order.transition;

import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleContext;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleEvent;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleMachine;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleStatus;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.StatusLogMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.order.points.OrderPointsClosureFlowService;
import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import ai.neargo.shop.trade.order.machine.order.OrderContext;
import ai.neargo.shop.trade.order.machine.order.OrderEvent;
import ai.neargo.shop.trade.order.machine.order.OrderMachine;
import ai.neargo.shop.trade.order.machine.order.OrderStatus;
import ai.neargo.shop.trade.order.machine.payment.PaymentContext;
import ai.neargo.shop.trade.order.machine.payment.PaymentEvent;
import ai.neargo.shop.trade.order.machine.payment.PaymentMachine;
import ai.neargo.shop.trade.order.machine.payment.PaymentStatus;
import ai.neargo.shop.trade.order.mapper.StatusCasMapper;
import ai.neargo.shop.trade.order.statemachine.Actor;
import ai.neargo.shop.trade.order.statemachine.Fired;
import ai.neargo.shop.trade.order.transition.impl.MybatisStatusStore;
import ai.neargo.shop.trade.order.transition.impl.StatusLogStore;
import org.springframework.stereotype.Component;

/**
 * 业务服务用的状态写入口（TDD-订单状态机 §2B）：按实体执行一次迁移，经转换门 CAS 落库 + 记流水。
 *
 * <p><b>成功后把实体的 status / version 同步到库里的新值</b>。实体继承 {@link BaseEntity}，带 {@code @Version}：
 * 门的 CAS 已把 version 加 1，调用方接着 {@code updateById} 写其它字段时若还拿着旧 version，
 * 乐观锁会让那条 UPDATE 静默影响 0 行 —— 不报错，字段就是没写进去。
 *
 * <p>流水由门写（label = reason），调用方不要再手写 {@code appendStatusLog}，否则时间线重复。
 */
@Component
public class TradeStatusWriter {

    private final StateTransitionGate gate;
    private final StatusCasMapper casMapper;
    private OrderMapper orderMapper;
    private OrderPointsClosureFlowService pointsClosure;

    public TradeStatusWriter(StatusCasMapper casMapper, StatusLogMapper logMapper) {
        this.casMapper = casMapper;
        this.gate = new StateTransitionGate(new MybatisStatusStore(casMapper), new StatusLogStore(logMapper),
                SignalPublisher.NONE);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPointsClosure(OrderMapper orders, OrderPointsClosureFlowService closure) {
        this.orderMapper = orders;
        this.pointsClosure = closure;
    }

    /** 支付单迁移。流水锚在支付单号上（不进子单时间线） */
    public Fired<PaymentStatus, PaymentEvent> payment(OrdOrder order, PaymentEvent event, Actor actor,
                                                      String operatorNo, String reason, PaymentContext ctx) {
        Fired<PaymentStatus, PaymentEvent> f = gate.apply(PaymentMachine.MACHINE, PaymentStatus.class,
                new Command<>(Aggregate.PAYMENT, order.getOrderNo(), order.getOrderNo(), event, actor,
                        operatorNo, reason, ctx));
        sync(order, f.changed());
        order.setStatus(f.to().name());
        return f;
    }

    /** 订单（子单）迁移。流水锚在子单号上（进订单时间线） */
    public Fired<OrderStatus, OrderEvent> order(OrdSubOrder sub, OrderEvent event, Actor actor,
                                                String operatorNo, String reason, OrderContext ctx) {
        Fired<OrderStatus, OrderEvent> f = gate.apply(OrderMachine.MACHINE, OrderStatus.class,
                new Command<>(Aggregate.ORDER, sub.getSubOrderNo(), sub.getSubOrderNo(), event, actor,
                        operatorNo, reason, ctx));
        sync(sub, f.changed());
        sub.setStatus(f.to().name());
        if (f.changed() && f.to() == OrderStatus.COMPLETED) {
            // 售后期起算点（TDD-订单状态机 AC21）。所有到 COMPLETED 的路径都经这里（步 4）
            long now = System.currentTimeMillis();
            ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(
                    () -> casMapper.markOrderCompleted(sub.getSubOrderNo(), now));
            sub.setCompletedAt(now);
            if (orderMapper != null && pointsClosure != null && sub.getOrderNo() != null) {
                var root = DataScopeContext.executeWithoutScope(() -> orderMapper.selectOne(
                        Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getOrderNo, sub.getOrderNo())));
                if (root != null && Boolean.TRUE.equals(root.getPointsIntegrated()))
                    pointsClosure.finalizeSubOrder(sub.getSubOrderNo());
            }
        }
        return f;
    }

    /** 售后单迁移（步 5.6）。流水锚在子单号上（进订单时间线），取代手写的 setStatus + appendLog */
    public Fired<AfterSaleStatus, AfterSaleEvent> afterSale(OrdAfterSale as, AfterSaleEvent event, Actor actor,
                                                            String operatorNo, String reason, AfterSaleContext ctx) {
        Fired<AfterSaleStatus, AfterSaleEvent> f = gate.apply(AfterSaleMachine.MACHINE, AfterSaleStatus.class,
                new Command<>(Aggregate.AFTER_SALE, as.getAfterSaleNo(), as.getSubOrderNo(), event, actor,
                        operatorNo, reason, ctx));
        sync(as, f.changed());
        as.setStatus(f.to().name());
        return f;
    }

    private static void sync(BaseEntity e, boolean changed) {
        if (changed && e.getVersion() != null) {
            e.setVersion(e.getVersion() + 1);
        }
    }
}
