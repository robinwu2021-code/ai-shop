package ai.neargo.shop.spi.entitlement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Service-authenticated reverse lookup; opaque refs always resolve immutable Core source facts. */
public final class BrandCouponSourceInternal {
    private BrandCouponSourceInternal() { }

    public static final String TEMPLATE_VALIDATION = "/internal/core/promotion/brand-coupons/template-validation";
    public static final String USE_VALIDATION = "/internal/core/promotion/brand-coupons/use-validation";
    public static final String AUDIENCE = "/internal/core/promotion/brand-coupons/audience";

    public record TemplateValidation(@NotBlank String tenantNo, @NotBlank String issuerEntityNo,
                                     @NotNull @Valid EntitlementDefinition definition) { }
    public record ValidationReply(boolean valid) { }
    public record UseValidation(@NotBlank String tenantNo, @NotBlank String issuerEntityNo,
                                @NotNull @Valid EntitlementDefinition definition,
                                @NotBlank String templateVersionNo, @NotBlank String consumerEntityNo, @NotBlank String storeNo) { }
    public record UseReply(boolean permitted) { }
    public record AudienceQuery(@NotBlank String tenantNo, @NotBlank String issuerEntityNo,
                                @NotNull @Valid EntitlementDefinition definition,
                                @NotBlank String templateVersionNo, @NotBlank String audienceFactRef,
                                @NotBlank String actorNo, @NotBlank String commandNo) { }
}
