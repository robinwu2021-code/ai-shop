package ai.neargo.shop.message.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * App 推送设备绑定（ADR-018）。
 *
 * <p>一人一平台一条：同一个人的 Android 手机与 iPad 各推各的；
 * 同一台设备换人登录时前一条必须解绑 —— 见 {@code V98__msg_push_token.sql} 的注释，
 * 那是隐私事故而不是「多推一条」。
 */
@Getter
@Setter
@TableName("msg_push_token")
public class MsgPushToken extends BaseEntity {

    public static final String APP_ANDROID = "APP_ANDROID";
    public static final String APP_IOS = "APP_IOS";

    private String receiverType;
    private String receiverNo;
    private String platform;

    /** 推送供应商 {@link ai.neargo.shop.spi.notify.PushProvider}（GETUI/FCM/APNS）。默认 GETUI。 */
    private String provider;

    /** 供应商设备标识（个推 cid / FCM registration token / APNs device token）。 */
    private String clientId;
}
