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
    private final CaptchaService captchaService;
    private final AuditLogPort auditLogPort;

    public OpsNotifyLogController(NotifyLogService notifyLogService, CaptchaService captchaService,
                                  AuditLogPort auditLogPort) {
        this.notifyLogService = notifyLogService;
        this.captchaService = captchaService;
        this.auditLogPort = auditLogPort;
    }

    @GetMapping("/ops/notify-logs")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_READ + "')")
    public PageData<SysNotifyLog> list(@RequestParam(required = false) String channel,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long size) {
        return notifyLogService.list(channel, status, page, size);
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
        notifyLogService.testSend(req.channel(), req.target(), req.captchaId(), req.captchaCode(),
                operator);
        // **审计里不写收件人明文**：发送记录那张表已经存了掩码版，这里只记「谁测了哪个渠道」
        auditLogPort.record("NOTIFY_TEST_SEND", operator, req.channel());
    }

    /**
     * @param channel     {@code SMS} / {@code MAIL}
     * @param target      收件手机号或邮箱
     * @param captchaId   {@link #captcha()} 返回的挑战 ID
     * @param captchaCode 用户看图输入的四位字符
     */
    public record TestSendReq(@NotBlank String channel, @NotBlank String target,
                              @NotBlank String captchaId, @NotBlank String captchaCode) {
    }
}
