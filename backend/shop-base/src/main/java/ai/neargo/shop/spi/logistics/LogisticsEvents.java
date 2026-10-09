package ai.neargo.shop.spi.logistics;

import ai.neargo.shop.event.DomainEvent;

/**
 * 物流告诉别的域的事（经 {@code sys_outbox}，与状态变更同一事务写入：状态改了事件一定发）。
 *
 * <p>只在<b>首次</b>进入某一档时发一次。消费方：交易域（签收 → 微信确认收货提醒）、
 * 通知（SELF 单 → 自有订阅消息；WX 单微信已经推了，再推就是重复打扰）。
 */
public final class LogisticsEvents {

    public static final String AGG_WAYBILL = "WAYBILL";

    private LogisticsEvents() {
    }

    /**
     * 首次揽收 / 首次派件 / 进入异常。
     *
     * @param status  PICKED_UP / DELIVERING / EXCEPTION
     * @param profile WX / SELF
     */
    public record WaybillProgressed(String shipmentNo, String bizRef, String profile, String status,
                                    boolean atLocker, long at) implements DomainEvent {
        @Override
        public String aggregateType() {
            return AGG_WAYBILL;
        }

        @Override
        public String aggregateId() {
            return shipmentNo;
        }

        @Override
        public String eventType() {
            return "WAYBILL_PROGRESSED";
        }
    }

    /**
     * 首次签收。
     *
     * @param signedAt 签收时间（毫秒，取渠道节点的时间）
     * @param source   哪条路来的（kuaidi100 推送 / wx 查询 …），排查用
     */
    public record WaybillSigned(String shipmentNo, String bizRef, String profile, long signedAt,
                                String source) implements DomainEvent {
        @Override
        public String aggregateType() {
            return AGG_WAYBILL;
        }

        @Override
        public String aggregateId() {
            return shipmentNo;
        }

        @Override
        public String eventType() {
            return "WAYBILL_SIGNED";
        }
    }
}
