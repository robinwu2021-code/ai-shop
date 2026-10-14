package ai.neargo.shop.message.notify.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.message.entity.NotifyChannel;
import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.message.mapper.MessageMappers.NotifyLogMapper;
import ai.neargo.shop.message.notify.NotifyChannelService;
import ai.neargo.shop.message.notify.PlatformChannelCredentials;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link NotifyChannelService} 实现。
 *
 * <p><b>凭证不再散读</b>：以前 health 的凭证清单、credsReady 的存在性检查、gateway 构造器
 * 各读一份配置，加一个凭证要改三处、必然漂移。现在统一问 {@link PlatformChannelCredentials}
 * ——「每条通道×供应商需要哪些凭证」只声明一次，这里只负责把它组织成体检视图。
 *
 * <p><b>密钥明文永不出后端</b>：凭证项只回 present；公开标识（签名/appId/topic）才回值。
 */
@Service
public class NotifyChannelServiceImpl implements NotifyChannelService {

    private final NotifyLogMapper logMapper;
    /** 问通道要「当前生效的模板号」，不自己解析配置 —— 见 {@link #templateIdOf} */
    private final ai.neargo.shop.spi.notify.WxSubscribePort wxPort;
    private final ai.neargo.shop.spi.platform.SettingPort settingPort;
    /** 平台凭证统一真源。 */
    private final PlatformChannelCredentials creds;

    public NotifyChannelServiceImpl(
            NotifyLogMapper logMapper,
            ai.neargo.shop.spi.notify.WxSubscribePort wxPort,
            ai.neargo.shop.spi.platform.SettingPort settingPort,
            PlatformChannelCredentials creds) {
        this.logMapper = logMapper;
        this.wxPort = wxPort;
        this.settingPort = settingPort;
        this.creds = creds;
    }

    @Override
    public List<ChannelHealth> health() {
        return List.of(smsHealth(), mailHealth(), wxHealth(), pushHealth());
    }

    private ChannelHealth smsHealth() {
        boolean stub = creds.isStub(SysNotifyLog.SMS, NotifyChannel.PROV_ALI);
        return new ChannelHealth(SysNotifyLog.SMS, stub, !stub,
                creds.credentials(SysNotifyLog.SMS, NotifyChannel.PROV_ALI),
                // endpoint 与签名不是密钥：签名本就印在每条短信开头
                List.of(new Param("endpoint", creds.value("shop.sms.ali.endpoint")),
                        new Param("sign", creds.value("shop.sms.ali.sign")),
                        new Param("templates.otp", creds.value("shop.sms.ali.templates.otp"))),
                sentToday(SysNotifyLog.SMS, SysNotifyLog.SENT),
                sentToday(SysNotifyLog.SMS, SysNotifyLog.FAILED));
    }

    private ChannelHealth mailHealth() {
        boolean stub = creds.isStub(SysNotifyLog.MAIL, NotifyChannel.PROV_SMTP);
        return new ChannelHealth(SysNotifyLog.MAIL, stub, !stub,
                creds.credentials(SysNotifyLog.MAIL, NotifyChannel.PROV_SMTP),
                List.of(new Param("host", creds.value("shop.mail.host")),
                        // from 要与 username 一致，否则 M365 认证成功而投递被拒
                        new Param("from", creds.value("shop.mail.from"))),
                sentToday(SysNotifyLog.MAIL, SysNotifyLog.SENT),
                sentToday(SysNotifyLog.MAIL, SysNotifyLog.FAILED));
    }

    private ChannelHealth wxHealth() {
        boolean stub = creds.isStub(SysNotifyLog.WXSUB, NotifyChannel.PROV_WECHAT);
        return new ChannelHealth(SysNotifyLog.WXSUB, stub, !stub,
                creds.credentials(SysNotifyLog.WXSUB, NotifyChannel.PROV_WECHAT),
                // 模板号不是凭据；**运营要能核对它与端上 VITE_WX_TPL_* 是否同值**
                List.of(new Param("appid", creds.value("shop.wx.appid")),
                        new Param("mpState", creds.value("shop.wx.mp-state", "formal")),
                        new Param("templates.orderArrived", creds.value("shop.wx.templates.order-arrived")),
                        new Param("templates.refunded", creds.value("shop.wx.templates.refunded"))),
                sentToday(SysNotifyLog.WXSUB, SysNotifyLog.SENT),
                sentToday(SysNotifyLog.WXSUB, SysNotifyLog.FAILED));
    }

    /*
     * PUSH 一条通道、三家供应商（个推/FCM/APNs）。**不拆成三行**：
     * 三家都写 sys_notify_log.channel=PUSH，计数无法按供应商拆，拆成三行会重复计数
     * （NotifyEndToEndFlowTest 也钉死了通道恰好四条）。所以把三家的凭据与启停并进这一行：
     *   stub    = 三家全桩才算整体桩
     *   enabled = 任一家真发即启用
     * FCM/APNs 的凭据 required=false —— 它们是可选供应商（个推已覆盖国内 + 透传 APNs），
     * 由 PlatformChannelCredentials 的 providerRequired 标注，缺配不误报「通道坏了」。
     */
    private ChannelHealth pushHealth() {
        boolean getuiStub = creds.isStub(SysNotifyLog.PUSH, NotifyChannel.PROV_GETUI);
        boolean fcmStub = creds.isStub(SysNotifyLog.PUSH, NotifyChannel.PROV_FCM);
        boolean apnsStub = creds.isStub(SysNotifyLog.PUSH, NotifyChannel.PROV_APNS);

        List<Credential> credentials = new ArrayList<>();
        credentials.addAll(creds.credentials(SysNotifyLog.PUSH, NotifyChannel.PROV_GETUI));
        credentials.addAll(creds.credentials(SysNotifyLog.PUSH, NotifyChannel.PROV_FCM));
        credentials.addAll(creds.credentials(SysNotifyLog.PUSH, NotifyChannel.PROV_APNS));

        // 每家的启停 + 今日发送数一眼可见（N3 拆到供应商）；appId/topic 可公开回显
        List<Param> params = List.of(
                new Param("getui", getuiStub ? "桩" : "启用"),
                new Param("getui.appId", creds.value("shop.push.getui.app-id")),
                new Param("getui.todaySent", String.valueOf(
                        sentToday(SysNotifyLog.PUSH, NotifyChannel.PROV_GETUI, SysNotifyLog.SENT))),
                new Param("fcm", fcmStub ? "桩" : "启用"),
                new Param("fcm.projectId", creds.value("shop.push.fcm.project-id")),
                new Param("fcm.todaySent", String.valueOf(
                        sentToday(SysNotifyLog.PUSH, NotifyChannel.PROV_FCM, SysNotifyLog.SENT))),
                new Param("apns", apnsStub ? "桩" : "启用"),
                new Param("apns.topic", creds.value("shop.push.apns.topic")),
                new Param("apns.todaySent", String.valueOf(
                        sentToday(SysNotifyLog.PUSH, NotifyChannel.PROV_APNS, SysNotifyLog.SENT))));

        return new ChannelHealth(SysNotifyLog.PUSH,
                getuiStub && fcmStub && apnsStub,
                !getuiStub || !fcmStub || !apnsStub,
                credentials, params,
                sentToday(SysNotifyLog.PUSH, SysNotifyLog.SENT),
                sentToday(SysNotifyLog.PUSH, SysNotifyLog.FAILED));
    }

    @Override
    public boolean isStub(String channelType, String provider) {
        return creds.isStub(channelType, provider);
    }

    @Override
    public boolean credsReady(String channelType, String provider) {
        return creds.credsReady(channelType, provider);
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

    private long sentToday(String channel, String status) {
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        return DataScopeContext.executeWithoutScope(() ->
                logMapper.selectCount(Wrappers.<SysNotifyLog>lambdaQuery()
                        .eq(SysNotifyLog::getChannel, channel)
                        .eq(SysNotifyLog::getStatus, status)
                        .ge(SysNotifyLog::getCreatedAt, dayStart)));
    }

    /** 按供应商细分的今日计数（N3：sys_notify_log.provider 落地后才拆得开个推/FCM/APNs）。 */
    private long sentToday(String channel, String provider, String status) {
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        return DataScopeContext.executeWithoutScope(() ->
                logMapper.selectCount(Wrappers.<SysNotifyLog>lambdaQuery()
                        .eq(SysNotifyLog::getChannel, channel)
                        .eq(SysNotifyLog::getProvider, provider)
                        .eq(SysNotifyLog::getStatus, status)
                        .ge(SysNotifyLog::getCreatedAt, dayStart)));
    }
}
