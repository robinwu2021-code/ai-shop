package ai.neargo.shop.spi.logistics;

import java.util.List;
import java.util.Map;
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

    /** Lightweight current identity checks do not reread receiver/payment snapshots on every callback. */
    default Optional<ShipmentIdentity> identityOf(String subOrderNo) {
        return sourceOf(subOrderNo).map(s -> new ShipmentIdentity(s.sourceShipmentNo(), s.sourceShipmentVersion(),
                s.carrier(), s.waybillNo()));
    }

    /** Registration caller owns the primary transaction; implementations retain the child lock until its commit. */
    default Optional<ShipmentIdentity> lockedIdentityOf(String subOrderNo) { return identityOf(subOrderNo); }

    /** Existing protected Ops replay recovers the same queue row for the expected current source. */
    default void retryUpload(String subOrderNo, String expectedShipmentNo, long generation) {
        throw new UnsupportedOperationException("Upload recovery is not installed");
    }

    /** Read-only queue progress; callers first authorize their logistics resource scope. */
    default Map<String, UploadState> uploadStatesOf(List<String> subOrderNos) { return Map.of(); }

    record ShipmentIdentity(String sourceShipmentNo, Long sourceShipmentVersion, String carrier, String waybillNo) { }
    record UploadState(String sourceShipmentNo, Long sourceShipmentVersion, String carrier, String waybillNo,
                       String status, String error) { }

    /** Mapped Ops correction shares the order package writer after the existing scope/terminal guards. */
    default Optional<ShipmentSource> correctSource(String subOrderNo, String expectedShipmentNo, long generation,
                                                   String carrier, String waybillNo, String reason) {
        throw new UnsupportedOperationException("Source correction is not installed");
    }

    /**
     * @param wx        微信支付键。<b>没有微信交易单号就是 null</b> → 物流把这单定为 SELF，不调任何微信物流接口
     * @param goods     商品（≤3 件）：微信 trace_waybill 必填
     * @param orderPath 订单页路径：微信物流页里「回到订单」用
     * @param wxUploadedAt 微信发货信息已上传成功的时刻（毫秒），没上传为 null。换 token 的前置条件之一；
     *                     上传事件可能先于登记到达，所以快照里也带一份，免得那一次通知落空
     */
    record ShipmentSource(String subOrderNo, String orderNo, String entityNo, String storeNo,
                          String carrier, String waybillNo,
                          String receiverName, String receiverPhone, String region,
                          WxKey wx, List<GoodsBrief> goods, String orderPath, Long wxUploadedAt,
                          String sourceShipmentNo, Long sourceShipmentVersion) {
        /** Preserve explicit unmapped legacy snapshots without inventing a package. */
        public ShipmentSource(String subOrderNo, String orderNo, String entityNo, String storeNo,
                              String carrier, String waybillNo, String receiverName, String receiverPhone,
                              String region, WxKey wx, List<GoodsBrief> goods, String orderPath, Long wxUploadedAt) {
            this(subOrderNo, orderNo, entityNo, storeNo, carrier, waybillNo, receiverName, receiverPhone,
                    region, wx, goods, orderPath, wxUploadedAt, null, null);
        }
    }

    record WxKey(String transId, String outTradeNo, String openid) {
    }

    record GoodsBrief(String name, String imageUrl) {
    }
}
