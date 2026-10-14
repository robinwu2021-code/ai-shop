package ai.neargo.shop.message.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.message.entity.NotifyPushTask;
import ai.neargo.shop.message.notify.NotifyPushTaskService;
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

import java.time.LocalDateTime;

/**
 * 平台端 · 营销广播推送任务（设计：触达推送中台-模块抽象 · N6）。
 *
 * <p>运营主动发起的群发：圈人群、预估触达、定时下发。到点由 {@code NotifyPushTaskJob} 执行。
 * <b>复用 message:template 权限</b>：能管触达模板/渠道的就能发广播，是同一批运营。
 */
@Profile("ops")
@RestController
@Validated
public class OpsPushTaskController {

    private final NotifyPushTaskService service;
    private final AuditLogPort auditLogPort;

    public OpsPushTaskController(NotifyPushTaskService service, AuditLogPort auditLogPort) {
        this.service = service;
        this.auditLogPort = auditLogPort;
    }

    @GetMapping("/ops/push-tasks")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_READ + "')")
    public PageData<NotifyPushTask> list(@RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "20") long size) {
        return service.list(status, page, size);
    }

    /**
     * 预估触达：**建任务前先看覆盖多少人**。运营在填广播表单时实时看到「这一发覆盖 N 人」，
     * 不用先建一个 QUEUED 任务再看数、发现不对再取消。
     */
    @GetMapping("/ops/push-tasks/estimate")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_READ + "')")
    public EstimateVO estimate(@RequestParam String audienceType) {
        return new EstimateVO(audienceType, service.estimate(audienceType));
    }

    /** @param count 当下人群规模；随人装/卸 App 变化，只是发起时的快照参考 */
    public record EstimateVO(String audienceType, int count) {
    }

    /**
     * 新建。**创建时即预估触达人数**（当下人群规模）—— 发之前先让运营看到「这一发覆盖多少人」，
     * 而不是发完才知道。scheduledAt 空 = 尽快发。
     */
    @PostMapping("/ops/push-tasks")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_UPDATE + "')")
    public NotifyPushTask create(@jakarta.validation.Valid @RequestBody CreateReq req) {
        LocalDateTime at = req.scheduledAt() == null || req.scheduledAt().isBlank()
                ? null : LocalDateTime.parse(req.scheduledAt());
        NotifyPushTask t = service.create(req.name(), req.audienceType(), req.title(),
                req.body(), req.link(), at, SecurityUtils.currentUserNo());
        auditLogPort.record("PUSH_TASK", t.getTaskNo(),
                "新建广播「%s」预估 %d 人".formatted(t.getName(), t.getEstimatedCount()));
        return t;
    }

    /** 取消（仅 QUEUED 可取消）。 */
    @PostMapping("/ops/push-tasks/{taskNo}/cancel")
    @PreAuthorize("@perm.can('" + Perms.MESSAGE_TEMPLATE_UPDATE + "')")
    public NotifyPushTask cancel(@PathVariable String taskNo) {
        NotifyPushTask t = service.cancel(taskNo, SecurityUtils.currentUserNo());
        auditLogPort.record("PUSH_TASK", taskNo, "取消广播");
        return t;
    }

    /** @param scheduledAt ISO 本地时刻字符串或空（尽快发） */
    public record CreateReq(@NotBlank String name, @NotBlank String audienceType,
                            @NotBlank String title, @NotBlank String body,
                            String link, String scheduledAt) {
    }
}
