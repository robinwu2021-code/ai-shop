package ai.neargo.shop.logistics.capability;

/**
 * 给一张运单换展示用的 token（今天只有微信 {@code trace_waybill} → waybill_token）。
 *
 * <p>结局：成功时 {@link ChannelOutcome#ref()} 是 token；微信还没收录这个运单 → {@code NOT_READY}（不重试，等下一条推送）。
 */
public interface WaybillTokenBinder extends ChannelCapability {

    ChannelOutcome bind(BindCmd cmd);

    /**
     * @param deliveryId    承运商在微信里的编码
     * @param receiverPhone 申通 / 中通等必填（缺了回 9300561）
     * @param goodsJson     商品（≤3 件，JSON：[{name,imageUrl}]）
     */
    record BindCmd(String openid, String transId, String waybillNo, String deliveryId,
                   String receiverPhone, String goodsJson, String orderPath) {
    }
}
