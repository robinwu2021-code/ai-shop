package ai.neargo.shop.spi.logistics;

import java.util.List;
import java.util.Optional;

/**
 * 物流向交易域取一张运单的登记快照 —— <b>物流唯一的反向 Port，预算 = 1</b>（ADR-032，{@code LogisticsBoundaryTest} 看住）。
 *
 * <p><b>只在登记那一刻调一次</b>，之后物流再不读交易域。为什么不让交易域把这些放进发货事件里：
 * 事件走 {@code sys_outbox}，而它<b>没有任何清理</b> —— 收件人手机号放进 payload 等于永久留一份明文。
 *
 * <p>实现在 {@code shop-app/logisticsbridge}（要拼支付台账里的付款人 openid，那在支付域）。
 */
public interface ShipmentSourcePort {

    Optional<ShipmentSource> sourceOf(String subOrderNo);

    /**
     * @param wx        微信支付键。<b>没有微信交易单号就是 null</b> → 物流把这单定为 SELF，不调任何微信物流接口
     * @param goods     商品（≤3 件）：微信 trace_waybill 必填
     * @param orderPath 订单页路径：微信物流页里「回到订单」用
     */
    record ShipmentSource(String subOrderNo, String orderNo, String entityNo, String storeNo,
                          String carrier, String waybillNo,
                          String receiverName, String receiverPhone, String region,
                          WxKey wx, List<GoodsBrief> goods, String orderPath) {
    }

    record WxKey(String transId, String outTradeNo, String openid) {
    }

    record GoodsBrief(String name, String imageUrl) {
    }
}
