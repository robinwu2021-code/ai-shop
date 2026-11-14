package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort;
import ai.neargo.shop.spi.trade.PricingFeeScopePort;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Profile("api")
@RestController
public class BizPricingFeeScopeController {
    public record ApplyReq(long expectedSubVersion, String sourceHash, String reason) {}

    private final PricingFeeScopePort fees;
    private final IndustryOrderAmendmentPort amendments;

    public BizPricingFeeScopeController(
            PricingFeeScopePort fees, IndustryOrderAmendmentPort amendments) {
        this.fees = fees;
        this.amendments = amendments;
    }

    @GetMapping("/biz/pricing/fee-scopes/{scopeNo}/adjustments")
    @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_READ + "')")
    public List<PricingFeeScopePort.AdjustmentAuthority> pending(
            @PathVariable String scopeNo, HttpServletRequest http) {
        noQuery(http);
        return fees.pendingAdjustments(scope(), scopeNo);
    }

    @PostMapping("/biz/pricing/fee-scopes/{scopeNo}/adjustments/{applicationNo}:apply")
    @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_PUBLISH + "')")
    public IndustryOrderAmendmentPort.Receipt apply(
            @PathVariable String scopeNo,
            @PathVariable String applicationNo,
            @RequestBody ApplyReq req,
            @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest http) {
        noQuery(http);
        if (req == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var scope = scope();
        var advice = fees.adjustment(scope, scopeNo, applicationNo, req.sourceHash());
        return amendments.adjustFee(
                new IndustryOrderAmendmentPort.FeeScopeAdjustment(
                        fees.originalReference(scope, applicationNo),
                        SecurityUtils.currentUserNo(),
                        req.expectedSubVersion(),
                        applicationNo,
                        advice.targetFeeMinor(),
                        advice.sourceHash(),
                        req.reason()),
                key);
    }

    private static Scope scope() {
        var c = BizContext.current();
        return new Scope("MAIN", c.requireMerchantNo(), c.currentStoreNo());
    }

    private static void noQuery(HttpServletRequest r) {
        if (!r.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
