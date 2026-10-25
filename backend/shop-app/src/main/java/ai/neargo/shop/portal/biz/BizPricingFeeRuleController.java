package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Rule;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.trade.service.PricingFeeRuleService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Profile("api")
@RestController
public class BizPricingFeeRuleController {
    private final PricingFeeRuleService rules;

    public BizPricingFeeRuleController(PricingFeeRuleService rules) {
        this.rules = rules;
    }

    @GetMapping("/biz/pricing/fee-rules/{ruleNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_READ + "')")
    public Rule get(@PathVariable String ruleNo, HttpServletRequest http) {
        noQuery(http);
        return rules.get(scope(), ruleNo);
    }

    @PostMapping("/biz/pricing/fee-rules/{ruleNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_PUBLISH + "')")
    public Rule save(
            @PathVariable String ruleNo,
            @Valid @RequestBody PricingFeeRuleService.SaveReq req,
            @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest http) {
        noQuery(http);
        return rules.save(scope(), SecurityUtils.currentUserNo(), ruleNo, req, key);
    }

    private static Scope scope() {
        var c = BizContext.current();
        return new Scope("MAIN", c.requireMerchantNo(), c.currentStoreNo());
    }

    private static void noQuery(HttpServletRequest r) {
        if (!r.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
