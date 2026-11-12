package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.payclient.PayServiceOnboardingQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("ops")
@RestController
@Validated
public class OpsPayServiceOnboardingController {
    private final PayServiceOnboardingQueryService query;

    public OpsPayServiceOnboardingController(PayServiceOnboardingQueryService query) {
        this.query = query;
    }

    @PreAuthorize("@perm.can('" + Perms.MERCHANT_ADMISSION_READ + "')")
    @GetMapping("/ops/payment-onboardings")
    public PageData<PayServiceOnboardingQueryService.Row> list(
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(20) long size) {
        return query.list(keyword, page, size);
    }
}
