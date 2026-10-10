package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.promotion.dto.PeriodVOs.Decision;
import ai.neargo.shop.promotion.dto.PeriodVOs.PeriodDetailVO;
import ai.neargo.shop.promotion.dto.PeriodVOs.PeriodVO;
import ai.neargo.shop.promotion.dto.PeriodVOs.PurchaseLineVO;
import ai.neargo.shop.promotion.service.MarketingSummaryService;
import ai.neargo.shop.promotion.service.MarketingSummaryService.SummaryVO;
import ai.neargo.shop.promotion.service.PeriodService;
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
 * 社区集单的「期」（原型 s20 / s31 / s33 · TDD-营销-活动统一模型与集单 §2.2）。
 *
 * <p>权限与活动同一个码：集单是活动的实例，能建集单活动的人就该能处理它的期。
 */
@Profile("api")
@RestController
public class BizPeriodController {

    private final PeriodService periodService;
    private final MarketingSummaryService summaryService;
    private final ai.neargo.shop.marketing.group.GroupService groupService;

    public BizPeriodController(PeriodService periodService, MarketingSummaryService summaryService,
                               ai.neargo.shop.marketing.group.GroupService groupService) {
        this.periodService = periodService;
        this.summaryService = summaryService;
        this.groupService = groupService;
    }

    /** 营销入口的一屏数字（s01）。集单排在团前，需处理的项由端上标黄 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @GetMapping("/biz/marketing/summary")
    public SummaryVO summary() {
        String entityNo = BizContext.requireMerchantNo();
        int groupsShort = (int) groupService.merchantGroups(entityNo).stream()
                .filter(g -> "OPEN".equals(g.status())).count();
        return summaryService.summary(entityNo).withGroups(groupsShort, groupService.quotableCount(entityNo));
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @GetMapping("/biz/period")
    public List<PeriodVO> periods(@RequestParam(required = false) String status) {
        return periodService.list(BizContext.requireMerchantNo(), status);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @GetMapping("/biz/period/{periodNo}")
    public PeriodDetailVO period(@PathVariable String periodNo) {
        return periodService.detail(BizContext.requireMerchantNo(), periodNo);
    }

    /** 提前截单（s20「提前截单」）。只能从收单中 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @PostMapping("/biz/period/{periodNo}/cutoff")
    public PeriodVO cutoff(@PathVariable String periodNo) {
        return periodService.cutoffNow(BizContext.requireMerchantNo(), periodNo, SecurityUtils.currentUserNo());
    }

    /** 未达起订量时的处理（s33「取消本期 / 照常发货」）。只能从待处理 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @PostMapping("/biz/period/{periodNo}/decision")
    public PeriodVO decide(@PathVariable String periodNo, @RequestBody DecisionReq req) {
        return periodService.decide(BizContext.requireMerchantNo(), periodNo,
                req == null ? null : req.action(), SecurityUtils.currentUserNo());
    }

    /** 按 SKU 汇总，给进销存进货单预填（s20「去采购」） */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @GetMapping("/biz/period/{periodNo}/purchase-lines")
    public List<PurchaseLineVO> purchaseLines(@PathVariable String periodNo) {
        return periodService.purchaseLines(BizContext.requireMerchantNo(), periodNo);
    }

    public record DecisionReq(Decision action) {
    }
}
