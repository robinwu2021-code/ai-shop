package ai.neargo.shop.message.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 场景 × 通道 触达配置（设计：多渠道推送与运营端触达配置 · 需求 1）。
 *
 * <p>一行 = 「某场景发给某受众时，某条通道开不开」。此前这套规则硬编码在
 * {@code NotificationConsumer}，现在搬进库让运营端可配。
 *
 * <p><b>INAPP 行只作展示</b>：站内信是事实记录，恒发不可关，代码里硬编码，
 * 不读这张表的 INAPP 行 —— 配置表被误删/误关也不能让事实记录消失。
 */
@Getter
@Setter
@TableName("notify_scene_channel")
public class MsgSceneChannel extends BaseEntity {

    // ---- 受众
    public static final String AUD_C_USER = "C_USER";
    public static final String AUD_B_STAFF = "B_STAFF";
    public static final String AUD_OPS_STAFF = "OPS_STAFF";

    // ---- 通道（沿用 SysNotifyLog 常量 + INAPP）
    public static final String CH_INAPP = "INAPP";
    public static final String CH_WXSUB = "WXSUB";
    public static final String CH_PUSH = "PUSH";
    public static final String CH_SMS = "SMS";

    // ---- 推送级别
    public static final String LEVEL_NORMAL = "NORMAL";
    public static final String LEVEL_RING = "RING";

    private String sceneCode;
    private String audience;
    private String channel;
    private Boolean enabled;
    private String pushLevel;
}
