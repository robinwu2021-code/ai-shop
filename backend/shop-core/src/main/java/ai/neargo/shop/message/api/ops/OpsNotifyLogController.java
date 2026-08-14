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
    private final ai.neargo.shop.message.notify.NotifyChannelService channelService;
    private final CaptchaService captchaService;
    private final AuditLogPort auditLogPort;

    public OpsNotifyLogController(NotifyLogService notifyLogService,
                                  ai.neargo.shop.message.notify.NotifyChannelService channelService,
                                  CaptchaService captchaService,
                                  AuditLogPort auditLogPort) {
        this.notifyLogService = notifyLogService;
        this.channelService = channelService;
        this.captchaService = captchaService;
        this.auditLogPort = auditLogPort;
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
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String bizType,
                                       @RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to,
                                       @RequestParam(required = false) String target,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long size) {
        return notifyLogService.list(channel, status, bizType, from, to, target, page, size);
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
