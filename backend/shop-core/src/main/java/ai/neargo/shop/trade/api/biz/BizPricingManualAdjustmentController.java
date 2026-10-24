package ai.neargo.shop.trade.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.QuoteVO;
import ai.neargo.shop.spi.trade.PricingManualAdjustmentPort.AdjustmentVO;
import ai.neargo.shop.spi.trade.PricingManualAdjustmentPort.ManualKind;
import ai.neargo.shop.spi.trade.PricingManualAdjustmentPort.Request;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.trade.dto.PricingManualPolicyCommands.Approve;
import ai.neargo.shop.trade.dto.PricingManualPolicyCommands.PolicyVO;
import ai.neargo.shop.trade.dto.PricingManualPolicyCommands.Save;
import ai.neargo.shop.trade.service.PricingManualAdjustmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Seller concessions use real authenticated platform identities; no client price or approval reference is admitted. */
@Profile("api")
@RestController
@Validated
public class BizPricingManualAdjustmentController {
    private final PricingManualAdjustmentService service;
    public BizPricingManualAdjustmentController(PricingManualAdjustmentService service) { this.service=service; }

    @GetMapping("/biz/pricing-manual-policies/{currency}/{reasonCode}/{kind}")
    @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')")
    public PolicyVO policy(@PathVariable @Pattern(regexp="[A-Z]{3}") String currency,
            @PathVariable @Pattern(regexp="[A-Z][A-Z0-9_]{0,31}") String reasonCode,@PathVariable ManualKind kind) {
        return service.getPolicy(scope(),SecurityUtils.currentUserNo(),currency,reasonCode,kind);
    }
    @PutMapping("/biz/pricing-manual-policies/{currency}/{reasonCode}/{kind}")
    @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')")
    public PolicyVO policy(@PathVariable @Pattern(regexp="[A-Z]{3}") String currency,
            @PathVariable @Pattern(regexp="[A-Z][A-Z0-9_]{0,31}") String reasonCode,
            @PathVariable ManualKind kind,@Valid @RequestBody Save req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key) {
        return service.savePolicy(scope(),SecurityUtils.currentUserNo(),currency,reasonCode,kind,req,key);
    }
    @PostMapping("/biz/pricing-manual-adjustments")
    @PreAuthorize("@perm.canBiz('"+BizPerms.CAMPAIGN+"')")
    public AdjustmentVO propose(@Valid @RequestBody Request req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key) {
        return service.propose(scope(),SecurityUtils.currentUserNo(),req,key);
    }
    @GetMapping("/biz/pricing-manual-adjustments/{no}")
    @PreAuthorize("@perm.canBiz('"+BizPerms.CAMPAIGN+"')")
    public AdjustmentVO get(@PathVariable @NotBlank @Size(max=32) String no) {
        return service.get(scope(),SecurityUtils.currentUserNo(),no);
    }
    @PostMapping("/biz/pricing-manual-adjustments/{no}:approve")
    @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')")
    public AdjustmentVO approve(@PathVariable @NotBlank @Size(max=32) String no,@Valid @RequestBody Approve req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key) {
        return service.approve(scope(),SecurityUtils.currentUserNo(),no,req.version(),key);
    }
    /** Calculates and saves a quote; this does not submit an order, finalize a tax invoice or collect funds. */
    @PostMapping("/biz/pricing-manual-adjustments/{no}:quote")
    @PreAuthorize("@perm.canBiz('"+BizPerms.CAMPAIGN+"')")
    public QuoteVO quote(@PathVariable @NotBlank @Size(max=32) String no,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key) {
        return service.quote(scope(),SecurityUtils.currentUserNo(),no,key);
    }
    private Scope scope() {
        var user=SecurityUtils.requireUser();
        if(user.realm()!=Realm.MERCHANT || !"MAIN".equals(user.tenantNo()))
            throw BizException.of(ErrorCode.FORBIDDEN);
        return new Scope(user.tenantNo(),BizContext.requireMerchantNo(),BizContext.requireStoreNo());
    }
}
