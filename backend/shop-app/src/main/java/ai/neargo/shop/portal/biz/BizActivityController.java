package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityVO;
import ai.neargo.shop.promotion.dto.ActivityVOs.ConflictVO;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.service.ActivityService;
import ai.neargo.shop.spi.platform.PlatformSwitchPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 商家活动（新模型，P5）。与券同一个权限码：都是花钱的东西 */
@Profile("api")
@RestController
public class BizActivityController {

    private final ActivityService activityService;

    private final PlatformSwitchPort switchPort;

    public BizActivityController(ActivityService activityService,
                                 PlatformSwitchPort switchPort) {
        this.activityService = activityService;
        this.switchPort = switchPort;
    }

    static final String FLAG_ALWAYS_ON_CUT_CONFIRM = "marketing.always-on-cut.confirm";

    /**
     * 常驻 + 无门槛 + 直减：每一单都减，直到限量用完（待办设计 P7）。
     * 这是<b>提醒不是规则</b>，「abc」这样的配置是合法的 —— 所以放在入口而不是服务里校验。
     */
    static boolean alwaysOnCut(ActivityDraft d) {
        return PmtActivity.ALWAYS_ON.equals(d.scheduleType())
                && (d.triggerType() == null
                        || PmtActivity.TRIGGER_NONE.equals(d.triggerType()))
                && PmtActivity.BENEFIT_CUT.equals(d.benefitType());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @GetMapping("/biz/activities")
    public List<ActivityVO> activities(@RequestParam(defaultValue = "false") boolean includeEnded) {
        return activityService.list(BizContext.requireMerchantNo(), includeEnded);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @GetMapping("/biz/activities/{activityNo}")
    public ActivityVO activity(@PathVariable String activityNo) {
        return activityService.detail(BizContext.requireMerchantNo(), activityNo);
    }

    /**
     * 建 / 改活动。**敞口在这一步算清**：长期活动必须有限量或预算，
     * 改价与送商品必须限量且必须选商品。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @PostMapping("/biz/activities")
    public ActivityVO save(@RequestBody ActivityDraft draft,
                           @RequestParam(defaultValue = "false") boolean riskConfirmed) {
        if (!riskConfirmed && alwaysOnCut(draft) && switchPort.bool(FLAG_ALWAYS_ON_CUT_CONFIRM, true)) {
            throw BizException.of(ErrorCode.ACTIVITY_RISK_UNCONFIRMED);
        }
        return activityService.save(BizContext.requireMerchantNo(), draft,
                SecurityUtils.currentUserNo());
    }

    /** 启停。**已结束的不能复活** —— ended_reason 被覆盖就再也查不到当初为什么停 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @PutMapping("/biz/activities/{activityNo}/status")
    public ActivityVO setStatus(@PathVariable String activityNo, @RequestBody StatusReq req) {
        return activityService.setStatus(BizContext.requireMerchantNo(), activityNo, req.status());
    }

    /**
     * 这些商品已经在哪些活动里 —— <b>建活动时的冲突提示</b>。
     *
     * <p>不阻止（同类取最优是既定口径），但要在保存前说出来：
     * 商家建第二个特价活动时，多半是忘了第一个还在跑。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @PostMapping("/biz/activity-conflicts")
    public List<ConflictVO> conflicts(@RequestBody ConflictReq req) {
        return activityService.conflicts(BizContext.requireMerchantNo(), req.goodsNos());
    }

    public record StatusReq(String status) {
    }

    public record ConflictReq(List<String> goodsNos) {
    }
}
