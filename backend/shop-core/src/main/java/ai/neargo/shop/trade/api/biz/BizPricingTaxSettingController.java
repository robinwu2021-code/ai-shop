package ai.neargo.shop.trade.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.trade.dto.PricingTaxSettingCommands;
import ai.neargo.shop.trade.dto.PricingTaxSettingCommands.SettingVO;
import ai.neargo.shop.trade.dto.PricingTaxSettingRequests;
import ai.neargo.shop.trade.policy.PricingTaxAccessPolicy;
import ai.neargo.shop.trade.service.PricingTaxSettingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Merchant-wide settings are edited by the authenticated entity owner. */
@Profile("api")
@RestController
@Validated
public class BizPricingTaxSettingController {
    private final PricingTaxSettingService service;
    private final PricingTaxAccessPolicy access;

    public BizPricingTaxSettingController(PricingTaxSettingService service, PricingTaxAccessPolicy access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/biz/pricing-tax-settings")
    @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_TAX_READ + "')")
    public SettingVO get() { return service.getMerchant(access.merchant(false)); }

    @PutMapping("/biz/pricing-tax-settings")
    @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_TAX_UPDATE + "')")
    public SettingVO save(@Valid @RequestBody PricingTaxSettingRequests.Save req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return service.saveMerchant(access.merchant(true),
                new PricingTaxSettingCommands.SaveReq(req.expectedVersion(), req.rateBp(), req.priceTaxMode()), key);
    }

    @PostMapping("/biz/pricing-tax-settings/reset")
    @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_TAX_UPDATE + "')")
    public SettingVO reset(@Valid @RequestBody PricingTaxSettingRequests.Reset req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return service.resetMerchant(access.merchant(true), new PricingTaxSettingCommands.ResetReq(
                req.expectedVersion()), key);
    }
}
