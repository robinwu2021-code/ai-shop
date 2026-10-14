package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.promotion.dto.OpsPromotionVOs.OpsActivityVO;
import ai.neargo.shop.promotion.dto.OpsPromotionVOs.OpsCouponVO;
import ai.neargo.shop.promotion.service.OpsPromotionService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运营侧的券与活动（P8，O5–O7）。
 *
 * <p><b>这一页存在的理由是「敞口」</b>：券与活动都在花商家的钱，而平台要能在
 * 出事之前看见 —— 谁家的券没设预算、谁家的活动是长期且不限量、谁家发得异常多。
 * 商家自己看不出这些（他只看自己那一张），只有跨商家排在一起才看得出来。
 */
@Profile("ops")
@RestController
public class OpsPromotionController {

    private final OpsPromotionService opsPromotionService;
    private final ai.neargo.shop.promotion.service.PlatformActivityService platformService;

    public OpsPromotionController(OpsPromotionService opsPromotionService,
                                  ai.neargo.shop.promotion.service.PlatformActivityService platformService) {
        this.opsPromotionService = opsPromotionService;
        this.platformService = platformService;
    }

    // ---------------------------------------------------------------- 平台活动（s29 · s30）

    /** 全部平台活动（含草稿），带审核计数与预算占用 */
    @PreAuthorize("@perm.can('" + Perms.MARKETING_CAMPAIGN_READ + "')")
    @GetMapping("/ops/promotion/platform-activities")
    public List<ai.neargo.shop.promotion.dto.PlatformVOs.PlatformActivityVO> platformActivities() {
        return platformService.opsList();
    }

    /**
     * 新建或修改平台活动（s29）。{@code publish = true} 即发布报名；发布后只能改报名截止与预算。
     * 规则部分与商家活动同一个模型，多出来的只有出资、预算、报名门槛。
     */
    @PreAuthorize("@perm.can('" + Perms.MARKETING_CAMPAIGN_UPDATE + "')")
    @PostMapping("/ops/promotion/platform-activities")
    public ai.neargo.shop.promotion.dto.PlatformVOs.PlatformActivityVO savePlatform(
            @RequestBody ai.neargo.shop.promotion.dto.PlatformVOs.PlatformDraft draft) {
        return platformService.save(draft, SecurityUtils.currentUserNo());
    }

    /** 一个平台活动的报名（s30）。走数据域：只看某些商家的运营只看到那些商家的报名 */
    @PreAuthorize("@perm.can('" + Perms.MARKETING_CAMPAIGN_READ + "')")
    @GetMapping("/ops/promotion/platform-activities/{activityNo}/enrollments")
    public List<ai.neargo.shop.promotion.dto.PlatformVOs.EnrollmentVO> enrollments(
            @PathVariable String activityNo, @RequestParam(required = false) String status) {
        return platformService.enrollments(activityNo, status);
    }

    /** 通过 / 驳回一份报名。通过占平台预算，超了拒（40032）；驳回理由必填 */
    @PreAuthorize("@perm.can('" + Perms.MARKETING_CAMPAIGN_UPDATE + "')")
    @PostMapping("/ops/promotion/enrollments/{enrollmentNo}/review")
    public ai.neargo.shop.promotion.dto.PlatformVOs.EnrollmentVO review(@PathVariable String enrollmentNo,
                                                                       @RequestBody ReviewReq req) {
        return platformService.review(enrollmentNo, Boolean.TRUE.equals(req.pass()), req.reason(),
                SecurityUtils.currentUserNo());
    }

    /** @param reason 驳回理由，商家原样看到；通过时可空 */
    public record ReviewReq(Boolean pass, String reason) {
    }

    /** O5 全平台券：归属、敞口、异常标记 */
    @PreAuthorize("@perm.can('" + Perms.MARKETING_COUPON_READ + "')")
    @GetMapping("/ops/promotion/coupons")
    public List<OpsCouponVO> coupons(@RequestParam(required = false) String entityNo) {
        return opsPromotionService.coupons(entityNo);
    }

    /** O6 全平台活动：归属、受众、限量 */
    @PreAuthorize("@perm.can('" + Perms.MARKETING_CAMPAIGN_READ + "')")
    @GetMapping("/ops/promotion/activities")
    public List<OpsActivityVO> activities(@RequestParam(required = false) String entityNo) {
        return opsPromotionService.activities(entityNo);
    }

    /**
     * O7 强制停止一个活动。
     *
     * <p><b>必须填原因，而且商家看得见</b>：平台停掉商家的活动是一次单方面动作，
     * 不给理由的话，商家看到的是「我的活动莫名其妙没了」——
     * 那会变成一通客服电话，而接电话的人也答不上来。
     */
    @PreAuthorize("@perm.can('" + Perms.MARKETING_CAMPAIGN_UPDATE + "')")
    @PostMapping("/ops/promotion/activities/{activityNo}/stop")
    public OpsActivityVO stop(@PathVariable String activityNo, @RequestBody StopReq req) {
        return opsPromotionService.stop(activityNo, req.reason(), SecurityUtils.currentUserNo());
    }

    public record StopReq(String reason) {
    }
}
