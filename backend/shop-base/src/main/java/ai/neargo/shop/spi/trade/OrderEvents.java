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
     * 子订单支付成功（按商家粒度）。消费方：message（B 端「新订单」提醒，B-N-1）。
     *
     * <p>与 {@link OrderPaid}（主单粒度）并存而不是取代：结算/履约要的是主单事实，
     * 而商家通知天然是子单粒度 —— 跨商家合单支付时，每家只该被自己的那单吵到。
     */
    public record SubOrderPaid(String subOrderNo, String orderNo, String entityNo,
                               String storeNo, String userNo, long payAmount)
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
            return "SUB_ORDER_PAID";
        }
    }

    /**
     * 售后申请提交。消费方：message（B 端提醒，B-N-2）。
     * <b>发布时机在申请落库之后、任何审核动作之前</b> —— 商家越早看到越可能协商解决，
     * 拖到平台介入时双方都已经在气头上。
     */
    public record AfterSaleApplied(String afterSaleNo, String subOrderNo, String entityNo,
                                   String userNo, String type, long refundMinor)
            implements DomainEvent {
        @Override
        public String aggregateType() {
            return "AFTER_SALE";
        }

        @Override
        public String aggregateId() {
            return afterSaleNo;
        }

        @Override
        public String eventType() {
            return "AFTER_SALE_APPLIED";
        }
    }

    /**
     * 到货：自提点把一批子订单标记为「可来取货」。消费方：message（到货通知，C-FF-02）。
     *
     * <p><b>按 userNo 聚合发布</b>，不是一个子订单一个事件：一次到货登记通常是一车货，
     * 同一个买家在这批里有三单的话，逐单发事件会让他收到三条「到货了」——
     * 到货通知是全链路最重要的一条触达，恰恰最不能被自己刷成噪音。
     */
    public record SubOrdersArrived(String userNo, String pickupNo, List<String> subOrderNos)
            implements DomainEvent {
        @Override
        public String aggregateType() {
            return AGG_SUB_ORDER;
        }

        @Override
        public String aggregateId() {
            return subOrderNos == null || subOrderNos.isEmpty() ? "" : subOrderNos.getFirst();
        }

        @Override
        public String eventType() {
            return "ORDER_ARRIVED";
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
