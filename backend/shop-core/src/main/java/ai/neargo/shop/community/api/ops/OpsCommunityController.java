package ai.neargo.shop.community.api.ops;

import ai.neargo.shop.spi.platform.MasterDataPort;
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

    /** 区划推断走 spi Port —— community 域不直连 platform（ArchUnit 第 1 条） */
    private final MasterDataPort masterDataPort;

    public OpsCommunityController(CommunityAdminService adminService, AuditLogPort auditLogPort,
                                  ai.neargo.shop.archive.ArchiveService archiveService,
                                  MasterDataPort masterDataPort) {
        this.adminService = adminService;
        this.auditLogPort = auditLogPort;
        this.archiveService = archiveService;
        this.masterDataPort = masterDataPort;
    }

    private final ai.neargo.shop.archive.ArchiveService archiveService;

    /*
     * 自提点归档 = 软删除，与「停用」正交：停用的点还在列表里（今天不接单，
     * 明天可能恢复），归档的从默认列表消失。
     *
     * 社区没有归档端点是**刻意的** —— 页面上那个按钮本来就禁用着，
     * 并带了解释「该社区下仍有自提点，请先迁移或停用」。前置校验做在了正确的位置。
     */
    @PostMapping("/ops/pickups/{pickupNo}/archive")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_PICKUP_UPDATE + "')")
    public java.util.Map<String, Object> archivePickup(@PathVariable String pickupNo) {
        long at = archiveService.archive(ai.neargo.shop.archive.ArchiveService.Kind.PICKUP,
                pickupNo, ai.neargo.shop.auth.SecurityUtils.currentUserNo());
        return java.util.Map.of("pickupNo", pickupNo, "archivedAt", at);
    }

    /**
     * 自建自提点审核（P1）。队列用现有 {@code GET /ops/pickups?status=PENDING}。
     */
    @PostMapping("/ops/pickups/{pickupNo}/decide")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_PICKUP_UPDATE + "')")
    public CommunityAdminService.PickupVO decidePickup(@PathVariable String pickupNo,
                                                       @RequestBody PickupDecideReq req) {
        CommunityAdminService.PickupVO vo = adminService.decidePickup(
                pickupNo, Boolean.TRUE.equals(req.pass()), req.reason(),
                ai.neargo.shop.auth.SecurityUtils.currentUserNo());
        auditLogPort.record("PICKUP_DECIDE", pickupNo,
                Boolean.TRUE.equals(req.pass()) ? "通过，自建点生效" : "驳回：" + req.reason());
        return vo;
    }

    public record PickupDecideReq(Boolean pass, String reason) {
    }

    @PostMapping("/ops/pickups/{pickupNo}/unarchive")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_PICKUP_UPDATE + "')")
    public java.util.Map<String, Object> unarchivePickup(@PathVariable String pickupNo) {
        archiveService.unarchive(ai.neargo.shop.archive.ArchiveService.Kind.PICKUP,
                pickupNo, ai.neargo.shop.auth.SecurityUtils.currentUserNo());
        return java.util.Map.of("pickupNo", pickupNo);
    }

    /**
     * 社区列表。**读权限，不是主数据维护权限** ——
     * 入驻审核要选覆盖小区，那是 BD 的活；而改社区是运营主数据的活。
     */
    @GetMapping("/ops/communities")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_READ + "')")
    public ai.neargo.shop.common.PageData<CommunityAdminService.CommunityVO> communities(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean showClosed,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        // 运营端列表页按 {records,total} 渲染 —— 返回裸数组会被当成空页
        return ai.neargo.shop.common.PageData.ofAll(adminService.communities(keyword, showClosed), page, size);
    }

    // ---------------------------------------------------------------- 商家提报的新社区（ADR-013 阶段三）

    /**
     * 提报队列。默认只看待审 —— 这是个队列，历史记录是次要视图。
     *
     * <p>用 {@code COMMUNITY_VIEW}：看队列是读。裁决那个端点才是写。
     */
    @GetMapping("/ops/communities/applies")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_READ + "')")
    public ai.neargo.shop.common.PageData<CommunityAdminService.ApplyVO> applies(
            @RequestParam(required = false, defaultValue = "PENDING") String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        String s = "ALL".equalsIgnoreCase(status) ? null : status;
        return ai.neargo.shop.common.PageData.ofAll(adminService.applies(s), page, size);
    }

    /**
     * 一个坐标附近已开通的聚落。裁决时查重用 —— 名字不同、位置只差 50 米的，靠文字比对看不出来。
     */
    @GetMapping("/ops/communities/near")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_READ + "')")
    public java.util.List<CommunityAdminService.NearbyVO> communitiesNear(
            @RequestParam int latE6, @RequestParam int lngE6,
            @RequestParam(defaultValue = "2000") int radiusM) {
        return adminService.communitiesNear(latE6, lngE6, radiusM);
    }

    /**
     * 疑似重复的聚落清单。
     *
     * <p><b>from-map 上线之后这条队列才真正需要</b>：商家在选择器里点一条地图地点就直接建档，
     * 建档时的三道查重只在当场比一次 —— 而改名、补坐标、误挂到隔壁街道都会让两条事后才撞上。
     * 撞上的后果不报错：商家甲选了 A、乙选了 B，买家在 B 里搜不到甲的货，两边都以为自己上架了。
     */
    @GetMapping("/ops/communities/duplicates")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_READ + "')")
    public java.util.List<CommunityAdminService.DuplicateVO> duplicates(
            @RequestParam(defaultValue = "50") int limit) {
        return adminService.duplicates(limit);
    }

    /**
     * 合并两条聚落：把 {@code fromNo} 并进 {@code intoNo}。
     *
     * <p>写权限用 {@code COMMUNITY_UPDATE}：它改的是主数据与一批商家的可见范围，
     * 与开城、改归属同一量级；看队列的人不该有这个权。
     */
    @PostMapping("/ops/communities/merge")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_UPDATE + "')")
    public CommunityAdminService.CommunityVO merge(@RequestBody MergeReq req) {
        return adminService.merge(req.fromNo(), req.intoNo(), SecurityUtils.currentUserNo());
    }

    /** @param intoNo 留下来的那条。运营要挑名字更规范的那个，被并的名字会进 alias */
    public record MergeReq(String fromNo, String intoNo) {
    }

    /**
     * 按提报单上的地址与坐标推断该挂哪个街道。
     *
     * <p>裁决那一屏原本要从 31 个省点到街道 —— 而单子上明明写着
     * 「广东省深圳市龙华区福城街道…」，坐标也在。推不出来就返回空，端上退回手选，不拦。
     */
    @GetMapping("/ops/regions/resolve")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_READ + "')")
    public java.util.List<MasterDataPort.RegionSuggestion> resolveRegion(
            @RequestParam(required = false) String address,
            @RequestParam(required = false) Integer latE6,
            @RequestParam(required = false) Integer lngE6) {
        return masterDataPort.resolveRegion(address, latE6, lngE6);
    }

    /**
     * 裁决提报：通过就<b>当场建出这个社区</b>，驳回必须写原因（原样回给商家）。
     *
     * <p>用 {@code INDUSTRY_MANAGE} 而不是 {@code COMMUNITY_VIEW}：它建的是一条主数据，
     * 与开城、改归属同一量级 —— 而看队列的人（BD、客服）不该有这个权。
     */
    @PostMapping("/ops/communities/applies/{applyNo}/decide")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_UPDATE + "')")
    public CommunityAdminService.ApplyVO decideApply(@PathVariable String applyNo,
                                                     @RequestBody ApplyDecideReq req) {
        boolean pass = Boolean.TRUE.equals(req.pass());
        var vo = adminService.decideApply(applyNo, pass, req.regionCode(), req.reason(),
                SecurityUtils.currentUserNo());
        // 通过 = 平台多了一个运营单元，必须能追到是谁批的
        auditLogPort.record("COMMUNITY_APPLY_DECIDE", applyNo,
                pass ? "通过，建社区 " + vo.communityNo() : "驳回：" + req.reason());
        return vo;
    }

    /** @param regionCode 运营最终认定的区划；空则沿用商家填的 */
    public record ApplyDecideReq(Boolean pass, String regionCode, String reason) {
    }

    /** 开城开关。关掉只停获客 —— **已有订单不受影响**。 */
    @PostMapping("/ops/communities/{communityNo}/open")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_UPDATE + "')")
    public CommunityAdminService.CommunityVO setOpen(@PathVariable String communityNo,
                                                     @RequestBody OpenReq req) {
        boolean opened = Boolean.TRUE.equals(req.opened());
        var vo = adminService.setOpened(communityNo, opened, SecurityUtils.currentUserNo());
        // 开关城直接决定一整片区域的成交，必须能追到是谁在什么时候动的
        auditLogPort.record("COMMUNITY_OPEN", communityNo, opened ? "开城" : "关城");
        return vo;
    }

    /**
     * 把社区挂到某个行政区划下（ADR-013 阶段一）。
     *
     * <p>挂了之后「按区/按街道覆盖」才能命中它。用 {@code INDUSTRY_MANAGE} 而不是
     * {@code COMMUNITY_VIEW}：改归属会改变这个社区出现在谁的经营范围里，
     * 与开城、围栏是同一量级的主数据操作。
     */
    @PostMapping("/ops/communities/{communityNo}/region")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_UPDATE + "')")
    public CommunityAdminService.CommunityVO setRegion(@PathVariable String communityNo,
                                                       @RequestBody RegionReq req) {
        var vo = adminService.setRegion(communityNo, req.regionCode(), SecurityUtils.currentUserNo());
        // 改归属会让这个社区进出别人的覆盖范围，必须能追到是谁在什么时候动的
        auditLogPort.record("COMMUNITY_REGION", communityNo,
                vo.regionPath() == null ? "清空归属" : vo.regionPath());
        return vo;
    }

    public record RegionReq(String regionCode) {
    }

    @PostMapping("/ops/communities/{communityNo}/fence")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_UPDATE + "')")
    public CommunityAdminService.CommunityVO setFence(@PathVariable String communityNo,
                                                      @RequestBody FenceReq req) {
        var vo = adminService.setFence(communityNo, req.fenceRadius() == null ? 0 : req.fenceRadius(),
                SecurityUtils.currentUserNo());
        auditLogPort.record("COMMUNITY_FENCE", communityNo, req.fenceRadius() + " 米");
        return vo;
    }

    // ---------------------------------------------------------------- 自提点

    @GetMapping("/ops/pickups")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_PICKUP_READ + "')")
    public ai.neargo.shop.common.PageData<CommunityAdminService.PickupVO> pickups(
            @RequestParam(required = false) String communityNo,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        // 运营端列表页按 {records,total} 渲染 —— 返回裸数组会被当成空页
        return ai.neargo.shop.common.PageData.ofAll(adminService.pickups(communityNo, type, status), page, size);
    }

    /**
     * 建自提点。
     *
     * <p><b>此前全平台没有任何创建路径</b>：运营端只有列表/停启/费率，商家不能申请、
     * 邻居不能报名 —— 社区自提这条核心履约方式，生产环境根本无法录入一个点，
     * 能跑通只因为开发种子建了两个。
     */
    @PostMapping("/ops/pickups")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_PICKUP_UPDATE + "')")
    public CommunityAdminService.PickupVO createPickup(@RequestBody CreatePickupReq req) {
        var vo = adminService.createPickup(new CommunityAdminService.CreatePickupCommand(
                req.communityNo(), req.name(), req.type(), req.ownerRef(), req.address(),
                req.openHours(), req.arrivalDesc(), req.serviceFeeRate(), req.serviceFeePerItemMinor()),
                SecurityUtils.currentUserNo());
        // 新增一个承接方是主数据变更，且它决定钱与货的去向 —— 必须留痕
        auditLogPort.record("PICKUP_CREATE", vo.pickupNo(), req.type() + " @ " + req.communityNo());
        return vo;
    }

    /** 状态。MIGRATING = 不再接新单，存量单仍在本点核销完。 */
    @PostMapping("/ops/pickups/{pickupNo}/status")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_PICKUP_UPDATE + "')")
    public CommunityAdminService.PickupVO setPickupStatus(@PathVariable String pickupNo,
                                                          @RequestBody PickupStatusReq req) {
        var vo = adminService.setPickupStatus(pickupNo, req.status(), SecurityUtils.currentUserNo());
        auditLogPort.record("PICKUP_STATUS", pickupNo, req.status());
        return vo;
    }

    /** 履约服务费费率（万分比）。**NEIGHBOR 必须为 0** —— 给了报酬他就变成团长了。 */
    @PostMapping("/ops/pickups/{pickupNo}/service-fee")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_PICKUP_UPDATE + "')")
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
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_PICKUP_READ + "')")
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

    /**
     * @param ownerRef STORE 传**门店号**、NEIGHBOR 传用户号、PLATFORM 传空 ——
     *                 这一列是多态的，传错会让「这个点属于谁」永久错位
     */
    public record CreatePickupReq(String communityNo, String name, String type, String ownerRef,
                                  String address, String openHours, String arrivalDesc,
                                  Integer serviceFeeRate, Long serviceFeePerItemMinor) {
    }

    public record PickupStatusReq(String status) {
    }

    public record ServiceFeeReq(Integer serviceFeeRate) {
    }

    // ────────────────────────────────────────────────── 社区归档

    /**
     * 归档社区。已关闭的区不会被删除，只从默认列表消失 ——
     * 需求单、订单等历史数据都留着，运营可随时恢复。
     */
    @PostMapping("/ops/communities/{communityNo}/archive")
    @PreAuthorize("@perm.can('" + ai.neargo.shop.auth.Perms.COMMUNITY_UPDATE + "')")
    public java.util.Map<String, Object> archiveCommunity(@PathVariable String communityNo) {
        String operator = ai.neargo.shop.auth.SecurityUtils.currentUserNo();
        long at = archiveService.archive(ai.neargo.shop.archive.ArchiveService.Kind.COMMUNITY,
                communityNo, operator);
        return java.util.Map.of("communityNo", communityNo, "archivedAt", at);
    }

    @PostMapping("/ops/communities/{communityNo}/unarchive")
    @PreAuthorize("@perm.can('" + ai.neargo.shop.auth.Perms.COMMUNITY_UPDATE + "')")
    public java.util.Map<String, Object> unarchiveCommunity(@PathVariable String communityNo) {
        String operator = ai.neargo.shop.auth.SecurityUtils.currentUserNo();
        archiveService.unarchive(ai.neargo.shop.archive.ArchiveService.Kind.COMMUNITY,
                communityNo, operator);
        return java.util.Map.of("communityNo", communityNo, "archivedAt", (Object) null);
    }
}
