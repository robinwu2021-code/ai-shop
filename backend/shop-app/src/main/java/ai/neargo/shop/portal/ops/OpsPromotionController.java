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

    public OpsPromotionController(OpsPromotionService opsPromotionService) {
        this.opsPromotionService = opsPromotionService;
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
