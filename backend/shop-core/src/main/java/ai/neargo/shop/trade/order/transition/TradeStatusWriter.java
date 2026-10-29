package ai.neargo.shop.trade.order.transition;

import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleContext;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleEvent;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleMachine;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleStatus;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.machine.shipment.ShipmentContext;
import ai.neargo.shop.trade.order.machine.shipment.ShipmentEvent;
import ai.neargo.shop.trade.order.machine.shipment.ShipmentMachine;
import ai.neargo.shop.trade.order.machine.shipment.ShipmentStatus;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.mapper.OrderStatusLogMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.points.OrderPointsClosureFlowService;
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
import org.springframework.beans.BeanUtils;

import java.util.List;

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

    private ai.neargo.shop.trade.order.printing.service.OrderPrintFactService printFacts;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPrintFacts(ai.neargo.shop.trade.order.printing.service.OrderPrintFactService facts) {this.printFacts=facts;}
    private final StateTransitionGate gate;
    private final StatusCasMapper casMapper;
    private final OrderStatusLogMapper channelLogs;
    private PaymentMapper orderMapper;
    private OrderPointsClosureFlowService pointsClosure;

    public TradeStatusWriter(StatusCasMapper casMapper, OrderStatusLogMapper logMapper) {
        this.casMapper = casMapper;
        this.channelLogs = logMapper;
        this.gate = new StateTransitionGate(new MybatisStatusStore(casMapper), new StatusLogStore(logMapper),
                SignalPublisher.NONE);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPointsClosure(PaymentMapper orders, OrderPointsClosureFlowService closure) {
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
        if (event == OrderEvent.PREPARE) {
            return prepareOrder(sub, actor, operatorNo, reason, ctx);
        }
        if (event == OrderEvent.VERIFIED || event == OrderEvent.DELIVERED_BY_MERCHANT) {
            /*
             * 收口边的「有没有进行中售后」由写入口自己查，不信调用方传进来的上下文。
             *
             * 状态机上的 NO_OPEN_AFTER_SALE 守卫读的是 ctx —— 而核销的调用点（自提核销、团长核销、
             * 行业核销）传的一律是 authorizedOnly()，openAfterSale 恒为 false。只在机器上加守卫、
             * 不在这里补事实，守卫就永远不会触发。只补不清：调用方已经判出「有售后」的照样算。
             */
            ctx = ctx.withOpenAfterSale(ctx.openAfterSale() || hasOpenAfterSale(sub.getSubOrderNo()));
        }
        Fired<OrderStatus, OrderEvent> f = gate.apply(OrderMachine.MACHINE, OrderStatus.class,
                new Command<>(Aggregate.ORDER, sub.getSubOrderNo(), sub.getSubOrderNo(), event, actor,
                        operatorNo, reason, ctx));
        sync(sub, f.changed());
        sub.setStatus(f.to().name());
        if (f.changed() && f.to() == OrderStatus.CANCELLED && printFacts != null) {
            printFacts.recordControl(sub.getOrderNo(),sub.getSubOrderNo(),"CANCELLED",
                "CANCEL:"+sub.getSubOrderNo()+":"+sub.getVersion(),java.time.Instant.now());
        }
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

    /** Original channel fulfillment uses a proof-gated CAS and the same order state machine. */
    public Fired<OrderStatus, OrderEvent> channelFulfillment(
            OrdSubOrder sub, String fulfillmentNo, String kind, String actorNo, String reason,
            boolean buyerConfirm) {
        if (!"CHANNEL".equals(sub.getAdmissionKind()) || fulfillmentNo == null || fulfillmentNo.isBlank()
                || !List.of("DELIVERY_HANDOVER", "PICKUP", "DINE_IN").contains(kind))
            throw BizException.of(ErrorCode.CONFLICT);
        boolean delivery = "DELIVERY_HANDOVER".equals(kind);
        long completionTime = System.currentTimeMillis();
        var channelStore = new StatusStore() {
            @Override public String load(Aggregate aggregate, String no) {
                if (aggregate != Aggregate.ORDER || !sub.getSubOrderNo().equals(no))
                    throw BizException.of(ErrorCode.CONFLICT);
                return DataScopeContext.executeWithoutScope(() -> casMapper.orderStatus(no));
            }
            @Override public boolean cas(Aggregate aggregate, String no, String from, String to) {
                boolean valid = delivery ? "WAIT_FULFILL".equals(from) && "FULFILLING".equals(to)
                        : List.of("WAIT_FULFILL", "FULFILLING").contains(from)
                          && List.of("FULFILLING", "COMPLETED").contains(to);
                if (aggregate != Aggregate.ORDER || !sub.getSubOrderNo().equals(no) || !valid) return false;
                return DataScopeContext.executeWithoutScope(() -> casMapper.casChannelFulfillment(
                        no, fulfillmentNo, kind, from, to, java.time.LocalDateTime.now(), completionTime)) == 1;
            }
        };
        var guardedGate = new StateTransitionGate(channelStore, new StatusLogStore(channelLogs), SignalPublisher.NONE);
        var ctx = OrderContext.authorizedOnly().withRequireBuyerConfirm(buyerConfirm)
                .withOpenAfterSale(hasOpenAfterSale(sub.getSubOrderNo()));
        var result = guardedGate.apply(OrderMachine.MACHINE, OrderStatus.class,
                new Command<>(Aggregate.ORDER, sub.getSubOrderNo(), sub.getSubOrderNo(),
                        delivery ? OrderEvent.SHIP_BY_MERCHANT : OrderEvent.VERIFIED,
                        Actor.MERCHANT, actorNo, reason, ctx));
        sync(sub, result.changed());
        sub.setStatus(result.to().name());
        if (result.changed() && result.to() == OrderStatus.COMPLETED) sub.setCompletedAt(completionTime);
        return result;
    }

    /** 售后占位或存量未收口售后行，任一即算「有进行中售后」（与发货侧 {@code MybatisShipmentReadStore} 同口径）。 */
    private boolean hasOpenAfterSale(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() -> casMapper.openAfterSaleNo(subOrderNo) != null
                || casMapper.countOpenAfterSales(subOrderNo) > 0);
    }

    /** 调用方持事务；当前读与条件写先完成，门只为实际首次备货记一次流水。 */
    private Fired<OrderStatus, OrderEvent> prepareOrder(OrdSubOrder sub, Actor actor,
                                                       String operatorNo, String reason, OrderContext ctx) {
        OrdSubOrder current = DataScopeContext.executeWithoutScope(
                () -> casMapper.lockOrder(sub.getSubOrderNo()));
        if (current == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (actor != Actor.MERCHANT || !ctx.authorized()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        BeanUtils.copyProperties(current, sub);
        OrderStatus status = OrderStatus.valueOf(current.getStatus());
        if (current.getPreparedAt() != null) {
            return new Fired<>(status, status, OrderEvent.PREPARE, actor, List.of());
        }
        OrderMachine.MACHINE.fire(status, OrderEvent.PREPARE, actor, ctx);
        long at = System.currentTimeMillis();
        int rows = DataScopeContext.executeWithoutScope(
                () -> casMapper.prepareIfEligible(sub.getSubOrderNo(), at));
        if (rows != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        Fired<OrderStatus, OrderEvent> fired = gate.apply(OrderMachine.MACHINE, OrderStatus.class,
                new Command<>(Aggregate.ORDER, sub.getSubOrderNo(), sub.getSubOrderNo(), OrderEvent.PREPARE,
                        actor, operatorNo, reason, ctx));
        sub.setPreparedAt(at);
        sync(sub, true);
        return fired;
    }

    /** Package corrections share the same CAS/log gate as parent and child order writes. */
    public Fired<ShipmentStatus, ShipmentEvent> shipment(OrdShipment shipment, ShipmentEvent event, Actor actor,
                                                        String operatorNo, String reason, ShipmentContext ctx) {
        Fired<ShipmentStatus, ShipmentEvent> fired = gate.apply(ShipmentMachine.MACHINE, ShipmentStatus.class,
                new Command<>(Aggregate.SHIPMENT, shipment.getShipmentNo(), shipment.getSubOrderNo(), event,
                        actor, operatorNo, reason, ctx));
        sync(shipment, fired.changed());
        shipment.setStatus(fired.to().name());
        return fired;
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
