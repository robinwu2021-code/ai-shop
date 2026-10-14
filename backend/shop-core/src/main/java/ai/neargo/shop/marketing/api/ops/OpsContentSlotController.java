package ai.neargo.shop.marketing.api.ops;

import ai.neargo.shop.archive.ArchiveService;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.marketing.slot.ContentSlotService;
import ai.neargo.shop.marketing.slot.dto.SlotVOs.ContentSlotVO;
import ai.neargo.shop.marketing.slot.dto.SlotVOs.SlotSaveCmd;
import ai.neargo.shop.spi.platform.AuditLogPort;
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
 * 平台端 · 内容位（首页楼层 / 轮播 / 频道）。
 *
 * <p><b>补的是一段「凑合」而不是一块空地</b>：C 端首页那个推荐位一直按销量兜底，
 * 页面上写的却是「推荐」—— 运营想推一件新货，唯一的办法是让它先卖起来。
 * 运营端那一页也一直在 mock 上点得动（能开关、能归档），后端一行都没有。
 *
 * <p><b>这一版只有 HOME_FLOOR 被端消费</b>：C 端既没有轮播位也没有频道页，
 * 没有承接位就定不了「跳去哪」那个模型。另两种能建、能排期，但没有任何端会读 ——
 * 这是明说的现状，写在 {@code MktContentSlot} 的类注释里。
 */
@Profile("ops")
@RestController
@Validated
public class OpsContentSlotController {

    private final ContentSlotService slotService;
    private final ArchiveService archiveService;
    private final AuditLogPort auditLogPort;

    public OpsContentSlotController(ContentSlotService slotService, ArchiveService archiveService,
                                    AuditLogPort auditLogPort) {
        this.slotService = slotService;
        this.archiveService = archiveService;
        this.auditLogPort = auditLogPort;
    }

    /**
     * @param kind    为空给全部；{@code HOME_FLOOR} / {@code BANNER} / {@code CHANNEL}
     * @param enabled 为空给全部
     */
    @GetMapping("/ops/content-slots")
    @PreAuthorize("@perm.can('" + Perms.MARKETING_SLOT_READ + "')")
    public PageData<ContentSlotVO> list(@RequestParam(required = false) String kind,
                                        @RequestParam(required = false) Boolean enabled,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(defaultValue = "false") boolean showArchived,
                                        @RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "50") long size) {
        // 运营端列表页按 {records,total} 渲染 —— 返回裸数组会被当成空页
        return PageData.ofAll(slotService.opsSlots(kind, enabled, keyword, showArchived), page, size);
    }

    /** 建 / 改。{@code slotNo} 为空 = 新建（与建券同一个约定）。 */
    @PostMapping("/ops/content-slots")
    @PreAuthorize("@perm.can('" + Perms.MARKETING_SLOT_UPDATE + "')")
    public ContentSlotVO save(@RequestBody SlotSaveCmd cmd) {
        boolean isNew = cmd.slotNo() == null || cmd.slotNo().isBlank();
        ContentSlotVO vo = slotService.saveSlot(cmd);
        auditLogPort.record(isNew ? "CONTENT_SLOT_CREATE" : "CONTENT_SLOT_UPDATE", vo.slotNo(),
                vo.title() + "｜" + vo.kind() + "｜" + vo.goodsNos().size() + " 件");
        return vo;
    }

    /** 开 / 关。**即刻生效**，不等下线时间。 */
    @PostMapping("/ops/content-slots/{slotNo}/enabled")
    @PreAuthorize("@perm.can('" + Perms.MARKETING_SLOT_UPDATE + "')")
    public ContentSlotVO setEnabled(@PathVariable String slotNo, @RequestBody EnabledReq req) {
        boolean on = Boolean.TRUE.equals(req.enabled());
        ContentSlotVO vo = slotService.setEnabled(slotNo, on);
        auditLogPort.record("CONTENT_SLOT_ENABLED", slotNo, on ? "开" : "关");
        return vo;
    }

    /** 改排期。下线必须晚于上线。 */
    @PostMapping("/ops/content-slots/{slotNo}/schedule")
    @PreAuthorize("@perm.can('" + Perms.MARKETING_SLOT_UPDATE + "')")
    public ContentSlotVO setSchedule(@PathVariable String slotNo, @RequestBody ScheduleReq req) {
        ContentSlotVO vo = slotService.setSchedule(slotNo, req.onlineAt(), req.offlineAt());
        auditLogPort.record("CONTENT_SLOT_SCHEDULE", slotNo, vo.onlineAt() + " ~ " + vo.offlineAt());
        return vo;
    }

    @PostMapping("/ops/content-slots/{slotNo}/archive")
    @PreAuthorize("@perm.can('" + Perms.MARKETING_SLOT_UPDATE + "')")
    public java.util.Map<String, Object> archive(@PathVariable String slotNo) {
        long at = archiveService.archive(ArchiveService.Kind.CONTENT_SLOT, slotNo,
                SecurityUtils.currentUserNo());
        return java.util.Map.of("slotNo", slotNo, "archivedAt", at);
    }

    @PostMapping("/ops/content-slots/{slotNo}/unarchive")
    @PreAuthorize("@perm.can('" + Perms.MARKETING_SLOT_UPDATE + "')")
    public java.util.Map<String, Object> unarchive(@PathVariable String slotNo) {
        archiveService.unarchive(ArchiveService.Kind.CONTENT_SLOT, slotNo, SecurityUtils.currentUserNo());
        return java.util.Map.of("slotNo", slotNo);
    }

    public record EnabledReq(Boolean enabled) {
    }

    public record ScheduleReq(String onlineAt, String offlineAt) {
    }
}
