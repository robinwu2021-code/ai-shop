package ai.neargo.shop.message.notify;

import ai.neargo.shop.message.entity.NotifyChannel;
import ai.neargo.shop.message.notify.NotifyChannelService.Credential;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 平台统一凭证真源（设计：触达推送中台-模块抽象 · 平台侧凭证管理）。
 *
 * <p><b>「每条通道×供应商需要哪些凭证」只在这里声明一次</b>。此前它散在三处：
 * {@code health()} 的凭证清单、{@code credsReady()} 的存在性检查、各 gateway 构造器 ——
 * 加一个凭证要改三处，必然漂移（配了却显示没配，或反之）。收敛到一处后，
 * health / credsReady / 状态派生都问它，不再各读各的。
 *
 * <p><b>密钥永不出后端这条铁律不变</b>：这里只声明**属性键**并回答「配了没有」（present），
 * 密钥的**值**从不经过这个类往外走 —— secret=true 的项只回 present，公开项（签名/appId/topic）
 * 才回值供运营核对。值全部经 {@link Environment} 读，是唯一的读取点。
 */
@Component
public class PlatformChannelCredentials {

    /** 一条凭证：显示用的环境变量名、Spring 属性键、是否密钥。 */
    public record Cred(String envVar, String propKey, boolean secret) {
    }

    /**
     * 一条 (通道类型 × 供应商) 的凭证规格。
     *
     * @param stubProp        走桩开关的属性键
     * @param stubDefault     桩开关缺省值（本地/测试默认走桩）
     * @param providerRequired 这个供应商是不是平台**必需**的：SMS/MAIL/WXSUB/个推 是；
     *                        FCM/APNs 是可选供应商（个推已覆盖国内+透传 APNs），缺配不代表通道坏
     * @param secretKeys      **商家自带渠道**时，其凭证 JSON 必须含的字段名（外部接入用，平台侧走 env）
     */
    public record ChannelSpec(String channelType, String provider, String stubProp,
                              boolean stubDefault, boolean providerRequired,
                              List<Cred> creds, List<String> secretKeys) {
    }

    /** 声明式规格表。加供应商 = 在这里加一条，health/credsReady 自动跟上。 */
    private static final List<ChannelSpec> SPECS = List.of(
            new ChannelSpec(NotifyChannel.TYPE_SMS, NotifyChannel.PROV_ALI,
                    "shop.sms.stub", true, true, List.of(
                    new Cred("ALI_SMS_AK", "shop.sms.ali.access-key-id", true),
                    new Cred("ALI_SMS_SK", "shop.sms.ali.access-key-secret", true),
                    new Cred("ALI_SMS_SIGN", "shop.sms.ali.sign", false),
                    new Cred("ALI_SMS_TPL_OTP", "shop.sms.ali.templates.otp", false)),
                    List.of("accessKeyId", "accessKeySecret", "sign")),
            new ChannelSpec(NotifyChannel.TYPE_MAIL, NotifyChannel.PROV_SMTP,
                    "shop.mail.stub", true, true, List.of(
                    new Cred("MAIL_USERNAME", "shop.mail.username", true),
                    new Cred("MAIL_PASSWORD", "shop.mail.password", true),
                    new Cred("MAIL_FROM", "shop.mail.from", false)),
                    List.of("username", "password", "from")),
            new ChannelSpec(NotifyChannel.TYPE_WXSUB, NotifyChannel.PROV_WECHAT,
                    "shop.wx.subscribe.stub", true, true, List.of(
                    new Cred("WX_APPID", "shop.wx.appid", false),
                    new Cred("WX_SECRET", "shop.wx.secret", true),
                    new Cred("WX_TPL_ORDER_ARRIVED", "shop.wx.templates.order-arrived", false),
                    new Cred("WX_TPL_REFUNDED", "shop.wx.templates.refunded", false)),
                    List.of("appId", "secret")),
            new ChannelSpec(NotifyChannel.TYPE_PUSH, NotifyChannel.PROV_GETUI,
                    "shop.push.stub", true, true, List.of(
                    new Cred("GETUI_APP_ID", "shop.push.getui.app-id", false),
                    new Cred("GETUI_APP_KEY", "shop.push.getui.app-key", true),
                    new Cred("GETUI_MASTER_SECRET", "shop.push.getui.master-secret", true)),
                    List.of("appId", "appKey", "masterSecret")),
            new ChannelSpec(NotifyChannel.TYPE_PUSH, NotifyChannel.PROV_FCM,
                    "shop.push.fcm.stub", true, false, List.of(
                    new Cred("FCM_PROJECT_ID", "shop.push.fcm.project-id", false),
                    new Cred("FCM_CLIENT_EMAIL", "shop.push.fcm.client-email", false),
                    new Cred("FCM_PRIVATE_KEY", "shop.push.fcm.private-key", true)),
                    List.of("projectId", "clientEmail", "privateKey")),
            new ChannelSpec(NotifyChannel.TYPE_PUSH, NotifyChannel.PROV_APNS,
                    "shop.push.apns.stub", true, false, List.of(
                    new Cred("APNS_TEAM_ID", "shop.push.apns.team-id", false),
                    new Cred("APNS_KEY_ID", "shop.push.apns.key-id", false),
                    new Cred("APNS_PRIVATE_KEY", "shop.push.apns.private-key", true),
                    new Cred("APNS_TOPIC", "shop.push.apns.topic", false)),
                    List.of("teamId", "keyId", "privateKey", "topic")));

    private static final Map<String, ChannelSpec> BY_KEY = SPECS.stream()
            .collect(Collectors.toMap(s -> key(s.channelType(), s.provider()), Function.identity()));

    private final Environment env;

    public PlatformChannelCredentials(Environment env) {
        this.env = env;
    }

    private static String key(String channelType, String provider) {
        return channelType + ":" + provider;
    }

    /** 某 (通道,供应商) 的规格；未声明返回 null（INAPP 等无凭证通道）。 */
    public ChannelSpec spec(String channelType, String provider) {
        return BY_KEY.get(key(channelType, provider));
    }

    /** 一条通道类型下声明过的所有供应商规格（PUSH 有三家；其余各一）。 */
    public List<ChannelSpec> specsOfType(String channelType) {
        return SPECS.stream().filter(s -> s.channelType().equals(channelType)).toList();
    }

    /** 走桩开关。INAPP 及未声明的通道恒 false（站内信从不桩）。 */
    public boolean isStub(String channelType, String provider) {
        ChannelSpec s = spec(channelType, provider);
        return s != null && env.getProperty(s.stubProp(), Boolean.class, s.stubDefault());
    }

    /** 该 (通道,供应商) 的**全部**凭证是否配齐。无凭证通道（INAPP）恒 true。 */
    public boolean credsReady(String channelType, String provider) {
        ChannelSpec s = spec(channelType, provider);
        if (s == null) {
            return true;
        }
        return s.creds().stream().allMatch(c -> present(c.propKey()));
    }

    /**
     * 该 (通道,供应商) **还缺哪些凭证**（返回环境变量名）。运营端据此直接告诉运维「要开这条
     * 通道，还差 GETUI_APP_KEY」，而不是只显示一个 UNCONFIGURED。无凭证通道返回空。
     */
    public List<String> missing(String channelType, String provider) {
        ChannelSpec s = spec(channelType, provider);
        if (s == null) {
            return List.of();
        }
        return s.creds().stream().filter(c -> !present(c.propKey())).map(Cred::envVar).toList();
    }

    /**
     * 体检用的凭证清单（present + required）。required = 该供应商是否平台必需 ——
     * FCM/APNs 缺配显示 required=false（可选供应商），不误报成「通道坏了」。
     */
    public List<Credential> credentials(String channelType, String provider) {
        ChannelSpec s = spec(channelType, provider);
        if (s == null) {
            return List.of();
        }
        return s.creds().stream()
                .map(c -> new Credential(c.envVar(), present(c.propKey()), s.providerRequired()))
                .toList();
    }

    /**
     * **商家自带渠道**的凭证 JSON 必须含哪些字段（外部接入）。未知供应商返回空 = 不校验。
     * 与平台侧 env 凭证同出一份规格，商家配错在保存时就拦下，不留到发送那一刻才炸。
     */
    public List<String> requiredSecretKeys(String channelType, String provider) {
        ChannelSpec s = spec(channelType, provider);
        return s == null ? List.of() : s.secretKeys();
    }

    /** 非密属性的值（供运营核对，如 endpoint/sign/appId/topic）。密钥项永不经此回传。 */
    public String value(String propKey) {
        return env.getProperty(propKey, "");
    }

    /** 带缺省的取值（如 mp-state 缺省 formal）。 */
    public String value(String propKey, String defaultValue) {
        String v = env.getProperty(propKey);
        return v == null || v.isBlank() ? defaultValue : v;
    }

    private boolean present(String propKey) {
        String v = env.getProperty(propKey);
        return v != null && !v.isBlank();
    }
}
