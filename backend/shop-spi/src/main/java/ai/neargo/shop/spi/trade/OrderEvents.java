package ai.neargo.shop.spi.trade;

import ai.neargo.shop.event.DomainEvent;

import java.util.List;

/**
 * 交易域对外发布的事件。**载荷自带消费方所需字段**，消费方不回查主表 ——
 * 回查会引入时序问题（事件到达时主表可能已被后续操作改写）。
 */
public final class OrderEvents {

    private OrderEvents() {
    }

    public static final String AGG_ORDER = "ORDER";
    public static final String AGG_SUB_ORDER = "SUB_ORDER";

    /** 下单成功。消费方：marketing(核销券) · message(发提醒) · risk(行为画像)。 */
    public record OrderCreated(String orderNo, String userNo, List<String> subOrderNos, long payAmount)
            implements DomainEvent {
        @Override
        public String aggregateType() {
            return AGG_ORDER;
        }

        @Override
        public String aggregateId() {
            return orderNo;
        }

        @Override
        public String eventType() {
            return "ORDER_CREATED";
        }
    }

    /** 支付成功。消费方：settle(生成结算单) · fulfillment(建履约任务) · message。 */
    public record OrderPaid(String orderNo, String userNo, long payAmount, String payChannel)
            implements DomainEvent {
        @Override
        public String aggregateType() {
            return AGG_ORDER;
        }

        @Override
        public String aggregateId() {
            return orderNo;
        }

        @Override
        public String eventType() {
            return "ORDER_PAID";
        }
    }

    /**
     * 子订单完成（核销/确认收货）。消费方：product(可写评价) · settle(解冻计时) · report。
     * 粒度是子订单而不是主单 —— 一次下单跨三家商家，三家各自完成，各自结算。
     */
    public record SubOrderCompleted(String subOrderNo, String orderNo, String merchantNo, String userNo)
            implements DomainEvent {
        @Override
        public String aggregateType() {
            return AGG_SUB_ORDER;
        }

        @Override
        public String aggregateId() {
            return subOrderNo;
        }

        @Override
        public String eventType() {
            return "SUB_ORDER_COMPLETED";
        }
    }

    /**
     * 售后退款完成。消费方：settle（账务冲销）、product（库存回补）、user（商家评分）。
     * <b>发布时机在退款成功之后</b> —— 提前发的话，下游会按「已退款」处理一笔还没退成的钱。
     */
    public record AfterSaleRefunded(String afterSaleNo, String subOrderNo, String userNo, long refundMinor)
            implements DomainEvent {

        @Override
        public String eventType() {
            return "AFTER_SALE_REFUNDED";
        }

        @Override
        public String aggregateType() {
            return "AFTER_SALE";
        }

        @Override
        public String aggregateId() {
            return afterSaleNo;
        }
    }
}
