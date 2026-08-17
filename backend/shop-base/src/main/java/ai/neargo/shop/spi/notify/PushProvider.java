package ai.neargo.shop.spi.notify;

/**
 * 推送供应商（设计：多渠道推送与运营端触达配置 · 需求 2）。
 *
 * <p>一台设备的推送归哪家：个推聚合国内厂商通道（小米/华为/OPPO/vivo/荣耀 + 透传 APNs），
 * 海外 Android 走 Google FCM，iOS 亦可直连 Apple APNs。{@code msg_push_token.provider}
 * 记的就是它，{@code PushRouter} 据此把一条推送分发到对应 gateway。
 *
 * <p><b>默认 GETUI</b>：存量设备与 uni-push 打包上报的都是个推 cid。
 */
public final class PushProvider {

    private PushProvider() {
    }

    /** 个推（uni-push 2.0 底座，聚合国内厂商通道 + 透传 APNs）。默认值。 */
    public static final String GETUI = "GETUI";
    /** Google Firebase Cloud Messaging（海外 Android）。 */
    public static final String FCM = "FCM";
    /** Apple Push Notification service（iOS 直连）。 */
    public static final String APNS = "APNS";

    /** 端上没上报或上报了不认识的值，一律回落个推 —— 存量都是个推。 */
    public static String normalize(String provider) {
        if (GETUI.equals(provider) || FCM.equals(provider) || APNS.equals(provider)) {
            return provider;
        }
        return GETUI;
    }
}
