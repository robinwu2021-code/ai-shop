package ai.neargo.shop.trade.api.ops;

import ai.neargo.shop.auth.Perms;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Platform defaults are foundational settings, without a merchant approval workflow. */
@Profile("ops")
@RestController
@Validated
public class OpsPricingTaxSettingController {
    private final PricingTaxSettingService service;
    private final PricingTaxAccessPolicy access;

    public OpsPricingTaxSettingController(PricingTaxSettingService service, PricingTaxAccessPolicy access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/ops/pricing-tax-settings")
    @PreAuthorize("@perm.can('" + Perms.PRICING_TAX_READ + "')")
    public SettingVO get() { return service.getPlatform(access.platform()); }

    @PutMapping("/ops/pricing-tax-settings")
    @PreAuthorize("@perm.can('" + Perms.PRICING_TAX_UPDATE + "')")
    public SettingVO save(@Valid @RequestBody PricingTaxSettingRequests.Save req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return service.savePlatform(access.platform(),
                new PricingTaxSettingCommands.SaveReq(req.expectedVersion(), req.rateBp(), req.priceTaxMode()), key);
    }
}
