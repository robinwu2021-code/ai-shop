package ai.neargo.shop.logistics.capability;

/**
 * 订阅：让渠道以后把这张运单的轨迹推给我们。每单只订一次（快递100 同一单号每月最多 4 次）。
 *
 * <p>⚠️ <b>订阅可用 ⇔ 同一渠道的推送接收也可用</b>（{@code ChannelRouter} 判，不靠实现自觉）：
 * 订阅了却收不到推送，订阅接口照样返回成功、运单就此沉默、不报任何错。
 */
public interface TrackingSubscriber extends ChannelCapability {

    ChannelOutcome subscribe(SubscribeCmd cmd);

    /**
     * @param carrier            我方承运商码
     * @param channelCarrierCode 这家承运商在该渠道里的叫法（经 {@code lgs_carrier_code} 转好的）
     * @param waybillNo          运单号
     * @param phone              收件人手机号，可空（顺丰、中通在快递100 必填）
     * @param callbackUrl        推送回调地址（{@code callback-base/渠道名}）
     */
    record SubscribeCmd(String carrier, String channelCarrierCode, String waybillNo,
                        String phone, String callbackUrl) {
    }
}
