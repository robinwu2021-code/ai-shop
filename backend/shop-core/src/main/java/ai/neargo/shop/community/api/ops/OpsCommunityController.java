package ai.neargo.shop.community.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.community.service.CommunityAdminService;
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

import java.util.List;

/**
 * 平台端 · 社区与自提点（P-2.1 / P-2.2）。
 *
 * <p>社区是这个平台的骨架：商家的服务范围、商品的可见性、订单的数据域全挂在它上面。
 * 此前这块在真实环境是一片 404 —— 运营开城、调围栏、停用自提点都只能改库。
 */
@Profile("ops")
@RestController
@Validated
public class OpsCommunityController {

    private final CommunityAdminService adminService;
    private final AuditLogPort auditLogPort;

    public OpsCommunityController(CommunityAdminService adminService, AuditLogPort auditLogPort) {
        this.adminService = adminService;
        this.auditLogPort = auditLogPort;
    }

    /**
     * 社区列表。**读权限，不是主数据维护权限** ——
     * 入驻审核要选覆盖小区，那是 BD 的活；而改社区是运营主数据的活。
     */
    @GetMapping("/ops/communities")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_VIEW + "')")
    public ai.neargo.shop.common.PageData<CommunityAdminService.CommunityVO> communities(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean showClosed,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        // 运营端列表页按 {records,total} 渲染 —— 返回裸数组会被当成空页
        return ai.neargo.shop.common.PageData.ofAll(adminService.communities(keyword, showClosed), page, size);
    }

    /** 开城开关。关掉只停获客 —— **已有订单不受影响**。 */
    @PostMapping("/ops/communities/{communityNo}/open")
    @PreAuthorize("@perm.can('" + Perms.INDUSTRY_MANAGE + "')")
    public CommunityAdminService.CommunityVO setOpen(@PathVariable String communityNo,
                                                     @RequestBody OpenReq req) {
        boolean opened = Boolean.TRUE.equals(req.opened());
        var vo = adminService.setOpened(communityNo, opened, SecurityUtils.currentUserNo());
        // 开关城直接决定一整片区域的成交，必须能追到是谁在什么时候动的
        auditLogPort.record("COMMUNITY_OPEN", communityNo, opened ? "开城" : "关城");
        return vo;
    }

    @PostMapping("/ops/communities/{communityNo}/fence")
    @PreAuthorize("@perm.can('" + Perms.INDUSTRY_MANAGE + "')")
    public CommunityAdminService.CommunityVO setFence(@PathVariable String communityNo,
                                                      @RequestBody FenceReq req) {
        var vo = adminService.setFence(communityNo, req.fenceRadius() == null ? 0 : req.fenceRadius(),
                SecurityUtils.currentUserNo());
        auditLogPort.record("COMMUNITY_FENCE", communityNo, req.fenceRadius() + " 米");
        return vo;
    }

    // ---------------------------------------------------------------- 自提点

    @GetMapping("/ops/pickups")
    @PreAuthorize("@perm.can('" + Perms.INDUSTRY_MANAGE + "')")
    public ai.neargo.shop.common.PageData<CommunityAdminService.PickupVO> pickups(
            @RequestParam(required = false) String communityNo,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        // 运营端列表页按 {records,total} 渲染 —— 返回裸数组会被当成空页
        return ai.neargo.shop.common.PageData.ofAll(adminService.pickups(communityNo, type, status), page, size);
    }

    /** 状态。MIGRATING = 不再接新单，存量单仍在本点核销完。 */
    @PostMapping("/ops/pickups/{pickupNo}/status")
    @PreAuthorize("@perm.can('" + Perms.INDUSTRY_MANAGE + "')")
    public CommunityAdminService.PickupVO setPickupStatus(@PathVariable String pickupNo,
                                                          @RequestBody PickupStatusReq req) {
        var vo = adminService.setPickupStatus(pickupNo, req.status(), SecurityUtils.currentUserNo());
        auditLogPort.record("PICKUP_STATUS", pickupNo, req.status());
        return vo;
    }

    /** 履约服务费费率（万分比）。**NEIGHBOR 必须为 0** —— 给了报酬他就变成团长了。 */
    @PostMapping("/ops/pickups/{pickupNo}/service-fee")
    @PreAuthorize("@perm.can('" + Perms.INDUSTRY_MANAGE + "')")
    public CommunityAdminService.PickupVO setServiceFee(@PathVariable String pickupNo,
                                                        @RequestBody ServiceFeeReq req) {
        var vo = adminService.setPickupServiceFee(pickupNo,
                req.serviceFeeRate() == null ? 0 : req.serviceFeeRate(), SecurityUtils.currentUserNo());
        // 改费率是改钱，必须留痕
        auditLogPort.record("PICKUP_SERVICE_FEE", pickupNo, req.serviceFeeRate() + "‱");
        return vo;
    }

    /**
     * 高频承接的邻里自提点（P-2.2.5）。
     *
     * <p>⚠️ {@code acceptCount30d} 目前恒为 0 —— 承接次数要按核销日志聚合，那部分未落地。
     * 不编一个看起来像真的数字：运营会照着它去处置，而它是假的。
     */
    @GetMapping("/ops/pickups/risky")
    @PreAuthorize("@perm.can('" + Perms.INDUSTRY_MANAGE + "')")
    public ai.neargo.shop.common.PageData<CommunityAdminService.PickupVO> risky(
            @RequestParam(defaultValue = "30") int minAcceptCount,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        // 运营端列表页按 {records,total} 渲染 —— 返回裸数组会被当成空页
        return ai.neargo.shop.common.PageData.ofAll(adminService.riskyNeighborPickups(minAcceptCount), page, size);
    }

    public record OpenReq(Boolean opened) {
    }

    /** @param fenceRadius 覆盖围栏半径（米）。必须大于 0 —— 0 等于这个社区谁也覆盖不到 */
    public record FenceReq(Integer fenceRadius) {
    }

    public record PickupStatusReq(String status) {
    }

    public record ServiceFeeReq(Integer serviceFeeRate) {
    }
}
