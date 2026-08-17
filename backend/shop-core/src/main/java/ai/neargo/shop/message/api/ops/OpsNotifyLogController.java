package ai.neargo.shop.message.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.common.captcha.CaptchaService;
import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.message.notify.NotifyLogService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 短信/邮件发送记录与测试发送（P-14.3）。
 *
 * <p><b>复用 {@code message:template:*} 而不新增权限码</b>：维护消息模板的
 * 与看发送记录的是同一批人，多一个码只增加配置负担。
 */
@Profile("ops")
@RestController
@Validated
public class OpsNotifyLogController {

    private final NotifyLogService notifyLogService;
    private final ai.neargo.shop.message.MessageService messageService;
    private final ai.neargo.shop.message.notify.NotifyChannelService channelService;
    private final ai.neargo.shop.message.notify.NotifyChannelRegistry channelRegistry;
    private final ai.neargo.shop.message.notify.MerchantChannelService merchantChannelService;
    private final CaptchaService captchaService;
    private final AuditLogPort auditLogPort;

    public OpsNotifyLogController(NotifyLogService notifyLogService,
                                  ai.neargo.shop.message.MessageService messageService,
                                  ai.neargo.shop.message.notify.NotifyChannelService channelService,
                                  ai.neargo.shop.message.notify.NotifyChannelRegistry channelRegistry,
                                  ai.neargo.shop.message.notify.MerchantChannelService merchantChannelService,
                                  CaptchaService captchaService,
                                  AuditLogPort auditLogPort) {
        this.notifyLogService = notifyLogService;
        this.messageService = messageService;
        this.channelService = channelService;
        this.channelRegistry = channelRegistry;
        this.merchantChannelService = merchantChannelService;
        this.captchaService = captchaService;
        this.auditLogPort = auditLogPort;
    }

    /**
     * 渠道注册表（设计：触达推送中台 · N2）。列出所有渠道实例（类型×供应商×接入范围），
     * 带**读时派生**的状态。与上面 {@code /ops/notify-channels}（按通道类型的体检）互补：
     * 那个答「这条通道能不能用」，这个答「平台登记了哪些渠道、各自接入范围与开关」。
     */
    @GetMapping("/ops/notify-channels/registry")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_READ + "')")
    public java.util.List<NotifyChannelVO> channelRegistry() {
        return channelRegistry.list().stream()
                .map(ch -> NotifyChannelVO.of(ch, channelRegistry.statusOf(ch),
                        channelRegistry.missingCreds(ch))).toList();
    }

    /**
     * 软启停某条渠道。INAPP 会被后端拒绝（站内信是事实记录，前端被绕过也兜住）。
     * 触达渠道启停要留痕：它决定平台某条外发通道整体开不开。
     */
    @PostMapping("/ops/notify-channels/registry/{channelNo}/enabled")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_UPDATE + "')")
    public NotifyChannelVO setChannelEnabled(@PathVariable String channelNo,
                                             @RequestBody EnabledReq req) {
        boolean on = Boolean.TRUE.equals(req.enabled());
        var ch = channelRegistry.setEnabled(channelNo, on, SecurityUtils.currentUserNo());
        auditLogPort.record("NOTIFY_CHANNEL", channelNo, on ? "启用" : "停用");
        return NotifyChannelVO.of(ch, channelRegistry.statusOf(ch), channelRegistry.missingCreds(ch));
    }

    /**
     * 外部接入（N5）：平台代商家配置其自带渠道。密钥经加密落库、**响应体永不含明文**
     * （NotifyChannelVO 就没有 secret 字段）。secret 为空表示只改非密项、不动已存密钥。
     */
    @GetMapping("/ops/notify-channels/merchant")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_READ + "')")
    public java.util.List<NotifyChannelVO> merchantChannels(@RequestParam String ownerNo) {
        return merchantChannelService.listForOwner(ownerNo).stream()
                .map(ch -> NotifyChannelVO.of(ch, channelRegistry.statusOf(ch),
                        channelRegistry.missingCreds(ch))).toList();
    }

    @PostMapping("/ops/notify-channels/merchant")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_UPDATE + "')")
    public NotifyChannelVO saveMerchantChannel(@RequestBody MerchantChannelReq req) {
        var ch = merchantChannelService.upsert(req.ownerNo(), req.channelType(), req.provider(),
                req.configJson(), req.secret(), SecurityUtils.currentUserNo());
        auditLogPort.record("NOTIFY_MERCHANT_CHANNEL", ch.getChannelNo(),
                "配置商家渠道 " + req.channelType() + "/" + req.provider());
        return NotifyChannelVO.of(ch, channelRegistry.statusOf(ch), channelRegistry.missingCreds(ch));
    }

    /** @param secret 商家凭据明文；空=只改非密项不动密钥。**请求进、密文存，永不回传** */
    public record MerchantChannelReq(String ownerNo, String channelType, String provider,
                                     String configJson, String secret) {
    }

    /**
     * @param status      读时派生（UNCONFIGURED/STUB/READY/DISABLED/DEGRADED），不落库
     * @param missingCreds 平台接入还缺哪些环境变量（供运维直接照配）；商家/测试接入为空
     * @param locked      INAPP 恒锁定：站内信不可关
     */
    public record NotifyChannelVO(String channelNo, String channelType, String provider,
                                  String scope, String ownerNo, boolean enabled, String status,
                                  int priority, String credRef, String configJson,
                                  java.util.List<String> missingCreds, boolean locked) {
        static NotifyChannelVO of(ai.neargo.shop.message.entity.NotifyChannel c, String status,
                                  java.util.List<String> missingCreds) {
            boolean inapp = ai.neargo.shop.message.entity.NotifyChannel.TYPE_INAPP.equals(c.getChannelType());
            return new NotifyChannelVO(c.getChannelNo(), c.getChannelType(), c.getProvider(),
                    c.getScope(), c.getOwnerNo(), Boolean.TRUE.equals(c.getEnabled()), status,
                    c.getPriority() == null ? 100 : c.getPriority(), c.getCredRef(),
                    c.getConfigJson(), missingCreds, inapp);
        }
    }

    public record EnabledReq(Boolean enabled) {
    }

    /**
     * 四条通道的体检（TDD-运营端触达中心 §4.1）。
     *
     * <p><b>只回「配了没有」，不回密钥明文</b>。此前「短信为什么没发出去」
     * 只能登服务器看环境变量 —— 而那时通常是深夜、且改不动。
     */
    @GetMapping("/ops/notify-channels")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_READ + "')")
    public java.util.List<ai.neargo.shop.message.notify.NotifyChannelService.ChannelHealth> channels() {
        return channelService.health();
    }

    @GetMapping("/ops/notify-logs")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_READ + "')")
    public PageData<SysNotifyLog> list(@RequestParam(required = false) String channel,
                                       @RequestParam(required = false) String provider,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String bizType,
                                       @RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to,
                                       @RequestParam(required = false) String target,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long size) {
        // provider 筛选（N3）：终于能只看 FCM / 只看 APNs 的记录，不再混在 PUSH 一格里
        return notifyLogService.list(channel, provider, status, bizType, from, to, target, page, size);
    }

    /**
     * 站内信记录（发送记录页的第二个 tab）。
     *
     * <p><b>与上面那条分开而不是合成一张表</b>：外发记录答「发出去了吗」（有失败态、
     * 排查要去通道后台查回执），站内信答「他读了吗」（入库即到达，没有失败态）。
     * 合成一列的话，同一个「已发送」在两种语义之间摇摆 ——
     * 而运营看到它时的下一步动作完全不同：一个去通道后台，一个去问用户为什么没点。
     *
     * <p>复用 {@code message:template:read}：看外发记录的与看站内信记录的是同一批人。
     */
    @GetMapping("/ops/inapp-messages")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_READ + "')")
    public PageData<ai.neargo.shop.message.dto.MessageVOs.InAppLogVO> inAppMessages(
            @RequestParam(required = false) String receiverType,
            @RequestParam(required = false) String receiverNo,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return messageService.opsInAppMessages(receiverType, receiverNo, from, to, page, size);
    }

    /**
     * 取一张图形验证码。
     *
     * <p><b>只挂登录，不挂权限码</b>：它本身不泄露任何东西，
     * 而挂上权限码会让「没权限的人连验证码都取不到」——那时页面上是一张裂图，
     * 看不出是权限问题。权限在 {@link #testSend} 那一步判。
     */
    @GetMapping("/ops/captcha")
    public CaptchaService.Challenge captcha() {
        return captchaService.issue();
    }

    /**
     * 测试发送。三道闸齐：权限码 + 图形验证码 + 按操作人限流。
     *
     * <p>它是能**指定任意收件人**的接口——只上权限码的话，账号泄漏就等于
     * 拿到一台群发机，而且发的是带平台签名的正规短信。
     */
    @PostMapping("/ops/notify-logs/test-send")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_UPDATE + "')")
    public void testSend(@RequestBody TestSendReq req) {
        String operator = SecurityUtils.currentUserNo();
        notifyLogService.testSend(req.channel(), req.target(), req.level(),
                new NotifyLogService.TestContent(req.subject(), req.body(), req.params()),
                req.captchaId(), req.captchaCode(), operator);
        // **审计里不写收件人明文**：发送记录那张表已经存了掩码版，这里只记「谁测了哪个渠道」
        auditLogPort.record("NOTIFY_TEST_SEND", operator, req.channel());
    }

    /**
     * 模拟发送前的收件人预检。
     *
     * <p>页面在运营填完 userNo 时调它 —— 图形验证码是一次性的，
     * 而「这个用户没额度 / 没绑设备」输完就能知道。不预检的话，
     * 填完表输完验证码点了发送才被告知「换个账号」，那张验证码已经废了。
     */
    @PostMapping("/ops/notify-logs/precheck")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_UPDATE + "')")
    public void precheck(@RequestBody PrecheckReq req) {
        notifyLogService.precheckTestTarget(req.channel(), req.target(), req.scene());
    }

    /** @param scene 仅微信用（ORDER_ARRIVED/REFUNDED）。额度逐模板授权，预检要查选中的那条 */
    public record PrecheckReq(@NotBlank String channel, @NotBlank String target, String scene) {
    }

    /**
     * 站内信的模拟发送（TDD-运营端触达中心 §5.5）。
     *
     * <p><b>不过图形验证码</b>：它只能往本平台的收件箱里塞，发不出去也骚扰不到外部的人 ——
     * 给它加验证码是把「防群发」的闸装在一扇不通往外面的门上。权限码与审计照旧。
     */
    @PostMapping("/ops/notify-logs/test-inapp")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_UPDATE + "')")
    public void testInApp(@RequestBody TestInAppReq req) {
        String operator = SecurityUtils.currentUserNo();
        notifyLogService.testInApp(req.receiverType(), req.receiverNo(), req.title(),
                req.body(), req.link(), operator);
        auditLogPort.record("NOTIFY_TEST_INAPP", operator, req.receiverType());
    }

    /** @param receiverType USER / STAFF / OPS */
    public record TestInAppReq(@NotBlank String receiverType, @NotBlank String receiverNo,
                               @NotBlank String title, String body, String link) {
    }

    /**
     * 微信订阅消息的模板号映射（TDD-运营端触达中心 §4.2）。
     *
     * <p><b>这是唯一一项从环境变量开放到运营端的通道参数</b>：模板号不是凭据
     * （拿到也发不出东西），而 mp 后台重新报备之后要发一次版才能生效是不合理的。
     * 没配过时读到的是环境变量的值，所以对既有部署零行为变化。
     *
     * <p>⚠️ 端上（{@code VITE_WX_TPL_*}）必须同值 —— 不同值的话前端攒的订阅额度
     * 后端查不到，一条也发不出去。页面要把这句话写在输入框旁边。
     */
    @GetMapping("/ops/notify-channels/wx-templates")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_READ + "')")
    public WxTemplatesVO wxTemplates() {
        return new WxTemplatesVO(
                channelService.templateIdOf(ai.neargo.shop.spi.notify.WxSubscribePort.SCENE_ORDER_ARRIVED),
                channelService.templateIdOf(ai.neargo.shop.spi.notify.WxSubscribePort.SCENE_REFUNDED));
    }

    @PostMapping("/ops/notify-channels/wx-templates")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_UPDATE + "')")
    public WxTemplatesVO saveWxTemplates(@RequestBody WxTemplatesVO req) {
        String operator = SecurityUtils.currentUserNo();
        channelService.saveWxTemplates(req.orderArrived(), req.refunded(), operator);
        auditLogPort.record("NOTIFY_WX_TEMPLATES_SAVE", operator, "wx-templates");
        return wxTemplates();
    }

    public record WxTemplatesVO(String orderArrived, String refunded) {
    }

    /**
     * 平台默认语言（触达能力矩阵 G2e）。
     *
     * <p><b>它答的是「收件人语言未知时按哪种发」</b>，不是「所有邮件用哪种语言」——
     * 知道收件人语言时（本人发起的请求，如忘记密码）一律用他自己的。
     * 目前唯一用到它的是「管理员替别人建账号」：那封信的收件人还没登录过。
     *
     * <p>跨通道，所以放在**通道总览**而不是邮件页 —— 它不是邮件的配置。
     */
    @GetMapping("/ops/notify-channels/default-lang")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_READ + "')")
    public DefaultLangVO defaultLang() {
        return new DefaultLangVO(channelService.defaultLang(),
                ai.neargo.shop.message.notify.NotifyChannelService.SUPPORTED_LANGS);
    }

    @PostMapping("/ops/notify-channels/default-lang")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_UPDATE + "')")
    public DefaultLangVO saveDefaultLang(@RequestBody DefaultLangVO req) {
        String operator = SecurityUtils.currentUserNo();
        channelService.saveDefaultLang(req.lang(), operator);
        auditLogPort.record("NOTIFY_DEFAULT_LANG_SAVE", operator, req.lang());
        return defaultLang();
    }

    /** @param options 可选值一并下发 —— 端上硬编码一份的话，加语言时两边会不同步 */
    public record DefaultLangVO(String lang, java.util.List<String> options) {
    }

    /**
     * @param target  随通道而变：SMS=手机号 / MAIL=邮箱 / WXSUB、PUSH=<b>userNo</b>
     * @param level   仅 PUSH 用（{@code RING} / {@code NORMAL}），其余通道忽略
     * @param subject 邮件主题 / 推送标题。**短信忽略**（正文由报备模板决定）
     * @param body    邮件正文 / 推送正文。同上
     * @param params  模板参数（短信 {@code code}、微信 {@code thing2}…）。
     *                <b>短信只认这个</b> —— 阿里云收模板号+参数，不收自由文本
     */
    public record TestSendReq(@NotBlank String channel, @NotBlank String target, String level,
                              String subject, String body, java.util.Map<String, String> params,
                              @NotBlank String captchaId, @NotBlank String captchaCode) {
    }
}
