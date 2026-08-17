package ai.neargo.shop.message.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 触达渠道注册表（设计：触达推送中台-模块抽象 · N2）。
 *
 * <p>一条 = (通道类型 × 供应商 × 接入范围 × 归属)。把「平台有哪些渠道、开没开」
 * 从代码里的 {@code @ConditionalOnProperty} + 环境变量抬成一等实体。
 *
 * <p><b>不带 status 字段</b>：状态必须反映「凭据齐没齐 + 启停 + 体检」的实时事实，
 * 落列必与现实分叉 —— 由 {@code NotifyChannelRegistry} 读时派生（见 {@link #STATUS_READY} 等）。
 * <b>不带密钥</b>：只有 {@code credRef}(env 前缀) 与非密 {@code configJson}。
 */
@Getter
@Setter
@TableName("notify_channel")
public class NotifyChannel extends BaseEntity {

    // ---- 通道类型
    public static final String TYPE_SMS = "SMS";
    public static final String TYPE_MAIL = "MAIL";
    public static final String TYPE_WXSUB = "WXSUB";
    public static final String TYPE_PUSH = "PUSH";
    public static final String TYPE_INAPP = "INAPP";

    // ---- 供应商
    public static final String PROV_ALI = "ALI";
    public static final String PROV_SMTP = "SMTP";
    public static final String PROV_WECHAT = "WECHAT";
    public static final String PROV_GETUI = "GETUI";
    public static final String PROV_FCM = "FCM";
    public static final String PROV_APNS = "APNS";
    public static final String PROV_INTERNAL = "INTERNAL";

    // ---- 接入范围
    public static final String SCOPE_PLATFORM = "PLATFORM";
    public static final String SCOPE_MERCHANT = "MERCHANT";
    public static final String SCOPE_TEST = "TEST";

    // ---- 派生状态（不落库，NotifyChannelRegistry 读时算）
    public static final String STATUS_UNCONFIGURED = "UNCONFIGURED";
    public static final String STATUS_STUB = "STUB";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_DEGRADED = "DEGRADED";

    private String channelNo;
    private String channelType;
    private String provider;
    private String scope;
    private String ownerNo;
    private Boolean enabled;
    private Integer priority;
    private String credRef;
    private String configJson;
}
