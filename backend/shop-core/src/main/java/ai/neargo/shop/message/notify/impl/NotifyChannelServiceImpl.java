package ai.neargo.shop.message.notify.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.message.mapper.MessageMappers.NotifyLogMapper;
import ai.neargo.shop.message.notify.NotifyChannelService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link NotifyChannelService} 实现。
 *
 * <p><b>所有值都从 {@code @Value} 读，与真正跑着的通道读的是同一份配置</b> ——
 * 另抄一份「配置清单」的话，两份必然分叉，而分叉时页面会说「配好了」而通道发不出去。
 *
 * <p>字段声明顺序与 {@code application.yml} 的段落一致，方便对照着看。
 */
@Service
public class NotifyChannelServiceImpl implements NotifyChannelService {

    private final NotifyLogMapper logMapper;
    /** 问通道要「当前生效的模板号」，不自己解析配置 —— 见 {@link #templateIdOf} */
    private final ai.neargo.shop.spi.notify.WxSubscribePort wxPort;
    private final ai.neargo.shop.spi.platform.SettingPort settingPort;

    // ---- 短信
    private final boolean smsStub;
    private final String smsEndpoint;
    private final String smsSign;
    private final String smsAk;
    private final String smsSk;
    private final String smsTplOtp;
    // ---- 邮件
    private final boolean mailStub;
    private final String mailHost;
    private final String mailUsername;
    private final String mailPassword;
    private final String mailFrom;
    // ---- 微信
    private final boolean wxStub;
    private final String wxAppid;
    private final String wxSecret;
    private final String wxMpState;
    private final String wxTplArrived;
    private final String wxTplRefunded;
    // ---- 推送 · 个推（默认供应商）
    private final boolean pushStub;
    private final String getuiAppId;
    private final String getuiAppKey;
    private final String getuiMasterSecret;
    // ---- 推送 · FCM（可选，海外 Android）
    private final boolean fcmStub;
    private final String fcmProjectId;
    private final String fcmClientEmail;
    private final String fcmPrivateKey;
    // ---- 推送 · APNs（可选，iOS 直连）
    private final boolean apnsStub;
    private final String apnsTeamId;
    private final String apnsKeyId;
    private final String apnsPrivateKey;
    private final String apnsTopic;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public NotifyChannelServiceImpl(
            NotifyLogMapper logMapper,
            ai.neargo.shop.spi.notify.WxSubscribePort wxPort,
            ai.neargo.shop.spi.platform.SettingPort settingPort,
            @Value("${shop.sms.stub:true}") boolean smsStub,
            @Value("${shop.sms.ali.endpoint:}") String smsEndpoint,
            @Value("${shop.sms.ali.sign:}") String smsSign,
            @Value("${shop.sms.ali.access-key-id:}") String smsAk,
            @Value("${shop.sms.ali.access-key-secret:}") String smsSk,
            @Value("${shop.sms.ali.templates.otp:}") String smsTplOtp,
            @Value("${shop.mail.stub:true}") boolean mailStub,
            @Value("${shop.mail.host:}") String mailHost,
            @Value("${shop.mail.username:}") String mailUsername,
            @Value("${shop.mail.password:}") String mailPassword,
            @Value("${shop.mail.from:}") String mailFrom,
            // 这里报的是**订阅消息通道**的健康度，跟的是订阅消息那个开关（登录另有一个）
            @Value("${shop.wx.subscribe.stub:true}") boolean wxStub,
            @Value("${shop.wx.appid:}") String wxAppid,
            @Value("${shop.wx.secret:}") String wxSecret,
            @Value("${shop.wx.mp-state:formal}") String wxMpState,
            @Value("${shop.wx.templates.order-arrived:}") String wxTplArrived,
            @Value("${shop.wx.templates.refunded:}") String wxTplRefunded,
            @Value("${shop.push.stub:true}") boolean pushStub,
            @Value("${shop.push.getui.app-id:}") String getuiAppId,
            @Value("${shop.push.getui.app-key:}") String getuiAppKey,
            @Value("${shop.push.getui.master-secret:}") String getuiMasterSecret,
            @Value("${shop.push.fcm.stub:true}") boolean fcmStub,
            @Value("${shop.push.fcm.project-id:}") String fcmProjectId,
            @Value("${shop.push.fcm.client-email:}") String fcmClientEmail,
            @Value("${shop.push.fcm.private-key:}") String fcmPrivateKey,
            @Value("${shop.push.apns.stub:true}") boolean apnsStub,
            @Value("${shop.push.apns.team-id:}") String apnsTeamId,
            @Value("${shop.push.apns.key-id:}") String apnsKeyId,
            @Value("${shop.push.apns.private-key:}") String apnsPrivateKey,
            @Value("${shop.push.apns.topic:}") String apnsTopic) {
        this.logMapper = logMapper;
        this.wxPort = wxPort;
        this.settingPort = settingPort;
        this.smsStub = smsStub;
        this.smsEndpoint = smsEndpoint;
        this.smsSign = smsSign;
        this.smsAk = smsAk;
        this.smsSk = smsSk;
        this.smsTplOtp = smsTplOtp;
        this.mailStub = mailStub;
        this.mailHost = mailHost;
        this.mailUsername = mailUsername;
        this.mailPassword = mailPassword;
        this.mailFrom = mailFrom;
        this.wxStub = wxStub;
        this.wxAppid = wxAppid;
        this.wxSecret = wxSecret;
        this.wxMpState = wxMpState;
        this.wxTplArrived = wxTplArrived;
        this.wxTplRefunded = wxTplRefunded;
        this.pushStub = pushStub;
        this.getuiAppId = getuiAppId;
        this.getuiAppKey = getuiAppKey;
        this.getuiMasterSecret = getuiMasterSecret;
        this.fcmStub = fcmStub;
        this.fcmProjectId = fcmProjectId;
        this.fcmClientEmail = fcmClientEmail;
        this.fcmPrivateKey = fcmPrivateKey;
        this.apnsStub = apnsStub;
        this.apnsTeamId = apnsTeamId;
        this.apnsKeyId = apnsKeyId;
        this.apnsPrivateKey = apnsPrivateKey;
        this.apnsTopic = apnsTopic;
    }

    @Override
    public List<ChannelHealth> health() {
        return List.of(
                new ChannelHealth(SysNotifyLog.SMS, smsStub, !smsStub,
                        List.of(cred("ALI_SMS_AK", smsAk, true),
                                cred("ALI_SMS_SK", smsSk, true),
                                cred("ALI_SMS_SIGN", smsSign, true),
                                cred("ALI_SMS_TPL_OTP", smsTplOtp, true)),
                        // endpoint 与签名不是密钥：签名本就印在每条短信开头
                        List.of(new Param("endpoint", smsEndpoint),
                                new Param("sign", smsSign),
                                new Param("templates.otp", smsTplOtp)),
                        sentToday(SysNotifyLog.SMS, SysNotifyLog.SENT),
                        sentToday(SysNotifyLog.SMS, SysNotifyLog.FAILED)),

                new ChannelHealth(SysNotifyLog.MAIL, mailStub, !mailStub,
                        List.of(cred("MAIL_USERNAME", mailUsername, true),
                                cred("MAIL_PASSWORD", mailPassword, true),
                                cred("MAIL_FROM", mailFrom, true)),
                        List.of(new Param("host", mailHost),
                                // from 要与 username 一致，否则 M365 认证成功而投递被拒
                                new Param("from", mailFrom)),
                        sentToday(SysNotifyLog.MAIL, SysNotifyLog.SENT),
                        sentToday(SysNotifyLog.MAIL, SysNotifyLog.FAILED)),

                new ChannelHealth(SysNotifyLog.WXSUB, wxStub, !wxStub,
                        List.of(cred("WX_APPID", wxAppid, true),
                                cred("WX_SECRET", wxSecret, true),
                                cred("WX_TPL_ORDER_ARRIVED", wxTplArrived, true),
                                cred("WX_TPL_REFUNDED", wxTplRefunded, true)),
                        // 模板号不是凭据；**运营要能核对它与端上 VITE_WX_TPL_* 是否同值**
                        List.of(new Param("appid", wxAppid),
                                new Param("mpState", wxMpState),
                                new Param("templates.orderArrived", wxTplArrived),
                                new Param("templates.refunded", wxTplRefunded)),
                        sentToday(SysNotifyLog.WXSUB, SysNotifyLog.SENT),
                        sentToday(SysNotifyLog.WXSUB, SysNotifyLog.FAILED)),

                /*
                 * PUSH 一条通道、三家供应商（个推/FCM/APNs）。**不拆成三行**：
                 * 三家都写 sys_notify_log.channel=PUSH，计数无法按供应商拆，拆成三行会重复计数
                 * （NotifyEndToEndFlowTest 也钉死了通道恰好四条）。所以把三家的凭据与启停并进这一行：
                 *   stub    = 三家全桩才算整体桩
                 *   enabled = 任一家真发即启用
                 * FCM/APNs 的凭据 required=false —— 它们是可选供应商（个推已覆盖国内 + 透传 APNs），
                 * 缺配不代表这条通道坏，只代表这家没开。
                 */
                new ChannelHealth(SysNotifyLog.PUSH,
                        pushStub && fcmStub && apnsStub,
                        !pushStub || !fcmStub || !apnsStub,
                        List.of(cred("GETUI_APP_ID", getuiAppId, true),
                                cred("GETUI_APP_KEY", getuiAppKey, true),
                                cred("GETUI_MASTER_SECRET", getuiMasterSecret, true),
                                cred("FCM_PROJECT_ID", fcmProjectId, false),
                                cred("FCM_CLIENT_EMAIL", fcmClientEmail, false),
                                cred("FCM_PRIVATE_KEY", fcmPrivateKey, false),
                                cred("APNS_TEAM_ID", apnsTeamId, false),
                                cred("APNS_KEY_ID", apnsKeyId, false),
                                cred("APNS_PRIVATE_KEY", apnsPrivateKey, false),
                                cred("APNS_TOPIC", apnsTopic, false)),
                        // 每家的启停一眼可见；appId/topic 是可公开标识，照常回显
                        List.of(new Param("getui", pushStub ? "桩" : "启用"),
                                new Param("getui.appId", getuiAppId),
                                new Param("fcm", fcmStub ? "桩" : "启用"),
                                new Param("fcm.projectId", fcmProjectId),
                                new Param("apns", apnsStub ? "桩" : "启用"),
                                new Param("apns.topic", apnsTopic)),
                        sentToday(SysNotifyLog.PUSH, SysNotifyLog.SENT),
                        sentToday(SysNotifyLog.PUSH, SysNotifyLog.FAILED)));
    }

    @Override
    public boolean isStub(String channelType, String provider) {
        return switch (channelType) {
            case SysNotifyLog.SMS -> smsStub;
            case SysNotifyLog.MAIL -> mailStub;
            case SysNotifyLog.WXSUB -> wxStub;
            case SysNotifyLog.PUSH -> switch (provider) {
                case ai.neargo.shop.message.entity.NotifyChannel.PROV_FCM -> fcmStub;
                case ai.neargo.shop.message.entity.NotifyChannel.PROV_APNS -> apnsStub;
                default -> pushStub; // GETUI
            };
            default -> false; // INAPP 从不桩
        };
    }

    @Override
    public boolean credsReady(String channelType, String provider) {
        return switch (channelType) {
            case SysNotifyLog.SMS -> present(smsAk, smsSk, smsSign, smsTplOtp);
            case SysNotifyLog.MAIL -> present(mailUsername, mailPassword, mailFrom);
            case SysNotifyLog.WXSUB -> present(wxAppid, wxSecret, wxTplArrived, wxTplRefunded);
            case SysNotifyLog.PUSH -> switch (provider) {
                case ai.neargo.shop.message.entity.NotifyChannel.PROV_FCM ->
                        present(fcmProjectId, fcmClientEmail, fcmPrivateKey);
                case ai.neargo.shop.message.entity.NotifyChannel.PROV_APNS ->
                        present(apnsTeamId, apnsKeyId, apnsPrivateKey, apnsTopic);
                default -> present(getuiAppId, getuiAppKey, getuiMasterSecret);
            };
            default -> true; // INAPP 无需凭据
        };
    }

    /** 全部非空才算齐 —— 缺一个就发不出，这里当「没配」。 */
    private static boolean present(String... values) {
        for (String v : values) {
            if (v == null || v.isBlank()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String templateIdOf(String scene) {
        // 问通道本身，而不是自己再解析一遍配置 —— 两份解析必然分叉，
        // 而分叉时页面显示的模板号与真正发出去用的不是同一个
        return wxPort.templateId(scene);
    }

    @Override
    public void saveWxTemplates(String orderArrived, String refunded, String operatorNo) {
        // 空值 = 清掉覆盖回落环境变量。写空串会让通道以为「配了一个空模板」而静默不发
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (var e : new String[][] {{"orderArrived", orderArrived}, {"refunded", refunded}}) {
            if (e[1] == null || e[1].isBlank()) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(e[0]).append("\":\"").append(e[1].trim().replace("\"", "")).append('"');
        }
        json.append('}');
        settingPort.put(ai.neargo.shop.spi.notify.WxSubscribePort.TEMPLATES_SETTING_KEY, json.toString(), operatorNo);
    }

    @Override
    public String defaultLang() {
        String v = settingPort.get(
                ai.neargo.shop.spi.notify.MailTemplatePort.DEFAULT_LANG_SETTING_KEY, null);
        /*
         * **认不出就回落 zh-CN**。这个值只在「收件人语言未知」时用，
         * 而它的下游是账号开通邮件 —— 一个拼错的语言码不该让新同事收不到登录信息。
         */
        return v != null && SUPPORTED_LANGS.contains(v.trim())
                ? v.trim() : ai.neargo.shop.message.entity.MsgTemplate.LANG_DEFAULT;
    }

    @Override
    public void saveDefaultLang(String lang, String operatorNo) {
        String v = lang == null ? "" : lang.trim();
        // 白名单校验放在写入这一侧：读的那侧回落是兜底，不是校验 ——
        // 只靠读侧兜底的话，页面上会一直显示着那个存进去的错值
        if (!SUPPORTED_LANGS.contains(v)) {
            throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.BAD_REQUEST);
        }
        settingPort.put(ai.neargo.shop.spi.notify.MailTemplatePort.DEFAULT_LANG_SETTING_KEY,
                v, operatorNo);
    }

    /** appid/appKey 这类**可公开的标识**照常回显；secret/AK/SK 只回 present。 */
    private Credential cred(String envVar, String value, boolean required) {
        return new Credential(envVar, value != null && !value.isBlank(), required);
    }

    private long sentToday(String channel, String status) {
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        return DataScopeContext.executeWithoutScope(() ->
                logMapper.selectCount(Wrappers.<SysNotifyLog>lambdaQuery()
                        .eq(SysNotifyLog::getChannel, channel)
                        .eq(SysNotifyLog::getStatus, status)
                        .ge(SysNotifyLog::getCreatedAt, dayStart)));
    }
}
