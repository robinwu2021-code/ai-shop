package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.promotion.dto.PlatformVOs.EnrollCommand;
import ai.neargo.shop.promotion.dto.PlatformVOs.EnrollmentVO;
import ai.neargo.shop.promotion.dto.PlatformVOs.PlatformActivityVO;
import ai.neargo.shop.promotion.service.PlatformActivityService;
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
 * 商家看平台活动、报名（原型 s27 · s28 · 详细设计 §3.2）。
 *
 * <p>没有「新建」：平台活动由运营发起，商家只能报名。报名是营销权限 —— 它承诺的是「每单我出多少」。
 */
@Profile("api")
@RestController
public class BizPlatformActivityController {

    private final PlatformActivityService service;

    public BizPlatformActivityController(PlatformActivityService service) {
        this.service = service;
    }

    /** @param tab ENROLLABLE（可报名）/ ENROLLED（已报名）/ ENDED（已结束）；空 = 全部 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @GetMapping("/biz/platform-activity")
    public List<PlatformActivityVO> list(@RequestParam(required = false) String tab) {
        return service.forMerchant(BizContext.requireMerchantNo(), tab);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @GetMapping("/biz/platform-activity/{activityNo}")
    public PlatformActivityVO detail(@PathVariable String activityNo) {
        return service.detailForMerchant(BizContext.requireMerchantNo(), activityNo);
    }

    /** 报名（审核前可改）：选哪几件货、报多少份。「最多承担」在这一刻算定 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @PostMapping("/biz/platform-activity/{activityNo}/enrollment")
    public EnrollmentVO enroll(@PathVariable String activityNo, @RequestBody EnrollReq req) {
        return service.enroll(BizContext.requireMerchantNo(), activityNo,
                new EnrollCommand(req.goodsNos() == null ? List.of() : req.goodsNos(),
                        req.quota() == null ? 0 : req.quota()));
    }

    /** 撤回待审的报名 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @PostMapping("/biz/platform-activity/{activityNo}/withdraw")
    public EnrollmentVO withdraw(@PathVariable String activityNo) {
        return service.withdraw(BizContext.requireMerchantNo(), activityNo);
    }

    /**
     * @param goodsNos 报名的货：必须是自己的、在售的，且在活动限定的类目里
     * @param quota    报多少份（一单一份）
     */
    public record EnrollReq(List<String> goodsNos, Integer quota) {
    }
}
