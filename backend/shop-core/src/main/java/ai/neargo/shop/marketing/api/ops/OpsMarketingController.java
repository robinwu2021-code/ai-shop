package ai.neargo.shop.marketing.api.ops;

import ai.neargo.shop.archive.ArchiveService;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.marketing.coupon.dto.CouponIssueVO;
import ai.neargo.shop.marketing.coupon.dto.OpsCouponVO;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.marketing.campaign.CampaignService;
import ai.neargo.shop.marketing.campaign.dto.CampaignVO;
import ai.neargo.shop.marketing.coupon.CouponService;
import ai.neargo.shop.marketing.coupon.dto.CouponVO;
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
 * 平台端 · 营销治理（P-7.1 / P-7.2）：优惠券与店铺活动的**止损开关**。
 *
 * <p>此前平台对这两块零干预：商家发了一张面额超过商品价的券、或者把满减门槛写成 0
 * （等于白送），运营只能去改数据库。
 *
 * <p><b>只做「停」不做「改」与「发」</b>，这是有意的边界：
 * <ul>
 *   <li>出事时能止损就够了，怎么改由商家自己去改——平台替商家改营销规则，
 *       改错了责任说不清</li>
 *   <li>契约里的<b>调预算</b>（{@code /ops/coupons/{no}/budget}）与
 *       <b>发券</b>（{@code issue} / {@code coupon-issues}）**没做**：
 *       {@code mkt_coupon} 没有预算列，也没有发券批次表
 *       （库里唯一的 {@code budget_minor} 是需求墙的，与券无关）。
 *       那两条属于「缺表」，不是「缺端点」</li>
 * </ul>
 *
 * <p>暂停券**不影响已领到手的券**——那是用户已有的权益，
 * 平台单方面作废会引发比「多发了几张券」严重得多的纠纷。
 */
@Profile("ops")
@RestController
@Validated
public class OpsMarketingController {

    private final CouponService couponService;
    private final CampaignService campaignService;
    private final AuditLogPort auditLogPort;
    private final ArchiveService archiveService;

    public OpsMarketingController(CouponService couponService, CampaignService campaignService,
                                  AuditLogPort auditLogPort, ArchiveService archiveService) {
        this.couponService = couponService;
        this.campaignService = campaignService;
        this.auditLogPort = auditLogPort;
        this.archiveService = archiveService;
    }

    /** @param status 为空给全部；{@code ACTIVE} / {@code PAUSED} / {@code ENDED} */
    @GetMapping("/ops/coupons")
    @PreAuthorize("@perm.can('" + Perms.MARKETING_GOVERN + "')")
    public PageData<OpsCouponVO> coupons(@RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "false") boolean showArchived,
                                      @RequestParam(defaultValue = "1") long page,
                                      @RequestParam(defaultValue = "50") long size) {
        // 运营端列表页按 {records,total} 渲染 —— 返回裸数组会被当成空页
        return PageData.ofAll(couponService.opsCoupons(status, showArchived), page, size);
    }

    /**
     * 改券预算。0 = 不限；不能改到低于已发放金额。
     *
     * <p>补这条端点之前，V21 的预算列、领券那条 UPDATE 里的闸门、页面上的预算进度条
     * <b>三样都在，唯独运营改不了它</b> —— 预算恒为 0，闸门永远不生效。
     */
    @PostMapping("/ops/coupons/{couponNo}/budget")
    @PreAuthorize("@perm.can('" + Perms.MARKETING_GOVERN + "')")
    public OpsCouponVO setCouponBudget(@PathVariable String couponNo, @RequestBody BudgetReq req) {
        OpsCouponVO vo = couponService.setBudget(couponNo, req.budget() == null ? 0 : req.budget(),
                SecurityUtils.currentUserNo());
        auditLogPort.record("COUPON_BUDGET", couponNo, "预算改为 " + vo.budget() + " 分");
        return vo;
    }

    /** 改券状态。暂停即刻生效：券从领券中心消失，领取被拒。 */
    @PostMapping("/ops/coupons/{couponNo}/status")
    @PreAuthorize("@perm.can('" + Perms.MARKETING_GOVERN + "')")
    public CouponVO setCouponStatus(@PathVariable String couponNo, @RequestBody StatusReq req) {
        String operator = SecurityUtils.currentUserNo();
        CouponVO vo = couponService.setCouponStatus(couponNo, req.status(), req.reason(), operator);
        auditLogPort.record("COUPON_STATUS", couponNo, req.status() + "｜" + req.reason());
        return vo;
    }

    @GetMapping("/ops/campaigns")
    @PreAuthorize("@perm.can('" + Perms.MARKETING_GOVERN + "')")
    public PageData<CampaignVO> campaigns(@RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "false") boolean showArchived,
                                          @RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "50") long size) {
        // 运营端列表页按 {records,total} 渲染 —— 返回裸数组会被当成空页
        return PageData.ofAll(campaignService.opsCampaigns(status, showArchived), page, size);
    }

    /** 停/启商家活动。与商家自己的开关走同一个状态字段，但不校验归属。 */
    @PostMapping("/ops/campaigns/{campaignNo}/toggle")
    @PreAuthorize("@perm.can('" + Perms.MARKETING_GOVERN + "')")
    public CampaignVO toggleCampaign(@PathVariable String campaignNo, @RequestBody ToggleReq req) {
        String operator = SecurityUtils.currentUserNo();
        CampaignVO vo = campaignService.opsToggle(campaignNo,
                Boolean.TRUE.equals(req.running()), req.reason(), operator);
        auditLogPort.record("CAMPAIGN_TOGGLE", campaignNo,
                (Boolean.TRUE.equals(req.running()) ? "启用" : "停用") + "｜" + req.reason());
        return vo;
    }

    /**
     * 主动发券（P-7.1.2）。客服的补偿券走同一条，所以**操作人必须留痕**（矩阵 §2.3）。
     *
     * <p>只有 {@code SINGLE_USER} 能真发 —— 其余三种在入口处就给不出收件人，
     * 返回 10501「还没做完」而不是 10400，理由见 {@code CouponService.issue}。
     */
    @PostMapping("/ops/coupons/{couponNo}/issue")
    @PreAuthorize("@perm.can('" + Perms.MARKETING_GOVERN + "')")
    public CouponIssueVO issueCoupon(@PathVariable String couponNo, @RequestBody IssueReq req) {
        String operator = SecurityUtils.currentUserNo();
        CouponIssueVO vo = couponService.issue(couponNo, req.target(), req.targetDesc(),
                req.userNo(), req.count() == null ? 0 : req.count(), operator);
        auditLogPort.record("COUPON_ISSUE", couponNo,
                "发放 " + vo.count() + " 张｜" + req.target() + "｜" + nz(req.targetDesc()));
        return vo;
    }

    /** 发放记录。留痕的**消费方** —— 没有它，记了也没人看得到 */
    @GetMapping("/ops/coupon-issues")
    @PreAuthorize("@perm.can('" + Perms.MARKETING_GOVERN + "')")
    public PageData<CouponIssueVO> couponIssues(@RequestParam(required = false) String couponNo,
                                                @RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "50") long size) {
        return PageData.ofAll(couponService.issues(couponNo), page, size);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /*
     * 归档 = **软删除**，不是停用：停用的券还在列表里等着被恢复，
     * 归档的从默认列表消失。两者正交，一张券可以「已暂停 + 已归档」。
     * 业务数据与关联记录一条不动 —— 契约里禁止 delete*（工程约定 §10.6），
     * 因为运营端的「删」几乎总是「不想看见了」，而不是「这条数据错了」。
     */
    @PostMapping("/ops/coupons/{couponNo}/archive")
    @PreAuthorize("@perm.can('" + Perms.MARKETING_GOVERN + "')")
    public java.util.Map<String, Object> archiveCoupon(@PathVariable String couponNo) {
        long at = archiveService.archive(ArchiveService.Kind.COUPON, couponNo,
                SecurityUtils.currentUserNo());
        return java.util.Map.of("couponNo", couponNo, "archivedAt", at);
    }

    @PostMapping("/ops/coupons/{couponNo}/unarchive")
    @PreAuthorize("@perm.can('" + Perms.MARKETING_GOVERN + "')")
    public java.util.Map<String, Object> unarchiveCoupon(@PathVariable String couponNo) {
        archiveService.unarchive(ArchiveService.Kind.COUPON, couponNo, SecurityUtils.currentUserNo());
        return java.util.Map.of("couponNo", couponNo);
    }

    @PostMapping("/ops/campaigns/{campaignNo}/archive")
    @PreAuthorize("@perm.can('" + Perms.MARKETING_GOVERN + "')")
    public java.util.Map<String, Object> archiveCampaign(@PathVariable String campaignNo) {
        long at = archiveService.archive(ArchiveService.Kind.CAMPAIGN, campaignNo,
                SecurityUtils.currentUserNo());
        return java.util.Map.of("campaignNo", campaignNo, "archivedAt", at);
    }

    @PostMapping("/ops/campaigns/{campaignNo}/unarchive")
    @PreAuthorize("@perm.can('" + Perms.MARKETING_GOVERN + "')")
    public java.util.Map<String, Object> unarchiveCampaign(@PathVariable String campaignNo) {
        archiveService.unarchive(ArchiveService.Kind.CAMPAIGN, campaignNo, SecurityUtils.currentUserNo());
        return java.util.Map.of("campaignNo", campaignNo);
    }

    /**
     * @param userNo SINGLE_USER 时的收券人。**其余目标类型给不出它** ——
     *               ops-web 的「定向说明」是自由文本，所以那三种当场拒绝
     */
    public record IssueReq(String target, String targetDesc, String userNo, Integer count) {
    }

    /** 预算（分）。0 = 不限 */
    public record BudgetReq(Long budget) {
    }

    public record StatusReq(String status, String reason) {
    }

    public record ToggleReq(Boolean running, String reason) {
    }
}
