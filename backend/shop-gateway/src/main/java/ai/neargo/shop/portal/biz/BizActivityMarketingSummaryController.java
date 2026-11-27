package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.service.ActivityMarketingSummaryService;
import ai.neargo.shop.promotion.service.ActivityMarketingSummaryService.Summary;
import ai.neargo.shop.trade.service.PosActivityEffectService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Versioned activity statistics use an explicit currency and never infer it for historical legacy amounts. */
@Profile("api")
@Validated
@RestController
public class BizActivityMarketingSummaryController {
    @Autowired
    private PosActivityEffectService pos;
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @GetMapping("/biz/activities/{activityNo}/effect")
    public PosActivityEffectService.Effect effect(
            @PathVariable String activityNo, @RequestParam String storeNo,
            @RequestParam LocalDate from, @RequestParam LocalDate through,
            @RequestParam ZoneId zone) {
        var user=SecurityUtils.requireUser();
        if (user.realm()!=Realm.MERCHANT) throw BizException.of(ErrorCode.FORBIDDEN);
        return pos.effect(new PosActivityEffectService.Scope(user.tenantNo(),BizContext.requireMerchantNo(),
                BizContext.current().allowedStoresOrAll()),storeNo,activityNo,from,through,zone);
    }

    private final ActivityMarketingSummaryService summaries;
    public BizActivityMarketingSummaryController(ActivityMarketingSummaryService summaries) {
        this.summaries = summaries;
    }
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @GetMapping("/biz/marketing-summary")
    public Summary summary(@RequestParam(required = false) String storeNo,
                           @RequestParam(required = false) String activityNo,
                           @RequestParam Instant from, @RequestParam Instant to) {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.MERCHANT) throw BizException.of(ErrorCode.FORBIDDEN);
        return summaries.summarize(new ActivityActor(user.tenantNo(), BizContext.requireMerchantNo(),
                user.userNo(), BizContext.current().allowedStoresOrAll()), storeNo, activityNo, from, to);
    }
}
