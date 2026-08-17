package ai.neargo.shop.message.api.ops;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.dto.MessageVOs.NotifyQuotaVO;
import ai.neargo.shop.message.dto.MessageVOs.TemplateVO;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 触达治理（P-14.1）：消息模板与频控。
 *
 * <p>缺口的来源：{@code msg_subscribe.template_id} 一直在引用模板 ID，
 * 而没有任何表管理这些模板——想停掉一个扰民的模板，平台侧无从下手。
 *
 * <p><b>推送任务（{@code /ops/push-tasks} 三条）没做</b>，那不是补齐：
 * 它要人群圈选、预估触达、定时调度，是一个新功能，不是「模板管理」缺的那半截。
 * 混进对齐的排期里会让人低估它。
 */
@Profile("ops")
@RestController
@Validated
public class OpsNotifyController {

    private final MessageService messageService;
    private final AuditLogPort auditLogPort;
    private final ai.neargo.shop.message.notify.SceneChannelRouting sceneChannelRouting;

    public OpsNotifyController(MessageService messageService, AuditLogPort auditLogPort,
                               ai.neargo.shop.message.notify.SceneChannelRouting sceneChannelRouting) {
        this.messageService = messageService;
        this.auditLogPort = auditLogPort;
        this.sceneChannelRouting = sceneChannelRouting;
    }

    @GetMapping("/ops/msg-templates")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_READ + "')")
    public PageData<TemplateVO> templates(@RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "50") long size) {
        // 运营端列表页按 {records,total} 渲染 —— 返回裸数组会被当成空页
        return PageData.ofAll(messageService.opsTemplates(), page, size);
    }

    /** 停用即刻生效：引用这个模板的推送发不出去。 */
    @PostMapping("/ops/msg-templates/{templateNo}/enabled")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_UPDATE + "')")
    public TemplateVO setEnabled(@PathVariable String templateNo, @RequestBody EnabledReq req) {
        boolean on = Boolean.TRUE.equals(req.enabled());
        TemplateVO vo = messageService.setTemplateEnabled(templateNo, on,
                SecurityUtils.currentUserNo());
        auditLogPort.record("MSG_TEMPLATE", templateNo, on ? "启用" : "停用");
        return vo;
    }

    @GetMapping("/ops/notify-quota")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_READ + "')")
    public NotifyQuotaVO quota() {
        return messageService.notifyQuota();
    }

    /**
     * 保存频控。两个上限都必须 &gt; 0 —— 0 等于没有频控但界面上看着像配了。
     *
     * <p>改频控要留痕：它决定平台一天能给用户发多少条，调松了是全体用户被打扰。
     */
    @PostMapping("/ops/notify-quota")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_UPDATE + "')")
    public NotifyQuotaVO saveQuota(@RequestBody QuotaReq req) {
        NotifyQuotaVO vo = messageService.saveNotifyQuota(req.dailyPerUser(), req.minIntervalHours(),
                SecurityUtils.currentUserNo());
        auditLogPort.record("NOTIFY_QUOTA", "notify.quota",
                "每人每日 %d 条｜同模板间隔 %d 小时".formatted(vo.dailyPerUser(), vo.minIntervalHours()));
        return vo;
    }

    // ------------------------------------------------------------ 场景×通道配置

    /**
     * 场景×通道矩阵（设计：多渠道推送与运营端触达配置 · 需求 1）。
     *
     * <p>「哪个事件走哪些通道」以前硬编码在编排里，这里让运营看得见、能勾选。
     * INAPP 行回传 {@code locked=true}：站内信是事实记录，界面锁定不给关。
     */
    @GetMapping("/ops/scene-channel")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_READ + "')")
    public List<SceneChannelVO> sceneChannels() {
        return sceneChannelRouting.list().stream().map(SceneChannelVO::of).toList();
    }

    /**
     * 切换某格开关。INAPP 会被后端拒绝（站内信不可关，前端被绕过也兜住）。
     * 触达策略调整要留痕：它决定平台在某场景对用户发不发某条通道。
     */
    @PostMapping("/ops/scene-channel/{scene}/{audience}/{channel}")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_UPDATE + "')")
    public SceneChannelVO setSceneChannel(@PathVariable String scene, @PathVariable String audience,
                                          @PathVariable String channel, @RequestBody EnabledReq req) {
        boolean on = Boolean.TRUE.equals(req.enabled());
        SceneChannelVO vo = SceneChannelVO.of(
                sceneChannelRouting.setEnabled(scene, audience, channel, on, SecurityUtils.currentUserNo()));
        auditLogPort.record("SCENE_CHANNEL", scene + "/" + audience + "/" + channel,
                on ? "开启" : "关闭");
        return vo;
    }

    public record EnabledReq(Boolean enabled) {
    }

    public record QuotaReq(int dailyPerUser, int minIntervalHours) {
    }

    /** @param locked INAPP 恒锁定：站内信是事实记录，运营不可关 */
    public record SceneChannelVO(String scene, String audience, String channel,
                                 boolean enabled, String pushLevel, boolean locked) {
        static SceneChannelVO of(ai.neargo.shop.message.entity.MsgSceneChannel r) {
            boolean inapp = ai.neargo.shop.message.entity.MsgSceneChannel.CH_INAPP.equals(r.getChannel());
            return new SceneChannelVO(r.getSceneCode(), r.getAudience(), r.getChannel(),
                    Boolean.TRUE.equals(r.getEnabled()), r.getPushLevel(), inapp);
        }
    }
}
