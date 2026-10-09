package ai.neargo.shop.paybridge;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.spi.logistics.LogisticsPort;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 物流签收事件 → 微信确认收货提醒（TDD-物流模块 M9，T3.4）。取代每 30 分钟扫一遍签收的 {@code wx-confirm-receive}
 * （批 2b 打开订阅、签收事件开始真的到达之后，那个作业停掉）。
 *
 * <p><b>这个支付单里所有快递子单都签收了才提醒</b>：微信的确认收货提醒每个支付单只有一次机会，
 * 子单按门店拆之后一个支付单可能有好几个包裹 —— 第一个到就提醒，买家还在等第二个。
 * 已取消 / 已退款的不挡；已完成的（买家已确认）不挡；还在待发货 / 履约中且没签收的 → 等它签收的那一条事件。
 * 签收时间取最晚那张：微信要求签收时间晚于发货时间，最晚的那张必然晚于任何一次发货。
 */
@Component
public class WxConfirmOnSignedConsumer implements OutboxConsumer {

    static final String TYPE = "WAYBILL_SIGNED";
    private static final Set<String> OPEN = Set.of(OrdSubOrder.WAIT_FULFILL, OrdSubOrder.FULFILLING);

    private final WxConfirmReceiveService confirm;
    private final LogisticsPort logistics;
    private final TradeMappers.SubOrderMapper subOrders;
    private final ObjectMapper json;

    public WxConfirmOnSignedConsumer(WxConfirmReceiveService confirm, LogisticsPort logistics,
                                     TradeMappers.SubOrderMapper subOrders, ObjectMapper json) {
        this.confirm = confirm;
        this.logistics = logistics;
        this.subOrders = subOrders;
        this.json = json;
    }

    @Override
    public boolean supports(String eventType) {
        return TYPE.equals(eventType);
    }

    @Override
    public void consume(SysOutbox event) {
        JsonNode p = json.readTree(event.getPayload());
        if (!"WX".equals(p.path("profile").asString(""))) {
            return;   // 非微信支付单：微信没有这笔交易，提醒不了；靠签收后 7 天自动确认
        }
        String subOrderNo = p.path("bizRef").asString("");
        OrdSubOrder sub = DataScopeContext.executeWithoutScope(() -> subOrders.selectOne(
                Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getSubOrderNo, subOrderNo).last("limit 1")));
        if (sub == null || sub.getOrderNo() == null) {
            return;
        }
        List<OrdSubOrder> express = DataScopeContext.executeWithoutScope(() -> subOrders.selectList(
                Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getOrderNo, sub.getOrderNo())
                        .eq(OrdSubOrder::getFulfillment, OrdSubOrder.EXPRESS)));
        Map<String, Long> signed = logistics.signedAtOf(express.stream().map(OrdSubOrder::getSubOrderNo).toList());
        boolean waiting = express.stream()
                .anyMatch(s -> OPEN.contains(s.getStatus()) && !signed.containsKey(s.getSubOrderNo()));
        if (waiting || signed.isEmpty()) {
            return;   // 还有包裹在路上：等最后一张签收的那条事件
        }
        confirm.notifyOne(sub.getOrderNo(), signed.values().stream().mapToLong(Long::longValue).max().orElseThrow());
    }
}
