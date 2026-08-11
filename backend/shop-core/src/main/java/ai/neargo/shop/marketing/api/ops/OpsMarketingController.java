package ai.neargo.shop.marketing.api.ops;

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

    public OpsMarketingController(CouponService couponService, CampaignService campaignService,
                                  AuditLogPort auditLogPort) {
        this.couponService = couponService;
        this.campaignService = campaignService;
        this.auditLogPort = auditLogPort;
    }

    /** @param status 为空给全部；{@code ACTIVE} / {@code PAUSED} / {@code ENDED} */
    @GetMapping("/ops/coupons")
    @PreAuthorize("@perm.can('" + Perms.MARKETING_GOVERN + "')")
    public List<CouponVO> coupons(@RequestParam(required = false) String status) {
        return couponService.opsCoupons(status);
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
    public List<CampaignVO> campaigns(@RequestParam(required = false) String status) {
        return campaignService.opsCampaigns(status);
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

    public record StatusReq(String status, String reason) {
    }

    public record ToggleReq(Boolean running, String reason) {
    }
}
